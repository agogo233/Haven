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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sh.haven.core.data.agent.PresentedMediaKind
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
    // App-global user-facing messages (e.g. shell-closed connect errors);
    // HavenNavHost shows these in a root snackbar over every screen (#215 follow-up).
    @Inject lateinit var userMessageBus: sh.haven.core.data.message.UserMessageBus
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
     * Only an APP_WINDOW auto-enters — an image/web sheet floats only via the
     * explicit PiP button (auto-PiP'ing a static image on Home is surprising).
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S &&
            pipController.activePipMedia.value?.kind == PresentedMediaKind.APP_WINDOW
        ) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
    }

    /** Explicit PiP from a present_media / present_web / app-window overlay button. */
    fun enterPipForMedia() {
        if (pipController.activePipMedia.value == null) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    private fun buildPipParams(): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(currentPipAspect())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Only a live app window auto-enters PiP on Home; image/web are
            // explicit-tap only.
            builder.setAutoEnterEnabled(
                pipController.activePipMedia.value?.kind == PresentedMediaKind.APP_WINDOW,
            )
        }
        return builder.build()
    }

    /** Aspect ratio for the current PiP item — VNC frame for an app window,
     *  intrinsic size for an image / PDF, 16:9 for a WebView — clamped to
     *  Android's allowed PiP range, with a 16:9 fallback. (#225) */
    private fun currentPipAspect(): Rational {
        val media = pipController.activePipMedia.value ?: return Rational(16, 9)
        val dims: Pair<Int, Int>? = when (media.kind) {
            PresentedMediaKind.APP_WINDOW -> {
                val sid = media.sessionId
                val h = media.host
                val p = media.port
                if (sid != null && h != null && p != null) {
                    appWindowConnectionStore.controllerFor(sid, h, p).frame.value
                        ?.let { it.width to it.height }
                } else null
            }
            PresentedMediaKind.IMAGE -> media.filePath?.let { imageDims(it) }
            PresentedMediaKind.WEB ->
                if (media.mimeType == "application/pdf") media.filePath?.let { pdfFirstPageDims(it) }
                else null // HTML/SVG WebView has no intrinsic size → 16:9
            else -> null
        }
        val (w, h) = dims ?: return Rational(16, 9)
        if (w <= 0 || h <= 0) return Rational(16, 9)
        val ratio = w.toFloat() / h.toFloat()
        // Android rejects aspect ratios outside roughly [1:2.39, 2.39:1].
        return if (ratio in 0.42f..2.39f) Rational(w, h) else Rational(16, 9)
    }

    /** Width/height of an image file via a bounds-only decode (no pixels). */
    private fun imageDims(path: String): Pair<Int, Int>? = runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    }.getOrNull()

    /** Width/height of a PDF's first page. */
    private fun pdfFirstPageDims(path: String): Pair<Int, Int>? = runCatching {
        android.os.ParcelFileDescriptor.open(
            java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
        ).use { pfd ->
            android.graphics.pdf.PdfRenderer(pfd).use { r ->
                if (r.pageCount <= 0) null
                else r.openPage(0).use { pg -> pg.width to pg.height }
            }
        }
    }.getOrNull()

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
                val activePipMedia by pipController.activePipMedia.collectAsState()
                // Keep PiP params current so API 31+ auto-enters PiP when an
                // app window is open, and disarms when it closes.
                LaunchedEffect(activePipMedia?.id) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        runCatching { setPictureInPictureParams(buildPipParams()) }
                    }
                }

                val pipWin = activePipMedia
                if (inPip && pipWin != null) {
                    // In PiP: render just the item, full-bleed on black. PiP is
                    // view-only on Android — interaction resumes on expand. (#225)
                    when (pipWin.kind) {
                        PresentedMediaKind.APP_WINDOW -> {
                            if (pipWin.sessionId != null && pipWin.host != null && pipWin.port != null) {
                                PipAppWindow(
                                    appWindowConnectionStore.controllerFor(
                                        pipWin.sessionId!!, pipWin.host!!, pipWin.port!!,
                                    ),
                                )
                            }
                        }
                        PresentedMediaKind.IMAGE -> pipWin.filePath?.let { PipImage(it) }
                        PresentedMediaKind.WEB ->
                            if (pipWin.mimeType == "application/pdf") {
                                pipWin.filePath?.let { PipPdfFirstPage(it) }
                            } else {
                                pipWin.url?.let { PipWebView(it) }
                            }
                        else -> {}
                    }
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
                        userMessageBus = userMessageBus,
                    )
                    // Floats above whatever screen is active so an
                    // agent's consent prompt is unmissable. No-op when
                    // there are no pending requests.
                    ConsentHost()
                    // Same top-of-tree pattern: an agent-pushed image or
                    // sound (present_media) floats over the active screen.
                    sh.haven.app.agent.PresentationHost()
                    // Backgrounded app windows dock here as draggable edge
                    // icons; tap restores one to the PresentationHost overlay.
                    sh.haven.app.agent.EdgeIconDock()
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
    PipBitmap(frame?.asImageBitmap())
}

/** Full-bleed bitmap on black — the shared body for the image and PDF PiP views. */
@Composable
private fun PipBitmap(bitmap: ImageBitmap?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** PiP for a present_media image: the decoded file, full-bleed. (#225) */
@Composable
private fun PipImage(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching { android.graphics.BitmapFactory.decodeFile(path)?.asImageBitmap() }
                .getOrNull()
        }
    }
    PipBitmap(bitmap)
}

/** PiP for a present_web PDF: the first page rasterised, full-bleed. (#225) */
@Composable
private fun PipPdfFirstPage(path: String) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                android.os.ParcelFileDescriptor.open(
                    java.io.File(path), android.os.ParcelFileDescriptor.MODE_READ_ONLY,
                ).use { pfd ->
                    android.graphics.pdf.PdfRenderer(pfd).use { r ->
                        if (r.pageCount <= 0) return@runCatching null
                        r.openPage(0).use { page ->
                            val w = 1080
                            val h = (w.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                            val bmp = android.graphics.Bitmap.createBitmap(
                                w, h, android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            bmp.eraseColor(android.graphics.Color.WHITE)
                            page.render(
                                bmp, null, null,
                                android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                            )
                            bmp.asImageBitmap()
                        }
                    }
                }
            }.getOrNull()
        }
    }
    PipBitmap(bitmap)
}

/** PiP for a present_web HTML/SVG page: a live WebView loading the loopback
 *  URL. Renders live; view-only in PiP (no touch until the window expands). (#225) */
@Composable
private fun PipWebView(url: String) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                android.webkit.WebView(ctx).apply {
                    webViewClient = android.webkit.WebViewClient()
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    loadUrl(url)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
