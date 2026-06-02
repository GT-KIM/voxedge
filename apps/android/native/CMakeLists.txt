cmake_minimum_required(VERSION 3.22.1)
project(conversational_ai_native LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# QAIRT SDK (relative to apps/android/native/): repo-root/tools/model_compile/qairt/...
set(QAIRT_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../tools/model_compile/qairt/extracted/qairt/2.46.0.260424")
set(QAIRT_INCLUDE "${QAIRT_ROOT}/include/SNPE")
set(QAIRT_LIB_DIR "${QAIRT_ROOT}/lib/aarch64-android")

if(NOT EXISTS "${QAIRT_INCLUDE}/SNPE/SNPE.hpp")
    message(FATAL_ERROR "QAIRT SNPE headers not found at ${QAIRT_INCLUDE}. Check the QAIRT path.")
endif()

# Imported SNPE shared library (the .so is also copied into jniLibs by the prepare step).
add_library(SNPE SHARED IMPORTED)
set_target_properties(SNPE PROPERTIES IMPORTED_LOCATION "${QAIRT_LIB_DIR}/libSNPE.so")

# Resident TTS engine + JNI bridge (in the same TU) -> libsupertonic_tts.so
add_library(supertonic_tts SHARED
    tts_engine/supertonic_tts_engine.cpp)

target_compile_definitions(supertonic_tts PRIVATE TTS_ENGINE_AS_LIBRARY=1)
target_include_directories(supertonic_tts PRIVATE "${QAIRT_INCLUDE}")

find_library(log-lib log)
target_link_libraries(supertonic_tts SNPE ${log-lib})

# --- Genie LLM (Qwen3-4B w4a16) -> libgenie_llm.so ---
# Genie headers live at ${QAIRT_ROOT}/include/Genie/*.h; include the parent so #include "Genie/..".
set(QAIRT_GENIE_INCLUDE "${QAIRT_ROOT}/include")
add_library(Genie SHARED IMPORTED)
set_target_properties(Genie PROPERTIES IMPORTED_LOCATION "${QAIRT_LIB_DIR}/libGenie.so")

add_library(genie_llm SHARED
    llm/genie_llm.cpp)
target_include_directories(genie_llm PRIVATE "${QAIRT_GENIE_INCLUDE}")
target_link_libraries(genie_llm Genie ${log-lib})
