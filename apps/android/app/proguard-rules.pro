# Keep JNI entry points for the native TTS engine.
-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.conversationalai.agent.tts.SupertonicTts { *; }
