import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
}

android {
    namespace = "com.ttdk1507d.inspectioncamera"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ttdk1507d.inspectioncamera"
        minSdk = 24
        targetSdk = 34
        versionCode = 42
        versionName = "1.6.1"

        // Khoá API gửi kèm header X-API-Key tới backend (đặt qua env PTCGDB_API_KEY hoặc -PptcgdbApiKey=...)
        val apiKey = (System.getenv("PTCGDB_API_KEY") ?: project.findProperty("ptcgdbApiKey")?.toString() ?: "")
            .replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
        buildConfigField("String", "FIREBASE_URLS", "\"\"")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        FileInputStream(keystorePropertiesFile).use { fis ->
            keystoreProperties.load(fis)
        }
    }

    val debugKeystoreFile = file("debug.keystore")

    signingConfigs {
        getByName("debug") {
            if (debugKeystoreFile.exists()) {
                storeFile = debugKeystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
        create("release") {
            val storeFilePath = System.getenv("RELEASE_KEYSTORE_PATH")
                ?: keystoreProperties.getProperty("storeFile")
            val sPassword = System.getenv("RELEASE_STORE_PASSWORD")
                ?: keystoreProperties.getProperty("storePassword")
            val kAlias = System.getenv("RELEASE_KEY_ALIAS")
                ?: keystoreProperties.getProperty("keyAlias")
            val kPassword = System.getenv("RELEASE_KEY_PASSWORD")
                ?: keystoreProperties.getProperty("keyPassword")

            if (!storeFilePath.isNullOrBlank()) {
                val candidateFile = listOf(
                    file(storeFilePath),
                    rootProject.file(storeFilePath)
                ).firstOrNull { it.exists() }

                if (candidateFile != null) {
                    storeFile = candidateFile
                    storePassword = sPassword
                    keyAlias = kAlias
                    keyPassword = kPassword
                }
            }
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            // R8: thu nhỏ + tối ưu mã và tài nguyên cho bản phát hành (rules trong proguard-rules.pro). Phải test bản release
            // trước khi phát hành: Gson/Firebase dùng reflection nên các lớp dữ liệu được giữ nguyên tên.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null && releaseSigning.storeFile!!.exists()) {
                releaseSigning
            } else {
                null
            }
        }
        debug {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        create("dev") {
            initWith(getByName("debug"))
            // Cụm Firebase riêng cho bản dev (4 URL cách nhau dấu phẩy: cụm 1, 2, 3, dự phòng); rỗng = dùng URL mặc định
            val devUrls = System.getenv("PTCGDB_DEV_FIREBASE_URLS")
                ?: project.findProperty("ptcgdbDevFirebaseUrls")?.toString() ?: ""
            buildConfigField("String", "FIREBASE_URLS", "\"${devUrls.replace("\"", "")}\"")
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            matchingFallbacks += listOf("debug", "release")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Firebase
    implementation(platform("com.google.firebase:firebase-bom:33.4.0"))
    implementation("com.google.firebase:firebase-database-ktx")

    // Camera
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // UI
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.core:core-ktx:1.13.1")

    // Image loading
    implementation("io.coil-kt:coil:2.7.0")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
}
