#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include "quickjs.h"

#define MAX_WIRE_BYTES (1024 * 1024)

typedef struct {
    JNIEnv *env;
    jobject bridge;
    jmethodID post_message;
    int64_t deadline_ns;
    int timed_out;
} Invocation;

static int64_t monotonic_ns(void) {
    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    return ((int64_t) now.tv_sec * 1000000000LL) + now.tv_nsec;
}

static int interrupt_js(JSRuntime *runtime, void *opaque) {
    (void) runtime;
    Invocation *call = opaque;
    if (monotonic_ns() >= call->deadline_ns) {
        call->timed_out = 1;
        return 1;
    }
    return 0;
}

static void throw_host(JNIEnv *env, const char *message) {
    jclass error = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (!error) return;
    jclass string_type = (*env)->FindClass(env, "java/lang/String");
    if (!string_type) { (*env)->DeleteLocalRef(env, error); return; }
    jsize length = (jsize) strlen(message);
    jbyteArray bytes = (*env)->NewByteArray(env, length);
    if (!bytes) goto end;
    (*env)->SetByteArrayRegion(env, bytes, 0, length, (const jbyte *) message);
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    if (!charset) { (*env)->DeleteLocalRef(env, bytes); goto end; }
    jmethodID string_constructor = (*env)->GetMethodID(env, string_type, "<init>", "([BLjava/lang/String;)V");
    jstring text = string_constructor ? (*env)->NewObject(env, string_type, string_constructor, bytes, charset) : NULL;
    (*env)->DeleteLocalRef(env, bytes); (*env)->DeleteLocalRef(env, charset);
    if (text) {
        jmethodID error_constructor = (*env)->GetMethodID(env, error, "<init>", "(Ljava/lang/String;)V");
        jthrowable exception = error_constructor ? (*env)->NewObject(env, error, error_constructor, text) : NULL;
        (*env)->DeleteLocalRef(env, text);
        if (exception) { (*env)->Throw(env, exception); (*env)->DeleteLocalRef(env, exception); }
    }
end:
    (*env)->DeleteLocalRef(env, string_type); (*env)->DeleteLocalRef(env, error);
}

static char *copy_bytes(JNIEnv *env, jbyteArray value, size_t *size) {
    if (!value) return NULL;
    jsize length = (*env)->GetArrayLength(env, value);
    if (length < 0 || length > MAX_WIRE_BYTES) return NULL;
    char *buffer = malloc((size_t) length + 1);
    if (!buffer) return NULL;
    (*env)->GetByteArrayRegion(env, value, 0, length, (jbyte *) buffer);
    if ((*env)->ExceptionCheck(env)) { free(buffer); return NULL; }
    buffer[length] = '\0';
    *size = (size_t) length;
    return buffer;
}

static JSValue post_message(JSContext *context, JSValueConst receiver, int argc, JSValueConst *argv) {
    (void) receiver;
    Invocation *call = JS_GetContextOpaque(context);
    if (interrupt_js(JS_GetRuntime(context), call)) return JS_ThrowInternalError(context, "Execution timeout");
    if (argc != 1 || !JS_IsString(argv[0])) return JS_ThrowTypeError(context, "postMessage requires one JSON string");
    size_t size;
    const char *request = JS_ToCStringLen(context, &size, argv[0]);
    if (!request) return JS_EXCEPTION;
    if (size > MAX_WIRE_BYTES) {
        JS_FreeCString(context, request);
        return JS_ThrowRangeError(context, "Host request is too large");
    }
    JNIEnv *env = call->env;
    jbyteArray input = (*env)->NewByteArray(env, (jsize) size);
    if (!input) {
        JS_FreeCString(context, request);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JS_ThrowOutOfMemory(context);
    }
    (*env)->SetByteArrayRegion(env, input, 0, (jsize) size, (const jbyte *) request);
    JS_FreeCString(context, request);
    jbyteArray output = (*env)->CallObjectMethod(env, call->bridge, call->post_message, input);
    (*env)->DeleteLocalRef(env, input);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        if (output) (*env)->DeleteLocalRef(env, output);
        return JS_ThrowInternalError(context, "Host bridge failed");
    }
    size_t response_size = 0;
    char *response = copy_bytes(env, output, &response_size);
    if (output) (*env)->DeleteLocalRef(env, output);
    if (!response) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return JS_ThrowInternalError(context, "Invalid host response");
    }
    JSValue result = JS_NewStringLen(context, response, response_size);
    free(response);
    if (interrupt_js(JS_GetRuntime(context), call)) {
        JS_FreeValue(context, result);
        return JS_ThrowInternalError(context, "Execution timeout");
    }
    return result;
}

static void throw_js(JNIEnv *env, JSContext *context, Invocation *call) {
    if (call->timed_out) { throw_host(env, "QuickJS execution timeout (maximum 2000ms)"); return; }
    JSValue error = JS_GetException(context);
    const char *description = JS_ToCString(context, error);
    char message[512];
    // QuickJS's built-in quota errors are ASCII. Bound the diagnostic; never return host internals.
    snprintf(message, sizeof(message), "QuickJS execution failed: %.440s", description ? description : "out of memory");
    if (description) JS_FreeCString(context, description);
    JS_FreeValue(context, error);
    throw_host(env, call->timed_out ? "QuickJS execution timeout (maximum 2000ms)" : message);
}

