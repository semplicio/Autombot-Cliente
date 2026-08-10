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

    // sing-box e o launcher OpenVPN são executados via ProcessBuilder a partir de
    // nativeLibraryDir. Portanto precisam ser extraídos para o filesystem durante
    // a instalação em vez de permanecer apenas mapeados dentro do APK.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    ndkVersion = "26.3.11579264"
}

// Hysteria2/TUIC não podem mais gerar um APK "aparentemente correto" sem o
// núcleo sing-box. O preBuild prepara o binário automaticamente e valida a saída
// antes de qualquer APK ser produzido. Assim o erro aparece na compilação, não
// só depois de instalar no telefone.
val singBoxCoreFile = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libsingbox.so").asFile

val prepareSingBoxCore by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Baixa e prepara o núcleo sing-box Android arm64 usado por Hysteria2/TUIC"
    commandLine("bash", rootProject.file("scripts/fetch_singbox_android_core.sh").absolutePath)

    doLast {
        if (!singBoxCoreFile.isFile || singBoxCoreFile.length() <= 0L) {
            throw GradleException(
                "Núcleo sing-box não foi preparado em ${singBoxCoreFile.absolutePath}. " +
                    "O APK não será gerado sem Hysteria2/TUIC funcionais."
            )
        }
        logger.lifecycle("[AutomBot] sing-box pronto: ${singBoxCoreFile.absolutePath} (${singBoxCoreFile.length()} bytes)")
    }
}

// O arquivo libopenvpn.so existente é o core compartilhado do ics-openvpn e não
// pode ser executado diretamente. O upstream usa um PIE mínimo (libovpnexec.so)
// ligado contra esse core. Geramos esse launcher automaticamente com o NDK já
// utilizado pelo projeto antes de o APK ser empacotado.
val openVpnCoreFile = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libopenvpn.so").asFile
val openVpnLauncherFile = layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libovpnexec.so").asFile

val prepareOpenVpnLauncher by tasks.registering(Exec::class) {
    group = "build setup"
    description = "Compila o launcher PIE Android do OpenVPN ligado ao libopenvpn.so"
    commandLine("bash", rootProject.file("scripts/build_openvpn_android_launcher.sh").absolutePath)

    doLast {
        if (!openVpnCoreFile.isFile || openVpnCoreFile.length() <= 0L) {
            throw GradleException("Core OpenVPN ausente em ${openVpnCoreFile.absolutePath}")
        }
        if (!openVpnLauncherFile.isFile || openVpnLauncherFile.length() <= 0L) {
            throw GradleException(
                "Launcher OpenVPN não foi preparado em ${openVpnLauncherFile.absolutePath}. " +
                    "O APK não será gerado com um OpenVPN que encerra antes da Management Interface."
            )
        }
        logger.lifecycle(
            "[AutomBot] OpenVPN launcher pronto: ${openVpnLauncherFile.absolutePath} " +
                "(${openVpnLauncherFile.length()} bytes)"
        )
    }
}

tasks.named("preBuild").configure {
    dependsOn(prepareSingBoxCore)
    dependsOn(prepareOpenVpnLauncher)
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
