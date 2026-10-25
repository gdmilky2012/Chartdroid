cmake_minimum_required(VERSION 3.10)
project(rb3native C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 11)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# ---- libogg + libvorbis (BSD, Xiph.org) ----
add_library(xiph STATIC
    ogg/bitwise.c ogg/framing.c
    vorbis/analysis.c vorbis/bitrate.c vorbis/block.c vorbis/codebook.c vorbis/envelope.c vorbis/floor0.c
    vorbis/floor1.c vorbis/info.c vorbis/lookup.c vorbis/lpc.c vorbis/lsp.c vorbis/mapping0.c vorbis/mdct.c
    vorbis/psy.c vorbis/registry.c vorbis/res0.c vorbis/sharedbook.c vorbis/smallft.c vorbis/synthesis.c
    vorbis/vorbisenc.c vorbis/vorbisfile.c vorbis/window.c)
target_include_directories(xiph PUBLIC ogg vorbis)
target_compile_definitions(xiph PRIVATE _REENTRANT)

# ---- npdata (EDAT), GPL-3, dari Onyx / make_npdata ----
add_library(npdata_jni SHARED
    np_api.c npdata/aes.c npdata/ec.c npdata/lz.c npdata/make_npdata.c npdata/sha1.c npdata/utils.c)
target_include_directories(npdata_jni PRIVATE npdata)

# ---- moggcrypt (enkripsi MOGG), dari Onyx ----
add_library(moggcrypt_jni SHARED
    mogg_api.cpp moggcrypt/aes.c moggcrypt/ops.c
    moggcrypt/BinkReader.cpp moggcrypt/ByteGrinder.cpp moggcrypt/keygen.cpp
    moggcrypt/VorbisEncrypter.cpp moggcrypt/VorbisReader.cpp moggcrypt/XTEABlockEncrypter.cpp)
target_include_directories(moggcrypt_jni PRIVATE moggcrypt)
target_compile_definitions(moggcrypt_jni PRIVATE VR_NO_DECODE)
target_link_libraries(moggcrypt_jni xiph)
