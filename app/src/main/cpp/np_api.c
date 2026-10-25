#include <string.h>
#include <stdbool.h>
#include <stdlib.h>
#ifdef __ANDROID__
#include <jni.h>
#endif

bool pack_data_from_paths(const char*, const char*, const char*, unsigned char*, unsigned char*, unsigned char*,
                          int, int, int, int, bool, bool, bool, bool);
bool extract_data_from_paths(const char*, const char*, const char*, unsigned char*, unsigned char*, bool);

/* Mengembalikan 0 bila sukses. contentId = string (dipad ke 0x30 byte), klic = 16 byte. */
int rb3_pack_edat(const char* in, const char* out, const char* hashName, const char* contentId, const unsigned char* klic) {
    unsigned char cid[0x30 + 0x10];
    unsigned char kl[0x20];
    unsigned char rif[0x10];
    memset(cid, 0, sizeof cid);
    memset(kl, 0, sizeof kl);
    memset(rif, 0, sizeof rif);
    strncpy((char*)cid, contentId, 0x30);
    memcpy(kl, klic, 16);
    return pack_data_from_paths(in, out, hashName, cid, kl, rif, 2, 3, 0, 16, false, true, true, false) ? 1 : 0;
}

int rb3_unpack_edat(const char* in, const char* out, const char* hashName, const unsigned char* klic) {
    unsigned char kl[0x20];
    unsigned char rif[0x10];
    memset(kl, 0, sizeof kl);
    memset(rif, 0, sizeof rif);
    memcpy(kl, klic, 16);
    return extract_data_from_paths(in, out, hashName, kl, rif, false) ? 1 : 0;
}

#ifdef __ANDROID__
JNIEXPORT jint JNICALL Java_com_example_chartdroid_Rb3Native_packEdat(JNIEnv* env, jclass c, jstring jin, jstring jout, jstring jhash, jstring jcid, jbyteArray jklic) {
    const char* in = (*env)->GetStringUTFChars(env, jin, 0);
    const char* out = (*env)->GetStringUTFChars(env, jout, 0);
    const char* hash = (*env)->GetStringUTFChars(env, jhash, 0);
    const char* cid = (*env)->GetStringUTFChars(env, jcid, 0);
    jbyte* kl = (*env)->GetByteArrayElements(env, jklic, 0);
    int r = rb3_pack_edat(in, out, hash, cid, (const unsigned char*)kl);
    (*env)->ReleaseByteArrayElements(env, jklic, kl, JNI_ABORT);
    (*env)->ReleaseStringUTFChars(env, jin, in);
    (*env)->ReleaseStringUTFChars(env, jout, out);
    (*env)->ReleaseStringUTFChars(env, jhash, hash);
    (*env)->ReleaseStringUTFChars(env, jcid, cid);
    return r;
}
#endif
