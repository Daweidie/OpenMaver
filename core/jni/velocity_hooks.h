#pragma once

#include <jni.h>
#include <jvmti.h>
#include <atomic>

// Shared by the attach agent and the isolated JVM retransformation regression.
namespace openmaver_velocity {

struct VelocityTransformContext {
    jobject transformer;
    jmethodID transform;
    jclass classes[4];
    jstring names[4];
    int count;
    std::atomic<bool> failed{false};
    std::atomic<unsigned> seen{0};
};
std::atomic<VelocityTransformContext*> velocityContext{nullptr};

void JNICALL velocityClassHook(jvmtiEnv* jvmti, JNIEnv* env, jclass redefining,
        jobject, const char* name, jobject, jint length, const unsigned char* bytes,
        jint* newLength, unsigned char** newBytes) {
    VelocityTransformContext* context=velocityContext.load();
    if(context==nullptr||redefining==nullptr)return;
    int target=-1;
    for(int i=0;i<context->count;i++)if(env->IsSameObject(redefining,context->classes[i])) {target=i;break;}
    if(target<0)return;
    context->seen.fetch_or(1u << target);
    jbyteArray input=env->NewByteArray(length);
    if(input==nullptr) {context->failed=true;env->ExceptionClear();return;}
    env->SetByteArrayRegion(input,0,length,reinterpret_cast<const jbyte*>(bytes));
    jstring original=env->NewStringUTF(name!=nullptr?name:"");
    auto output=static_cast<jbyteArray>(env->CallObjectMethod(context->transformer,
            context->transform,original,context->names[target],input));
    if(env->ExceptionCheck()||output==nullptr) {
        env->ExceptionDescribe();env->ExceptionClear();context->failed=true;
    } else {
        jint size=env->GetArrayLength(output);
        unsigned char* memory=nullptr;
        if(jvmti->Allocate(size,&memory)!=JVMTI_ERROR_NONE)context->failed=true;
        else {env->GetByteArrayRegion(output,0,size,reinterpret_cast<jbyte*>(memory));*newLength=size;*newBytes=memory;}
    }
    if(output!=nullptr)env->DeleteLocalRef(output);
    env->DeleteLocalRef(original);env->DeleteLocalRef(input);
}

jboolean JNICALL installVelocityHooks(JNIEnv* env,jclass,jobject transformer,
        jobjectArray classes,jobjectArray names) {
    JavaVM* vm=nullptr;env->GetJavaVM(&vm);
    jvmtiEnv* jvmti=nullptr;
    if(vm->GetEnv(reinterpret_cast<void**>(&jvmti),JVMTI_VERSION_1_2)!=JNI_OK)return JNI_FALSE;
    int count=env->GetArrayLength(classes);
    if(count<1||count>4||env->GetArrayLength(names)!=count||velocityContext!=nullptr)return JNI_FALSE;
    jvmtiCapabilities caps{};caps.can_retransform_classes=1;
    if(jvmti->AddCapabilities(&caps)!=JVMTI_ERROR_NONE)return JNI_FALSE;
    VelocityTransformContext context{};
    context.count=count;
    context.transformer=env->NewGlobalRef(transformer);
    jclass type=env->GetObjectClass(transformer);
    context.transform=env->GetMethodID(type,"transform","(Ljava/lang/String;Ljava/lang/String;[B)[B");
    env->DeleteLocalRef(type);
    if(context.transform==nullptr) {env->ExceptionClear();env->DeleteGlobalRef(context.transformer);return JNI_FALSE;}
    for(int i=0;i<count;i++) {
        jobject target=env->GetObjectArrayElement(classes,i),name=env->GetObjectArrayElement(names,i);
        context.classes[i]=static_cast<jclass>(env->NewGlobalRef(target));
        context.names[i]=static_cast<jstring>(env->NewGlobalRef(name));
        env->DeleteLocalRef(target);env->DeleteLocalRef(name);
    }
    jvmtiEventCallbacks callbacks{};callbacks.ClassFileLoadHook=&velocityClassHook;
    jvmtiError error=jvmti->SetEventCallbacks(&callbacks,sizeof(callbacks));
    if(error==JVMTI_ERROR_NONE) {
        velocityContext=&context;
        error=jvmti->SetEventNotificationMode(JVMTI_ENABLE,JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,nullptr);
        if(error==JVMTI_ERROR_NONE)error=jvmti->RetransformClasses(count,context.classes);
        jvmti->SetEventNotificationMode(JVMTI_DISABLE,JVMTI_EVENT_CLASS_FILE_LOAD_HOOK,nullptr);
        velocityContext=nullptr;
    }
    for(int i=0;i<count;i++) {env->DeleteGlobalRef(context.classes[i]);env->DeleteGlobalRef(context.names[i]);}
    env->DeleteGlobalRef(context.transformer);
    return error==JVMTI_ERROR_NONE&&!context.failed&&context.seen==((1u<<count)-1)?JNI_TRUE:JNI_FALSE;
}

} // namespace openmaver_velocity
