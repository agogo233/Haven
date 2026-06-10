package sh.haven.core.mail

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * CP-1 (Stage 2a) — proves [MailSessionManager] routes by the session's
 * [MailEngine] across a multi-engine registry, so a Proton and an IMAP session
 * each reach their own [MailClient]. No mockk: a recording fake suffices.
 */
class MailSessionManagerRoutingTest {

    private class FakeClient : MailClient {
        val logins = mutableListOf<Pair<String, MailConnectParams>>()
        val logouts = mutableListOf<String>()
        override suspend fun login(sessionId: String, params: MailConnectParams): MailLoginResult {
            logins += sessionId to params
            return MailLoginResult(uid = "uid", accessToken = "at", refreshToken = "rt", saltedKeyPass = "skp")
        }
        override suspend fun listFolders(sessionId: String) = emptyList<MailFolder>()
        override suspend fun listMessages(
            sessionId: String,
            folderId: String,
            desc: Boolean,
            limit: Int,
            offset: Int,
        ) = emptyList<MailMessage>()
        override suspend fun getMessageRaw(sessionId: String, messageId: String) = ByteArray(0)
        override suspend fun send(sessionId: String, mail: OutgoingMail) = SendResult(null, false)
        override suspend fun setSeen(sessionId: String, messageId: String, seen: Boolean) {}
        override suspend fun setFlagged(sessionId: String, messageId: String, flagged: Boolean) {}
        override suspend fun moveMessage(sessionId: String, messageId: String, destFolderId: String) {}
        override suspend fun deleteMessage(sessionId: String, messageId: String) {}
        override suspend fun logout(sessionId: String) { logouts += sessionId }
    }

    private fun managerWith(proton: FakeClient, imap: FakeClient) =
        MailSessionManager(mapOf(MailEngine.PROTON to proton, MailEngine.IMAP to imap))

    @Test
    fun connectRoutesToTheSessionsEngine() = runBlocking {
        val proton = FakeClient(); val imap = FakeClient()
        val mgr = managerWith(proton, imap)

        val ps = mgr.registerSession("p1", "Proton", MailEngine.PROTON)
        mgr.connectSession(ps, MailConnectParams.Proton(username = "u", password = "pw"))

        val isid = mgr.registerSession("p2", "Imap", MailEngine.IMAP)
        mgr.connectSession(
            isid,
            MailConnectParams.Imap(
                username = "u", password = "pw",
                server = "imap.example.com", port = 993, smtpPort = 465, tls = true,
            ),
        )

        assertEquals("proton engine saw exactly its session", listOf(ps), proton.logins.map { it.first })
        assertEquals("imap engine saw exactly its session", listOf(isid), imap.logins.map { it.first })
        assertTrue(proton.logins[0].second is MailConnectParams.Proton)
        assertTrue(imap.logins[0].second is MailConnectParams.Imap)
    }

    @Test
    fun clientLookupReturnsTheEngineForProfileAndSession() = runBlocking {
        val proton = FakeClient(); val imap = FakeClient()
        val mgr = managerWith(proton, imap)

        val ps = mgr.registerSession("p1", "Proton", MailEngine.PROTON)
        mgr.connectSession(ps, MailConnectParams.Proton(username = "u", password = "pw"))
        val isid = mgr.registerSession("p2", "Imap", MailEngine.IMAP)
        mgr.connectSession(
            isid,
            MailConnectParams.Imap(
                username = "u", password = "pw",
                server = "s", port = 993, smtpPort = 465, tls = true,
            ),
        )

        assertSame(proton, mgr.clientForProfile("p1"))
        assertSame(imap, mgr.clientForProfile("p2"))
        assertSame(proton, mgr.clientForSession(ps))
        assertSame(imap, mgr.clientForSession(isid))
        assertNull("unknown profile has no client", mgr.clientForProfile("nope"))
        assertNull("unknown session has no client", mgr.clientForSession("nope"))
    }

    @Test
    fun connectOnUnknownSessionThrows() = runBlocking {
        val mgr = managerWith(FakeClient(), FakeClient())
        try {
            mgr.connectSession("ghost", MailConnectParams.Proton(username = "u", password = "pw"))
            fail("expected IllegalStateException for an unregistered session")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("ghost"))
        }
    }
}
