package sh.haven.core.mail

/**
 * Mail domain models shared across the engine (Proton Go bridge in v1; JVM
 * Jakarta Mail in stage 2) and the UI. Kept deliberately small — message
 * bodies are parsed from RFC822 in the feature layer, not here.
 */

/** A mailbox folder / Proton label. [type] is Proton's LabelType (1=label, 2=folder, 3=system). */
data class MailFolder(
    val id: String,
    val name: String,
    val type: Int,
    val color: String? = null,
    val parentId: String? = null,
) {
    /** Proton system folders carry well-known stable ids. */
    val isInbox: Boolean get() = id == INBOX_ID

    companion object {
        const val INBOX_ID = "0"
        const val ALL_MAIL_ID = "5"
        const val SENT_ID = "7"
        const val DRAFTS_ID = "8"
        const val TRASH_ID = "3"
        const val SPAM_ID = "4"
        const val ARCHIVE_ID = "6"
        const val STARRED_ID = "10"
        const val TYPE_SYSTEM = 3
    }
}

/** An email address with an optional display name. */
data class MailAddress(
    val name: String,
    val address: String,
) {
    /** "Alice <alice@example.com>" or just the address when unnamed. */
    fun display(): String = if (name.isBlank()) address else "$name <$address>"
}

/**
 * Message envelope metadata (the message-list row). The decrypted body is
 * fetched separately via [MailClient.getMessageRaw] and parsed in the feature
 * layer; this model intentionally omits it.
 */
data class MailMessage(
    val id: String,
    val subject: String,
    val from: MailAddress?,
    val to: List<MailAddress> = emptyList(),
    val unread: Boolean = false,
    /** Server timestamp, unix epoch seconds. */
    val timeSeconds: Long = 0L,
    val numAttachments: Int = 0,
)

/**
 * Result of a successful Proton SRP login + keyring unlock.
 *
 * SECURITY (R3): [saltedKeyPass] is the derived passphrase that unlocks the
 * account's PGP keyrings — as sensitive as the mailbox password. In v1 it is
 * held only for the lifetime of the in-memory session and is NOT persisted; on
 * process death the user re-authenticates. If silent resume is ever added it
 * must be encrypted via core/security CredentialEncryption, never stored raw.
 */
data class MailLoginResult(
    val uid: String,
    val accessToken: String,
    val refreshToken: String,
    val saltedKeyPass: String,
)

/**
 * Typed failures from the mail engine, mapped from the bridge's HTTP-ish status
 * codes so the connect flow can drive a staged dialog (2FA / mailbox password)
 * and distinguish a dead session from a hard auth failure.
 */
sealed class MailException(message: String) : Exception(message) {
    /** Account has TOTP enabled; retry login with a code. (bridge 412 "2fa_required") */
    class TwoFaRequired : MailException("Two-factor authentication code required")

    /** Two-password-mode account; retry login with the mailbox password. (bridge 412 "mailbox_password_required") */
    class MailboxPasswordRequired : MailException("Mailbox password required")

    /** The Go session no longer exists (process restarted / logged out) — re-login. (bridge 440) */
    class SessionExpired(message: String) : MailException(message)

    /** Wrong credentials / 2FA. (bridge 401) */
    class AuthFailed(message: String) : MailException(message)

    /** Any other bridge or protocol error, with the originating status. */
    class ProtocolError(val status: Int, message: String) : MailException(message)
}

/** Which engine backs a mail session — selects the [MailClient] in the registry. */
enum class MailEngine { PROTON, IMAP }

/**
 * Engine-specific connect parameters. The sealed type lets the single
 * [MailClient.login] carry both Proton (SRP + a SOCKS5 listener) and IMAP
 * (server coordinates + a JVM [javax.net.SocketFactory]) without an FFI-shaped
 * `socks: String?` signature leaking into the JVM engine, or vice-versa. Each
 * [MailClient] handles exactly one variant and rejects the other.
 */
sealed interface MailConnectParams {
    val username: String
    val password: String

    /** Proton SRP login (+ optional 2FA / mailbox password), routed via SOCKS5. */
    data class Proton(
        override val username: String,
        override val password: String,
        val mailboxPassword: String? = null,
        val twoFA: String? = null,
        /** Bare `host:port` of a SOCKS5 listener (the per-profile tunnel), or null. NOT a URL. */
        val socks: String? = null,
    ) : MailConnectParams

    /** Generic IMAP/SMTP with password / app-password, routed via a JVM SocketFactory. */
    data class Imap(
        override val username: String,
        override val password: String,
        val server: String,
        val port: Int,
        val smtpPort: Int,
        val tls: Boolean,
        /** SocketFactory wrapping the per-profile tunnel, or null for a direct connection. */
        val socketFactory: javax.net.SocketFactory? = null,
    ) : MailConnectParams
}
