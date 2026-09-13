package com.flossypickle.poweroutagemonitor.integrations.alerts.email

import com.flossypickle.poweroutagemonitor.integrations.alerts.AlertMessage
import com.flossypickle.poweroutagemonitor.integrations.alerts.DeliveryResult

/** Pure Gmail SMTP validation and RFC 5322 message construction. */
internal object GmailSmtpProtocol {
    fun normalizedAppPassword(value: String): String =
        value.filterNot(Char::isWhitespace)

    fun isValidAppPassword(value: String): Boolean =
        normalizedAppPassword(value).matches(Regex("[A-Za-z0-9]{16}"))

    fun isValidAccount(value: String): Boolean =
        ResendEmailProtocol.isValidEmailAddress(value)

    fun encodeBase64(value: String): String = encodeBase64(value.toByteArray(Charsets.UTF_8))

    fun messageData(account: String, recipient: String, message: AlertMessage): String {
        require(isValidAccount(account))
        require(ResendEmailProtocol.isValidEmailAddress(recipient))
        val subject = "=?UTF-8?B?${encodeBase64(message.title)}?="
        val body = wrapBase64(encodeBase64(message.body), 76)
        val messageId = ResendEmailProtocol.idempotencyKey(message, recipient)
            .substringAfterLast('/')
        return listOf(
            "From: FP Grid Monitor <$account>",
            "To: <$recipient>",
            "Subject: $subject",
            "Message-ID: <$messageId@fp-grid-monitor.local>",
            "MIME-Version: 1.0",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: base64",
            "",
            body
        ).joinToString("\r\n")
    }

    fun redactReply(reply: String): String = reply
        .replace(Regex("[A-Za-z0-9]{16}"), "••••")
        .replace(Regex("[\r\n]+"), " ")
        .take(240)

    fun failureResult(code: Int, reply: String): DeliveryResult {
        val reason = redactReply(reply).ifBlank { "Gmail rejected the email" }
        return if (code in 400..499) {
            DeliveryResult.RetryableFailure(reason)
        } else {
            DeliveryResult.PermanentFailure(reason)
        }
    }

    private fun wrapBase64(value: String, width: Int): String =
        value.chunked(width).joinToString("\r\n")

    private fun encodeBase64(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val output = StringBuilder(((bytes.size + 2) / 3) * 4)
        var index = 0
        while (index < bytes.size) {
            val first = bytes[index++].toInt() and 0xff
            val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
            output.append(alphabet[first ushr 2])
            output.append(alphabet[((first and 0x03) shl 4) or if (second >= 0) second ushr 4 else 0])
            output.append(if (second >= 0) {
                alphabet[((second and 0x0f) shl 2) or if (third >= 0) third ushr 6 else 0]
            } else '=')
            output.append(if (third >= 0) alphabet[third and 0x3f] else '=')
        }
        return output.toString()
    }
}
