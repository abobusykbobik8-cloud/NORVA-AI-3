package ai.norva.inference
import kotlin.test.*
import javax.crypto.AEADBadTagException
class SyncCipherTest {
    private val pass = "Пароль тестовой копии".toCharArray()
    @Test fun authenticatesAndRandomizesArchives() {
        val source = "Личные сообщения".toByteArray()
        val a = SyncCipher.seal(pass,source)
        val b = SyncCipher.seal(pass,source)
        assertFalse(a.contentEquals(b))
        assertContentEquals(source,SyncCipher.open(pass,a))
        a[a.lastIndex]=(a.last().toInt() xor 1).toByte()
        assertFailsWith<AEADBadTagException> { SyncCipher.open(pass,a) }
        assertFailsWith<AEADBadTagException> { SyncCipher.open("incorrect key".toCharArray(),b) }
    }
    @Test fun refuseTruncatedArchiveAndShortPassword() {
        assertFailsWith<IllegalArgumentException> { SyncCipher.open(pass,byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { SyncCipher.seal("short".toCharArray(),byteArrayOf()) }
    }
}
