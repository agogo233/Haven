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
    ): List<MailMessage> = withContext(Dispatchers.IO) {
        val res = MailBridge.listMessages(sessionId, folderId, desc)
        if (res.status != 200) throw mapError(res.status, res.output)
        val arr = JSONArray(res.output)
        (0 until arr.length()).map { i -> parseMessage(arr.getJSONObject(i)) }
    }

    override suspend fun getMessageRaw(sessionId: String, messageId: String): ByteArray =
        withContext(Dispatchers.IO) {
            val res = MailBridge.getMessage(sessionId, messageId)
            if (res.status != 200) throw mapError(res.status, res.output)
            val b64 = JSONObject(res.output).optString("rfc822")
            Base64.decode(b64, Base64.DEFAULT)
        }

    override suspend fun logout(sessionId: String) {
        withContext(Dispatchers.IO) { MailBridge.logout(sessionId) }
    }

    // ---- parsing (Proton structs marshal with capitalised Go field names) ----

    private fun parseFolder(o: JSONObject): MailFolder = MailFolder(
        id = o.optString("ID"),
        name = o.optString("Name"),
        type = o.optInt("Type"),
        color = o.optString("Color").ifBlank { null },
        parentId = o.optString("ParentID").ifBlank { null },
    )

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
