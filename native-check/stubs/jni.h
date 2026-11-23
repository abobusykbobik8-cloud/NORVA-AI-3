// Заглушка для проверки синтаксиса norva_llama.cpp без Android NDK: повторяет только используемые вызовы JNI.
#pragma once
#include <cstdint>
typedef int32_t jint; typedef int64_t jlong; typedef uint8_t jboolean; typedef int8_t jbyte;
typedef float jfloat; typedef jint jsize;
#define JNI_TRUE 1
#define JNI_FALSE 0
#define JNI_ABORT 2
#define JNIEXPORT
#define JNICALL
struct _jobject {};
typedef _jobject* jobject; typedef jobject jclass; typedef jobject jstring;
typedef jobject jbyteArray; typedef jobject jlongArray;
typedef void* jmethodID;
struct JNIEnv {
    const char* GetStringUTFChars(jstring, jboolean*);
    void ReleaseStringUTFChars(jstring, const char*);
    jbyte* GetByteArrayElements(jbyteArray, jboolean*);
    void ReleaseByteArrayElements(jbyteArray, jbyte*, jint);
    jsize GetArrayLength(jobject);
    jclass GetObjectClass(jobject);
    jmethodID GetMethodID(jclass, const char*, const char*);
    jbyteArray NewByteArray(jsize);
    void SetByteArrayRegion(jbyteArray, jsize, jsize, const jbyte*);
    jboolean CallBooleanMethod(jobject, jmethodID, ...);
    void DeleteLocalRef(jobject);
    jboolean ExceptionCheck();
    void ExceptionClear();
    void SetLongArrayRegion(jlongArray, jsize, jsize, const jlong*);
    jstring NewStringUTF(const char*);
};
