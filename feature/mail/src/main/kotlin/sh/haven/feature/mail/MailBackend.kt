package sh.haven.feature.mail

import sh.haven.core.mail.MailFolder
import sh.haven.core.mail.MailMessage

/**
 * The feature-layer view of a connected mail account: folders, message lists,
 * and a fully-parsed (decrypted + MIME-parsed) message ready to render. Backed
 * by the engine-neutral [RfcMailBackend] (Proton and IMAP both feed RFC822).
 */
interface MailBackend {
    suspend fun listFolders(): List<MailFolder>
    suspend fun listMessages(folderId: String): List<MailMessage>
    suspend fun readMessage(messageId: String): ParsedMessage
}

/** A decrypted, MIME-parsed message ready for the reader UI. */
data class ParsedMessage(
    val subject: String,
    val from: String,
    val to: List<String>,
    val dateMillis: Long?,
    /**
     * Best displayable text. v1 renders this as plain text only — no WebView,
     * so a message's remote images/scripts can never load (R5: no tracking-pixel
     * beacon, no tunnel-exit-IP leak, no JS). HTML-only messages are converted to
     * a stripped-text approximation; rich rendering in a locked-down WebView is a
     * later refinement.
     */
    val bodyText: String,
    /** True when [bodyText] was derived by stripping an HTML part (no plain part existed). */
    val bodyWasHtml: Boolean,
    val attachments: List<MailAttachmentInfo>,
)

data class MailAttachmentInfo(
    val filename: String,
    val mimeType: String,
    val sizeBytes: Long,
)
