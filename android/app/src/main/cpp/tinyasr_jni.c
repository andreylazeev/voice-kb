#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include "tinyasr.h"

static jbyteArray event_bytes(JNIEnv *env, const char *json) {
    if (!json) return NULL;
    size_t length = strlen(json);
    if (length > INT32_MAX) return NULL;
    jbyteArray result = (*env)->NewByteArray(env, (jsize)length);
    if (result && length) (*env)->SetByteArrayRegion(env, result, 0, (jsize)length, (const jbyte *)json);
    return result;
}

JNIEXPORT jlong JNICALL Java_ly_anoma_hex_speech_TinyAsr_load(
    JNIEnv *env, jobject instance, jbyteArray weights, jbyteArray detector) {
    jbyte *model = (*env)->GetByteArrayElements(env, weights, NULL);
    jbyte *vad = (*env)->GetByteArrayElements(env, detector, NULL);
    tinyasr_models *loaded = NULL;
    if (model && vad) {
        loaded = tinyasr_load((const uint8_t *)model, (*env)->GetArrayLength(env, weights),
                             (const uint8_t *)vad, (*env)->GetArrayLength(env, detector), NULL, 0);
        if (loaded) tinyasr_set_threads(loaded, 2);
    }
    if (vad) (*env)->ReleaseByteArrayElements(env, detector, vad, JNI_ABORT);
    if (model) (*env)->ReleaseByteArrayElements(env, weights, model, JNI_ABORT);
    return (jlong)(intptr_t)loaded;
}

JNIEXPORT jlong JNICALL Java_ly_anoma_hex_speech_TinyAsr_openSession(
    JNIEnv *env, jobject instance, jlong handle, jstring language) {
    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    if (!lang) return 0;
    tinyasr_session *session = tinyasr_session_new((tinyasr_models *)(intptr_t)handle, lang, NULL, NULL, 4);
    (*env)->ReleaseStringUTFChars(env, language, lang);
    return (jlong)(intptr_t)session;
}

JNIEXPORT jbyteArray JNICALL Java_ly_anoma_hex_speech_TinyAsr_pushSession(
    JNIEnv *env, jobject instance, jlong handle, jshortArray audio, jint offset, jint count) {
    if (!handle || offset < 0 || count <= 0 || offset > (*env)->GetArrayLength(env, audio) - count) return NULL;
    float *pcm = malloc((size_t)count * sizeof(*pcm));
    if (!pcm) return NULL;
    jshort *samples = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!samples) { free(pcm); return NULL; }
    for (jint i = 0; i < count; i++) pcm[i] = samples[offset + i] / 32768.0f;
    (*env)->ReleaseShortArrayElements(env, audio, samples, JNI_ABORT);
    const char *events = tinyasr_session_push((tinyasr_session *)(intptr_t)handle, pcm, count);
    free(pcm);
    return event_bytes(env, events);
}

JNIEXPORT jbyteArray JNICALL Java_ly_anoma_hex_speech_TinyAsr_finishSession(
    JNIEnv *env, jobject instance, jlong handle) {
    return event_bytes(env, tinyasr_session_finish((tinyasr_session *)(intptr_t)handle));
}

JNIEXPORT void JNICALL Java_ly_anoma_hex_speech_TinyAsr_closeSession(
    JNIEnv *env, jobject instance, jlong handle) {
    tinyasr_session_free((tinyasr_session *)(intptr_t)handle);
}

JNIEXPORT jbyteArray JNICALL Java_ly_anoma_hex_speech_TinyAsr_transcribe(
    JNIEnv *env, jobject instance, jlong handle, jshortArray audio, jint count, jstring language) {
    if (count <= 0 || count > (*env)->GetArrayLength(env, audio)) return NULL;
    float *pcm = malloc((size_t)count * sizeof(*pcm));
    if (!pcm) return NULL;
    jshort *samples = (*env)->GetShortArrayElements(env, audio, NULL);
    if (!samples) { free(pcm); return NULL; }
    for (jint i = 0; i < count; i++) pcm[i] = samples[i] / 32768.0f;
    (*env)->ReleaseShortArrayElements(env, audio, samples, JNI_ABORT);

    const char *lang = (*env)->GetStringUTFChars(env, language, NULL);
    tinyasr_file *file = lang ? tinyasr_file_new((tinyasr_models *)(intptr_t)handle, pcm, count, lang, 4) : NULL;
    if (lang) (*env)->ReleaseStringUTFChars(env, language, lang);
    free(pcm);
    if (!file) return NULL;

    int failed = 0;
    size_t size = 0;
    char *lines = NULL;
    const char *piece;
    while ((piece = tinyasr_file_next(file)) != NULL) {
        size_t len = strlen(piece);
        if (len + 1 > INT32_MAX - size) { failed = 1; break; }
        char *extended = realloc(lines, size + len + 1);
        if (!extended) { failed = 1; break; }
        lines = extended;
        memcpy(lines + size, piece, len);
        size += len;
        lines[size++] = '\n';
    }
    tinyasr_file_free(file);
    jbyteArray result = failed ? NULL : (*env)->NewByteArray(env, (jsize)size);
    if (result && size) (*env)->SetByteArrayRegion(env, result, 0, (jsize)size, (jbyte *)lines);
    free(lines);
    return result;
}
