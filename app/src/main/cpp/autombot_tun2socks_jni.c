/*
 * Ponte JNI entre o Kotlin (NativeTun2Socks.kt) e a biblioteca nativa
 * hev-socks5-tunnel (terceiros: https://github.com/heiher/hev-socks5-tunnel).
 *
 * Por que essa ponte existe: em vez de continuar corrigindo bug por bug do motor
 * de tun2socks escrito do zero em Kotlin (Tun2SocksEngine.kt — TCP com sutilezas
 * erradas, protect() com bug de fd, bytes de IP com sinal errado, UDP genuino nunca
 * implementado direito), passamos a usar uma biblioteca nativa C madura, usada em
 * producao por apps reais (NekoBox, Matsuri e outros clientes VLESS/VMess/Shadowsocks
 * populares), que ja resolve TCP e UDP completos, IPv4/IPv6, e todos os casos de
 * borda que a gente vinha caçando um por um.
 */

#include <jni.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include "hev-main.h"

#define TAG "AutomBotTun2Socks"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static pthread_t run_thread;
static volatile int running = 0;

typedef struct {
    char *config;
    size_t config_len;
    int tun_fd;
} start_args_t;

static void *run_entry(void *arg) {
    start_args_t *args = (start_args_t *) arg;
    LOGI("Iniciando hev_socks5_tunnel_main_from_str (tun_fd=%d, config_len=%zu)",
         args->tun_fd, args->config_len);

    int result = hev_socks5_tunnel_main_from_str((const unsigned char *)args->config, args->config_len, args->tun_fd);

    LOGI("hev_socks5_tunnel_main_from_str retornou %d (0 = saida normal via quit(), "
         "-1 = erro — checar o Logcat da propria lib logo acima pra causa exata)", result);

    running = 0;
    free(args->config);
    free(args);
    return NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_autombot_client_core_tun2socks_NativeTun2Socks_nativeStart(
        JNIEnv *env, jobject thiz, jstring configYaml, jint tunFd) {
    if (running) {
        LOGE("nativeStart chamado com o tunel ja rodando — chame nativeStop() primeiro");
        return JNI_FALSE;
    }

    const char *configChars = (*env)->GetStringUTFChars(env, configYaml, NULL);
    if (configChars == NULL) {
        LOGE("GetStringUTFChars falhou (out of memory?)");
        return JNI_FALSE;
    }

    size_t len = strlen(configChars);
    start_args_t *args = malloc(sizeof(start_args_t));
    if (args == NULL) {
        (*env)->ReleaseStringUTFChars(env, configYaml, configChars);
        return JNI_FALSE;
    }
    args->config = malloc(len + 1);
    if (args->config == NULL) {
        free(args);
        (*env)->ReleaseStringUTFChars(env, configYaml, configChars);
        return JNI_FALSE;
    }
    memcpy(args->config, configChars, len + 1);
    args->config_len = (unsigned int)len;
    args->tun_fd = tunFd;

    (*env)->ReleaseStringUTFChars(env, configYaml, configChars);

    running = 1;
    int rc = pthread_create(&run_thread, NULL, run_entry, args);
    if (rc != 0) {
        LOGE("pthread_create falhou: %d", rc);
        running = 0;
        free(args->config);
        free(args);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_autombot_client_core_tun2socks_NativeTun2Socks_nativeStop(
        JNIEnv *env, jobject thiz) {
    if (!running) {
        LOGI("nativeStop chamado mas o tunel ja nao esta rodando — nada a fazer");
        return;
    }
    LOGI("Parando o tunel (hev_socks5_tunnel_quit)");
    hev_socks5_tunnel_quit();
    pthread_join(run_thread, NULL);
    running = 0;
    LOGI("Tunel parado");
}

JNIEXPORT jlongArray JNICALL
Java_com_autombot_client_core_tun2socks_NativeTun2Socks_nativeGetStats(
        JNIEnv *env, jobject thiz) {
    size_t tx_packets = 0, tx_bytes = 0, rx_packets = 0, rx_bytes = 0;
    hev_socks5_tunnel_stats(&tx_packets, &tx_bytes, &rx_packets, &rx_bytes);

    jlong values[4];
    values[0] = (jlong) tx_packets;
    values[1] = (jlong) tx_bytes;
    values[2] = (jlong) rx_packets;
    values[3] = (jlong) rx_bytes;

    jlongArray result = (*env)->NewLongArray(env, 4);
    if (result != NULL) {
        (*env)->SetLongArrayRegion(env, result, 0, 4, values);
    }
    return result;
}