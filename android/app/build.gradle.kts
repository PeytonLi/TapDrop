import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.peytonli.tapdrop"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.peytonli.tapdrop"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val keystoreB64 = providers.environmentVariable("TAPDROP_KEYSTORE_B64").getOrElse("").trim()
            val storePass = providers.environmentVariable("TAPDROP_KEYSTORE_PASSWORD").getOrElse("")
            val keyAlias = providers.environmentVariable("TAPDROP_KEY_ALIAS").getOrElse("")
            val keyPass = providers.environmentVariable("TAPDROP_KEY_PASSWORD").getOrElse("")
            if (listOf(keystoreB64, storePass, keyAlias, keyPass).all { it.isNotBlank() }) {
                val keystoreFile = layout.buildDirectory.file("signing/tapdrop-release.jks").get().asFile
                keystoreFile.parentFile.mkdirs()
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreB64))
                storeFile = keystoreFile
                storePassword = storePass
                this.keyAlias = keyAlias
                keyPassword = keyPass
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                logger.lifecycle("TapDrop release build: signing with TAPDROP_KEYSTORE_B64 key")
                signingConfigs.getByName("release")
            } else {
                logger.lifecycle("TapDrop release build: signing with debug key")
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "160m"
    systemProperty("tapdrop.vectors", rootProject.file("../protocol/test-vectors.json").absolutePath)
    val runInterop = providers.gradleProperty("tapdropInteropE2E").map { it == "true" }.orElse(false)
    if (name == "testDebugUnitTest" && !runInterop.get()) {
        exclude("**/InteropE2ETest.class")
    }
    if (name == "testDebugUnitTest" && runInterop.get()) {
        systemProperty("tapdrop.interop.qr", providers.gradleProperty("interopQr").getOrElse(""))
        systemProperty("tapdrop.interop.dir", providers.gradleProperty("interopDir").getOrElse(""))
        systemProperty("tapdrop.interop.phoneFile", providers.gradleProperty("interopPhoneFile").getOrElse(""))
        systemProperty("tapdrop.interop.laptopFile", providers.gradleProperty("interopLaptopFile").getOrElse(""))
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
