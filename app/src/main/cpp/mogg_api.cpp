#include <stdio.h>
#include <stdlib.h>
#include <vector>
#include <stdint.h>
#include <string.h>
#include <vorbis/vorbisfile.h>
#include "VorbisEncrypter.h"
#include "VorbisReader.h"
#ifdef __ANDROID__
#include <jni.h>
#endif

static size_t f_read(void* ptr, size_t size, size_t n, void* ds) { return fread(ptr, size, n, (FILE*)ds); }
static int f_seek(void* ds, ogg_int64_t off, int wh) { return fseek((FILE*)ds, (long)off, wh); }
static long f_tell(void* ds) { return ftell((FILE*)ds); }

/* 0x0A (tanpa enkripsi) -> 0x0B. Mengembalikan 0 bila sukses. */
extern "C" int rb3_encrypt_mogg(const char* in, const char* out) {
    FILE* fi = fopen(in, "rb");
    if (!fi) return 1;
    FILE* fo = fopen(out, "wb");
    if (!fo) { fclose(fi); return 2; }
    ov_callbacks cb;
    cb.read_func = f_read; cb.seek_func = f_seek; cb.close_func = NULL; cb.tell_func = f_tell;
    int rc = 0;
    try {
        VorbisEncrypter* ve = new VorbisEncrypter(fi, cb);
        std::vector<unsigned char> buf(8192);
        for (;;) {
            size_t n = ve->ReadRaw(buf.data(), 1, buf.size());
            if (n == 0) break;
            if (fwrite(buf.data(), 1, n, fo) != n) { rc = 3; break; }
        }
        delete ve;
    } catch (...) { rc = 4; }
    fclose(fi); fclose(fo);
    return rc;
}

/* Untuk pengujian: dekripsi mogg 0x0B -> ogg. */
extern "C" int rb3_decrypt_mogg(const char* in, const char* out) {
    FILE* fi = fopen(in, "rb");
    if (!fi) return 1;
    FILE* fo = fopen(out, "wb");
    if (!fo) { fclose(fi); return 2; }
    ov_callbacks cb;
    cb.read_func = f_read; cb.seek_func = f_seek; cb.close_func = NULL; cb.tell_func = f_tell;
    VorbisReader* vr = new VorbisReader();
    int rc = 0;
    if (vr->Open(fi, cb)) { rc = 5; }
    else {
        std::vector<unsigned char> buf(8192);
        for (;;) {
            size_t n = vr->ReadRaw(buf.data(), 1, buf.size());
            if (n == 0) break;
            fwrite(buf.data(), 1, n, fo);
        }
    }
    delete vr;
    fclose(fi); fclose(fo);
    return rc;
}


static void put32(std::vector<unsigned char>& v, uint32_t x) {
    v.push_back(x & 0xFF); v.push_back((x >> 8) & 0xFF); v.push_back((x >> 16) & 0xFF); v.push_back((x >> 24) & 0xFF);
}

/* info[0]=kanal, info[1]=sample rate, info[2]=total sampel. Mengembalikan 0 bila sukses. */
extern "C" int rb3_ogg_info(const char* in, long long* info) {
    FILE* fi = fopen(in, "rb");
    if (!fi) return 1;
    OggVorbis_File vf;
    if (ov_open_callbacks(fi, &vf, NULL, 0, OV_CALLBACKS_DEFAULT) < 0) { fclose(fi); return 2; }
    vorbis_info* vi = ov_info(&vf, -1);
    info[0] = vi->channels;
    info[1] = vi->rate;
    info[2] = ov_pcm_total(&vf, -1);
    ov_clear(&vf);
    return 0;
}

/* OGG -> MOGG tipe 0x0A (tanpa enkripsi), meniru oggToMogg Onyx. */
extern "C" int rb3_ogg_to_mogg(const char* in, const char* out) {
    FILE* fi = fopen(in, "rb");
    if (!fi) return 1;
    OggVorbis_File vf;
    if (ov_open_callbacks(fi, &vf, NULL, 0, OV_CALLBACKS_NOCLOSE) < 0) { fclose(fi); return 2; }
    uint32_t audioLen = (uint32_t)ov_pcm_total(&vf, -1);
    std::vector<std::pair<uint32_t, uint32_t> > table;
    for (int64_t bytes = 0;; bytes += 0x8000) {
        if (ov_raw_seek(&vf, bytes) != 0) break;
        table.push_back(std::make_pair((uint32_t)bytes, (uint32_t)ov_pcm_tell(&vf)));
    }
    ov_clear(&vf);
    const uint32_t bufSize = 20000;
    std::vector<std::pair<uint32_t, uint32_t> > t2;
    {
        uint32_t cur = 0;
        std::pair<uint32_t, uint32_t> prev(0, 0);
        size_t idx = 0;
        while (cur < audioLen) {
            if (idx >= table.size()) { t2.push_back(prev); cur += bufSize; continue; }
            if (table[idx].second <= cur) { prev = table[idx]; idx++; continue; }
            t2.push_back(prev); cur += bufSize;
        }
    }
    std::vector<unsigned char> hdr;
    uint32_t len = (uint32_t)t2.size();
    put32(hdr, 0xA); put32(hdr, 20 + 8 * len); put32(hdr, 0x10); put32(hdr, bufSize); put32(hdr, len);
    for (size_t i = 0; i < t2.size(); i++) { put32(hdr, t2[i].first); put32(hdr, t2[i].second); }
    fseek(fi, 0, SEEK_SET);
    FILE* fo = fopen(out, "wb");
    if (!fo) { fclose(fi); return 3; }
    fwrite(hdr.data(), 1, hdr.size(), fo);
    std::vector<unsigned char> buf(65536);
    size_t n;
    while ((n = fread(buf.data(), 1, buf.size(), fi)) > 0) fwrite(buf.data(), 1, n, fo);
    fclose(fi); fclose(fo);
    return 0;
}

#ifdef __ANDROID__
extern "C" JNIEXPORT jint JNICALL Java_com_example_chartdroid_Rb3Native_encryptMogg(JNIEnv* env, jclass, jstring jin, jstring jout) {
    const char* in = env->GetStringUTFChars(jin, 0);
    const char* out = env->GetStringUTFChars(jout, 0);
    int r = rb3_encrypt_mogg(in, out);
    env->ReleaseStringUTFChars(jin, in);
    env->ReleaseStringUTFChars(jout, out);
    return r;
}
extern "C" JNIEXPORT jint JNICALL Java_com_example_chartdroid_Rb3Native_oggToMogg(JNIEnv* env, jclass, jstring jin, jstring jout) {
    const char* in = env->GetStringUTFChars(jin, 0);
    const char* out = env->GetStringUTFChars(jout, 0);
    int r = rb3_ogg_to_mogg(in, out);
    env->ReleaseStringUTFChars(jin, in);
    env->ReleaseStringUTFChars(jout, out);
    return r;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_example_chartdroid_Rb3Native_oggInfo(JNIEnv* env, jclass, jstring jin) {
    const char* in = env->GetStringUTFChars(jin, 0);
    long long info[3] = {0, 0, 0};
    int r = rb3_ogg_info(in, info);
    env->ReleaseStringUTFChars(jin, in);
    if (r != 0) return NULL;
    jlongArray arr = env->NewLongArray(3);
    jlong tmp[3] = {info[0], info[1], info[2]};
    env->SetLongArrayRegion(arr, 0, 3, tmp);
    return arr;
}
#endif
