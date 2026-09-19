# Add project specific ProGuard rules here.
# MVP build does not enable minification yet (see app/build.gradle.kts).
# Required by Myket's billing client when release shrinking is enabled.
-keep class com.android.vending.billing.** { *; }
-keep class ir.myket.billingclient.** { *; }
