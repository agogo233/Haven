package sh.haven.core.mail

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "MailSessionMgr"

/**
 * Tracks live mail sessions across engines, mirroring `RcloneSessionManager` but
 * far simpler. Each session names a [MailEngine]; [connectSession] and the
 * revoke path route to that engine's [MailClient] via the injected registry, so
 * a Proton (Go bridge) and an IMAP (JVM) session coexist behind one manager. The
 * engine owns the actual session; this holds the per-profile [SessionState] the
 * UI observes and the [SessionManagerRegistry] reports.
 */
@Singleton
class MailSessionManager @Inject constructor(
    private val clients: Map<MailEngine, @JvmSuppressWildcards MailClient>,
) {

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val engine: MailEngine,
        val status: Status,
        val errorMessage: String? = null,
    ) {
        enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /** The engine client for a live [sessionId], or null if unknown. */
    fun clientForSession(sessionId: String): MailClient? =
        _sessions.value[sessionId]?.let { clients[it.engine] }

    /** The engine client for [profileId]'s connected session, or null. */
    fun clientForProfile(profileId: String): MailClient? =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.let { clients[it.engine] }

    /** Background scope for fire-and-forget session revokes on disconnect. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Connected sessions — used by the registry's foreground keep-alive check. */
    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter { it.status == SessionState.Status.CONNECTED }

    fun registerSession(profileId: String, label: String, engine: MailEngine): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                engine = engine,
                status = SessionState.Status.CONNECTING,
            ))
        }
        return sessionId
    }

    /**
     * Log in and unlock the account for [sessionId] via its engine, marking it
     * CONNECTED on success. On [MailException.TwoFaRequired] /
     * [MailException.MailboxPasswordRequired] the session is left CONNECTING and
     * the exception rethrown so the caller can re-prompt and retry with the same
     * [sessionId]. Any other failure marks the session ERROR and rethrows.
     */
    suspend fun connectSession(sessionId: String, params: MailConnectParams) {
        val state = _sessions.value[sessionId]
            ?: throw IllegalStateException("Session $sessionId not found")
        val client = clients[state.engine]
            ?: throw IllegalStateException("No mail engine registered for ${state.engine}")
        try {
            client.login(sessionId = sessionId, params = params)
            markConnected(sessionId)
        } catch (e: MailException.TwoFaRequired) {
            throw e // retryable — keep CONNECTING
        } catch (e: MailException.MailboxPasswordRequired) {
            throw e // retryable — keep CONNECTING
        } catch (e: Exception) {
            Log.w(TAG, "Mail login failed for session $sessionId", e)
            failSession(sessionId, e.message ?: e.javaClass.simpleName)
            throw e
        }
    }

    private fun markConnected(sessionId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.CONNECTED,
                errorMessage = null,
            ))
        }
    }

    private fun failSession(sessionId: String, message: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                status = SessionState.Status.ERROR,
                errorMessage = message,
            ))
        }
    }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    /** The opaque Go-bridge session id of [profileId]'s connected session, if any. */
    fun getSessionIdForProfile(profileId: String): String? =
        _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.sessionId

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun removeSession(sessionId: String) {
        val state = _sessions.value[sessionId]
        _sessions.update { it - sessionId }
        if (state != null) revoke(listOf(state.sessionId to state.engine))
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRevoke = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.sessionId to it.engine }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        revoke(toRevoke)
    }

    /**
     * Best-effort revoke the session(s) in their engine so a disconnect actually
     * tears down the server-side session (the Go bridge's in-process entry for
     * Proton, the JVM Store for IMAP) — not just the local [SessionState]. Fire-
     * and-forget on [scope]; the local map is already cleared.
     */
    private fun revoke(sessions: List<Pair<String, MailEngine>>) {
        if (sessions.isEmpty()) return
        scope.launch {
            for ((id, engine) in sessions) {
                val client = clients[engine] ?: continue
                runCatching { client.logout(id) }
                    .onFailure { Log.w(TAG, "logout($id) failed: ${it.message}") }
            }
        }
    }
}
