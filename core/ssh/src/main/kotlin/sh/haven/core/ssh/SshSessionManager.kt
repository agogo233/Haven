package sh.haven.core.ssh

import android.util.Log
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.ChannelShell
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.repository.ConnectionLogRepository
import sh.haven.core.data.terminal.ScrollbackRing
import sh.haven.core.ssh.sftp.JschSftpSession
import sh.haven.core.ssh.sftp.SftpSession
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Soft cap on per-session terminal scrollback exposed to the agent
 * transport. The agent's `read_terminal_scrollback` tool returns
 * (a slice of) this buffer; everything older than 256 KiB rolls off.
 * Compose's terminal still keeps its own visual scrollback — this is a
 * separate, app-scoped mirror so the agent can read what the user sees
 * without going through activity-scoped state.
 */
private const val AGENT_SCROLLBACK_CAPACITY_BYTES = 256 * 1024

private const val TAG = "SshSessionManager"

/**
 * Time to watch a freshly-opened shell before declaring it healthy. A real
 * interactive shell stays open well past this; a shell with no session manager
 * (or a broken exec) EOFs within ms, so the failure path returns fast and the
 * success path waits at most this long. (#215 follow-up)
 */
private const val SHELL_SETTLE_MS = 1500L

/** Definitive outcome of opening a shell for a freshly-connected SSH session. */
sealed interface ShellOutcome {
    val sessionId: String
    /** Live terminal: channel connected + reader running past the settle window. */
    data class Ready(override val sessionId: String) : ShellOutcome
    /** Remote shell exited immediately (clean exit) — e.g. no session manager installed. */
    data class ShellClosed(override val sessionId: String, val exitStatus: Int) : ShellOutcome
    /** Opening the shell threw, or the channel dropped during connect. */
    data class Failed(override val sessionId: String, val reason: String) : ShellOutcome
}

/**
 * Manages active SSH sessions across the app.
 * Sessions are identified by a unique sessionId (UUID).
 * Multiple sessions may share the same profileId (multi-tab).
 */
