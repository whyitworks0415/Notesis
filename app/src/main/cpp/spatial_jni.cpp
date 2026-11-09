#include <jni.h>
#include "spatial_index.h"
#include "erase_geometry.h"
#include "continuous_erase.h"

extern "C" JNIEXPORT jint JNICALL Java_com_notesis_SpatialNative_clipPolyline(
    JNIEnv* env,jobject,jobject points,jint count,jobject sweeps,jint sweepCount,jfloat brushRadius,jobject output) {
    if(count<1||count>65536||sweepCount<1||sweepCount>128||!points||!sweeps||!output||
        env->GetDirectBufferCapacity(points)<jlong(count)*8||env->GetDirectBufferCapacity(sweeps)<jlong(sweepCount)*20) return -2;
    auto size=env->GetDirectBufferCapacity(output);
    if(size<16 || size>16*65536) return -2;
    auto* p=static_cast<notesis::Point*>(env->GetDirectBufferAddress(points));
    auto* s=static_cast<notesis::Sweep*>(env->GetDirectBufferAddress(sweeps));
    auto* o=static_cast<notesis::Span*>(env->GetDirectBufferAddress(output));
    if(reinterpret_cast<std::uintptr_t>(p)%alignof(notesis::Point)||
       reinterpret_cast<std::uintptr_t>(s)%alignof(notesis::Sweep)||
       reinterpret_cast<std::uintptr_t>(o)%alignof(notesis::Span)) return -2;
    return notesis::clipPolyline(p,count,s,sweepCount,brushRadius,o,int(size/16));
}

extern "C" JNIEXPORT jint JNICALL Java_com_notesis_SpatialNative_cutRuns(
    JNIEnv* env, jobject, jobject points, jint count, jfloat ax, jfloat ay, jfloat bx, jfloat by,
    jfloat reach, jobject output) {
    if (count < 0 || count > 65536 || !points || !output ||
        env->GetDirectBufferCapacity(points) < jlong(count) * 8 ||
        env->GetDirectBufferCapacity(output) < jlong(count) * 8) return -2;
    auto* input = static_cast<notesis::Point*>(env->GetDirectBufferAddress(points));
    auto* result = static_cast<jint*>(env->GetDirectBufferAddress(output));
    if (reinterpret_cast<std::uintptr_t>(input) % alignof(notesis::Point) ||
        reinterpret_cast<std::uintptr_t>(result) % alignof(jint)) return -2;
    return notesis::cutRuns(input, count, ax, ay, bx, by, reach, result, count * 2);
}

extern "C" JNIEXPORT jint JNICALL Java_com_notesis_SpatialNative_build(
    JNIEnv* env, jobject, jobject records, jint count, jobject tree) {
    if (count < 0 || count > 200000 || !records || !tree) return -1;
    const auto recordBytes = env->GetDirectBufferCapacity(records);
    const auto treeBytes = env->GetDirectBufferCapacity(tree);
    if (recordBytes < jlong(count) * jlong(sizeof(notesis::Item)) || treeBytes < jlong(notesis::indexCapacity(count)) * jlong(sizeof(notesis::Node))) return -1;
    return notesis::buildIndex(static_cast<notesis::Item*>(env->GetDirectBufferAddress(records)), count,
        static_cast<notesis::Node*>(env->GetDirectBufferAddress(tree)), notesis::indexCapacity(count));
}

extern "C" JNIEXPORT jint JNICALL Java_com_notesis_SpatialNative_query(
    JNIEnv* env, jobject, jobject records, jint count, jobject tree, jint nodes,
    jfloat left, jfloat top, jfloat right, jfloat bottom, jobject result) {
    if (count < 0 || count > 200000 || nodes < 0 || nodes > notesis::indexCapacity(count) || !records || !tree || !result) return -1;
    if (env->GetDirectBufferCapacity(records) < jlong(count) * jlong(sizeof(notesis::Item)) ||
        env->GetDirectBufferCapacity(tree) < jlong(nodes) * jlong(sizeof(notesis::Node)) ||
        env->GetDirectBufferCapacity(result) < jlong(count) * jlong(sizeof(jint))) return -1;
    return notesis::queryIndex(static_cast<const notesis::Item*>(env->GetDirectBufferAddress(records)), count,
        static_cast<const notesis::Node*>(env->GetDirectBufferAddress(tree)), nodes, left, top, right, bottom,
        static_cast<jint*>(env->GetDirectBufferAddress(result)), count);
}
