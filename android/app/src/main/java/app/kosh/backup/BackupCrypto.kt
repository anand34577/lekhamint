package app.kosh.backup

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Thrown when a passphrase is wrong or encrypted data was modified or cut short. */
class BackupDecryptException(message: String) : IOException(message)

/**
 * Passphrase encryption for .kosh backups (docs/06):
 *
 * - key = Argon2id(passphrase, 16-byte salt, m = 64 MiB, t = 3, p = 1) -> 32 bytes
 * - plaintext is split into 1 MiB chunks; each chunk is AES-256-GCM sealed with
 *   nonce = 8-byte random prefix ‖ 4-byte chunk counter, and AAD marks the final
 *   chunk, so reordering, dropping or truncating chunks is detected
 * - on disk each chunk is `uint32 length ‖ ciphertext+tag`
 */
object BackupCrypto {
    const val KDF = "argon2id"
    const val CIPHER = "aes-256-gcm-chunked"
    const val MEMORY_KIB = 65536
    const val ITERATIONS = 3
    const val PARALLELISM = 1
    const val CHUNK = 1 shl 20

    private val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also(random::nextBytes)

    fun deriveKey(
        passphrase: CharArray,
        salt: ByteArray,
        memoryKib: Int = MEMORY_KIB,
        iterations: Int = ITERATIONS,
        parallelism: Int = PARALLELISM,
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build()
        val gen = Argon2BytesGenerator()
        gen.init(params)
        val key = ByteArray(32)
        gen.generateBytes(passphrase, key)
        return key
    }

    private fun aad(counter: Int, last: Boolean): ByteArray =
        ByteBuffer.allocate(9).put("kosh".toByteArray()).putInt(counter).put(if (last) 1 else 0).array()

    private fun nonce(prefix: ByteArray, counter: Int): ByteArray =
        ByteBuffer.allocate(12).put(prefix).putInt(counter).array()

    /** Wraps [out] so everything written is encrypted. Close the returned stream to finish. */
    fun encrypting(out: OutputStream, key: ByteArray, noncePrefix: ByteArray): OutputStream =
        ChunkedEncryptStream(out, SecretKeySpec(key, "AES"), noncePrefix)

    /** Decrypts a stream written by [encrypting] into [out]. */
    fun decrypt(input: InputStream, out: OutputStream, key: ByteArray, noncePrefix: ByteArray) {
        val data = DataInputStream(input)
        val k = SecretKeySpec(key, "AES")
        var counter = 0
        var pending: ByteArray? = readChunk(data) ?: throw BackupDecryptException("Encrypted data is empty")
        while (pending != null) {
            val next = readChunk(data)
            val last = next == null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, nonce(noncePrefix, counter)))
            cipher.updateAAD(aad(counter, last))
            val plain = try {
                cipher.doFinal(pending)
            } catch (e: AEADBadTagException) {
                throw BackupDecryptException("Wrong password, or the backup is damaged")
            }
            out.write(plain)
            counter++
            pending = next
        }
    }

    private fun readChunk(data: DataInputStream): ByteArray? {
        val len = try {
            data.readInt()
        } catch (e: EOFException) {
            return null
        }
        if (len < 16 || len > CHUNK + 16) throw BackupDecryptException("The backup is damaged")
        val buf = ByteArray(len)
        data.readFully(buf)
        return buf
    }

    private class ChunkedEncryptStream(
        out: OutputStream,
        private val key: SecretKeySpec,
        private val prefix: ByteArray,
    ) : FilterOutputStream(out) {
        private val buffer = ByteArray(CHUNK)
        private var size = 0
        private var counter = 0
        private var closed = false
        /** A full chunk is held back until we know whether it is the last one. */
        private var held: ByteArray? = null
        private val data = DataOutputStream(out)

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var remaining = len
            while (remaining > 0) {
                if (size == CHUNK) flushChunk()
                val n = minOf(remaining, CHUNK - size)
                System.arraycopy(b, o, buffer, size, n)
                size += n; o += n; remaining -= n
            }
        }

        private fun flushChunk() {
            held?.let { seal(it, last = false) }
            held = buffer.copyOf(size)
            size = 0
        }

        private fun seal(plain: ByteArray, last: Boolean) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce(prefix, counter)))
            cipher.updateAAD(aad(counter, last))
            val sealed = cipher.doFinal(plain)
            data.writeInt(sealed.size)
            data.write(sealed)
            counter++
        }

        override fun flush() = data.flush()

        override fun close() {
            if (closed) return
            closed = true
            val tail = buffer.copyOf(size)
            val h = held
            if (h != null && tail.isEmpty()) {
                seal(h, last = true)
            } else {
                h?.let { seal(it, last = false) }
                seal(tail, last = true)
            }
            data.flush()
            out.close()
        }
    }
}
