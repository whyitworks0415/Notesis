#include <jni.h>
#include "document_core.h"
#include <cstring>
#include <new>
#include <vector>

namespace {
void fail(JNIEnv* env, const char* type, const char* message) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass(type);
    if (cls) env->ThrowNew(cls, message);
}
bool pathBytes(JNIEnv* env, jbyteArray path, std::vector<std::uint8_t>& bytes) {
    if (!path || env->GetArrayLength(path) < 1 || env->GetArrayLength(path) > 65536) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid document path"); return false;
    }
    bytes.resize(env->GetArrayLength(path));
    env->GetByteArrayRegion(path, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<jbyte*>(bytes.data()));
    return !env->ExceptionCheck();
}
struct OwnedBuffer {
    DocumentBuffer value{nullptr, 0, nullptr};
    ~OwnedBuffer() { notesis_buffer_free(&value); }
};
struct TextInput {
    JNIEnv* env;
    jstring text;
    jint start, end;
    const jchar* chars = nullptr;
    ~TextInput() { if (chars) env->ReleaseStringChars(text, chars); }
};
int textSnapshot(void* context, DocumentTextView* result) {
    auto& input = *static_cast<TextInput*>(context);
    if (!input.chars) input.chars = input.env->GetStringChars(input.text, nullptr);
    if (!input.chars) return -1;
    *result = {input.chars, std::size_t(input.env->GetStringLength(input.text)), input.start, input.end};
    return 0;
}
struct OwnedText {
    DocumentOwnedText value{{nullptr, 0, 0, 0}, nullptr, 0};
    ~OwnedText() { notesis_text_free(&value); }
};
}

extern "C" JNIEXPORT jint JNICALL Java_com_notesis_RustNative_abi(JNIEnv*, jobject) {
    return notesis_document_abi();
}
extern "C" JNIEXPORT jlong JNICALL Java_com_notesis_RustNative_openReader(JNIEnv* env, jobject, jbyteArray path) {
    try {
        std::vector<std::uint8_t> bytes;
        return pathBytes(env, path, bytes) ? static_cast<jlong>(notesis_reader_open(bytes.data(), bytes.size())) : 0;
    } catch (const std::bad_alloc&) { fail(env, "java/lang/OutOfMemoryError", "Document path allocation"); return 0; }
}
extern "C" JNIEXPORT jintArray JNICALL Java_com_notesis_RustNative_readerInfo(JNIEnv* env, jobject, jlong handle) {
    std::int32_t info[2];
    if (notesis_reader_info(static_cast<std::uint64_t>(handle), info) != 0) {
        fail(env, "java/lang/IllegalStateException", "Reader is closed"); return nullptr;
    }
    const auto result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, info);
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL Java_com_notesis_RustNative_readNext(
    JNIEnv* env, jobject, jlong handle, jintArray metadata) {
    if (!metadata || env->GetArrayLength(metadata) < 6) {
        fail(env, "java/lang/IllegalArgumentException", "Metadata needs six slots"); return nullptr;
    }
    OwnedBuffer buffer;
    DocumentHeader header{};
    const int status = notesis_reader_next(static_cast<std::uint64_t>(handle), &header, &buffer.value);
    if (status == 0) return nullptr;
    if (status == -4) {
        fail(env, "java/lang/UnsupportedOperationException", "Page needs the streaming compatibility reader"); return nullptr;
    }
    if (status < 0 || buffer.value.len > 64 * 1024 * 1024) {
        fail(env, "java/io/IOException", "Could not read the next stroke"); return nullptr;
    }
    const auto result = env->NewByteArray(static_cast<jsize>(buffer.value.len));
    if (!result) return nullptr;
    if (buffer.value.len) env->SetByteArrayRegion(result, 0, static_cast<jsize>(buffer.value.len), reinterpret_cast<const jbyte*>(buffer.value.data));
    if (env->ExceptionCheck()) return nullptr;
    jint fields[6] = {header.tool, header.color, 0, 0, header.layer, header.nib};
    std::memcpy(&fields[2], &header.size_bits, 4);
    std::memcpy(&fields[3], &header.epsilon_bits, 4);
    env->SetIntArrayRegion(metadata, 0, 6, fields);
    return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_notesis_RustNative_closeReader(JNIEnv*, jobject, jlong handle) {
    notesis_reader_close(static_cast<std::uint64_t>(handle));
}
extern "C" JNIEXPORT jlong JNICALL Java_com_notesis_RustNative_openWriter(
    JNIEnv* env, jobject, jbyteArray path, jint count) {
    if (count < 0 || count > 1000000) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid stroke count"); return 0;
    }
    try {
        std::vector<std::uint8_t> bytes;
        return pathBytes(env, path, bytes) ? static_cast<jlong>(notesis_writer_open(bytes.data(), bytes.size(), std::uint32_t(count))) : 0;
    } catch (const std::bad_alloc&) { fail(env, "java/lang/OutOfMemoryError", "Document path allocation"); return 0; }
}
extern "C" JNIEXPORT jlong JNICALL Java_com_notesis_RustNative_openAppender(
    JNIEnv* env, jobject, jbyteArray path, jint oldCount, jint count) {
    if (oldCount < 1 || count <= oldCount || count > 1000000) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid append count"); return 0;
    }
    try {
        std::vector<std::uint8_t> bytes;
        return pathBytes(env, path, bytes) ? static_cast<jlong>(notesis_appender_open(bytes.data(), bytes.size(),
            std::uint32_t(oldCount), std::uint32_t(count))) : 0;
    } catch (const std::bad_alloc&) { fail(env, "java/lang/OutOfMemoryError", "Document path allocation"); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_notesis_RustNative_writeNext(
    JNIEnv* env, jobject, jlong handle, jint tool, jint color, jfloat size, jfloat epsilon,
    jbyteArray payload, jint length, jint layer, jint nib) {
    if (!payload || length < 0 || env->GetArrayLength(payload) < length ||
        layer < 0 || layer > 255 || nib < -128 || nib > 127) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid stroke payload"); return;
    }
    if (length > 64 * 1024 * 1024) {
        fail(env, "java/lang/UnsupportedOperationException", "Stroke needs the compatibility writer"); return;
    }
    try {
        DocumentHeader header{tool, color, 0, 0, layer, nib};
        std::memcpy(&header.size_bits, &size, 4);
        std::memcpy(&header.epsilon_bits, &epsilon, 4);
        std::vector<std::uint8_t> input(length);
        if (length) env->GetByteArrayRegion(payload, 0, length, reinterpret_cast<jbyte*>(input.data()));
        if (env->ExceptionCheck()) return;
        const auto status = notesis_writer_push(static_cast<std::uint64_t>(handle), &header, input.data(), input.size());
        if (status == -4) fail(env, "java/lang/UnsupportedOperationException", "Page needs the compatibility writer");
        else if (status != 0)
            fail(env, "java/io/IOException", "Could not write the next stroke");
    } catch (const std::bad_alloc&) { fail(env, "java/lang/OutOfMemoryError", "Stroke write allocation"); }
}
extern "C" JNIEXPORT void JNICALL Java_com_notesis_RustNative_commitWriter(JNIEnv* env, jobject, jlong handle) {
    if (notesis_writer_commit(static_cast<std::uint64_t>(handle)) != 0)
        fail(env, "java/io/IOException", "Could not commit the page replacement");
}
extern "C" JNIEXPORT void JNICALL Java_com_notesis_RustNative_abortWriter(JNIEnv*, jobject, jlong handle) {
    notesis_writer_abort(static_cast<std::uint64_t>(handle));
}

