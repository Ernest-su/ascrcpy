package ernest.ascrcpy.adb.crypto

import android.content.Context
import android.util.Base64
import ernest.ascrcpy.adb.AdbKeyProvider
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/** Persistent 2048-bit RSA identity compatible with the adbd authentication protocol. */
class FileAdbKeyProvider private constructor(private val keyPair: KeyPair) : AdbKeyProvider {
    override fun sign(token: ByteArray): ByteArray {
        require(token.size == 20) { "adbd authentication tokens must contain 20 bytes" }
        val sha1DigestInfo = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14,
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, keyPair.private)
        return cipher.doFinal(sha1DigestInfo + token)
    }

    override fun encodedPublicKey(): ByteArray {
        val key = keyPair.public as RSAPublicKey
        val words = 64
        val wordBase = BigInteger.ONE.shiftLeft(32)
        val n0 = key.modulus.mod(wordBase)
        val n0inv = wordBase.subtract(n0.modInverse(wordBase)).mod(wordBase)
        val rr = BigInteger.ONE.shiftLeft(words * 64).mod(key.modulus)

        val binary = ByteBuffer.allocate(4 + 4 + words * 4 + words * 4 + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        binary.putInt(words)
        binary.putInt(n0inv.toLong().toInt())
        binary.putLittleEndianWords(key.modulus, words)
        binary.putLittleEndianWords(rr, words)
        binary.putInt(key.publicExponent.toInt())

        val encoded = Base64.encodeToString(binary.array(), Base64.NO_WRAP)
        return "$encoded ascrcpy@android\u0000".toByteArray(Charsets.UTF_8)
    }

    companion object {
        fun create(context: Context): FileAdbKeyProvider {
            val privateFile = context.noBackupFilesDir.resolve("adbkey.pk8")
            val publicFile = context.noBackupFilesDir.resolve("adbkey.pub.der")
            val factory = KeyFactory.getInstance("RSA")
            val pair = if (privateFile.exists() && publicFile.exists()) {
                KeyPair(
                    factory.generatePublic(X509EncodedKeySpec(publicFile.readBytes())),
                    factory.generatePrivate(PKCS8EncodedKeySpec(privateFile.readBytes())),
                )
            } else {
                KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().also {
                    privateFile.writeBytes(it.private.encoded)
                    publicFile.writeBytes(it.public.encoded)
                }
            }
            return FileAdbKeyProvider(pair)
        }
    }
}

private fun ByteBuffer.putLittleEndianWords(number: BigInteger, count: Int) {
    var remaining = number
    repeat(count) {
        putInt(remaining.and(BigInteger("ffffffff", 16)).toLong().toInt())
        remaining = remaining.shiftRight(32)
    }
}
