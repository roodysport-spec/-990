plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.skyline.proctor"
    // الجهاز الحالي (السبورة التفاعلية) شغال أندرويد 13 = API 33
    compileSdk = 34

    defaultConfig {
        applicationId = "com.skyline.proctor"
        minSdk = 26          // يدعم أندرويد 8.0 فما فوق (يغطي جهازك بأمان)
        targetSdk = 34
        versionCode = 1
        versionName = "0.1-base"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true   // للوصول لعناصر الواجهة بأمان من الكود
    }

    // لازم نمنع ضغط ملفات النماذج وإلا MediaPipe ما يكدر يقرأها من الـ APK
    androidResources {
        noCompress += listOf("task", "tflite")
    }
}

dependencies {
    // واجهة أساسية
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX - المكتبة الرسمية لكشف وعرض الكاميرا بأندرويد
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MediaPipe Tasks - كشف الوجوه ومعالمها (نفس face_landmarker.task الموجود عندك)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")

    // OpenCV - لحساب اتجاه الرأس (Pitch/Yaw) بنفس طريقة solvePnP المستخدمة بالبايثون
    implementation("com.quickbirdstudios:opencv:4.5.3.0")
}
