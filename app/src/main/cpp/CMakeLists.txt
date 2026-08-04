# CMake do motor de tun2socks nativo do AutomBot Connect.
#
# Compila nossa ponte JNI (autombot_tun2socks_jni.c) e linka com a biblioteca
# hev-socks5-tunnel (terceiros), que PRECISA ter sido baixada e pre-compilada
# manualmente antes — ver README.md nesta mesma pasta pros passos exatos.

cmake_minimum_required(VERSION 3.22.1)
project(autombot_tun2socks)

set(HEV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/hev-socks5-tunnel)

if(NOT EXISTS ${HEV_DIR}/src/hev-main.h)
    message(FATAL_ERROR
        "hev-socks5-tunnel nao encontrado em ${HEV_DIR}. "
        "Siga os passos do README.md desta pasta (cpp/) antes de compilar: "
        "clonar o repositorio e rodar 'make static' com o toolchain do NDK.")
endif()

add_library(autombot_tun2socks SHARED
    autombot_tun2socks_jni.c
)

target_include_directories(autombot_tun2socks PRIVATE
    ${HEV_DIR}/src
)

# Biblioteca principal
add_library(hev_socks5_tunnel STATIC IMPORTED)
set_target_properties(hev_socks5_tunnel PROPERTIES
    IMPORTED_LOCATION ${HEV_DIR}/bin/libhev-socks5-tunnel.a
)

# Dependencia: hev-task-system
add_library(hev_task_system STATIC IMPORTED)
set_target_properties(hev_task_system PROPERTIES
    IMPORTED_LOCATION ${HEV_DIR}/third-part/hev-task-system/bin/libhev-task-system.a
)

# Dependencia: lwip
add_library(lwip STATIC IMPORTED)
set_target_properties(lwip PROPERTIES
    IMPORTED_LOCATION ${HEV_DIR}/third-part/lwip/bin/liblwip.a
)

# Dependencia: yaml
add_library(yaml STATIC IMPORTED)
set_target_properties(yaml PROPERTIES
    IMPORTED_LOCATION ${HEV_DIR}/third-part/yaml/bin/libyaml.a
)

find_library(log-lib log)

target_link_libraries(autombot_tun2socks
    hev_socks5_tunnel
    hev_task_system
    lwip
    yaml
    ${log-lib}
)