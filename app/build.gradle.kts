plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}
val enableCastConnect = providers.gradleProperty("tvplayair.castConnect").orNull == "true"

android {
    namespace = "com.tvplayair.receiver"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "com.tvplayair.receiver"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("boolean", "CAST_CONNECT_ENABLED", enableCastConnect.toString())
        manifestPlaceholders["castConnectEnabled"] = enableCastConnect.toString()

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
        prefab = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.tv.material)
    implementation(libs.oboe)
    implementation(libs.androidx.media3.session)
    implementation(libs.google.cast.tv)
    implementation(libs.google.cast.core)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.bouncycastle.bcprov)

    testImplementation(libs.junit)

    debugImplementation("androidx.compose.ui:ui-tooling")
}
