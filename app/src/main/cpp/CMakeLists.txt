cmake_minimum_required(VERSION 3.22.1)
project(notesis_spatial LANGUAGES CXX)
find_program(NOTESIS_CARGO cargo HINTS "$ENV{CARGO_HOME}/bin" REQUIRED)
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(NOTESIS_RUST_TARGET "aarch64-linux-android")
elseif(ANDROID_ABI STREQUAL "x86_64")
    set(NOTESIS_RUST_TARGET "x86_64-linux-android")
else()
    message(FATAL_ERROR "The native document engine needs an arm64-v8a or x86_64 target")
endif()
get_filename_component(NOTESIS_RUST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/document" ABSOLUTE)
set(NOTESIS_RUST_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/rust")
set(NOTESIS_RUST_ARCHIVE "${NOTESIS_RUST_OUTPUT}/${NOTESIS_RUST_TARGET}/release/libnotesis_document.a")
file(GLOB NOTESIS_RUST_SOURCES CONFIGURE_DEPENDS "${NOTESIS_RUST_ROOT}/src/*.rs")
add_custom_command(OUTPUT "${NOTESIS_RUST_ARCHIVE}"
    COMMAND ${CMAKE_COMMAND} -E env "CARGO_TARGET_DIR=${NOTESIS_RUST_OUTPUT}"
        ${NOTESIS_CARGO} build --manifest-path "${NOTESIS_RUST_ROOT}/Cargo.toml"
        --release --locked --target "${NOTESIS_RUST_TARGET}"
    DEPENDS ${NOTESIS_RUST_SOURCES} "${NOTESIS_RUST_ROOT}/Cargo.toml" "${NOTESIS_RUST_ROOT}/Cargo.lock"
    COMMENT "Build the Rust document engine")
add_custom_target(notesis_document_build DEPENDS "${NOTESIS_RUST_ARCHIVE}")
add_library(notesis_document STATIC IMPORTED GLOBAL)
set_target_properties(notesis_document PROPERTIES IMPORTED_LOCATION "${NOTESIS_RUST_ARCHIVE}")
add_dependencies(notesis_document notesis_document_build)
add_library(notesis_core SHARED note_core.cpp note_core_jni.cpp document_jni.cpp)
add_dependencies(notesis_core notesis_document_build)
target_link_libraries(notesis_core PRIVATE notesis_document log dl m)
target_compile_features(notesis_core PRIVATE cxx_std_17)
target_compile_options(notesis_core PRIVATE -O3 -Wall -Wextra -Werror -ffp-contract=off -fvisibility=hidden)
target_link_options(notesis_core PRIVATE "-Wl,-z,max-page-size=16384")
add_library(notesis_spatial SHARED spatial_index.cpp erase_geometry.cpp continuous_erase.cpp spatial_jni.cpp)
target_compile_features(notesis_spatial PRIVATE cxx_std_17)
target_compile_options(notesis_spatial PRIVATE -O3 -Wall -Wextra -Werror -ffp-contract=off -fvisibility=hidden)
target_link_options(notesis_spatial PRIVATE "-Wl,-z,max-page-size=16384")

# The Lab's experiment in showing wet ink without waiting for a surface's canvas.
# Android only (surface transactions and hardware buffers); not part of the host checks.
add_library(notesis_present SHARED present_jni.cpp)
target_compile_features(notesis_present PRIVATE cxx_std_17)
target_compile_options(notesis_present PRIVATE -O2 -Wall -Wextra -Werror -fvisibility=hidden)
target_link_libraries(notesis_present android jnigraphics)
target_link_options(notesis_present PRIVATE "-Wl,-z,max-page-size=16384")
