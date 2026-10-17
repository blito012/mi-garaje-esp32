plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "ec.pablo.migaraje"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "ec.pablo.migaraje"
        minSdk = 31
        targetSdk = 37
        versionCode = 2
        versionName = "0.2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Ruta y contrasena llegan al compilar (herramientas/compilar_release.ps1);
            // nunca se guardan en el proyecto.
            storeFile = file(System.getenv("MIGARAJE_FIRMA") ?: "falta-la-firma.jks")
            storePassword = System.getenv("MIGARAJE_CLAVE_FIRMA")
            keyAlias = "mi-garaje"
            keyPassword = System.getenv("MIGARAJE_CLAVE_FIRMA")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}