// Shows the wet ink through buffers of our own, handed to the screen with a
// surface transaction, instead of waiting for a surface's canvas.
//
// Locking a surface's canvas waits for the display to hand a buffer back,
// which on the S6 Lite is most of a frame (see CompatWetInkView). Here three
// hardware buffers are allocated once. A frame is written into one that the
// compositor has let go of, and submitted; the transaction does not wait for
// the display. When a frame completes, the buffer it replaced comes back with
// a fence, which is waited on only if that buffer is the next one wanted.
//
// Everything here is an experiment behind a switch in the Lab: any failure is
// reported to Kotlin, which stops using it and draws the way it always did.
#include <jni.h>

#include <android/bitmap.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <android/surface_control.h>
#include <poll.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>

namespace {

constexpr int kBuffers = 3;
// How long a frame waits for a buffer before it is retried a moment later.
constexpr int kWaitMillis = 8;

struct Rect {
    int l = 0, t = 0, r = 0, b = 0;
    bool empty() const { return r <= l || b <= t; }
};

void unite(Rect& into, const Rect& other) {
    if (other.empty()) return;
    if (into.empty()) {
        into = other;
        return;
    }
    into.l = std::min(into.l, other.l);
    into.t = std::min(into.t, other.t);
    into.r = std::max(into.r, other.r);
    into.b = std::max(into.b, other.b);
}

// Free: may be written once its fence (if any) has signalled. Cpu: being
// written by us. Shown: submitted; the compositor has not let go of it yet.
enum class Use { Free, Cpu, Shown };

struct Slot {
    AHardwareBuffer* buffer = nullptr;
    Use use = Use::Free;
    int fence = -1;
    // What this buffer is missing of the picture: it last held an older frame.
    Rect stale;
};

struct Presenter {
    ANativeWindow* window = nullptr;
    ASurfaceControl* control = nullptr;
    int width = 0;
    int height = 0;
    uint32_t stride = 0;  // in pixels
    Slot slots[kBuffers];
    int last = -1;        // the buffer submitted most recently
    bool shown = false;
    bool alive = true;
    std::mutex mutex;
    std::condition_variable freed;
};

// What a completed transaction needs to know: whose, and which buffer it replaced.
struct Completion {
    std::shared_ptr<Presenter> presenter;
    int replaced;
};

void onComplete(void* context, ASurfaceTransactionStats* stats) {
    std::unique_ptr<Completion> done(static_cast<Completion*>(context));
    Presenter& p = *done->presenter;
    std::lock_guard<std::mutex> lock(p.mutex);
    // Gone already: the control is released, and so is everything it held.
    if (!p.alive || done->replaced < 0) return;
    const int fence = ASurfaceTransactionStats_getPreviousReleaseFenceFd(stats, p.control);
    Slot& slot = p.slots[done->replaced];
    if (slot.fence >= 0) close(slot.fence);
    slot.fence = fence;
    slot.use = Use::Free;
    p.freed.notify_all();
}

void release(Presenter& p) {
    std::lock_guard<std::mutex> lock(p.mutex);
    p.alive = false;
    for (Slot& slot : p.slots) {
        if (slot.fence >= 0) close(slot.fence);
        slot.fence = -1;
        if (slot.buffer != nullptr) AHardwareBuffer_release(slot.buffer);
        slot.buffer = nullptr;
    }
    if (p.control != nullptr) ASurfaceControl_release(p.control);
    p.control = nullptr;
    if (p.window != nullptr) ANativeWindow_release(p.window);
    p.window = nullptr;
}

using Handle = std::shared_ptr<Presenter>;

Handle* handleOf(jlong value) { return reinterpret_cast<Handle*>(value); }

}  // namespace

extern "C" JNIEXPORT jlong JNICALL Java_com_notesis_PresentNative_create(
    JNIEnv* env, jobject, jobject surface, jint width, jint height) {
    if (surface == nullptr || width < 1 || height < 1 || width > 8192 || height > 8192) return 0;
    auto presenter = std::make_shared<Presenter>();
    presenter->window = ANativeWindow_fromSurface(env, surface);
    if (presenter->window == nullptr) return 0;
    presenter->control = ASurfaceControl_createFromWindow(presenter->window, "notesis-wet-ink");
    if (presenter->control == nullptr) {
        release(*presenter);
        return 0;
    }
    presenter->width = width;
    presenter->height = height;
    for (Slot& slot : presenter->slots) {
        AHardwareBuffer_Desc desc = {};
        desc.width = static_cast<uint32_t>(width);
        desc.height = static_cast<uint32_t>(height);
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        if (AHardwareBuffer_allocate(&desc, &slot.buffer) != 0 || slot.buffer == nullptr) {
            release(*presenter);
            return 0;
        }
        AHardwareBuffer_Desc got = {};
        AHardwareBuffer_describe(slot.buffer, &got);
        presenter->stride = got.stride;
        // Nothing in it yet: the first frame written must fill all of it.
        slot.stale = Rect{0, 0, width, height};
    }
    return reinterpret_cast<jlong>(new Handle(std::move(presenter)));
}

