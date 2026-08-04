plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.autombot.client"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autombot.client"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-skeleton"

        // CORRECAO: motor de tun2socks trocado de Kotlin puro (Tun2SocksEngine.kt,
        // cheio de bugs sutis achados um a um em teste real) pra biblioteca nativa
        // C madura (hev-socks5-tunnel, ver app/src/main/cpp/README.md). Por
        // enquanto so arm64-v8a (cobre a esmagadora maioria dos aparelhos reais em
        // uso) — adicionar mais ABIs aqui exige compilar a lib nativa pra cada uma
        // tambem (ver README.md).
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    ndkVersion = "26.3.11579264"
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // WireGuard oficial para Android (backend Go + parser de config .conf)
    implementation("com.wireguard.android:tunnel:1.0.20230706")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Cliente SSH puro-Java/Kotlin, usado pelo driver SSH (protocols/ssh/SshTunnelManager.kt)
    implementation("com.hierynomus:sshj:0.38.0")
    // BouncyCastle "de verdade" — o Android traz um "BC" proprio capado (sem X25519 e
    // outros algoritmos que o sshj precisa). Precisa ser registrado manualmente no
    // codigo tambem (ver SshTunnelManager.kt) alem de estar no classpath.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Icones extras (usados no dashboard/telas de protocolo)
    implementation("androidx.compose.material:material-icons-extended:1.6.8")
}
