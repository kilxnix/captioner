#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include "whisper.h"

#define LOG_TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C"
JNIEXPORT jlong JNICALL
Java_com_sheltron_captioner_audio_WhisperCpp_nativeInit(
        JNIEnv *env, jclass, jstring jModelPath) {
    const char *path = env->GetStringUTFChars(jModelPath, nullptr);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;
    struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jModelPath, path);
    if (ctx == nullptr) {
        LOGE("whisper_init_from_file_with_params failed for %s", path);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_sheltron_captioner_audio_WhisperCpp_nativeRelease(
        JNIEnv *, jclass, jlong ctxPtr) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx != nullptr) {
        whisper_free(ctx);
    }
}

extern "C"
JNIEXPORT jobjectArray JNICALL
Java_com_sheltron_captioner_audio_WhisperCpp_nativeTranscribe(
        JNIEnv *env, jclass, jlong ctxPtr, jfloatArray jAudio, jint nThreads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ctxPtr);
    if (ctx == nullptr) return nullptr;

    jsize len = env->GetArrayLength(jAudio);
    jfloat *samples = env->GetFloatArrayElements(jAudio, nullptr);
    if (samples == nullptr || len <= 0) {
        if (samples) env->ReleaseFloatArrayElements(jAudio, samples, 0);
        return nullptr;
    }

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.n_threads = nThreads > 0 ? nThreads : 4;
    params.language = "en";
    params.translate = false;
    params.no_context = true;
    params.single_segment = false;
    params.print_progress = false;
    params.print_special = false;
    params.print_realtime = false;
    params.print_timestamps = false;
    params.token_timestamps = false;

    int ret = whisper_full(ctx, params, samples, len);
    env->ReleaseFloatArrayElements(jAudio, samples, 0);

    if (ret != 0) {
        LOGE("whisper_full failed: %d", ret);
        return nullptr;
    }

    int n_segments = whisper_full_n_segments(ctx);
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(n_segments, stringClass, nullptr);
    for (int i = 0; i < n_segments; i++) {
        int64_t t0 = whisper_full_get_segment_t0(ctx, i) * 10; // 10ms units → ms
        int64_t t1 = whisper_full_get_segment_t1(ctx, i) * 10;
        const char *text = whisper_full_get_segment_text(ctx, i);
        std::string s = std::to_string(t0) + "|" + std::to_string(t1) + "|" + (text ? text : "");
        env->SetObjectArrayElement(result, i, env->NewStringUTF(s.c_str()));
    }
    return result;
}