// Picks a buffer to write the next frame into. Returns its index, -1 when none
// is free yet (try again a moment later) or -2 on a failure.
extern "C" JNIEXPORT jint JNICALL Java_com_notesis_PresentNative_acquire(JNIEnv*, jobject, jlong value) {
    if (value == 0) return -2;
    Presenter& p = **handleOf(value);
    int index = -1;
    int fence = -1;
    {
        std::unique_lock<std::mutex> lock(p.mutex);
        if (!p.alive) return -2;
        auto pick = [&] {
            for (int i = 0; i < kBuffers; ++i) {
                if (p.slots[i].use == Use::Free) return i;
            }
            return -1;
        };
        index = pick();
        if (index < 0) {
            p.freed.wait_for(lock, std::chrono::milliseconds(kWaitMillis));
            index = pick();
        }
        if (index < 0) return -1;
        fence = p.slots[index].fence;
        p.slots[index].fence = -1;
        p.slots[index].use = Use::Cpu;
    }
    if (fence >= 0) {
        struct pollfd wait = {fence, POLLIN, 0};
        const int ready = poll(&wait, 1, kWaitMillis);
        if (ready <= 0) {
            // Not released yet: give it back with its fence and try later.
            std::lock_guard<std::mutex> lock(p.mutex);
            p.slots[index].fence = fence;
            p.slots[index].use = Use::Free;
            return -1;
        }
        close(fence);
    }
    return index;
}

// Copies the changed part of [bitmap] into buffer [index] and submits it.
// Returns 0, or a negative number when it could not: the caller stops using
// this path.
extern "C" JNIEXPORT jint JNICALL Java_com_notesis_PresentNative_post(
    JNIEnv* env, jobject, jlong value, jint index, jobject bitmap, jint left, jint top, jint right, jint bottom) {
    if (value == 0 || bitmap == nullptr || index < 0 || index >= kBuffers) return -2;
    Handle presenterRef = *handleOf(value);
    Presenter& p = *presenterRef;

    AndroidBitmapInfo info = {};
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || static_cast<int>(info.width) != p.width ||
        static_cast<int>(info.height) != p.height) {
        return -2;
    }
    Rect dirty{std::max(left, 0), std::max(top, 0), std::min(right, p.width), std::min(bottom, p.height)};
    if (dirty.empty()) return 0;

    // What this buffer is missing: what it already lacked, and this frame's change.
    Rect copy;
    {
        std::lock_guard<std::mutex> lock(p.mutex);
        if (!p.alive || p.slots[index].use != Use::Cpu) return -2;
        copy = p.slots[index].stale;
    }
    unite(copy, dirty);

    void* pixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS || pixels == nullptr) {
        return -2;
    }
    void* address = nullptr;
    const int locked = AHardwareBuffer_lock(p.slots[index].buffer, AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN, -1,
                                            nullptr, &address);
    if (locked != 0 || address == nullptr) {
        AndroidBitmap_unlockPixels(env, bitmap);
        return -2;
    }
    const size_t bytes = static_cast<size_t>(copy.r - copy.l) * 4;
    for (int y = copy.t; y < copy.b; ++y) {
        std::memcpy(static_cast<uint8_t*>(address) + (static_cast<size_t>(y) * p.stride + copy.l) * 4,
                    static_cast<const uint8_t*>(pixels) + static_cast<size_t>(y) * info.stride + copy.l * 4u, bytes);
    }
    AndroidBitmap_unlockPixels(env, bitmap);
    int acquire = -1;
    if (AHardwareBuffer_unlock(p.slots[index].buffer, &acquire) != 0) return -2;

    int replaced = -1;
    {
        std::lock_guard<std::mutex> lock(p.mutex);
        if (!p.alive) {
            if (acquire >= 0) close(acquire);
            return -2;
        }
        // Every other buffer now lacks this frame's change as well.
        for (int i = 0; i < kBuffers; ++i) {
            if (i != index) unite(p.slots[i].stale, dirty);
        }
        p.slots[index].stale = Rect{};
        p.slots[index].use = Use::Shown;
        replaced = p.last;
        p.last = index;
    }

    ASurfaceTransaction* transaction = ASurfaceTransaction_create();
    if (transaction == nullptr) {
        if (acquire >= 0) close(acquire);
        return -2;
    }
    ASurfaceTransaction_setBuffer(transaction, p.control, p.slots[index].buffer, acquire);
    ASurfaceTransaction_setBufferTransparency(transaction, p.control, ASURFACE_TRANSACTION_TRANSPARENCY_TRANSPARENT);
    const ARect damage = {dirty.l, dirty.t, dirty.r, dirty.b};
    ASurfaceTransaction_setDamageRegion(transaction, p.control, &damage, 1);
    if (!p.shown) {
        ASurfaceTransaction_setVisibility(transaction, p.control, ASURFACE_TRANSACTION_VISIBILITY_SHOW);
        ASurfaceTransaction_setZOrder(transaction, p.control, 1);
        p.shown = true;
    }
    ASurfaceTransaction_setOnComplete(transaction, new Completion{presenterRef, replaced}, onComplete);
    ASurfaceTransaction_apply(transaction);
    ASurfaceTransaction_delete(transaction);
    return 0;
}

extern "C" JNIEXPORT void JNICALL Java_com_notesis_PresentNative_destroy(JNIEnv*, jobject, jlong value) {
    if (value == 0) return;
    Handle* handle = handleOf(value);
    release(**handle);
    delete handle;
}
