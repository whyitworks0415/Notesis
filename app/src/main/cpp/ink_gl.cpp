// The GPU ink renderer: one tile of a page drawn from its mesh cache in a single
// draw call, on a GL context of its own, into a hardware buffer that the app
// shows as a bitmap without copying it.
//
// The render thread used to paint a dense tile stroke by stroke - one mesh
// draw for each of a few thousand strokes - in the frame that first showed it.
// Here the strokes under the tile are batched into one vertex buffer
// (ink_batch.cpp) and drawn at once with 4x multisampling for the edges, on
// the worker that asked, away from the frame.
//
// An experiment behind a switch (NativeInk.kt): any failure here comes back as
// null, and the tile is painted the usual way.
#include <jni.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>

#include <cstdio>
#include <memory>
#include <mutex>
#include <vector>

#include "ink_batch.h"

namespace {

using notesis::PageMeshes;
using notesis::TileBatch;

const char* kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 position;
layout(location = 1) in vec4 color;
uniform vec2 size;
out vec4 tint;
void main() {
    tint = color;
    gl_Position = vec4(position / size * 2.0 - 1.0, 0.0, 1.0);
}
)";

const char* kFragmentShader = R"(#version 300 es
precision mediump float;
in vec4 tint;
out vec4 outColor;
void main() {
    outColor = vec4(tint.rgb * tint.a, tint.a);
}
)";

GLuint compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    if (shader == 0) return 0;
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (ok != GL_TRUE) {
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

class Renderer {
public:
    ~Renderer() { release(); }

    bool init() {
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || !eglInitialize(display_, nullptr, nullptr)) return false;
        const EGLint configAttributes[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
        EGLint count = 0;
        if (!eglChooseConfig(display_, configAttributes, &config_, 1, &count) || count < 1) return false;
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, contextAttributes);
        if (context_ == EGL_NO_CONTEXT) return false;
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface_ = eglCreatePbufferSurface(display_, config_, surfaceAttributes);
        if (surface_ == EGL_NO_SURFACE) return false;
        getClientBuffer_ = reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
        createImage_ = reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
        destroyImage_ = reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
        imageTarget_ = reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
        if (!getClientBuffer_ || !createImage_ || !destroyImage_ || !imageTarget_) return false;
        if (!eglMakeCurrent(display_, surface_, surface_, context_)) return false;
        bool ok = buildProgram();
        GLint samples = 0;
        glGetIntegerv(GL_MAX_SAMPLES, &samples);
        samples_ = samples >= 4 ? 4 : (samples > 0 ? samples : 0);
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return ok;
    }

    // The tile, or null when anything on the way failed.
    AHardwareBuffer* render(const PageMeshes& page, float left, float top, float scale, int width, int height) {
        std::lock_guard<std::mutex> lock(mutex_);
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) return nullptr;
        // An empty tile is still drawn (cleared): null means only that something failed.
        notesis::batchTile(page, left, top, scale, width, height, batch_);
        if (!eglMakeCurrent(display_, surface_, surface_, context_)) return nullptr;
        AHardwareBuffer* buffer = draw(width, height);
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        return buffer;
    }

