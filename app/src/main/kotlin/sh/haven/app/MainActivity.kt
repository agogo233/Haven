package sh.haven.app

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import sh.haven.app.agent.AppWindowConnectionStore
import sh.haven.app.agent.AppWindowVncController
import sh.haven.app.agent.PipController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import sh.haven.app.agent.ConsentHost
import sh.haven.core.data.agent.AgentConsentManager
import sh.haven.app.navigation.HavenNavHost
import sh.haven.core.data.preferences.UserPreferencesRepository
import sh.haven.core.data.repository.ConnectionRepository
import sh.haven.core.fido.FidoAuthenticator
import sh.haven.core.security.BiometricAuthenticator
import sh.haven.core.ssh.SshConnectionService
import sh.haven.core.ui.KeyEventInterceptor
import sh.haven.core.ui.theme.HavenTheme
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var preferencesRepository: UserPreferencesRepository
    @Inject lateinit var biometricAuthenticator: BiometricAuthenticator
    // Eagerly injected to trigger one-time password encryption migration
    @Inject lateinit var connectionRepository: ConnectionRepository
    @Inject lateinit var sshKeyRepository: sh.haven.core.data.repository.SshKeyRepository
    @Inject lateinit var stepCaConfigRepository: sh.haven.core.data.repository.StepCaConfigRepository
    // Published to FidoAuthenticator in onResume so NFC reader mode can
    // be enabled during FIDO2 SSH assertions. Without this, Nitrokey /
    // SoloKey / YubiKey-over-NFC flows never saw a Tag (#15).
    @Inject lateinit var fidoAuthenticator: FidoAuthenticator
    // Tracks foreground state so AgentConsentManager can fail-closed
    // when there's no activity to render the prompt. The §85 rule
    // forbids letting destructive agent calls slip through silently.
    @Inject lateinit var agentConsentManager: AgentConsentManager
    // Cross-tab navigation verbs: HavenNavHost collects from this so an
    // MCP `navigate_sftp_browser` switches the pager to the right tab.
    @Inject lateinit var agentUiCommandBus: sh.haven.core.data.agent.AgentUiCommandBus
    // Cert renewal request bus: notifications fire haven://renew-cert/<id>
    // intents which land here and we re-publish via the existing
    // AgentUiCommandBus so HavenNavHost flips to Keys and KeysViewModel
    // picks up the regenerate request. (#133 phase 2b)
    // Keystore biometric gate: a fetch on a BIOMETRIC_PROTECTED entry
    // queues a request here, this Activity collects, runs
    // BiometricPrompt, and posts the decision back. Foreground tracking
    // mirrors AgentConsentManager (fail-closed when backgrounded).
    @Inject lateinit var biometricGate: sh.haven.core.data.keystore.BiometricGate
    // Workspace launcher (singleton) — invoked when a launcher
    // long-press shortcut routes the workspace id through onCreate /
    // onNewIntent.
    @Inject lateinit var workspaceLauncher: sh.haven.app.workspace.WorkspaceLauncher
    // Picture-in-Picture for app windows: PipController bridges PiP state to
    // the composition; the store owns the live VNC connection so it survives
    // the overlay→PiP→overlay round-trip.
    @Inject lateinit var pipController: PipController
    @Inject lateinit var appWindowConnectionStore: AppWindowConnectionStore

    private fun exitIfDisconnected() {
        if (SshConnectionService.disconnectedAll) {
            Log.d("MainActivity", "Disconnect All detected — exiting")
            SshConnectionService.clearDisconnectedAll()
            finishAndRemoveTask()
        }
    }

    override fun onResume() {
        super.onResume()
        exitIfDisconnected()
        fidoAuthenticator.setActiveActivity(this)
        agentConsentManager.setForegroundActive(true)
        biometricGate.setForegroundActive(true)
    }

    override fun onPause() {
        fidoAuthenticator.clearActiveActivity(this)
        agentConsentManager.setForegroundActive(false)
        biometricGate.setForegroundActive(false)
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        exitIfDisconnected()
        handleWorkspaceShortcut(intent)
        handleRenewCertDeepLink(intent)
    }

    // --- Picture-in-Picture (app windows) ---

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pipController.setInPip(isInPictureInPictureMode)
    }

    /**
     * API 26–30 auto-enter fallback: those releases lack
     * `setAutoEnterEnabled`, so enter PiP explicitly when the user leaves
     * while an app window is open. On API 31+ the armed params auto-enter.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            pipController.activeAppWindow.value != null
        ) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    /** Explicit PiP from the overlay's PiP button. */
    fun enterPipForAppWindow() {
        if (pipController.activeAppWindow.value == null) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(currentAppWindowAspect())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Arm/disarm auto-enter based on whether an app window is open.
            builder.setAutoEnterEnabled(pipController.activeAppWindow.value != null)
        }
        return builder.build()
    }

    /** Aspect ratio from the current app-window frame, clamped to Android's
     *  allowed PiP range; falls back to 16:9. */
    private fun currentAppWindowAspect(): Rational {
        val media = pipController.activeAppWindow.value
        val frame = media?.sessionId?.let { sid ->
            val h = media.host
            val p = media.port
            if (h != null && p != null) {
                appWindowConnectionStore.controllerFor(sid, h, p).frame.value
            } else null
        }
        if (frame == null || frame.width <= 0 || frame.height <= 0) return Rational(16, 9)
        val ratio = frame.width.toFloat() / frame.height.toFloat()
        // Android rejects aspect ratios outside roughly [1:2.39, 2.39:1].
        return if (ratio in 0.42f..2.39f) Rational(frame.width, frame.height) else Rational(16, 9)
    }

    /**
     * Parse `haven://renew-cert/<keyId>` deep links posted by
     * [sh.haven.core.stepca.RenewalNotifier] and re-publish onto the
     * existing UI command bus. The notifier never imports the bus
     * directly — that would couple core/stepca to core/data — so the
     * Activity bridges the two.
     */
    private fun handleRenewCertDeepLink(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "haven" || data.host != "renew-cert") return
        val keyId = data.lastPathSegment ?: return
        Log.d("MainActivity", "renew-cert deep link for key $keyId")
        agentUiCommandBus.emit(
            sh.haven.core.data.agent.AgentUiCommand.RegenerateStepCaCert(keyId),
        )
        intent.data = null
    }

    /**
     * If [intent] carries a workspace launch action (long-press shortcut
     * tap, or a `compose_workspace` MCP call routed through here in a
     * future patch), kick off the launcher. Idempotent — extras are
     * cleared once consumed so a configuration change doesn't replay.
     */
    private fun handleWorkspaceShortcut(intent: Intent?) {
        if (intent?.action != sh.haven.app.workspace.WorkspaceShortcutManager.ACTION_LAUNCH_WORKSPACE) return
        val workspaceId = intent.getStringExtra(
            sh.haven.app.workspace.WorkspaceShortcutManager.EXTRA_WORKSPACE_ID,
        ) ?: return
        intent.removeExtra(sh.haven.app.workspace.WorkspaceShortcutManager.EXTRA_WORKSPACE_ID)
        Log.d("MainActivity", "launching workspace $workspaceId from shortcut")
        MainScope().launch {
            workspaceLauncher.launch(workspaceId)
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        KeyEventInterceptor.handler?.let { interceptor ->
            if (interceptor(event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleWorkspaceShortcut(intent)
        handleRenewCertDeepLink(intent)
        setContent {
            // Prevent screenshots/screen recording when enabled
            val screenSecurity by preferencesRepository.screenSecurity
                .collectAsState(initial = false)
            LaunchedEffect(screenSecurity) {
                if (screenSecurity) {
                    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                }
            }

            val themeMode by preferencesRepository.theme
                .collectAsState(initial = UserPreferencesRepository.ThemeMode.SYSTEM)
            // Mirror the user's choice to AppCompatDelegate so the next
            // cold-launch splash window matches without waiting for the
            // Application to re-read DataStore (#153 follow-up).
            LaunchedEffect(themeMode) {
                AppCompatDelegate.setDefaultNightMode(themeMode.toNightMode())
            }
            val darkTheme = when (themeMode) {
                UserPreferencesRepository.ThemeMode.LIGHT -> false
                UserPreferencesRepository.ThemeMode.DARK -> true
                UserPreferencesRepository.ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            HavenTheme(darkTheme = darkTheme) {
                val biometricEnabled by preferencesRepository.biometricEnabled
                    .collectAsState(initial = false)
                val lockTimeout by preferencesRepository.lockTimeout
                    .collectAsState(initial = sh.haven.core.data.preferences.UserPreferencesRepository.LockTimeout.IMMEDIATE)
                var unlocked by remember { mutableStateOf(false) }
                var backgroundedAt by remember { mutableStateOf(0L) }

                // Re-lock when app goes to background, respecting timeout.
                // Minimum 5s grace period so file pickers, permission dialogs,
                // and other brief system activities don't trigger re-lock.
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        // Don't arm the re-lock when we stop because we entered
                        // PiP — the app window is still floating, and re-locking
                        // would slam the lock screen over it on expand.
                        if (event == Lifecycle.Event.ON_STOP && !pipController.isInPip.value) {
                            backgroundedAt = System.currentTimeMillis()
                        }
                        if (event == Lifecycle.Event.ON_START && unlocked && backgroundedAt > 0) {
                            val elapsed = (System.currentTimeMillis() - backgroundedAt) / 1000
                            val effectiveTimeout = maxOf(lockTimeout.seconds, 5L)
                            if (elapsed >= effectiveTimeout) {
                                unlocked = false
                            }
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                val inPip by pipController.isInPip.collectAsState()
                val activeAppWindow by pipController.activeAppWindow.collectAsState()
                // Keep PiP params current so API 31+ auto-enters PiP when an
                // app window is open, and disarms when it closes.
                LaunchedEffect(activeAppWindow?.id) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { setPictureInPictureParams(buildPipParams()) }
                    }
                }

                val pipWin = activeAppWindow
                if (inPip && pipWin?.sessionId != null && pipWin.host != null && pipWin.port != null) {
                    // In PiP: render just the live frame, full-bleed. PiP is
                    // view-only on Android — interaction resumes on expand.
                    PipAppWindow(
                        appWindowConnectionStore.controllerFor(
                            pipWin.sessionId!!, pipWin.host!!, pipWin.port!!,
                        ),
                    )
                } else if (biometricEnabled && !unlocked) {
                    BiometricLockScreen(
                        authenticator = biometricAuthenticator,
                        onUnlocked = { unlocked = true },
                    )
                } else {
                    HavenNavHost(
                        preferencesRepository = preferencesRepository,
                        connectionRepository = connectionRepository,
                        sshKeyRepository = sshKeyRepository,
                        stepCaConfigRepository = stepCaConfigRepository,
                        agentUiCommandBus = agentUiCommandBus,
                    )
                    // Floats above whatever screen is active so an
                    // agent's consent prompt is unmissable. No-op when
                    // there are no pending requests.
                    ConsentHost()
                    // Same top-of-tree pattern: an agent-pushed image or
                    // sound (present_media) floats over the active screen.
                    sh.haven.app.agent.PresentationHost()
                    // Same pattern for BIOMETRIC_PROTECTED keystore
                    // fetches — the gate publishes; this host renders
                    // BiometricPrompt; the result resumes the
                    // suspending fetch caller.
                    sh.haven.app.agent.BiometricGateHost()
                }
            }
        }
    }
}

/**
 * The minimal Picture-in-Picture view: the app window's live frame,
 * full-bleed on black. PiP content is view-only on Android (taps expand the
 * window), so no input wiring — interaction resumes in the expanded overlay.
 */
@Composable
private fun PipAppWindow(controller: AppWindowVncController) {
    val frame by controller.frame.collectAsState()
    val bmp = frame
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}
