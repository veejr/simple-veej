# libwebrtc calls back into these classes from native code by name.
-keep class org.webrtc.** { *; }

# Release builds drop debug and verbose logging. The call diagnostics log
# network candidates and call identifiers, which belong only in debug
# builds; warnings and errors are kept.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
