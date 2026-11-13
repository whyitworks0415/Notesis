#include <jni.h>
#include "note_core.h"
#include <cmath>
#include <new>
#include <stdexcept>

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}
}

extern "C" JNIEXPORT jbooleanArray JNICALL Java_com_notesis_CppCore_tidyMask(
    JNIEnv* env, jobject, jfloatArray xs, jfloatArray ys, jlongArray times,
    jint count, jfloat width, jint mode) {
    if (!xs || !ys || !times || count < 0 || count > 1'048'576 ||
        env->GetArrayLength(xs) < count || env->GetArrayLength(ys) < count ||
        env->GetArrayLength(times) < count || !std::isfinite(width) || mode < 0 || mode > 2) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid tidy samples");
        return nullptr;
    }
    try {
        // Copy only the used prefix, never a worker's larger retained capacity.
        // No JVM array remains pinned while the geometry algorithm runs.
        std::vector<float> x(count), y(count);
        std::vector<jlong> rawTimes(count);
        if (count) {
            env->GetFloatArrayRegion(xs, 0, count, x.data());
            if (env->ExceptionCheck()) return nullptr;
            env->GetFloatArrayRegion(ys, 0, count, y.data());
            if (env->ExceptionCheck()) return nullptr;
            env->GetLongArrayRegion(times, 0, count, rawTimes.data());
            if (env->ExceptionCheck()) return nullptr;
        }
        // jlong is not necessarily the same C++ type as int64_t on the host.
        std::vector<std::int64_t> t(rawTimes.begin(), rawTimes.end());
        const auto mask = notesis::tidyInputs(x.data(), y.data(), t.data(), count, width, mode);
        if (mask.empty()) return nullptr;
        const auto result = env->NewBooleanArray(count);
        if (result) env->SetBooleanArrayRegion(result, 0, count, mask.data());
        return result;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Native tidy workspace");
    } catch (const std::exception&) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid tidy samples");
    }
    return nullptr;
}

extern "C" JNIEXPORT jintArray JNICALL Java_com_notesis_CppCore_scanPage(
    JNIEnv* env, jobject, jobject source, jint length) {
    if (!source || length < 0 || env->GetDirectBufferCapacity(source) < length) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid page buffer");
        return nullptr;
    }
    try {
        const auto* bytes = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(source));
        const auto index = notesis::scanPage(bytes, std::size_t(length));
        if (index.empty()) return nullptr;
        const auto result = env->NewIntArray(static_cast<jsize>(index.size()));
        // jint and int32_t are both signed 32-bit int on the supported ABIs.
        if (result) env->SetIntArrayRegion(result, 0, static_cast<jsize>(index.size()), index.data());
        return result;
    } catch (const std::bad_alloc&) {
        fail(env, "java/lang/OutOfMemoryError", "Native page index");
    } catch (const std::exception&) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid page records");
    }
    return nullptr;
}
