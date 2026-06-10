package sh.haven.core.mail

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import sh.haven.mail.bridge.MailBridge
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [MailClient] backed by the Go mailbridge (rclone's go-proton-api + gopenpgp),
 * reached through [MailBridge]. Parses the bridge's raw Proton JSON and maps its
 * HTTP-ish status codes to [MailException] subtypes.
 *
 * The Go side holds the logged-in/unlocked session keyed by [sessionId]; this
 * class is otherwise stateless.
 */
@Singleton
class ProtonMailClient @Inject constructor() : MailClient {

    override suspend fun login(
        sessionId: String,
        params: MailConnectParams,
    ): MailLoginResult = withContext(Dispatchers.IO) {
        val p = params as? MailConnectParams.Proton
            ?: throw IllegalArgumentException(
                "ProtonMailClient requires MailConnectParams.Proton, got ${params::class.simpleName}",
            )
        val res = MailBridge.login(
            sessionId = sessionId,
            username = p.username,
            password = p.password,
            mailboxPassword = p.mailboxPassword?.ifBlank { null },
            twoFA = p.twoFA?.ifBlank { null },
            appVersion = APP_VERSION,
            socks = p.socks?.ifBlank { null },
        )
        if (res.status != 200) throw mapError(res.status, res.output)
        val o = JSONObject(res.output)
        MailLoginResult(
            uid = o.optString("uid"),
            accessToken = o.optString("accessToken"),
            refreshToken = o.optString("refreshToken"),
            saltedKeyPass = o.optString("saltedKeyPass"),
        )
    }

    override suspend fun listFolders(sessionId: String): List<MailFolder> = withContext(Dispatchers.IO) {
        val res = MailBridge.listFolders(sessionId)
        if (res.status != 200) throw mapError(res.status, res.output)
        val arr = JSONArray(res.output)
        (0 until arr.length()).map { i -> parseFolder(arr.getJSONObject(i)) }
    }

    override suspend fun listMessages(
        sessionId: String,
        folderId: String,
        desc: Boolean,
        limit: Int,
        offset: Int,
    ): List<MailMessage> = withContext(Dispatchers.IO) {
        val res = MailBridge.listMessages(sessionId, folderId, desc)
        if (res.status != 200) throw mapError(res.status, res.output)
        val arr = JSONArray(res.output)
        val all = (0 until arr.length()).map { i -> parseMessage(arr.getJSONObject(i)) }
        // The Go bridge still fetches the whole label (server-side paging is a
        // follow-up); apply the page window client-side so the UI list is bounded
        // and "Load older" advances consistently with the IMAP engine.
        if (offset >= all.size) emptyList()
        else all.drop(offset).take(limit.coerceAtLeast(0))
    }

    override suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val res = MailBridge.getMessage(sessionId, messageId)
            if (res.status != 200) throw mapError(res.status, res.output)
            val b64 = JSONObject(res.output).optString("rfc822")
            Base64.decode(b64, Base64.DEFAULT)
        }

    override suspend fun send(sessionId: String, mail: OutgoingMail): SendResult {
        // Proton send (per-recipient key discovery + internal-E2E / PGP-external /
        // encrypt-to-outside schemes + CreateDraft/SendDraft) is the hardest,
        // highest-risk piece — a mis-scheme leaks plaintext — and is a later
        // checkpoint. The Go mailbridge's `send` RPC also returns 501. Fail loudly
        // rather than pretend success; CP-6 ships IMAP/SMTP send only.
        throw MailException.ProtocolError(501, "Proton send is not implemented yet")
    }

    // Mail Rules' IMAP filter actions aren't wired for the Proton (Go-bridge) engine
    // yet — its label/message-mutation RPCs are a later checkpoint. Fail loudly (501)
    // like send() rather than silently no-op, so a rule on a Proton account is visibly
    // unsupported instead of appearing to succeed.
    override suspend fun setSeen(sessionId: String, messageId: String, seen: Boolean): Unit =
        throw MailException.ProtocolError(501, "Proton mark-read is not implemented yet")

    override suspend fun setFlagged(sessionId: String, messageId: String, flagged: Boolean): Unit =
        throw MailException.ProtocolError(501, "Proton flag is not implemented yet")

    override suspend fun moveMessage(sessionId: String, messageId: String, destFolderId: String): Unit =
        throw MailException.ProtocolError(501, "Proton move is not implemented yet")

    override suspend fun deleteMessage(sessionId: String, messageId: String): Unit =
        throw MailException.ProtocolError(501, "Proton delete is not implemented yet")

    override suspend fun logout(sessionId: String) {
        withContext(Dispatchers.IO) { MailBridge.logout(sessionId) }
    }

    // ---- parsing (Proton structs marshal with capitalised Go field names) ----

    private fun parseFolder(o: JSONObject): MailFolder {
        val id = o.optString("ID")
        return MailFolder(
            id = id,
            name = o.optString("Name"),
            type = o.optInt("Type"),
            color = o.optString("Color").ifBlank { null },
            parentId = o.optString("ParentID").ifBlank { null },
            role = protonRole(id),
        )
    }

    /** Proton's system folders carry stable ids; map them to the engine-neutral role for the UI. */
    private fun protonRole(id: String): MailFolderRole = when (id) {
        MailFolder.INBOX_ID -> MailFolderRole.INBOX
        MailFolder.STARRED_ID -> MailFolderRole.STARRED
        MailFolder.SENT_ID -> MailFolderRole.SENT
        MailFolder.DRAFTS_ID -> MailFolderRole.DRAFTS
        MailFolder.ARCHIVE_ID, MailFolder.ALL_MAIL_ID -> MailFolderRole.ARCHIVE
        MailFolder.SPAM_ID -> MailFolderRole.SPAM
        MailFolder.TRASH_ID -> MailFolderRole.TRASH
        else -> MailFolderRole.NONE
    }

    private fun parseMessage(o: JSONObject): MailMessage = MailMessage(
        id = o.optString("ID"),
        subject = o.optString("Subject"),
        from = o.optJSONObject("Sender")?.let { parseAddress(it) },
        to = o.optJSONArray("ToList").toAddressList(),
        // proton.Bool marshals as APIBool int (0/1); tolerate a JSON bool too.
        unread = o.optInt("Unread", if (o.optBoolean("Unread")) 1 else 0) == 1,
        timeSeconds = o.optLong("Time"),
        numAttachments = o.optInt("NumAttachments"),
    )

    private fun parseAddress(o: JSONObject): MailAddress = MailAddress(
        name = o.optString("Name"),
        address = o.optString("Address"),
    )

    private fun JSONArray?.toAddressList(): List<MailAddress> {
        if (this == null) return emptyList()
        return (0 until length()).mapNotNull { i ->
            optJSONObject(i)?.let { parseAddress(it) }
        }
    }

    /** Map a bridge status + `{"error":".."}` body to a typed exception. */
    private fun mapError(status: Int, output: String): MailException {
        val err = runCatching { JSONObject(output).optString("error") }
            .getOrNull()?.ifBlank { null } ?: output
        return when {
            status == 412 && err.contains("2fa", ignoreCase = true) -> MailException.TwoFaRequired()
            status == 412 && err.contains("mailbox_password", ignoreCase = true) ->
                MailException.MailboxPasswordRequired()
            status == 401 -> MailException.AuthFailed(err)
            status == 440 -> MailException.SessionExpired(err)
            else -> MailException.ProtocolError(status, err)
        }
    }

    companion object {
        /**
         * Proton `x-pm-appversion`. go-proton-api's default ("go-proton-api") is
         * explicitly flagged not-for-production; this borrows rclone's shipping
         * Drive default, which authenticates against live Proton via the same
         * SRP endpoints. UNVERIFIED for Mail data endpoints — Proton may gate
         * those by a mail-specific version; revisit if reads 4xx after a good
         * login (R2). userAgent is left at the bridge default (empty), as rclone
         * does; wire WithUserAgent in mailbridge.go if Proton starts requiring it.
         */
        const val APP_VERSION = "macos-drive@1.0.0-alpha.1+rclone"
    }
}
