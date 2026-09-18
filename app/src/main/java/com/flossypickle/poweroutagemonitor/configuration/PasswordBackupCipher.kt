package com.flossypickle.poweroutagemonitor.configuration

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Password encryption for backups that must move between Android devices. */
internal object PasswordBackupCipher {
    const val MIN_PASSWORD_LENGTH = 10
    const val MAX_BACKUP_BYTES = 2 * 1024 * 1024
    private const val ITERATIONS = 250_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val MAGIC = "FPGRIDB1".toByteArray(Charsets.US_ASCII)

    fun encrypt(
        plaintext: ByteArray,
        password: CharArray,
        secureRandom: SecureRandom = SecureRandom()
    ): ByteArray {
        require(plaintext.size <= MAX_BACKUP_BYTES) { "Backup data is too large." }
        require(password.size >= MIN_PASSWORD_LENGTH) {
            "Use a password with at least $MIN_PASSWORD_LENGTH characters."
        }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val iv = ByteArray(IV_BYTES).also(secureRandom::nextBytes)
        val header = header(salt, iv)
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally {
            key.encoded?.fill(0)
        }
    }

    fun decrypt(backup: ByteArray, password: CharArray): ByteArray {
        require(backup.size <= MAX_BACKUP_BYTES + HEADER_BYTES + 32) { "Backup file is too large." }
        require(backup.size > HEADER_BYTES) { "This is not an Flockle Grid Outage Monitor backup." }
        val magic = backup.copyOfRange(0, MAGIC.size)
        require(magic.contentEquals(MAGIC)) { "This is not an Flockle Grid Outage Monitor backup." }
        val iterations = readInt(backup, MAGIC.size)
        require(iterations == ITERATIONS) { "This backup uses an unsupported security format." }
        val saltStart = MAGIC.size + Int.SIZE_BYTES
        val salt = backup.copyOfRange(saltStart, saltStart + SALT_BYTES)
        val iv = backup.copyOfRange(saltStart + SALT_BYTES, HEADER_BYTES)
        val header = backup.copyOfRange(0, HEADER_BYTES)
        val ciphertext = backup.copyOfRange(HEADER_BYTES, backup.size)
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(header)
            cipher.doFinal(ciphertext)
        } catch (_: Exception) {
            throw IllegalArgumentException("The password is wrong or the backup file is damaged.")
        } finally {
            key.encoded?.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val specification = PBEKeySpec(password, salt, ITERATIONS, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
                .generateSecret(specification).encoded
            try {
                SecretKeySpec(bytes, "AES")
            } finally {
                bytes.fill(0)
            }
        } finally {
            specification.clearPassword()
        }
    }

    private fun header(salt: ByteArray, iv: ByteArray): ByteArray =
        ByteArrayOutputStream(HEADER_BYTES).use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.writeInt(ITERATIONS)
                output.write(salt)
                output.write(iv)
            }
            bytes.toByteArray()
        }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private const val HEADER_BYTES = 8 + Int.SIZE_BYTES + SALT_BYTES + IV_BYTES
}
