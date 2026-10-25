package com.example.chartdroid

/** Jembatan JNI ke kode C Onyx: EDAT (npdata) dan enkripsi MOGG (moggcrypt). */
object Rb3Native {
    private var ok: Boolean? = null

    fun available(): Boolean {
        if (ok == null) {
            ok = try {
                System.loadLibrary("npdata_jni")
                System.loadLibrary("moggcrypt_jni")
                true
            } catch (e: Throwable) {
                false
            }
        }
        return ok == true
    }

    /** 0 = sukses. */
    @JvmStatic external fun packEdat(input: String, output: String, hashName: String, contentId: String, klic: ByteArray): Int

    /** 0 = sukses. Input: mogg tipe 0x0A (tanpa enkripsi). */
    @JvmStatic external fun encryptMogg(input: String, output: String): Int

    /** OGG -> MOGG 0x0A. 0 = sukses. */
    @JvmStatic external fun oggToMogg(input: String, output: String): Int

    /** [kanal, sampleRate, totalSampel] atau null. */
    @JvmStatic external fun oggInfo(input: String): LongArray?
}