JNIEXPORT jbyteArray JNICALL
Java_com_planlock_plugin_QuickJsEngine_evaluateNative(JNIEnv *env, jobject self, jbyteArray source_bytes,
    jbyteArray entry_bytes, jbyteArray event_bytes, jobject bridge, jlong memory_bytes,
    jlong stack_bytes, jlong timeout_millis) {
    (void) self;
    jbyteArray output = NULL;
    JSRuntime *runtime = NULL;
    JSContext *context = NULL;
    JSValue global = JS_UNDEFINED, host = JS_UNDEFINED, entry = JS_UNDEFINED;
    JSValue event = JS_UNDEFINED, result = JS_UNDEFINED, encoded = JS_UNDEFINED;
    size_t source_size = 0, entry_size = 0, event_size = 0;
    char *source = copy_bytes(env, source_bytes, &source_size);
    char *entry_name = copy_bytes(env, entry_bytes, &entry_size);
    char *event_json = copy_bytes(env, event_bytes, &event_size);
    Invocation call = { .env = env, .bridge = bridge, .post_message = NULL,
        .deadline_ns = monotonic_ns() + timeout_millis * 1000000LL, .timed_out = 0 };
    if ((*env)->ExceptionCheck(env)) goto cleanup;
    if (!source || !entry_name || !event_json || entry_size > 256 || !bridge ||
        memory_bytes < 512 * 1024 || memory_bytes > 32 * 1024 * 1024 ||
        stack_bytes < 64 * 1024 || stack_bytes > 512 * 1024 || timeout_millis < 1 || timeout_millis > 2000) {
        throw_host(env, "Invalid QuickJS invocation limits or input"); goto cleanup;
    }
    jclass bridge_type = (*env)->GetObjectClass(env, bridge);
    call.post_message = (*env)->GetMethodID(env, bridge_type, "postMessageUtf8", "([B)[B");
    (*env)->DeleteLocalRef(env, bridge_type);
    if (!call.post_message || (*env)->ExceptionCheck(env)) goto cleanup;
    runtime = JS_NewRuntime();
    if (!runtime) { throw_host(env, "Cannot allocate QuickJS runtime"); goto cleanup; }
    JS_SetMemoryLimit(runtime, (size_t) memory_bytes);
    JS_SetMaxStackSize(runtime, (size_t) stack_bytes);
    JS_SetInterruptHandler(runtime, interrupt_js, &call);
    // No quickjs-libc, module loader, std/os helpers, or JVM object references are registered.
    context = JS_NewContext(runtime);
    if (!context) { throw_host(env, "QuickJS context memory limit exceeded"); goto cleanup; }
    JS_SetContextOpaque(context, &call);
    result = JS_Eval(context, source, source_size, "index.js", JS_EVAL_TYPE_GLOBAL);
    if (JS_IsException(result)) { throw_js(env, context, &call); goto cleanup; }
    JS_FreeValue(context, result); result = JS_UNDEFINED;
    global = JS_GetGlobalObject(context);
    entry = JS_GetPropertyStr(context, global, entry_name);
    if (JS_IsException(entry)) { throw_js(env, context, &call); goto cleanup; }
    if (!JS_IsFunction(context, entry)) { throw_host(env, "JS entry point is not a function"); goto cleanup; }
    host = JS_NewObject(context);
    if (JS_IsException(host) || JS_DefinePropertyValueStr(context, host, "postMessage",
        JS_NewCFunction(context, post_message, "postMessage", 1), JS_PROP_ENUMERABLE) < 0 ||
        JS_PreventExtensions(context, host) < 0) { throw_js(env, context, &call); goto cleanup; }
    event = JS_ParseJSON(context, event_json, event_size, "event.json");
    if (JS_IsException(event)) { throw_js(env, context, &call); goto cleanup; }
    JSValue args[2] = { event, host };
    result = JS_Call(context, entry, JS_UNDEFINED, 2, args);
    if (JS_IsException(result)) { throw_js(env, context, &call); goto cleanup; }
    if ((int) JS_PromiseState(context, result) >= 0) {
        throw_host(env, "JS entry point must return synchronously; Promise is unsupported"); goto cleanup;
    }
    encoded = JS_JSONStringify(context, JS_IsUndefined(result) ? JS_NULL : result, JS_UNDEFINED, JS_UNDEFINED);
    if (JS_IsException(encoded)) { throw_js(env, context, &call); goto cleanup; }
    if (JS_IsUndefined(encoded)) { throw_host(env, "JS result is not JSON serializable"); goto cleanup; }
    if (interrupt_js(runtime, &call)) { throw_host(env, "QuickJS execution timeout (maximum 2000ms)"); goto cleanup; }
    size_t output_size = 0;
    const char *json = JS_ToCStringLen(context, &output_size, encoded);
    if (!json) { throw_js(env, context, &call); goto cleanup; }
    if (output_size > MAX_WIRE_BYTES) {
        JS_FreeCString(context, json); throw_host(env, "JS result exceeds size limit"); goto cleanup;
    }
    output = (*env)->NewByteArray(env, (jsize) output_size);
    if (output) (*env)->SetByteArrayRegion(env, output, 0, (jsize) output_size, (const jbyte *) json);
    JS_FreeCString(context, json);
cleanup:
    if (context) {
        JS_FreeValue(context, encoded); JS_FreeValue(context, result); JS_FreeValue(context, event);
        JS_FreeValue(context, entry); JS_FreeValue(context, host); JS_FreeValue(context, global);
        JS_FreeContext(context);
    }
    if (runtime) JS_FreeRuntime(runtime);
    free(source); free(entry_name); free(event_json);
    return output;
}
