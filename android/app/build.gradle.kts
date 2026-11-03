plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.filum"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.filum"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.1"
    }

    // Ключ релизов: путь и пароль из окружения (на GitHub — из секретов репозитория). Без них — отладочный ключ,
    // чтобы локальная release-сборка тоже ставилась на телефон.
    val releaseKeystore = System.getenv("FILUM_KEYSTORE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = releaseKeystore
            storePassword = System.getenv("FILUM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FILUM_KEY_ALIAS") ?: "filum"
            keyPassword = System.getenv("FILUM_KEY_PASSWORD") ?: System.getenv("FILUM_KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
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
        buildConfig = true  // VERSION_NAME — для проверки обновлений
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Плагины на JavaScript: Rhino — чистая Java, на Android работает в режиме интерпретатора
    implementation("org.mozilla:rhino:1.7.15")
    testImplementation("junit:junit:4.13.2")
    // в юнит-тестах android.jar отдаёт заглушки org.json — нужна настоящая реализация
    testImplementation("org.json:json:20240303")
}