private:
    bool buildProgram() {
        GLuint vertex = compile(GL_VERTEX_SHADER, kVertexShader);
        GLuint fragment = compile(GL_FRAGMENT_SHADER, kFragmentShader);
        if (vertex == 0 || fragment == 0) return false;
        program_ = glCreateProgram();
        glAttachShader(program_, vertex);
        glAttachShader(program_, fragment);
        glLinkProgram(program_);
        glDeleteShader(vertex);
        glDeleteShader(fragment);
        GLint ok = GL_FALSE;
        glGetProgramiv(program_, GL_LINK_STATUS, &ok);
        if (ok != GL_TRUE) return false;
        sizeUniform_ = glGetUniformLocation(program_, "size");
        glGenBuffers(1, &vertexBuffer_);
        glGenBuffers(1, &indexBuffer_);
        return sizeUniform_ >= 0;
    }

    AHardwareBuffer* draw(int width, int height) {
        AHardwareBuffer_Desc desc{};
        desc.width = std::uint32_t(width);
        desc.height = std::uint32_t(height);
        desc.layers = 1;
        desc.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
        desc.usage = AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
        AHardwareBuffer* buffer = nullptr;
        if (AHardwareBuffer_allocate(&desc, &buffer) != 0 || buffer == nullptr) return nullptr;
        bool ok = false;
        EGLImageKHR image = EGL_NO_IMAGE_KHR;
        GLuint texture = 0, resolve = 0, multisample = 0, samples = 0;
        do {
            EGLClientBuffer client = getClientBuffer_(buffer);
            if (client == nullptr) break;
            const EGLint imageAttributes[] = {EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE};
            image = createImage_(display_, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, client, imageAttributes);
            if (image == EGL_NO_IMAGE_KHR) break;
            glGenTextures(1, &texture);
            glBindTexture(GL_TEXTURE_2D, texture);
            imageTarget_(GL_TEXTURE_2D, static_cast<GLeglImageOES>(image));
            glGenFramebuffers(1, &resolve);
            glBindFramebuffer(GL_FRAMEBUFFER, resolve);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) break;
            GLuint target = resolve;
            if (samples_ > 1) {
                // Drawn multisampled, then resolved into the buffer: the edges' antialiasing.
                glGenRenderbuffers(1, &samples);
                glBindRenderbuffer(GL_RENDERBUFFER, samples);
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples_, GL_RGBA8, width, height);
                glGenFramebuffers(1, &multisample);
                glBindFramebuffer(GL_FRAMEBUFFER, multisample);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, samples);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) break;
                target = multisample;
            }
            glBindFramebuffer(GL_FRAMEBUFFER, target);
            glViewport(0, 0, width, height);
            glDisable(GL_BLEND);
            glDisable(GL_DEPTH_TEST);
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            glUseProgram(program_);
            glUniform2f(sizeUniform_, float(width), float(height));
            glBindBuffer(GL_ARRAY_BUFFER, vertexBuffer_);
            glBufferData(GL_ARRAY_BUFFER, GLsizeiptr(batch_.vertices.size()), batch_.vertices.data(), GL_STREAM_DRAW);
            glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, indexBuffer_);
            glBufferData(GL_ELEMENT_ARRAY_BUFFER, GLsizeiptr(batch_.indices.size() * 4), batch_.indices.data(),
                         GL_STREAM_DRAW);
            glEnableVertexAttribArray(0);
            glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, GLsizei(notesis::kBatchStride), nullptr);
            glEnableVertexAttribArray(1);
            glVertexAttribPointer(1, 4, GL_UNSIGNED_BYTE, GL_TRUE, GLsizei(notesis::kBatchStride),
                                  reinterpret_cast<const void*>(8));
            if (!batch_.indices.empty()) glDrawElements(GL_TRIANGLES, GLsizei(batch_.indices.size()), GL_UNSIGNED_INT, nullptr);
            if (target != resolve) {
                glBindFramebuffer(GL_READ_FRAMEBUFFER, multisample);
                glBindFramebuffer(GL_DRAW_FRAMEBUFFER, resolve);
                glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
            }
            // The buffer is read by another context (the screen's) next: finished here first.
            glFinish();
            ok = glGetError() == GL_NO_ERROR;
        } while (false);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (multisample) glDeleteFramebuffers(1, &multisample);
        if (samples) glDeleteRenderbuffers(1, &samples);
        if (resolve) glDeleteFramebuffers(1, &resolve);
        if (texture) glDeleteTextures(1, &texture);
        if (image != EGL_NO_IMAGE_KHR) destroyImage_(display_, image);
        if (!ok) {
            AHardwareBuffer_release(buffer);
            return nullptr;
        }
        return buffer;
    }

    void release() {
        if (display_ == EGL_NO_DISPLAY) return;
        if (context_ != EGL_NO_CONTEXT && eglMakeCurrent(display_, surface_, surface_, context_)) {
            if (program_) glDeleteProgram(program_);
            if (vertexBuffer_) glDeleteBuffers(1, &vertexBuffer_);
            if (indexBuffer_) glDeleteBuffers(1, &indexBuffer_);
            eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        }
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        display_ = EGL_NO_DISPLAY;
    }

    std::mutex mutex_;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig config_ = nullptr;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getClientBuffer_ = nullptr;
    PFNEGLCREATEIMAGEKHRPROC createImage_ = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC destroyImage_ = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imageTarget_ = nullptr;
    GLuint program_ = 0, vertexBuffer_ = 0, indexBuffer_ = 0;
    GLint sizeUniform_ = -1;
    GLint samples_ = 0;
    TileBatch batch_;
};

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_notesis_NativeInk_nativeCreate(JNIEnv*, jobject) {
    auto renderer = std::make_unique<Renderer>();
    if (!renderer->init()) return 0;
    return reinterpret_cast<jlong>(renderer.release());
}

JNIEXPORT void JNICALL Java_com_notesis_NativeInk_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Renderer*>(handle);
}

// Reads and checks a page's mesh cache; 0 when it is missing, damaged or made for another version of the page.
JNIEXPORT jlong JNICALL Java_com_notesis_NativeInk_nativeOpenPage(JNIEnv* env, jobject, jstring path, jlong stamp) {
    if (path == nullptr) return 0;
    const char* name = env->GetStringUTFChars(path, nullptr);
    if (name == nullptr) return 0;
    std::FILE* file = std::fopen(name, "rb");
    env->ReleaseStringUTFChars(path, name);
    if (file == nullptr) return 0;
    std::vector<std::uint8_t> bytes;
    std::uint8_t chunk[1 << 16];
    std::size_t read = 0;
    while ((read = std::fread(chunk, 1, sizeof(chunk), file)) > 0) {
        bytes.insert(bytes.end(), chunk, chunk + read);
        if (bytes.size() > (std::size_t(256) << 20)) break;  // past any real page: not read further
    }
    std::fclose(file);
    auto page = std::make_unique<PageMeshes>();
    if (!notesis::parseMeshCache(bytes.data(), bytes.size(), stamp, *page)) return 0;
    return reinterpret_cast<jlong>(page.release());
}

JNIEXPORT void JNICALL Java_com_notesis_NativeInk_nativeClosePage(JNIEnv*, jobject, jlong page) {
    delete reinterpret_cast<PageMeshes*>(page);
}

JNIEXPORT jobject JNICALL Java_com_notesis_NativeInk_nativeRender(JNIEnv* env, jobject, jlong handle, jlong page,
                                                                   jfloat left, jfloat top, jfloat scale,
                                                                   jint width, jint height) {
    auto* renderer = reinterpret_cast<Renderer*>(handle);
    auto* meshes = reinterpret_cast<PageMeshes*>(page);
    if (renderer == nullptr || meshes == nullptr) return nullptr;
    AHardwareBuffer* buffer = renderer->render(*meshes, left, top, scale, width, height);
    if (buffer == nullptr) return nullptr;
    jobject wrapped = AHardwareBuffer_toHardwareBuffer(env, buffer);
    AHardwareBuffer_release(buffer);  // the Java object holds its own reference
    return wrapped;
}

}  // extern "C"
