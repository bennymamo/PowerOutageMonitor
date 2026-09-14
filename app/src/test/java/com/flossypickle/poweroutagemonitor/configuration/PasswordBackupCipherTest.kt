package com.flossypickle.poweroutagemonitor.configuration

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PasswordBackupCipherTest {
    @Test
    fun encryptedArchiveRoundTripsWithoutExposingPlaintext() {
        val plaintext = "telegram.token=secret-value-that-must-not-leak".toByteArray()
        val password = "correct horse battery staple".toCharArray()

        val encrypted = PasswordBackupCipher.encrypt(plaintext, password)

        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("secret-value"))
        assertArrayEquals(plaintext, PasswordBackupCipher.decrypt(encrypted, password))
    }

    @Test
    fun wrongPasswordIsRejected() {
        val encrypted = PasswordBackupCipher.encrypt(
            "backup".toByteArray(),
            "a sufficiently long password".toCharArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            PasswordBackupCipher.decrypt(encrypted, "a different valid password".toCharArray())
        }
    }

    @Test
    fun changedCiphertextIsRejected() {
        val password = "another sufficiently long password".toCharArray()
        val encrypted = PasswordBackupCipher.encrypt("backup".toByteArray(), password)
        encrypted[encrypted.lastIndex] = (encrypted.last() + 1).toByte()

        assertThrows(IllegalArgumentException::class.java) {
            PasswordBackupCipher.decrypt(encrypted, password)
        }
    }
}
