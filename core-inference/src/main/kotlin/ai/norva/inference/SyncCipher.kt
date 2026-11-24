package ai.norva.inference

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SyncCipher {
    private val magic = "NORVA14!".toByteArray(Charsets.US_ASCII)
    const val LIMIT = 8 * 1024 * 1024
    private fun crypt(mode: Int, password: CharArray, salt: ByteArray, nonce: ByteArray, value: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, 600_000, 256)
        val raw = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
                  finally { spec.clearPassword() }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(raw, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(magic)
            cipher.doFinal(value)
        } finally { raw.fill(0) }
    }
    fun seal(password: CharArray, value: ByteArray): ByteArray {
        require(password.size >= 12 && value.size <= LIMIT) { "Пароль от 12 символов, архив до 8 МиБ" }
        val rng = SecureRandom()
        val salt = ByteArray(16).also(rng::nextBytes)
        val nonce = ByteArray(12).also(rng::nextBytes)
        return magic + salt + nonce + crypt(Cipher.ENCRYPT_MODE, password, salt, nonce, value)
    }
    fun open(password: CharArray, bytes: ByteArray): ByteArray {
        require(bytes.size in 52..(LIMIT+52) && bytes.copyOfRange(0,8).contentEquals(magic)) { "Недопустимый архив NØRVA" }
        return crypt(Cipher.DECRYPT_MODE, password, bytes.copyOfRange(8,24), bytes.copyOfRange(24,36), bytes.copyOfRange(36,bytes.size))
    }
}
