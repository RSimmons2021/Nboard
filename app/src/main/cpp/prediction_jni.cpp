#include "prediction.h"
#include <jni.h>
#include <android/log.h>

namespace {
std::string string(JNIEnv *env, jstring value) {
    // Java modified UTF-8 differs for supplementary characters. Prediction input
    // is normalized Latin text; use a standard UTF-8 byte conversion for context.
    jclass cls=env->FindClass("java/lang/String");
    jmethodID getBytes=env->GetMethodID(cls,"getBytes","(Ljava/lang/String;)[B");
    jstring encoding=env->NewStringUTF("UTF-8");
    auto bytes=(jbyteArray)env->CallObjectMethod(value,getBytes,encoding);
    std::string result(env->GetArrayLength(bytes),'\0');
    env->GetByteArrayRegion(bytes,0,result.size(),reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(encoding); env->DeleteLocalRef(cls);
    return result;
}
void fail(JNIEnv *env, const std::exception &error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),error.what());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_nboard_ime_prediction_NeuralPredictor_nativeOpen(JNIEnv *env,jobject,jstring path) {
    try { return reinterpret_cast<jlong>(new KeyboardModel(string(env,path))); }
    catch (const std::exception &e) { fail(env,e); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_nboard_ime_prediction_NeuralPredictor_nativeClose(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<KeyboardModel *>(handle);
}
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_nboard_ime_prediction_NeuralPredictor_nativeScore(JNIEnv *env,jobject,jlong handle,jstring context,jstring prefix,jobjectArray input) {
    try {
        std::vector<std::string> candidates;
        for (int i=0;i<env->GetArrayLength(input);++i) {
            auto item=(jstring)env->GetObjectArrayElement(input,i); candidates.push_back(string(env,item)); env->DeleteLocalRef(item);
        }
        auto output=reinterpret_cast<KeyboardModel *>(handle)->score(string(env,context),string(env,prefix),candidates);
        auto result=env->NewObjectArray(output.size()*2,env->FindClass("java/lang/String"),nullptr);
        for (size_t i=0;i<output.size();++i) {
            auto word=env->NewStringUTF(output[i].first.c_str()); auto score=env->NewStringUTF(std::to_string(output[i].second).c_str());
            env->SetObjectArrayElement(result,i*2,word); env->SetObjectArrayElement(result,i*2+1,score);
            env->DeleteLocalRef(word); env->DeleteLocalRef(score);
        }
        return result;
    } catch (const std::exception &e) { fail(env,e); return nullptr; }
}
