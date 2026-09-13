package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Minimal authenticated SMTP client fixed to Gmail's documented TLS endpoint. */
internal class GmailSmtpClient {
    fun send(
        account: String,
        appPassword: String,
        recipient: String,
        message: AlertMessage
    ): DeliveryResult {
        val cleanAccount = account.trim()
        val cleanPassword = GmailSmtpProtocol.normalizedAppPassword(appPassword)
        if (!GmailSmtpProtocol.isValidAccount(cleanAccount)) {
            return DeliveryResult.PermanentFailure("Google account email is not valid")
        }
        if (!GmailSmtpProtocol.isValidAppPassword(cleanPassword)) {
            return DeliveryResult.PermanentFailure("Google App Password must contain 16 characters")
        }
        if (!ResendEmailProtocol.isValidEmailAddress(recipient)) {
            return DeliveryResult.PermanentFailure("Recipient email address is not valid")
        }

        var plainSocket: Socket? = null
        var tlsSocket: SSLSocket? = null
        return try {
            plainSocket = Socket().apply {
                connect(InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS)
                soTimeout = READ_TIMEOUT_MS
            }
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            tlsSocket = factory.createSocket(plainSocket, HOST, PORT, true) as SSLSocket
            tlsSocket.soTimeout = READ_TIMEOUT_MS
            tlsSocket.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(HOST, tlsSocket.session)) {
                throw SSLHandshakeException("Gmail certificate hostname did not match")
            }
            plainSocket = null

            val reader = BufferedReader(InputStreamReader(tlsSocket.inputStream, Charsets.US_ASCII))
            val writer = BufferedWriter(OutputStreamWriter(tlsSocket.outputStream, Charsets.US_ASCII))
            expect(readReply(reader), setOf(220))
            command(writer, "EHLO fp-grid-monitor.local")
            expect(readReply(reader), setOf(250))
            command(writer, "AUTH LOGIN")
            expect(readReply(reader), setOf(334))
            command(writer, GmailSmtpProtocol.encodeBase64(cleanAccount))
            expect(readReply(reader), setOf(334))
            command(writer, GmailSmtpProtocol.encodeBase64(cleanPassword))
            expect(readReply(reader), setOf(235))
            command(writer, "MAIL FROM:<$cleanAccount>")
            expect(readReply(reader), setOf(250))
            command(writer, "RCPT TO:<${recipient.trim()}>")
            expect(readReply(reader), setOf(250, 251))
            command(writer, "DATA")
            expect(readReply(reader), setOf(354))
            writer.write(GmailSmtpProtocol.messageData(cleanAccount, recipient.trim(), message))
            writer.write("\r\n.\r\n")
            writer.flush()
            val accepted = readReply(reader)
            expect(accepted, setOf(250))
            runCatching {
                command(writer, "QUIT")
                readReply(reader)
            }
            DeliveryResult.Sent(accepted.text.substringAfter(" ", "").takeIf(String::isNotBlank))
        } catch (failure: SmtpFailure) {
            GmailSmtpProtocol.failureResult(failure.reply.code, failure.reply.text)
        } catch (_: SSLException) {
            DeliveryResult.RetryableFailure("A secure connection to Gmail could not be established")
        } catch (_: IOException) {
            DeliveryResult.RetryableFailure("Gmail could not be reached")
        } catch (_: Exception) {
            DeliveryResult.PermanentFailure("Gmail delivery could not be completed")
        } finally {
            runCatching { tlsSocket?.close() }
            runCatching { plainSocket?.close() }
        }
    }

    private fun command(writer: BufferedWriter, value: String) {
        require(!value.contains('\r') && !value.contains('\n'))
        writer.write(value)
        writer.write("\r\n")
        writer.flush()
    }

    private fun readReply(reader: BufferedReader): Reply {
        val first = reader.readLine() ?: throw IOException("SMTP connection closed")
        if (first.length < 3) throw IOException("Invalid SMTP reply")
        val code = first.take(3).toIntOrNull() ?: throw IOException("Invalid SMTP reply")
        val lines = mutableListOf(first)
        if (first.getOrNull(3) == '-') {
            repeat(MAX_REPLY_LINES - 1) {
                val line = reader.readLine() ?: throw IOException("SMTP connection closed")
                lines += line
                if (line.startsWith("$code ")) return Reply(code, lines.joinToString(" "))
            }
            throw IOException("SMTP reply was too long")
        }
        return Reply(code, first)
    }

    private fun expect(reply: Reply, acceptedCodes: Set<Int>) {
        if (reply.code !in acceptedCodes) throw SmtpFailure(reply)
    }

    private data class Reply(val code: Int, val text: String)
    private class SmtpFailure(val reply: Reply) : Exception()

    private companion object {
        const val HOST = "smtp.gmail.com"
        const val PORT = 465
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_REPLY_LINES = 40
    }
}
