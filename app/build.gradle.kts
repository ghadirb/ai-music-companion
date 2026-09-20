plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val myketPublicKey = providers.gradleProperty("MYKET_IAB_PUBLIC_KEY").orElse("").get()
val myketPremiumSku = providers.gradleProperty("MYKET_PREMIUM_SKU").orElse("premium_lifetime").get()
val escapedMyketPublicKey = myketPublicKey.replace("\\", "\\\\").replace("\"", "\\\"")
val escapedMyketPremiumSku = myketPremiumSku.replace("\\", "\\\\").replace("\"", "\\\"")
val myketPremiumSkus = providers.gradleProperty("MYKET_PREMIUM_SKUS").orElse(myketPremiumSku).get()
    .replace("\\", "\\\\").replace("\"", "\\\"")
// Public key (X.509 SPKI, base64) used to verify server-signed entitlement tokens offline. Not a secret.
val entitlementPublicKey = providers.gradleProperty("ENTITLEMENT_PUBLIC_KEY").orElse("").get()
    .replace("\\", "\\\\").replace("\"", "\\\"")
val cloudAiBaseUrl = providers.gradleProperty("CLOUD_AI_BASE_URL")
    .orElse("https://ai-music-companion-embedding.ghadir-baraty.workers.dev")
    .get().trimEnd('/').replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.ghadirb.aimusic"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ghadirb.aimusic"
        minSdk = 26
        targetSdk = 35
        // CI passes -PVERSION_CODE=<run number>; -PVERSION_NAME sets the marketing version.
        versionCode = providers.gradleProperty("VERSION_CODE").orElse("1").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").orElse("1.0.0-rc1").get()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val marketApplicationId = "ir.mservices.market"
        val marketBindAddress = "ir.mservices.market.InAppBillingService.BIND"
        manifestPlaceholders.apply {
            this["marketApplicationId"] = marketApplicationId
            this["marketBindAddress"] = marketBindAddress
            this["marketPermission"] = "$marketApplicationId.BILLING"
        }
        buildConfigField("String", "IAB_PUBLIC_KEY", "\"$escapedMyketPublicKey\"")
        buildConfigField("String", "MYKET_PREMIUM_SKU", "\"$escapedMyketPremiumSku\"")
        buildConfigField("String", "CLOUD_AI_BASE_URL", "\"$cloudAiBaseUrl\"")
        buildConfigField("String", "MYKET_PREMIUM_SKUS", "\"$myketPremiumSkus\"")
        buildConfigField("String", "ENTITLEMENT_PUBLIC_KEY", "\"$entitlementPublicKey\"")
    }

    // Release signing: keystore + passwords come ONLY from environment variables / Gradle properties
    // (GitHub Secrets in CI). Nothing secret is ever committed. Without them the release build is unsigned.
    val releaseKeystorePath = providers.environmentVariable("RELEASE_KEYSTORE_PATH")
        .orElse(providers.gradleProperty("RELEASE_KEYSTORE_PATH")).orNull
    signingConfigs {
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "VERBOSE_LOGS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            buildConfigField("boolean", "VERBOSE_LOGS", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseKeystorePath != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    lint {
        // UnsafeOptInUsageError is a known false positive for Kotlin @OptIn(UnstableApi) with Media3.
        disable += setOf("UnsafeOptInUsageError")
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        textReport = true
    }

    sourceSets {
        // Room schema JSONs are used by MigrationTestHelper in instrumented tests.
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core / Compose
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Media3 / ExoPlayer
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-common:1.4.1")

    // Room
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Image loading (album art)
    implementation("io.coil-kt:coil-compose:2.6.0")

    // Permissions helper (Accompanist)
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Myket in-app billing. The merchant key is supplied locally as a Gradle property.
    implementation("com.github.myketstore:myket-billing-client:1.6")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.room:room-testing:2.6.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.06.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