extern "C" JNIEXPORT jlong JNICALL Java_com_notesis_RustNative_newHistory(JNIEnv* env, jobject, jint limit) {
    if (limit < 0 || limit > 10000) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid history limit"); return 0;
    }
    return static_cast<jlong>(notesis_history_create(std::uint32_t(limit)));
}
extern "C" JNIEXPORT jint JNICALL Java_com_notesis_RustNative_recordHistory(
    JNIEnv* env, jobject, jlong handle, jstring before, jint afterLength, jint start, jint end, jboolean typed, jlong now) {
    if (!before || afterLength < 0) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid history state"); return -1;
    }
    TextInput input{env, before, start, end};
    const auto result = notesis_history_record(static_cast<std::uint64_t>(handle),
        std::size_t(env->GetStringLength(before)), std::size_t(afterLength), typed == JNI_TRUE,
        static_cast<std::int64_t>(now), textSnapshot, &input);
    if (result < 0) fail(env, "java/lang/IllegalStateException", "Could not record the edit");
    return result;
}
extern "C" JNIEXPORT jstring JNICALL Java_com_notesis_RustNative_moveHistory(
    JNIEnv* env, jobject, jlong handle, jstring current, jint start, jint end, jboolean redo, jintArray selection) {
    if (!current || !selection || env->GetArrayLength(selection) < 3) {
        fail(env, "java/lang/IllegalArgumentException", "Invalid history selection"); return nullptr;
    }
    TextInput input{env, current, start, end};
    OwnedText output;
    const auto status = notesis_history_move(static_cast<std::uint64_t>(handle), redo == JNI_TRUE, textSnapshot, &input, &output.value);
    if (status == 0) return nullptr;
    if (status < 0 || output.value.value.len > std::size_t(INT32_MAX)) {
        fail(env, "java/lang/IllegalStateException", "Could not restore the edit"); return nullptr;
    }
    const jchar empty = 0;
    const auto result = env->NewString(output.value.value.len ? output.value.value.data : &empty,
        static_cast<jsize>(output.value.value.len));
    if (!result) return nullptr;
    const jint fields[] = {output.value.value.start, output.value.value.end, output.value.flags};
    env->SetIntArrayRegion(selection, 0, 3, fields);
    return result;
}
extern "C" JNIEXPORT void JNICALL Java_com_notesis_RustNative_closeHistory(JNIEnv*, jobject, jlong handle) {
    notesis_history_close(static_cast<std::uint64_t>(handle));
}
