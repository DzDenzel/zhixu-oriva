# JNI callback names and native entry names are part of the host bridge contract.
-keep class com.planlock.plugin.QuickJsEngine { *; }
-keepclassmembers class com.planlock.plugin.JsPluginBridge {
    public byte[] postMessageUtf8(byte[]);
}
