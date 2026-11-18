cmake_minimum_required(VERSION 3.22.1)
project(notesis_spatial LANGUAGES CXX)
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

# The GPU ink renderer: a tile's strokes from the page's mesh cache, batched
# into one draw (ink_batch.cpp, also in the host checks) on a GL context of its
# own, into a hardware buffer. An experiment behind a switch (NativeInk.kt).
add_library(notesis_ink SHARED ink_batch.cpp ink_gl.cpp)
target_compile_features(notesis_ink PRIVATE cxx_std_17)
target_compile_options(notesis_ink PRIVATE -O3 -Wall -Wextra -Werror -ffp-contract=off -fvisibility=hidden)
target_link_libraries(notesis_ink EGL GLESv3 nativewindow android)
target_link_options(notesis_ink PRIVATE "-Wl,-z,max-page-size=16384")
