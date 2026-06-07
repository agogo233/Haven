package sh.haven.core.mail

/**
 * Provider-agnostic mail engine. Implementations: [ProtonMailClient] (Go
 * mailbridge, SRP) and the JVM IMAP/SMTP engine (Stage 2a). Each handles one
 * [MailConnectParams] variant; [MailSessionManager] routes by the session's
 * [MailEngine].
 *
 * Session state (logged-in, keyring-unlocked accounts) lives behind the
 * implementation, keyed by an opaque [sessionId] the caller mints. All methods
 * are main-safe (they switch to a background dispatcher internally) and throw
 * [MailException] subtypes on failure.
 */
interface MailClient {

    /**
     * Authenticate and unlock the account using engine-specific [params]. Throws
     * [MailException.TwoFaRequired] / [MailException.MailboxPasswordRequired]
     * when the caller must re-prompt and retry (the same [sessionId] can be
     * reused — no session is registered until unlock succeeds). Throws
     * [IllegalArgumentException] if handed the wrong [MailConnectParams] variant.
     */
    suspend fun login(
        sessionId: String,
        params: MailConnectParams,
    ): MailLoginResult

    /** List the account's folders/labels. */
    suspend fun listFolders(sessionId: String): List<MailFolder>

    /** List message envelopes in [folderId], newest first when [desc]. */
    suspend fun listMessages(sessionId: String, folderId: String, desc: Boolean = true): List<MailMessage>

    /** Fetch and decrypt one message, returning the raw RFC822 MIME bytes. */
    suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray

    /** Revoke and drop the session. */
    suspend fun logout(sessionId: String)
}
