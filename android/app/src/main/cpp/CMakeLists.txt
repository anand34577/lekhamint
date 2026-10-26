# On-device text recognition for receipts: PaddleOCR PP-OCRv5 mobile models run with ncnn (BSD-3-Clause).
# No Google or other service is involved. NCNN_ROOT points at the prebuilt ncnn release that Gradle downloads
# (pinned version and checksum in app/build.gradle.kts).
cmake_minimum_required(VERSION 3.22.1)
project(koshocr CXX)

set(CMAKE_CXX_STANDARD 17)
set(ncnn_DIR ${NCNN_ROOT}/${ANDROID_ABI}/lib/cmake/ncnn)
find_package(ncnn REQUIRED)

add_library(koshocr SHARED ocr_jni.cpp)
target_compile_options(koshocr PRIVATE -fvisibility=hidden -ffunction-sections -fdata-sections -O2)
# Drop unused code and keep ncnn's symbols private, so the library stays small.
target_link_options(koshocr PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384)
target_link_libraries(koshocr ncnn)