@Singleton
class SshSessionManager @Inject constructor(
    private val hostKeyVerifier: HostKeyVerifier,
    private val connectionLogRepository: ConnectionLogRepository,
) {

    // Off-thread sink for reconnect breadcrumbs — never blocks the reader /
    // ioExecutor; logEvent self-gates on the connection-logging preference.
    private val logScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Record a reconnect-lifecycle breadcrumb in the connection log (visible in
     * Settings on release builds, where app logcat is stripped). Used to diagnose
     * reconnects that leave the terminal pane blank: the timeline of channel-swap
     * → reattach-sent → reader-ended shows whether the reattach fired and whether
     * the new shell produced any output. Best-effort; failures are swallowed.
     */
    private fun breadcrumb(profileId: String, detail: String) {
        logScope.launch {
            runCatching {
                connectionLogRepository.logEvent(
                    profileId, ConnectionLog.Status.DISCONNECTED, details = "reconnect-trace: $detail",
                )
            }
        }
    }

    data class PortForwardInfo(
        val ruleId: String,
        val type: PortForwardType,
        val bindAddress: String,
        val bindPort: Int,
        val targetHost: String,
        val targetPort: Int,
        val actualBoundPort: Int = bindPort,
        /**
         * When true, this forward is load-bearing for the session: if it
         * fails to (re-)bind, the session must NOT be considered healthy.
         * Used for the MCP reverse tunnel (`-R`), where a server-side
         * stale bind would otherwise leave a silently forward-less but
         * "CONNECTED" session. Mirrors OpenSSH `ExitOnForwardFailure`.
         */
        val critical: Boolean = false,
        /**
         * When true, a REMOTE (`-R`) bind failure triggers a one-shot
         * self-heal: over the (already-connected) SSH session, kill a stale
         * `sshd` process still holding this listen port on the endpoint host,
         * then retry the bind once. Used only for Haven's own dedicated
         * reverse-tunnel ports (the MCP `-R` + multiplexed guest MCP ports),
         * where the holder can only be a stale instance of our own forward —
         * automating the manual `kill` that previously un-wedged the tunnel
         * after an app restart / WiFi roam. Never set on arbitrary user
         * forwards.
         */
        val selfHealOnBindFailure: Boolean = false,
    )

    enum class PortForwardType { LOCAL, REMOTE, DYNAMIC }

    data class SessionState(
        val sessionId: String,
        val profileId: String,
        val label: String,
        val status: Status,
        val client: SshClient,
        val shellChannel: ChannelShell? = null,
        val terminalSession: TerminalSession? = null,
        val sftpChannel: ChannelSftp? = null,
        val connectionConfig: ConnectionConfig? = null,
        val sessionManager: SessionManager = SessionManager.NONE,
        val sessionCommandOverride: String? = null,
        val chosenSessionName: String? = null,
        /**
         * Per-connection bypass of [sessionManager]. When true,
         * [buildSessionManagerCommand] returns null even when the
         * profile has tmux/zellij/screen configured, so the SSH shell
         * stays as the user's natural login shell. Set from the session
         * picker's "Plain shell" affordance for one-off bare-bash
         * sessions on a profile that's normally wrapped in a multiplexer.
         */
        val bypassSessionManager: Boolean = false,
        val postLoginCommand: String? = null,
        val postLoginBeforeSessionManager: Boolean = false,
        val activeForwards: List<PortForwardInfo> = emptyList(),
        /** Session ID of the jump host session, if this connection goes through one. */
        val jumpSessionId: String? = null,
        /**
         * True when this session was opened automatically as a tunnel for
         * another profile (VNC/RDP/SMB-over-SSH-forward), as opposed to
         * being opened directly by the user. Combined with an empty
         * [tunnelDependents], signals the session can be torn down when
         * its last consumer disconnects (#121, KoriKraut).
         */
        val tunnelOpened: Boolean = false,
        /**
         * Profile IDs of non-SSH connections (VNC, RDP, SMB) currently
         * using this SSH session as their tunnel. When this empties on a
         * [tunnelOpened] session, the session is torn down by
         * [releaseTunnelDependent].
         */
        val tunnelDependents: Set<String> = emptySet(),
        /**
         * True for tunnel-only sessions that intentionally have no shell
         * (e.g. the dedicated MCP reverse tunnel). The reconnect path
         * skips opening a shell channel for these, so a network blip
         * re-establishes the port forwards without spawning an unwanted
         * (and possibly server-rejected) shell. (#150 tunnel-only.)
         */
        val headless: Boolean = false,
    ) {
        enum class Status { CONNECTING, CONNECTED, RECONNECTING, DISCONNECTED, ERROR }
    }

    private val _sessions = MutableStateFlow<Map<String, SessionState>>(emptyMap())
    val sessions: StateFlow<Map<String, SessionState>> = _sessions.asStateFlow()

    /**
     * Per-session app-scoped mirror of recent SSH stdout bytes for the
     * agent transport's `read_terminal_scrollback` tool. Allocated lazily
     * when the first terminal session is created on a session, dropped
     * in [removeSession] so closed sessions don't leak. We keep this in
     * a separate map rather than [SessionState] so the data class shape
     * stays unchanged for unrelated consumers.
     */
    private val agentScrollback = ConcurrentHashMap<String, ScrollbackRing>()

    /**
     * Live tunnel leases, keyed by leaseId. A lease ties a tunnel-dependent
     * resource (a VNC/RDP Desktop tab and its protocol client) to the SSH
     * session that carries its forward, so teardown cascades both ways:
     * removing the SSH session (for any reason) fires each lease's
     * [LeaseRecord.onParentGone] so the dependent closes itself, and closing
     * a lease releases the dependent (and tears the SSH down if it was opened
     * solely for this tunnel). This replaces the loose profileId-string
     * tracking that left tabs orphaned over dead pipes (#121).
     */
    private val leases = ConcurrentHashMap<String, LeaseRecord>()

    /**
     * Per-session provider that re-resolves the routing proxy (per-profile
     * WireGuard / Tailscale tunnel, or legacy SOCKS/HTTP) on auto-reconnect.
     *
     * core:ssh cannot depend on core:tunnel (core:tunnel already depends on
     * core:ssh — a cycle), so [SshSessionManager] has no [TunnelResolver]. The
     * ViewModel that owns the resolver registers this callback after a
     * successful connect; [attemptReconnect] invokes it to rebuild the same
     * proxy the initial connect used. Without it, a tunnel-routed session whose
     * host is only reachable through the tunnel (device off-LAN) reconnects
     * directly and never recovers. Jump-host sessions don't use this — they go
     * through [createProxyJump] keyed on [SessionState.jumpSessionId].
     */
    private val reconnectProxyProviders = ConcurrentHashMap<String, suspend () -> HavenProxy?>()

    /**
     * Per-session hooks run once after a successful reconnect (status back to
     * CONNECTED, forwards re-applied). Used by the USB/IP forwarder to re-run the
     * host-side `usbip attach` — the device node on the host vanishes when the
     * tunnel drops, and re-binding the `-R` alone doesn't restore it.
     */
    private val reconnectHooks = ConcurrentHashMap<String, suspend () -> Unit>()

    private class LeaseRecord(
        val leaseId: String,
        val sessionId: String,
        val dependentProfileId: String,
        val localForwardPort: Int?,
        val onParentGone: () -> Unit,
        val closed: AtomicBoolean = AtomicBoolean(false),
    )

    /** Background executor for disconnect I/O so callers on main thread don't block. */
    private val ioExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ssh-session-io").apply { isDaemon = true }
    }

    val activeSessions: List<SessionState>
        get() = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED ||
            it.status == SessionState.Status.CONNECTING ||
            it.status == SessionState.Status.RECONNECTING
        }

    val hasActiveSessions: Boolean
        get() = activeSessions.isNotEmpty()

    /**
     * Register a new session. Returns the generated sessionId (UUID).
     */
    fun registerSession(
        profileId: String,
        label: String,
        client: SshClient,
        headless: Boolean = false,
    ): String {
        val sessionId = UUID.randomUUID().toString()
        _sessions.update { map ->
            map + (sessionId to SessionState(
                sessionId = sessionId,
                profileId = profileId,
                label = label,
                status = SessionState.Status.CONNECTING,
                client = client,
                headless = headless,
            ))
        }
        return sessionId
    }

    fun storeConnectionConfig(
        sessionId: String,
        config: ConnectionConfig,
        sessionMgr: SessionManager,
        sessionCommandOverride: String? = null,
        postLoginCommand: String? = null,
        postLoginBeforeSessionManager: Boolean = true,
    ) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                connectionConfig = config,
                sessionManager = sessionMgr,
                sessionCommandOverride = sessionCommandOverride,
                postLoginCommand = postLoginCommand,
                postLoginBeforeSessionManager = postLoginBeforeSessionManager,
            ))
        }
    }

    /**
     * Register (or clear, when [provider] is null) the routing-proxy provider
     * invoked on auto-reconnect for [sessionId]. Call after a successful connect
     * with the same resolution the initial connect used
     * (`tunnelResolver.havenProxy(profile)`). Cleared automatically in
     * [removeSession].
     */
    fun setReconnectProxyProvider(sessionId: String, provider: (suspend () -> HavenProxy?)?) {
        if (provider == null) reconnectProxyProviders.remove(sessionId)
        else reconnectProxyProviders[sessionId] = provider
    }

    /**
     * Register (or clear, with null) a hook run once after each successful
     * reconnect of [sessionId], after forwards are re-applied. Cleared
     * automatically in [removeSession]. See [reconnectHooks].
     */
    fun setOnReconnected(sessionId: String, hook: (suspend () -> Unit)?) {
        if (hook == null) reconnectHooks.remove(sessionId)
        else reconnectHooks[sessionId] = hook
    }

    fun updateStatus(sessionId: String, status: SessionState.Status) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(status = status))
        }
    }

    /**
     * Open a shell channel on the SSH session and store it in the session state.
     * Must be called after the SSH session is connected.
     */
    fun openShellForSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        val channel = session.client.openShellChannel()
        attachShellChannel(sessionId, channel)
    }

    fun attachShellChannel(sessionId: String, channel: ChannelShell) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(shellChannel = channel))
        }
    }

    /**
     * Create a [TerminalSession] for a connected session that has a shell channel.
     * Returns the session, or null if the session/channel doesn't exist.
     * The [onDataReceived] callback delivers SSH output bytes.
     * Call [TerminalSession.start] after wiring up the emulator.
     */
    fun createTerminalSession(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit,
        connectSignal: CompletableDeferred<ShellOutcome>? = null,
    ): TerminalSession? {
        val session = _sessions.value[sessionId] ?: return null
        val channel = session.shellChannel ?: return null
        val pendingCmds = buildPendingCommands(sessionId, session.sessionManager, session.postLoginCommand, session.postLoginBeforeSessionManager)
        // Mirror raw SSH stdout into an app-scoped ring buffer so the
        // agent transport can read the same bytes the emulator sees,
        // without reaching into activity-scoped state.
        val ring = agentScrollback.computeIfAbsent(sessionId) {
            ScrollbackRing(AGENT_SCROLLBACK_CAPACITY_BYTES)
        }
        val termSession = TerminalSession(
            sessionId = sessionId,
            profileId = session.profileId,
            label = session.label,
            channel = channel,
            client = session.client,
            onDataReceived = onDataReceived,
            // Permanent ring mirror — NOT swapped by replaceDataCallback, so it
            // survives reattach now that the session is created at connect time
            // and the emulator attaches afterwards. (#215 follow-up)
            onMirror = { data, off, len -> ring.append(data, off, len) },
            onDisconnected = { cleanExit ->
                // Initial-connect window: report the outcome to the awaiting
                // connect path exactly once, instead of the normal
                // disconnect/reconnect handling. Once completed (here or
                // disarmed by openShellAndAwaitReady after the window),
                // established sessions take the reconnect-aware path below.
                if (connectSignal != null && !connectSignal.isCompleted) {
                    connectSignal.complete(
                        if (cleanExit) ShellOutcome.ShellClosed(sessionId, channel.exitStatus)
                        else ShellOutcome.Failed(sessionId, "shell closed during connect"),
                    )
                    updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                } else if (cleanExit) {
                    Log.d(TAG, "Session $sessionId exited cleanly — not reconnecting")
                    updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                } else {
                    Log.d(TAG, "Session $sessionId disconnected unexpectedly")
                    val sess = _sessions.value[sessionId]
                    val cfg = sess?.connectionConfig
                    if (cfg != null && cfg.reconnectPolicy.autoReconnect) {
                        ioExecutor.execute { attemptReconnect(sessionId) }
                    } else {
                        if (cfg != null) {
                            Log.d(TAG, "Session $sessionId auto-reconnect disabled by profile policy")
                        }
                        updateStatus(sessionId, SessionState.Status.DISCONNECTED)
                    }
                }
            },
            pendingCommands = pendingCmds,
            onBreadcrumb = { detail -> breadcrumb(session.profileId, detail) },
        )
        attachTerminalSession(sessionId, termSession)
        return termSession
    }

    /**
     * Open a shell for a freshly-connected session, attach + start its terminal
     * session, and AWAIT a definitive outcome before returning — so callers
     * navigate to the terminal ONLY on a confirmed live shell. A shell that
     * closes immediately (no session manager on the host) returns
     * [ShellOutcome.ShellClosed] within ms; a healthy shell returns
     * [ShellOutcome.Ready] after at most [settleMs]. Replaces the old
     * open-shell-then-poll pattern. (#215 follow-up)
     *
     * NOTE: leaves status as-is on Ready (the caller flips it to CONNECTED on
     * success); sets ERROR on ShellClosed/Failed so a dead shell never reads
     * as connected.
     */
    suspend fun openShellAndAwaitReady(
        sessionId: String,
        onDataReceived: (ByteArray, Int, Int) -> Unit = { _, _, _ -> },
        settleMs: Long = SHELL_SETTLE_MS,
    ): ShellOutcome {
        val session = _sessions.value[sessionId]
            ?: return ShellOutcome.Failed(sessionId, "session not found")
        val channel = try {
            session.client.openShellChannel()
        } catch (e: Exception) {
            Log.w(TAG, "openShellChannel failed for $sessionId: ${e.message}")
            updateStatus(sessionId, SessionState.Status.ERROR)
            return ShellOutcome.Failed(sessionId, e.message ?: "failed to open shell")
        }
        attachShellChannel(sessionId, channel)
        val connectSignal = CompletableDeferred<ShellOutcome>()
        val term = createTerminalSession(sessionId, onDataReceived, connectSignal)
            ?: run {
                updateStatus(sessionId, SessionState.Status.ERROR)
                return ShellOutcome.Failed(sessionId, "could not create terminal session")
            }
        term.start()
        val signalled = withTimeoutOrNull(settleMs) { connectSignal.await() }
        // Disarm: after the window, future disconnects must take the normal
        // (reconnect-aware) path, not the connect-outcome path.
        if (!connectSignal.isCompleted) connectSignal.complete(ShellOutcome.Ready(sessionId))
        return when {
            signalled is ShellOutcome.ShellClosed || signalled is ShellOutcome.Failed -> {
                updateStatus(sessionId, SessionState.Status.ERROR)
                signalled
            }
            channel.isConnected -> ShellOutcome.Ready(sessionId)
            else -> {
                updateStatus(sessionId, SessionState.Status.ERROR)
                ShellOutcome.ShellClosed(sessionId, channel.exitStatus)
            }
        }
    }

    /**
     * True iff the session has a connected channel AND an attached terminal
     * session — i.e. a usable terminal tab can/does exist. (#215 follow-up)
     */
    fun isLiveTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.shellChannel?.isConnected == true &&
            session.terminalSession != null
    }

    /**
     * Whether a session has a shell channel ready but no terminal session yet.
     */
    fun isReadyForTerminal(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.shellChannel != null &&
            session.terminalSession == null
    }

    /**
     * Whether a session already has a terminal session attached (e.g. after Activity recreation).
     * The TerminalViewModel should reattach to it rather than creating a new one.
     */
    fun hasExistingTerminalSession(sessionId: String): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        return session.status == SessionState.Status.CONNECTED &&
            session.terminalSession != null
    }

    /** Get the existing terminal session for reattach after Activity recreation. */
    fun getExistingTerminalSession(sessionId: String): TerminalSession? {
        return _sessions.value[sessionId]?.terminalSession
    }

    /**
     * Open an SFTP channel for a profile. Finds any connected session for that profile
     * and opens (or reuses) an SFTP channel on it.
     * Returns the channel, or null if no session for this profile is connected.
     */
    fun openSftpForProfile(profileId: String): ChannelSftp? {
        val session = _sessions.value.values
            .filter { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            .firstOrNull() ?: return null
        // Reuse existing channel if still connected
        session.sftpChannel?.let { if (it.isConnected) return it }
        val channel = session.client.openSftpChannel()
        _sessions.update { map ->
            val existing = map[session.sessionId] ?: return@update map
            map + (session.sessionId to existing.copy(sftpChannel = channel))
        }
        return channel
    }

    /**
     * Open (or reuse) an [SftpSession] for [profileId] — the Haven-internal
     * facade over JSch's `ChannelSftp`. Callers in feature- and app-modules
     * use this instead of [openSftpForProfile] so they do not import JSch
     * types directly. Backed by the same channel that [openSftpForProfile]
     * caches, so the two return semantically the same session.
     */
    fun openSftpSession(profileId: String): SftpSession? =
        openSftpForProfile(profileId)?.let { JschSftpSession(it) }

    /**
     * Find a connected [SshClient] for this profile. Used by file-transfer
     * code paths that need shell exec (SCP, shell-ls browsing) — i.e. the
     * legacy transport for servers without an SFTP subsystem.
     */
    fun getSshClientForProfile(profileId: String): SshClient? {
        return _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.status == SessionState.Status.CONNECTED }
            ?.client
    }

    /**
     * True when another live session shares [sessionId]'s [SshClient] — i.e.
     * this session was opened via connection reuse (a second terminal on an
     * already-connected profile, sharing one SSH connection across several
     * shell channels / tmux sessions). Callers use this to avoid duplicating
     * connection-scoped setup (port forwards) and to keep the shared client
     * alive in [tearDown] until its last session goes away.
     */
    fun isClientShared(sessionId: String): Boolean {
        val target = _sessions.value[sessionId] ?: return false
        return _sessions.value.values.any { it.sessionId != sessionId && it.client === target.client }
    }

    /**
     * Resolve a live [SshClient] to reuse for [profileId], coordinating with
     * an in-flight connect. Returns a CONNECTED session's client immediately
     * if one exists; otherwise, if a session for the profile is currently
     * CONNECTING (e.g. the MCP carrier coming up on app launch), suspends
     * until that connect resolves and returns its client (or null if it
     * failed). Returns null when nothing is live and nothing is in flight.
     * Bounded by [timeoutMs] so a stuck connect can't hang the caller.
     *
     * This closes the app-launch race where a restored terminal tab and the
     * MCP carrier both target the same tunnel-routed profile: without the
     * wait, the tab's reuse check runs before the carrier reaches CONNECTED,
     * so it dials a second concurrent WireGuard flow the peer can't service
     * and times out. MUST be called BEFORE the caller registers its own
     * session, so it never waits on itself.
     */
    suspend fun awaitReusableClient(profileId: String, timeoutMs: Long = 35_000): SshClient? {
        getSshClientForProfile(profileId)?.let { return it }
        val pending = _sessions.value.values.firstOrNull {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTING
        } ?: return null
        Log.d(TAG, "awaitReusableClient($profileId): waiting on in-flight connect ${pending.sessionId}")
        return withTimeoutOrNull(timeoutMs) {
            _sessions.first { map ->
                val s = map[pending.sessionId]
                s == null || s.status != SessionState.Status.CONNECTING
            }
            getSshClientForProfile(profileId)
        }
    }

    /**
     * Store a bookkeeping [ConnectionConfig] for a connection-reuse session
     * [sessionId] — one that was registered against another session's live
     * [SshClient] instead of dialing its own. The config carries reconnect
     * OFF (the primary session owns reconnect of the shared client) and a
     * fresh empty password, so [tearDown]'s auth-zeroing can never reach the
     * primary's live credential. Host/port/username are copied from the
     * profile's primary session purely for display/bookkeeping.
     */
    fun storeReuseConfig(sessionId: String, profileId: String, sessionMgr: SessionManager) {
        val ref = _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.sessionId != sessionId && it.connectionConfig != null }
            ?.connectionConfig
        val config = ConnectionConfig(
            host = ref?.host ?: "reused-connection",
            port = ref?.port ?: 22,
            username = ref?.username ?: "reused",
            authMethod = ConnectionConfig.AuthMethod.Password(""),
            reconnectPolicy = ConnectionConfig.ReconnectPolicy(autoReconnect = false),
        )
        storeConnectionConfig(sessionId, config, sessionMgr)
    }

    /**
     * Create an [ScpClient] bound to the connected JSch session for
     * [profileId]. Each call produces a new façade — the session itself is
     * shared, but SCP opens a fresh exec channel per operation so there is
     * no caching to do here.
     */
    fun openScpForProfile(profileId: String): ScpClient? {
        val client = getSshClientForProfile(profileId) ?: return null
        val jschSession = client.jschSession ?: return null
        return ScpClient(jschSession)
    }

    fun attachTerminalSession(sessionId: String, terminalSession: TerminalSession) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(terminalSession = terminalSession))
        }
    }

    /**
     * Detach a terminal session without closing the shell channel.
     * Called when TerminalViewModel is cleared (Activity destroyed) but the
     * process stays alive via the foreground service. Allows a new
     * TerminalViewModel to re-create a TerminalSession on the same channel.
     */
    fun detachTerminalSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        session.terminalSession?.detach()
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(terminalSession = null))
        }
    }

    /**
     * Request reconnection of a specific session.
     * No-op if session is already RECONNECTING or CONNECTED, or has no connection config.
     * Used by [NetworkMonitor] to trigger immediate reconnect on network change.
     */
    fun requestReconnect(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        if (session.status == SessionState.Status.RECONNECTING) return
        if (session.status == SessionState.Status.CONNECTED) return
        if (session.connectionConfig == null) return
        Log.d(TAG, "requestReconnect: $sessionId (status=${session.status})")
        ioExecutor.execute { attemptReconnect(sessionId) }
    }

    /**
     * Request reconnection of all sessions that are DISCONNECTED or ERROR.
     * Used by [SshConnectionService] when network becomes available.
     */
    fun requestReconnectAll() {
        _sessions.value.forEach { (id, session) ->
            val cfg = session.connectionConfig ?: return@forEach
            if (session.status !in listOf(SessionState.Status.DISCONNECTED, SessionState.Status.ERROR)) return@forEach
            // #150: honour per-profile policy. Either toggle alone is
            // enough to disable a network-change reconnect — both have
            // to be on for the NetworkMonitor → reconnect path.
            if (!cfg.reconnectPolicy.autoReconnect) return@forEach
            if (!cfg.reconnectPolicy.onNetworkChange) return@forEach
            requestReconnect(id)
        }
    }

    /**
     * Probe every live SSH session and reconnect any whose socket died
     * silently. Called on every return-to-foreground (see [SshConnectionService])
     * because a backgrounded socket can be dropped by NAT/Doze without a
     * default-network transport change — so neither the keepalive-timeout path
     * nor the [NetworkMonitor] path fires, and the session sits frozen while
     * still reporting CONNECTED.
     *
     * That CONNECTED status is exactly why we must probe first: [requestReconnect]
     * no-ops on a CONNECTED session, so a failed probe is flipped to DISCONNECTED
     * before being handed off. Probes run concurrently with a bounded per-probe
     * timeout so one dead session doesn't serialize behind another's wait.
     *
     * Skips:
     *  - non-CONNECTED sessions (CONNECTING/RECONNECTING are already in flight;
     *    DISCONNECTED/ERROR are covered by [requestReconnect]/[requestReconnectAll]),
     *  - headless tunnel sessions (e.g. the MCP reverse tunnel) — McpTunnelManager
     *    owns those via its own watchdog and probing here would race its reconnect,
     *  - sessions whose profile policy disables autoReconnect.
     */
    suspend fun probeAndReconnectStale(probeTimeoutMs: Long = 5_000L) {
        val candidates = _sessions.value.values.filter {
            it.status == SessionState.Status.CONNECTED &&
                !it.headless &&
                it.connectionConfig?.reconnectPolicy?.autoReconnect == true
        }
        if (candidates.isEmpty()) return
        Log.d(TAG, "probeAndReconnectStale: probing ${candidates.size} live session(s)")
        coroutineScope {
            candidates.map { session ->
                async {
                    if (!session.client.isAlive(probeTimeoutMs)) {
                        Log.d(TAG, "probeAndReconnectStale: ${session.sessionId} stale — reconnecting")
                        updateStatus(session.sessionId, SessionState.Status.DISCONNECTED)
                        requestReconnect(session.sessionId)
                    }
                }
            }.awaitAll()
        }
    }

    /** Sessions with a reconnect loop currently running, so a second caller
     *  (e.g. a late onDisconnected racing a network-probe) can't start a
     *  duplicate loop that tears down a healthy session. (#208 finding 10) */
    private val reconnectingInFlight = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * Attempt to reconnect a dropped session with exponential backoff.
     * Called on the ioExecutor thread.
     */
    private fun attemptReconnect(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        val config = session.connectionConfig ?: return
        val sessionMgr = session.sessionManager

        if (!reconnectingInFlight.add(sessionId)) {
            Log.d(TAG, "attemptReconnect: $sessionId already in flight — skipping duplicate")
            return
        }
        try {
        updateStatus(sessionId, SessionState.Status.RECONNECTING)
        breadcrumb(session.profileId, "attempt started → ${config.host}:${config.port}")

        // Proactively mark dependent sessions (that use this as jump host) as RECONNECTING
        // so the UI shows the reconnecting indicator immediately instead of waiting for keepalive timeout
        val dependents = _sessions.value.values.filter { it.jumpSessionId == sessionId }
        for (dep in dependents) {
            if (dep.status == SessionState.Status.CONNECTED) {
                Log.d(TAG, "Marking dependent session ${dep.sessionId} as RECONNECTING (jump host $sessionId dropped)")
                updateStatus(dep.sessionId, SessionState.Status.RECONNECTING)
            }
        }

        // #150: per-profile cap. 0 means unlimited (useful for tunnel-only
        // profiles that hold port forwards alive). Negative values are
        // treated as the legacy default; the data model schema rejects
        // them but defensive code is cheap.
        val maxAttempts = config.reconnectPolicy.maxAttempts
        val unlimited = maxAttempts == 0
        val attemptCap = when {
            unlimited -> Int.MAX_VALUE
            maxAttempts > 0 -> maxAttempts
            else -> RECONNECT_MAX_ATTEMPTS
        }
        var delayMs = RECONNECT_INITIAL_DELAY_MS
        var attempt = 0
        while (attempt < attemptCap) {
            attempt++
            // Check if session was removed (user manually disconnected)
            if (_sessions.value[sessionId] == null) {
                Log.d(TAG, "Reconnect cancelled for $sessionId — session removed")
                return
            }

            val capLabel = if (unlimited) "∞" else "$attemptCap"
            Log.d(TAG, "Reconnect attempt $attempt/$capLabel for $sessionId (delay ${delayMs}ms)")
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
                return
            }

            // Check again after sleep
            if (_sessions.value[sessionId] == null) return

            try {
                // Rebuild the routing proxy the same way the initial connect
                // did. Jump host > per-profile tunnel (WireGuard / Tailscale) /
                // SOCKS / HTTP — mirroring ConnectionsViewModel.connectSsh.
                // Without the tunnel branch a tunnel-routed profile reconnects
                // directly and never recovers when the host is only reachable
                // through the tunnel (device off-LAN).
                val jumpSid = _sessions.value[sessionId]?.jumpSessionId
                val proxy = if (jumpSid != null) {
                    val jumpSession = _sessions.value[jumpSid]
                    if (jumpSession?.status != SessionState.Status.CONNECTED) {
                        Log.w(TAG, "Jump host $jumpSid not connected — cannot reconnect $sessionId")
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        continue
                    }
                    createProxyJump(jumpSid)
                } else {
                    reconnectProxyProviders[sessionId]?.let { provider ->
                        runBlocking { provider() }
                    }
                }

                val newClient = SshClient()
                val hostKeyEntry = newClient.connectBlocking(config, proxy = proxy)

                // Silent TOFU on reconnect: auto-accept new, abort on change
                val hkResult = runBlocking { hostKeyVerifier.verify(hostKeyEntry) }
                when (hkResult) {
                    is HostKeyResult.Trusted -> { /* matches — continue */ }
                    is HostKeyResult.NewHost -> {
                        runBlocking { hostKeyVerifier.accept(hkResult.entry) }
                    }
                    is HostKeyResult.KeyChanged -> {
                        Log.w(TAG, "Host key changed during reconnect for $sessionId — aborting")
                        newClient.disconnect()
                        updateStatus(sessionId, SessionState.Status.ERROR)
                        return
                    }
                }

                // Update session state with new client
                _sessions.update { map ->
                    val existing = map[sessionId] ?: return@update map
                    map + (sessionId to existing.copy(client = newClient))
                }

                // Open shell and reconnect terminal — skipped for headless
                // tunnel-ONLY sessions (e.g. the MCP reverse tunnel), which
                // carry port forwards and never had a shell. But a headless
                // session that ALSO holds an interactive terminal (the "near"
                // profile used as BOTH the MCP reverse-tunnel endpoint and a
                // REPL tab — tunnel + REPL collapsed onto one session) must
                // still reattach its shell, or the tunnel recovers on
                // return-to-foreground (via McpTunnelManager.kickNow) while the
                // terminal stays frozen. A pure tunnel has terminalSession ==
                // null and still skips this.
                val reconnecting = _sessions.value[sessionId]
                if (reconnecting?.headless != true || reconnecting.terminalSession != null) {
                    val channel = newClient.openShellChannel()
                    attachShellChannel(sessionId, channel)

                    // Swap channel in the terminal session and restart reader
                    val termSession = _sessions.value[sessionId]?.terminalSession
                    val sess = _sessions.value[sessionId]
                    val pendingCmds = buildPendingCommands(sessionId, sessionMgr, sess?.postLoginCommand, sess?.postLoginBeforeSessionManager ?: false)
                    if (pendingCmds.isNotEmpty()) {
                        termSession?.setPendingCommands(pendingCmds)
                    }
                    termSession?.reconnect(channel, newClient)
                }

                // Restore port forwards
                val forwards = _sessions.value[sessionId]?.activeForwards.orEmpty()
                if (forwards.isNotEmpty()) {
                    // Clear current list, re-apply will add them back
                    _sessions.update { map ->
                        val existing = map[sessionId] ?: return@update map
                        map + (sessionId to existing.copy(activeForwards = emptyList()))
                    }
                    // ExitOnForwardFailure: if a critical forward (e.g. the
                    // MCP -R tunnel) can't re-bind — typically because a
                    // stale server-side bind hasn't timed out yet — tear the
                    // transport down and let the backoff loop retry rather
                    // than declaring a healthy-but-forward-less session.
                    if (!applyPortForwards(sessionId, forwards)) {
                        Log.w(TAG, "Critical port forward failed to re-bind for $sessionId — retrying")
                        try { newClient.disconnect() } catch (_: Throwable) {}
                        delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
                        continue
                    }
                }

                updateStatus(sessionId, SessionState.Status.CONNECTED)
                // Post-reconnect hooks (e.g. USB/IP host re-attach). Best-effort —
                // a failing hook must never abort a successful reconnect.
                reconnectHooks[sessionId]?.let { hook -> runCatching { runBlocking { hook() } } }
                Log.d(TAG, "Reconnected $sessionId on attempt $attempt")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed for $sessionId: ${e.message}")
                delayMs = (delayMs * 2).coerceAtMost(RECONNECT_MAX_DELAY_MS)
            }
        }

        Log.d(TAG, "Reconnect failed after $attempt attempts for $sessionId")
        updateStatus(sessionId, SessionState.Status.DISCONNECTED)
        } finally {
            reconnectingInFlight.remove(sessionId)
        }
    }

    fun removeSession(sessionId: String) {
        val session = _sessions.value[sessionId] ?: return
        // Cascade: disconnect any sessions that use this one as a jump host
        val dependents = _sessions.value.values.filter { it.jumpSessionId == sessionId }
        _sessions.update { it - sessionId }
        agentScrollback.remove(sessionId)
        reconnectProxyProviders.remove(sessionId)
        reconnectHooks.remove(sessionId)
        // Fire any tunnel leases riding on this session so their dependents
        // (VNC/RDP tabs) tear themselves down. Single-shot per lease; runs on
        // the caller thread (the consumer re-dispatches to its own scope).
        // Only true removal fires this — reconnect copies the entry in place
        // and never calls removeSession, so a network blip won't close tabs.
        fireParentGone(sessionId)
        ioExecutor.execute { tearDown(session) }
        for (dep in dependents) {
            Log.d(TAG, "Cascading disconnect from jump host $sessionId to ${dep.sessionId}")
            removeSession(dep.sessionId)
        }
    }

    private fun fireParentGone(sessionId: String) {
        val affected = leases.values.filter { it.sessionId == sessionId }
        for (rec in affected) {
            if (leases.remove(rec.leaseId) != null && rec.closed.compareAndSet(false, true)) {
                Log.d(TAG, "Tunnel parent $sessionId gone — notifying dependent ${rec.dependentProfileId}")
                runCatching { rec.onParentGone() }
                    .onFailure { Log.w(TAG, "onParentGone callback for ${rec.dependentProfileId} threw", it) }
            }
        }
    }

    /**
     * Read the most recent [maxBytes] of raw SSH stdout for [sessionId]
     * from the agent-scope ring buffer, or null if no ring exists yet
     * (no terminal session has been created on this SSH session).
     *
     * Bytes are returned in chronological order. ANSI escape sequences,
     * OSC markers, and other control bytes are preserved — the agent is
     * expected to parse them or ask the user to strip them.
     */
    fun readAgentScrollback(sessionId: String, maxBytes: Int): ByteArray? {
        val ring = agentScrollback[sessionId] ?: return null
        val full = ring.snapshot()
        return if (full.size <= maxBytes) full
        else full.copyOfRange(full.size - maxBytes, full.size)
    }

    /**
     * Monotonic count of bytes appended to a session's agent scrollback
     * ring over its lifetime. Unlike the ring's [snapshot] size (which
     * saturates at the ring capacity), this never saturates and never
     * resets — so it's the right value to capture as a "baseline" if
     * you want to detect "have new bytes arrived since this point?"
     * Returns null when the session has no ring (i.e. never had a
     * terminal channel attached). (#161)
     */
    fun agentScrollbackTotalBytes(sessionId: String): Long? =
        agentScrollback[sessionId]?.totalBytesAppended

    /**
     * Identify the SSH session, if any, carrying a remote port-forward
     * for [bindPort] on the loopback. By convention Haven uses 8730 for
     * the MCP reverse tunnel — i.e. the session running the Claude Code
     * REPL on the workstation. Used by the `queue_terminal_input` MCP
     * tool to auto-detect which session to watch + type into. Returns
     * the first match (there should be at most one in practice). (#161)
     */
    fun findRemoteForwardSession(bindPort: Int): String? =
        _sessions.value.values.firstOrNull { state ->
            // Skip the dedicated headless MCP tunnel — it carries the same
            // `-R` but has no terminal to type into. The auto-detect wants
            // the interactive session running the agent's REPL.
            !state.headless && state.activeForwards.any {
                it.type == PortForwardType.REMOTE && it.bindPort == bindPort
            }
        }?.sessionId

    /**
     * Send [text] as UTF-8 bytes to the active terminal session for
     * [sessionId]. Throws [IllegalStateException] if there is no
     * connected session with an attached terminal session — the agent
     * transport surfaces that as a JSON-RPC error.
     *
     * Routed through [TerminalSession.sendToSsh] (rather than writing
     * the shell channel directly) so it shares the same back-pressure
     * and serialised-write executor that user keystrokes use; an agent
     * pasting a long block can't interleave bytes with a human typing.
     */
    fun sendInput(sessionId: String, text: String) {
        val session = _sessions.value[sessionId]
            ?: throw IllegalStateException("No SSH session: $sessionId")
        val terminal = session.terminalSession
            ?: throw IllegalStateException(
                "Session $sessionId has no active terminal — open a terminal tab first")
        terminal.sendToSsh(text.toByteArray(Charsets.UTF_8))
    }

    fun getSession(sessionId: String): SessionState? = _sessions.value[sessionId]

    // --- Profile-level helpers ---

    fun getSessionsForProfile(profileId: String): List<SessionState> =
        _sessions.value.values.filter { it.profileId == profileId }

    fun isProfileConnected(profileId: String): Boolean =
        _sessions.value.values.any {
            it.profileId == profileId && it.status == SessionState.Status.CONNECTED
        }

    fun getProfileStatus(profileId: String): SessionState.Status? {
        val statuses = _sessions.value.values
            .filter { it.profileId == profileId }
            .map { it.status }
        if (statuses.isEmpty()) return null
        // Priority: CONNECTED > RECONNECTING > CONNECTING > ERROR > DISCONNECTED
        return when {
            SessionState.Status.CONNECTED in statuses -> SessionState.Status.CONNECTED
            SessionState.Status.RECONNECTING in statuses -> SessionState.Status.RECONNECTING
            SessionState.Status.CONNECTING in statuses -> SessionState.Status.CONNECTING
            SessionState.Status.ERROR in statuses -> SessionState.Status.ERROR
            else -> SessionState.Status.DISCONNECTED
        }
    }

    fun getConnectionConfigForProfile(profileId: String): Pair<ConnectionConfig, SessionManager>? {
        val session = _sessions.value.values
            .firstOrNull { it.profileId == profileId && it.connectionConfig != null }
            ?: return null
        // Deep-copy auth material so tearDown() zeroing one session's bytes
        // doesn't corrupt the config for other sessions sharing the same profile.
        val config = session.connectionConfig!!
        val safeCopy = when (val auth = config.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> config.copy(
                authMethod = ConnectionConfig.AuthMethod.Password(auth.password.copyOf())
            )
            is ConnectionConfig.AuthMethod.PrivateKey -> config.copy(
                authMethod = auth.copy(keyBytes = auth.keyBytes.copyOf())
            )
            else -> config
        }
        return safeCopy to session.sessionManager
    }

    fun removeAllSessionsForProfile(profileId: String) {
        val toRemove = _sessions.value.values.filter { it.profileId == profileId }
        _sessions.update { map -> map.filterValues { it.profileId != profileId } }
        ioExecutor.execute {
            toRemove.forEach { tearDown(it) }
        }
    }

    fun disconnectAll() {
        val snapshot = _sessions.value.values.toList()
        _sessions.update { emptyMap() }
        ioExecutor.execute {
            snapshot.forEach { tearDown(it) }
            SshClient.clearDnsCache()
        }
    }

    fun setChosenSessionName(sessionId: String, name: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(chosenSessionName = name))
        }
    }

    fun setBypassSessionManager(sessionId: String, bypass: Boolean) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(bypassSessionManager = bypass))
        }
    }

    fun setJumpSessionId(sessionId: String, jumpSessionId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(jumpSessionId = jumpSessionId))
        }
    }

    /**
     * Mark [sessionId] as having been opened as a tunnel for
     * [dependentProfileId] (a VNC/RDP/SMB profile that needs this SSH
     * session for port forwarding). When the last dependent is released,
     * the session is torn down — see [releaseTunnelDependent].
     *
     * Call this only when the session is freshly opened by the auto-tunnel
     * path; for sessions that were already connected and just had a new
     * dependent attached, use [attachTunnelDependent] instead so the
     * tunnelOpened flag isn't (re-)set on a user-opened session.
     */
    fun markTunnelOpened(sessionId: String, dependentProfileId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                tunnelOpened = true,
                tunnelDependents = existing.tunnelDependents + dependentProfileId,
            ))
        }
    }

    /**
     * Add [dependentProfileId] to an existing session's dependent set
     * without changing its [SessionState.tunnelOpened] flag. Use when an
     * already-connected session is reused by a new VNC/RDP/SMB tunnel —
     * the session might have been opened directly by the user (terminal),
     * and we don't want to inherit a teardown policy that would close it
     * when the tunnel goes away.
     */
    fun attachTunnelDependent(sessionId: String, dependentProfileId: String) {
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                tunnelDependents = existing.tunnelDependents + dependentProfileId,
            ))
        }
    }

    /**
     * Remove [dependentProfileId] from every session's tunnel-dependent
     * set. Tears down any session that (a) was opened solely as a tunnel
     * ([SessionState.tunnelOpened] = true) and (b) has no remaining
     * dependents after the removal. Returns the list of session IDs
     * actually torn down (for logging / audit purposes).
     *
     * Called by [ConnectionsViewModel.disconnect] when a VNC/RDP/SMB
     * profile is disconnected, so the SSH session that was opened only
     * to carry its forward stops idling on the user's connections list
     * (#121, KoriKraut).
     */
    fun releaseTunnelDependent(dependentProfileId: String): List<String> {
        val toTearDown = mutableListOf<String>()
        _sessions.update { map ->
            map.mapValues { (_, session) ->
                if (dependentProfileId !in session.tunnelDependents) return@mapValues session
                val newDeps = session.tunnelDependents - dependentProfileId
                if (newDeps.isEmpty() && session.tunnelOpened) {
                    toTearDown += session.sessionId
                }
                session.copy(tunnelDependents = newDeps)
            }
        }
        for (sid in toTearDown) {
            Log.d(TAG, "Tearing down orphaned tunnel session $sid (last dependent $dependentProfileId released)")
            removeSession(sid)
        }
        return toTearDown
    }

    /** A first-class handle for a tunnel-dependent resource. See [acquireTunnelLease]. */
    interface TunnelLease : AutoCloseable {
        val sessionId: String
        val dependentProfileId: String
        /** Release this dependent. Idempotent. */
        override fun close()
    }

    /**
     * Acquire a lease tying a tunnel-dependent resource (a VNC/RDP tab and
     * its client) to the SSH session [sessionId] that carries its forward.
     *
     * The dependent must already have been registered on the session via
     * [markTunnelOpened] / [attachTunnelDependent] (done by the connect
     * orchestrator, which knows whether the SSH was auto-opened or reused).
     * The lease owns the *local port forward*, the *teardown callback*, and
     * the *dependent release*:
     *  - [onParentGone] fires when the SSH session is removed for any reason
     *    (user disconnect, network-death teardown, jump-host cascade) so the
     *    dependent closes itself. It runs on the [removeSession] caller
     *    thread — the consumer must re-dispatch to its own scope.
     *  - [close] (consumer-initiated: tab closed / connect failed / swipe)
     *    removes the forward and releases the dependent, tearing the SSH down
     *    iff it was opened solely for this tunnel.
     *
     * Both directions are single-shot and collision-free, so a tab-close
     * racing an SSH teardown can't double-free (#121).
     */
    fun acquireTunnelLease(
        sessionId: String,
        dependentProfileId: String,
        localForwardPort: Int?,
        onParentGone: () -> Unit,
    ): TunnelLease {
        val rec = LeaseRecord(
            leaseId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            dependentProfileId = dependentProfileId,
            localForwardPort = localForwardPort,
            onParentGone = onParentGone,
        )
        leases[rec.leaseId] = rec
        return object : TunnelLease {
            override val sessionId = sessionId
            override val dependentProfileId = dependentProfileId
            override fun close() = closeLease(rec.leaseId)
        }
    }

    /**
     * Tear down the tunnel-dependent resources for [dependentProfileId] —
     * used when the user disconnects that VNC/RDP/SMB profile directly from
     * the connections list. Fires each matching lease's onParentGone (so the
     * Desktop tab closes even when the carrying SSH is a shared / user-opened
     * session that legitimately survives), then releases the SSH dependent
     * (tearing the SSH down iff it was opened solely for this tunnel). Returns
     * the SSH session ids actually torn down. (#121: the parent-gone cascade
     * alone can't close the tab when the SSH isn't removed.)
     */
    fun teardownTunnelDependent(dependentProfileId: String): List<String> {
        val matching = leases.values.filter { it.dependentProfileId == dependentProfileId }
        for (rec in matching) {
            if (leases.remove(rec.leaseId) != null && rec.closed.compareAndSet(false, true)) {
                runCatching { rec.onParentGone() }
                    .onFailure { Log.w(TAG, "onParentGone for $dependentProfileId threw", it) }
            }
        }
        return releaseTunnelDependent(dependentProfileId)
    }

    /** Consumer-initiated lease release. Idempotent; does not fire [LeaseRecord.onParentGone]. */
    private fun closeLease(leaseId: String) {
        val rec = leases.remove(leaseId) ?: return
        if (!rec.closed.compareAndSet(false, true)) return
        rec.localForwardPort?.let { port ->
            _sessions.value[rec.sessionId]?.client?.let { client ->
                runCatching { client.delPortForwardingL("127.0.0.1", port) }
                    .onFailure { Log.w(TAG, "Failed to remove tunnel forward $port on ${rec.sessionId}", it) }
            }
        }
        releaseTunnelDependent(rec.dependentProfileId)
    }

    /**
     * Create a [ProxyJump] from a connected jump host session.
     * Returns null if the session doesn't exist or isn't connected.
     */
    fun createProxyJump(jumpSessionId: String): HavenProxy? {
        val jumpSession = _sessions.value[jumpSessionId]
        if (jumpSession == null) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId not found in sessions map")
            return null
        }
        if (jumpSession.status != SessionState.Status.CONNECTED) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId status=${jumpSession.status}, expected CONNECTED")
            return null
        }
        val jschSession = jumpSession.client.jschSession
        if (jschSession == null) {
            Log.w(TAG, "createProxyJump: session $jumpSessionId has no jschSession (client.isConnected=${jumpSession.client.isConnected})")
            return null
        }
        Log.d(TAG, "createProxyJump: success for $jumpSessionId (jsch connected=${jschSession.isConnected})")
        return HavenProxy(ProxyJump(jschSession))
    }

    /**
     * Activate port forwards on a connected session.
     * Each rule is applied independently; failures are logged but don't block others.
     */
    /**
     * Activate [rules] on [sessionId]. Returns true iff every rule the
     * caller treats as load-bearing came up.
     *
     * A rule fails the call when its [PortForwardInfo.critical] flag is
     * set and the bind throws — this is the `ExitOnForwardFailure`
     * semantics the MCP reverse tunnel relies on, so a stale server-side
     * `-R` bind surfaces as a failed (re)connect instead of a healthy
     * session with no working forward. Non-critical forwards keep the
     * previous best-effort behaviour (log and continue), so the return
     * value is true unless a *critical* forward failed.
     */
    fun applyPortForwards(sessionId: String, rules: List<PortForwardInfo>): Boolean {
        val session = _sessions.value[sessionId] ?: return false
        val activated = mutableListOf<PortForwardInfo>()
        var criticalFailed = false

        for (rule in rules) {
            try {
                when (rule.type) {
                    PortForwardType.LOCAL -> {
                        val actualPort = session.client.setPortForwardingL(
                            rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort,
                        )
                        activated.add(rule.copy(actualBoundPort = actualPort))
                        Log.d(TAG, "Port forward activated: L ${rule.bindAddress}:$actualPort -> ${rule.targetHost}:${rule.targetPort}")
                    }
                    PortForwardType.REMOTE -> {
                        bindRemoteWithSelfHeal(session.client, rule)
                        activated.add(rule)
                        Log.d(TAG, "Port forward activated: R ${rule.bindAddress}:${rule.bindPort} -> ${rule.targetHost}:${rule.targetPort}")
                    }
                    PortForwardType.DYNAMIC -> {
                        val actualPort = session.client.setPortForwardingDynamic(
                            rule.bindAddress, rule.bindPort,
                        )
                        activated.add(rule.copy(actualBoundPort = actualPort))
                        Log.d(TAG, "Port forward activated: D ${rule.bindAddress}:$actualPort (SOCKS5)")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to activate port forward ${rule.ruleId}: ${e.message}")
                if (rule.critical) criticalFailed = true
            }
        }

        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(activeForwards = existing.activeForwards + activated))
        }
        // After a clean (re)connect of a self-healing reverse tunnel, record
        // this connection's endpoint sshd PID so a future instance can kill
        // this session if it lingers stale and blocks the rebind.
        if (!criticalFailed && rules.any { it.selfHealOnBindFailure && it.type == PortForwardType.REMOTE }) {
            recordTunnelSshPid(session.client)
        }
        return !criticalFailed
    }

    /**
     * Bind a REMOTE (`-R`) forward, with one-shot self-heal for
     * [PortForwardInfo.selfHealOnBindFailure] forwards: if the bind throws
     * (typically "Address already in use" from a stale `sshd` holding the
     * port after an app restart / roam), free the stale holder over the
     * already-connected session and retry once. A second failure propagates
     * to the caller (which sets `criticalFailed` as before).
     */
    private fun bindRemoteWithSelfHeal(client: SshClient, rule: PortForwardInfo) {
        try {
            client.setPortForwardingR(rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort)
        } catch (e: Exception) {
            if (!rule.selfHealOnBindFailure) throw e
            Log.w(TAG, "R ${rule.bindPort} bind failed (${e.message}) — self-healing stale forward on endpoint")
            if (!freeStaleReverseForward(client, rule.bindPort)) throw e
            try {
                Thread.sleep(600)
            } catch (_: InterruptedException) {}
            client.setPortForwardingR(rule.bindAddress, rule.bindPort, rule.targetHost, rule.targetPort)
            Log.i(TAG, "R ${rule.bindPort} self-heal succeeded — rebound after freeing stale holder")
        }
    }

    /**
     * Over the connected [client], kill the STALE prior tunnel's `sshd`
     * session that is still holding the reverse-forward port on the endpoint,
     * so a fresh `-R` bind can succeed. Uses the PID recorded by
     * [recordTunnelSshPid] on the previous successful connect — because
     * `ss -p` without root cannot attribute the listen socket to a PID (sshd
     * hardens its processes, `dumpable=0`, so `/proc/<pid>/fd` is root-only).
     * Surgical + root-free: kills only a live `sshd-session` PID (comm check
     * guards against PID reuse) that is NOT the current connection's own
     * session (`$PPID`). Returns true iff it killed the recorded holder.
     */
    private fun freeStaleReverseForward(client: SshClient, port: Int): Boolean {
        val cmd =
            "F=\"\$HOME/.haven-mcp-tunnel.pid\"; old=\$(cat \"\$F\" 2>/dev/null); " +
                "if [ -n \"\$old\" ] && [ \"\$old\" != \"\$PPID\" ] && grep -qa sshd \"/proc/\$old/comm\" 2>/dev/null; then " +
                "kill \"\$old\" 2>/dev/null && echo HEALED || echo KILLFAIL; " +
                "else echo NOHOLDER; fi"
        return try {
            val r = runBlocking { client.execCommand(cmd) }
            Log.i(TAG, "self-heal on :$port -> ${r.stdout.trim().ifEmpty { r.stderr.trim() }}")
            r.stdout.contains("HEALED")
        } catch (e: Exception) {
            Log.w(TAG, "self-heal exec on :$port failed: ${e.message}")
            false
        }
    }

    /**
     * Record THIS connection's endpoint-side `sshd-session` PID (`$PPID` of an
     * exec) to `~/.haven-mcp-tunnel.pid` after a successful self-heal forward
     * bind, so a later instance whose bind is blocked by this (now stale)
     * session can find + kill it (see [freeStaleReverseForward]). Best-effort.
     */
    private fun recordTunnelSshPid(client: SshClient) {
        try {
            runBlocking { client.execCommand("echo \"\$PPID\" > \"\$HOME/.haven-mcp-tunnel.pid\" 2>/dev/null") }
        } catch (_: Exception) {
        }
    }

    /**
     * Remove a single port forward from a connected session.
     */
    fun removePortForward(sessionId: String, forward: PortForwardInfo) {
        val session = _sessions.value[sessionId] ?: return
        try {
            when (forward.type) {
                PortForwardType.LOCAL -> session.client.delPortForwardingL(forward.bindAddress, forward.actualBoundPort)
                PortForwardType.REMOTE -> session.client.delPortForwardingR(forward.bindPort)
                PortForwardType.DYNAMIC -> session.client.delPortForwardingDynamic(forward.bindAddress, forward.actualBoundPort)
            }
            Log.d(TAG, "Port forward removed: ${forward.type} ${forward.bindAddress}:${forward.bindPort}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove port forward ${forward.ruleId}: ${e.message}")
        }
        _sessions.update { map ->
            val existing = map[sessionId] ?: return@update map
            map + (sessionId to existing.copy(
                activeForwards = existing.activeForwards.filter { it.ruleId != forward.ruleId },
            ))
        }
    }

    /**
     * Build the list of commands to send after login. Each fires on the next
     * detected shell prompt. By default: session manager first, then post-login
     * command (runs inside the session manager). When [beforeSessionManager] is
     * true, the post-login command runs first (in the raw SSH shell).
     */
    private fun buildPendingCommands(
        sessionId: String,
        manager: SessionManager,
        postLoginCommand: String?,
        beforeSessionManager: Boolean = false,
    ): List<String> = buildList {
        val sessionCmd = buildSessionManagerCommand(sessionId, manager)
        val loginCmd = postLoginCommand?.takeIf { it.isNotBlank() }
        if (beforeSessionManager) {
            loginCmd?.let { add(it) }
            sessionCmd?.let { add(it) }
        } else {
            sessionCmd?.let { add(it) }
            loginCmd?.let { add(it) }
        }
    }

    /**
     * Build the session manager command string for a given session, or null if none.
     * Uses the user-chosen session name if set, otherwise a deterministic name.
     */
    private fun buildSessionManagerCommand(sessionId: String, manager: SessionManager): String? {
        val session = _sessions.value[sessionId]
        // Per-session bypass wins over both the manager template and any
        // sessionCommandOverride — this is the "Plain shell" picker path.
        if (session?.bypassSessionManager == true) return null
        val commandTemplate = manager.command ?: return null
        val rawName = session?.chosenSessionName
            ?: session?.label ?: sessionId.take(8)
        // Sanitize for use as tmux/screen/zellij session name (no spaces or shell metacharacters)
        val sessionName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "-")
        // User override replaces the built-in command template
        val override = session?.sessionCommandOverride
        if (!override.isNullOrBlank()) {
            return override.replace("{name}", sessionName)
        }
        return commandTemplate(sessionName)
    }

    companion object {
        private const val RECONNECT_MAX_ATTEMPTS = 5
        private const val RECONNECT_INITIAL_DELAY_MS = 2_000L
        private const val RECONNECT_MAX_DELAY_MS = 30_000L
    }

    private fun tearDown(session: SessionState) {
        try { session.terminalSession?.close() } catch (e: Exception) {
            Log.e(TAG, "tearDown: terminalSession.close() failed", e)
        }
        try {
            if (session.sftpChannel?.isConnected == true) {
                session.sftpChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: sftpChannel.disconnect() failed", e)
        }
        try {
            if (session.shellChannel?.isConnected == true) {
                session.shellChannel.disconnect()
            }
        } catch (e: Exception) {
            Log.e(TAG, "tearDown: shellChannel.disconnect() failed", e)
        }
        // Only drop the underlying SSH connection when no other live session
        // still rides it. Connection reuse (a 2nd terminal on an already-
        // connected profile) shares one SshClient across several shell
        // channels; tearing the client down here would kill the sibling
        // session. removeSession() has already pulled this session from the
        // map, so this checks the *remaining* sessions.
        val clientStillInUse = _sessions.value.values.any { it.client === session.client }
        if (clientStillInUse) {
            Log.d(TAG, "tearDown: keeping shared SSH client alive for ${session.sessionId}'s sibling session(s)")
        } else {
            try { session.client.disconnect() } catch (e: Exception) {
                Log.e(TAG, "tearDown: client.disconnect() failed", e)
            }
        }
        // Zero out auth material so it doesn't linger in heap
        when (val auth = session.connectionConfig?.authMethod) {
            is ConnectionConfig.AuthMethod.Password -> auth.clear()
            is ConnectionConfig.AuthMethod.PrivateKey -> {
                auth.keyBytes.fill(0)
                auth.clear()
            }
            else -> {}
        }
    }
}
