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

    /**
     * List a page of message envelopes in [folderId], newest first when [desc].
     * [limit] caps how many are fetched (so a multi-thousand-message inbox doesn't
     * pull every envelope over the tunnel); [offset] skips that many from the
     * newest end, so `offset = page * limit` walks older pages ("Load older").
     */
    suspend fun listMessages(
        sessionId: String,
        folderId: String,
        desc: Boolean = true,
        limit: Int = DEFAULT_PAGE_SIZE,
        offset: Int = 0,
    ): List<MailMessage>

    /** Fetch and decrypt one message, returning the raw RFC822 MIME bytes. */
    suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray

    /**
     * Send [mail] from the connected account, returning the assigned Message-ID
     * and whether a copy was filed in Sent. Throws [MailException] on
     * transport/auth failure. The Proton engine is not yet wired for send and
     * throws [MailException.ProtocolError] with status 501.
     */
    suspend fun send(sessionId: String, mail: OutgoingMail): SendResult

    /**
     * Mark [messageId] read/unread (the IMAP `\Seen` flag). Used by Mail Rules'
     * filter actions. The Proton engine throws [MailException.ProtocolError] (501).
     */
    suspend fun setSeen(sessionId: String, messageId: String, seen: Boolean)

    /** Set/clear the IMAP `\Flagged` (starred) flag on [messageId]. Proton: 501. */
    suspend fun setFlagged(sessionId: String, messageId: String, flagged: Boolean)

    /**
     * Move [messageId] to [destFolderId] (server-side copy + delete + expunge on
     * the source). Proton: 501. On Gmail this relabels — "All Mail" retains the copy.
     */
    suspend fun moveMessage(sessionId: String, messageId: String, destFolderId: String)

    /**
     * Delete [messageId] (set `\Deleted` + expunge). On Gmail this moves it to Trash
     * rather than erasing it. Proton: 501.
     */
    suspend fun deleteMessage(sessionId: String, messageId: String)

    /** Revoke and drop the session. */
    suspend fun logout(sessionId: String)

    companion object {
        /** Default message-list page size — most-recent N envelopes per fetch. */
        const val DEFAULT_PAGE_SIZE = 100
    }
}
