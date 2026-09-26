package ernest.ascrcpy.adb

/** Supplies the RSA identity used when an adbd instance requests authentication. */
interface AdbKeyProvider {
    fun sign(token: ByteArray): ByteArray
    fun encodedPublicKey(): ByteArray
}
