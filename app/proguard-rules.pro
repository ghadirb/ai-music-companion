# ---- AI Music Companion release (R8) rules ----

# Myket in-app billing client (reflection / AIDL).
-keep class com.android.vending.billing.** { *; }
-keep class ir.myket.billingclient.** { *; }
-dontwarn ir.myket.**

# Never ship verbose logging in release builds (no song names, URIs, tokens or identifiers in logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep readable stack traces for the local crash report (no mapping upload; nothing leaves the device).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Room / WorkManager / Media3 / Compose / Coil ship their own consumer rules.
