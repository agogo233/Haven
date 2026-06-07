package sh.haven.feature.mail

import sh.haven.core.mail.MailClient
import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage

/**
 * Engine-neutral [MailBackend] over any connected [MailClient]: list calls
 * delegate straight through, and [readMessage] fetches the raw RFC822 bytes and
 * parses them with [MimeParser]. Both engines converge here — Proton decrypts to
 * RFC822 in the Go bridge, IMAP emits RFC822 via `message.writeTo` — so a single
 * backend (and a single parser) serves every provider.
 */
class RfcMailBackend(
    private val client: MailClient,
    private val sessionId: String,
) : MailBackend {

    override suspend fun listFolders(): List<MailFolder> = client.listFolders(sessionId)

    override suspend fun listMessages(folderId: String): List<MailMessage> =
        client.listMessages(sessionId, folderId, desc = true)

    override suspend fun readMessage(messageId: String): ParsedMessage {
        val raw = client.getMessageRaw(sessionId, messageId)
        return MimeParser.parse(raw)
    }
}
