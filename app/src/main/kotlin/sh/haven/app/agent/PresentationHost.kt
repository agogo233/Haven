package sh.haven.app.agent

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.view.MotionEvent
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import sh.haven.app.MainActivity
import sh.haven.app.R
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.agent.PresentedMedia
import sh.haven.core.data.agent.PresentedMediaKind
import sh.haven.core.local.DesktopManager
import sh.haven.feature.vnc.VncSessionContent
import java.io.File
import javax.inject.Inject

/** Max PDF pages [PdfContent] rasterises (bounds memory for a chatty agent). */
private const val MAX_PDF_PAGES = 20

/**
 * Thin Hilt + Compose wrapper around the app-scoped
 * [AgentPresentationManager], mirroring [ConsentHostViewModel].
 */
@HiltViewModel
internal class PresentationHostViewModel @Inject constructor(
    private val manager: AgentPresentationManager,
    private val desktopManager: DesktopManager,
    private val connectionStore: AppWindowConnectionStore,
    private val pipController: PipController,
) : ViewModel() {
    val pending: StateFlow<List<PresentedMedia>> = manager.pending
    val minimizedIds: StateFlow<Set<Long>> = manager.minimizedIds

    /** Background an app window to an edge icon (keeps the cage + VNC alive). */
    fun minimize(id: Long) = manager.minimize(id)

    /** Restore a backgrounded app window to the full overlay. */
    fun restore(id: Long) = manager.restore(id)

    /**
     * The live VNC controller for an app window, owned by the store (so it
     * survives the overlay→PiP→overlay transition). Null if the media lacks
     * connection details.
     */
    fun controllerFor(media: PresentedMedia): AppWindowVncController? {
        val host = media.host ?: return null
        val port = media.port ?: return null
        val sid = media.sessionId ?: return null
        return connectionStore.controllerFor(sid, host, port)
    }

    /** Tell the PiP layer which presented item (if any) is currently on screen. */
    fun setActivePipMedia(media: PresentedMedia?) = pipController.setActivePipMedia(media)

    /**
     * Dismiss a presented item. Removes it from the queue immediately (UI
     * stays responsive); for an APP_WINDOW it also clears the PiP active
     * window, releases the VNC connection from the store, and stops the
     * backing cage-kiosk session off-thread. core:data's manager can't do
     * the last two — it doesn't depend on core:local / core:vnc — so the
     * teardown lives here.
     */
    fun dismiss(media: PresentedMedia) {
        manager.dismiss(media.id)
        if (media.kind == PresentedMediaKind.APP_WINDOW) {
            pipController.setActivePipMedia(null)
            media.sessionId?.let { sid ->
                connectionStore.release(sid)
                viewModelScope.launch(Dispatchers.IO) { desktopManager.stopAppWindow(sid) }
            }
        }
    }
}

/**
 * Top-of-tree host for agent-pushed media. Mounted from
 * `MainActivity.setContent { ... }` next to [ConsentHost] so an image or
 * sound the agent shares floats above whatever screen is active.
 *
 * Renders the **oldest** pending [PresentedMedia]; when the user dismisses
 * it the next (if any) slides in. Unlike the consent sheet this is freely
 * dismissible — showing an image isn't a gate, so a tap-outside or swipe
 * is a perfectly good "I'm done looking".
 *
 * The displayed-snapshot indirection is the same defence ConsentHost uses:
 * tearing a ModalBottomSheet out of composition the instant the manager
 * clears the item leaves a stuck full-screen scrim. We instead animate the
 * sheet out via `sheetState.hide()` and drop the snapshot only once it's
 * gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PresentationHost(viewModel: PresentationHostViewModel = hiltViewModel()) {
    val pending by viewModel.pending.collectAsStateWithLifecycle()
    val minimized by viewModel.minimizedIds.collectAsStateWithLifecycle()
    // Render the focused item: the oldest pending one that isn't backgrounded.
    // Minimized app windows stay live (edge icons) but are skipped here.
    val upstream = pending.firstOrNull { it.id !in minimized }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    var displayed by remember { mutableStateOf<PresentedMedia?>(null) }
    LaunchedEffect(upstream?.id) {
        val u = upstream
        if (u != null) {
            displayed = u
            if (!sheetState.isVisible) sheetState.show()
        } else if (displayed != null) {
            runCatching { sheetState.hide() }
            displayed = null
        }
        // Tell the PiP layer which item is on screen (for the floating view, and
        // APP_WINDOW auto-enter). Image / web / app-window are PiP-eligible;
        // AUDIO has no visual surface so it is excluded. Cleared when nothing is
        // shown. NOT cleared on dispose — an overlay→PiP transition disposes this
        // host but the item must stay PiP-active. (#225)
        viewModel.setActivePipMedia(u?.takeIf { it.kind != PresentedMediaKind.AUDIO })
    }
    val current = displayed ?: return

    ModalBottomSheet(
        // Tap-outside / swipe-away: for a running app window this *backgrounds*
        // it (keeps the cage + VNC alive, docks an edge icon) rather than
        // tearing it down — only the explicit Dismiss button / edge-icon ✕
        // kill the cage. Images/audio have nothing to keep alive, so they
        // dismiss as before.
        onDismissRequest = {
            if (current.kind == PresentedMediaKind.APP_WINDOW) {
                viewModel.minimize(current.id)
            } else {
                viewModel.dismiss(current)
            }
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 8.dp),
        ) {
            current.caption?.let { cap ->
                Text(text = cap, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(12.dp))
            }

            when (current.kind) {
                PresentedMediaKind.IMAGE -> ImageContent(current)
                PresentedMediaKind.AUDIO -> AudioContent(current)
                PresentedMediaKind.WEB -> WebContent(current)
                PresentedMediaKind.APP_WINDOW -> {
                    val controller = viewModel.controllerFor(current)
                    if (controller != null) {
                        val context = LocalContext.current
                        AppWindowContent(
                            controller = controller,
                            // Close (in the viewer's own toolbar) tears the cage
                            // down; minimize backgrounds it to an edge icon
                            // (keeps it alive); PiP enters system PiP. All three
                            // live in the viewer's existing control row — no
                            // second button row here.
                            onDismiss = { viewModel.dismiss(current) },
                            onMinimize = { viewModel.minimize(current.id) },
                            onPictureInPicture = {
                                viewModel.setActivePipMedia(current)
                                (context.findActivity() as? MainActivity)?.enterPipForMedia()
                            },
                        )
                    } else {
                        Text(stringResource(R.string.app_present_app_missing_details))
                    }
                }
            }

            // Image/audio need a Dismiss button; an app window carries its own
            // control row (close / minimize / PiP / keyboard / fullscreen)
            // inside the VNC viewer, so it doesn't add a second row here.
            if (current.kind != PresentedMediaKind.APP_WINDOW) {
                Spacer(Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    // Picture-in-picture floats the item over *other* apps (unlike
                    // minimize, which only docks within Haven). Images and web
                    // pages/PDFs are visual; audio has nothing to float. (#225)
                    if (current.kind == PresentedMediaKind.IMAGE ||
                        current.kind == PresentedMediaKind.WEB
                    ) {
                        val context = LocalContext.current
                        OutlinedButton(onClick = {
                            // Pin the active PiP item to what's on screen *now* — the
                            // LaunchedEffect that normally tracks it is fragile across
                            // dismiss/re-present and PiP enter/exit cycles, so set it
                            // synchronously here so enterPipForMedia never sees null. (#225)
                            viewModel.setActivePipMedia(current)
                            (context.findActivity() as? MainActivity)?.enterPipForMedia()
                        }) {
                            Text(stringResource(R.string.app_present_pip))
                        }
                    }
                    // Minimize parks it as an edge icon (kept until dismissed) so
                    // the user can glance back at an image/page while they work;
                    // Dismiss removes it (and deletes its cache file).
                    OutlinedButton(onClick = { viewModel.minimize(current.id) }) {
                        Text(stringResource(R.string.app_present_minimize))
                    }
                    // Compact X — the "Dismiss" label wrapped in the 3-button row.
                    FilledIconButton(onClick = { viewModel.dismiss(current) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.app_present_dismiss),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ImageContent(media: PresentedMedia) {
    val path = media.filePath ?: return
    // Decode off the main thread; null until ready / on failure.
    val bitmap by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }
                .getOrNull()
        }
    }
    val bmp = bitmap
    if (bmp != null) {
        // Pinch-zoom + pan. Pan is enabled only past 1× and bounded to the
        // scaled overflow so the image can't be flung out of its card; a
        // double-tap toggles 1×↔2×. detectTransformGestures consumes the
        // drags, so a zoomed image pans cleanly rather than fighting the
        // bottom sheet's swipe-to-dismiss (dismiss stays on the X / tap-out).
        var scale by remember(media.id) { mutableStateOf(1f) }
        var offset by remember(media.id) { mutableStateOf(Offset.Zero) }
        Image(
            bitmap = bmp,
            contentDescription = media.caption ?: stringResource(R.string.app_present_image_shared_cd),
            // FillWidth scales the bitmap up/down to the card width (height
            // follows aspect ratio, clamped by heightIn) so a small image
            // is shown prominently rather than as a tiny centred dot, while
            // a large screenshot is fit to width. Tall images crop centred.
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
                .clipToBounds()
                .pointerInput(media.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val newScale = (scale * zoom).coerceIn(1f, 5f)
                        offset = if (newScale > 1f) {
                            val maxX = size.width * (newScale - 1f) / 2f
                            val maxY = size.height * (newScale - 1f) / 2f
                            Offset(
                                (offset.x + pan.x).coerceIn(-maxX, maxX),
                                (offset.y + pan.y).coerceIn(-maxY, maxY),
                            )
                        } else {
                            Offset.Zero
                        }
                        scale = newScale
                    }
                }
                .pointerInput(media.id) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f
                                offset = Offset.Zero
                            } else {
                                scale = 2f
                            }
                        },
                    )
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            // No bitmap yet means either still decoding or undecodable. We
            // can't tell them apart here without extra state; a spinner is
            // the honest default and resolves to the image when it lands.
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun AudioContent(media: PresentedMedia) {
    val path = media.filePath ?: return
    var playing by remember(media.id) { mutableStateOf(false) }
    var ready by remember(media.id) { mutableStateOf(false) }

    val player = remember(media.id) {
        MediaPlayer().apply {
            runCatching {
                setDataSource(path)
                setOnPreparedListener {
                    ready = true
                    if (media.autoPlay) {
                        it.start()
                        playing = true
                    }
                }
                setOnCompletionListener { playing = false }
                prepareAsync()
            }
        }
    }
    DisposableEffect(player) {
        onDispose { runCatching { player.release() } }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            enabled = ready,
            onClick = {
                if (playing) {
                    runCatching { player.pause() }
                    playing = false
                } else {
                    runCatching { player.start() }
                    playing = true
                }
            },
        ) {
            Text(if (playing) stringResource(R.string.app_present_pause) else stringResource(R.string.app_present_play))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (ready) File(path).name else stringResource(R.string.app_present_preparing_audio),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Web content: HTML/SVG loaded from a loopback-served [PresentedMedia.url] in
 * an in-app WebView (cleartext to 127.0.0.1 is permitted by the network
 * security config), or a downloaded PDF ([PresentedMedia.filePath]) paged via
 * [PdfContent]. The rung between a static image and a live app window.
 */
@SuppressLint("ClickableViewAccessibility") // onTouch only toggles parent intercept; WebView keeps its own click/scroll handling
@Composable
private fun WebContent(media: PresentedMedia) {
    val pdfPath = media.filePath
    if (media.mimeType == "application/pdf" && pdfPath != null) {
        PdfContent(pdfPath)
        return
    }
    val url = media.url ?: return
    // key() so a new URL (a different presented item reusing this node) forces
    // a fresh WebView + load rather than keeping the old page.
    key(url) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    webViewClient = WebViewClient()
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // Keep the bottom sheet from stealing pan/zoom drags inside
                    // the page: claim the gesture from the parent on touch-down,
                    // release it on up/cancel. Without this the sheet intercepts
                    // vertical drags and panning a zoomed page feels broken.
                    setOnTouchListener { v, event ->
                        when (event.actionMasked) {
                            MotionEvent.ACTION_DOWN,
                            MotionEvent.ACTION_POINTER_DOWN ->
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            MotionEvent.ACTION_UP,
                            MotionEvent.ACTION_CANCEL ->
                                v.parent?.requestDisallowInterceptTouchEvent(false)
                        }
                        false // never consume — WebView handles scroll/zoom/links
                    }
                    loadUrl(url)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(480.dp),
        )
    }
}

/**
 * Render a downloaded PDF to a vertical run of page bitmaps via
 * [android.graphics.pdf.PdfRenderer] (which needs a seekable local fd, hence
 * the cache download rather than a served URL). Mirrors [ImageContent]'s
 * "spinner until ready / on failure" honesty — null or empty both show the
 * spinner. Capped at [MAX_PDF_PAGES].
 */
@Composable
private fun PdfContent(path: String) {
    val pages by produceState<List<ImageBitmap>?>(initialValue = null, path) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                val pfd = ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
                try {
                    PdfRenderer(pfd).use { renderer ->
                        val count = renderer.pageCount.coerceAtMost(MAX_PDF_PAGES)
                        (0 until count).map { i ->
                            renderer.openPage(i).use { page ->
                                val w = 1080
                                val h = (w.toFloat() * page.height / page.width)
                                    .toInt().coerceAtLeast(1)
                                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                bmp.eraseColor(android.graphics.Color.WHITE)
                                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                bmp.asImageBitmap()
                            }
                        }
                    }
                } finally {
                    runCatching { pfd.close() }
                }
            }.getOrNull()
        }
    }
    val list = pages
    if (list.isNullOrEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            list.forEach { pg ->
                Image(
                    bitmap = pg,
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }
    }
}

/** Unwrap a Compose [Context] to its hosting [Activity], or null. */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * A live single-app window: embeds the reusable [VncSessionContent] (which
 * already does pinch-zoom / pan / drag / fullscreen) bound to a
 * store-owned [AppWindowVncController] connected to the cage-kiosk wayvnc.
 * The controller lifecycle is the PresentedMedia (via [AppWindowConnectionStore]),
 * not this composable — so it survives an overlay→PiP→overlay round-trip.
 * Constrained to a fixed height in the sheet; the viewer's own fullscreen
 * toggle promotes it. [onDismiss] tears the window down.
 */
@Composable
private fun AppWindowContent(
    controller: AppWindowVncController,
    onDismiss: () -> Unit,
    onMinimize: () -> Unit,
    onPictureInPicture: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
    ) {
        VncSessionContent(
            connected = controller.connected,
            frame = controller.frame,
            error = controller.error,
            onTap = { x, y -> controller.click(x, y, 1) },
            onLongPress = { x, y -> controller.click(x, y, 3) },
            onDragStart = { x, y -> controller.dragStart(x, y) },
            onDrag = { x, y -> controller.drag(x, y) },
            onDragEnd = { controller.dragEnd() },
            onScrollUp = { controller.scroll(true) },
            onScrollDown = { controller.scroll(false) },
            onPressButton = { btn -> controller.pressButton(btn) },
            onReleaseButton = { btn -> controller.releaseButton(btn) },
            onTypeChar = { c -> controller.typeText(c.toString()) },
            onTypeText = { s -> controller.typeText(s) },
            onKeyDown = { sym -> controller.key(sym, true) },
            onKeyUp = { sym -> controller.key(sym, false) },
            onDisconnect = onDismiss,
            // The viewer's own toolbar gains a minimize + PiP button for app
            // windows (null for full desktops, which don't show them).
            onMinimize = onMinimize,
            onPictureInPicture = onPictureInPicture,
            // App windows expect ordinary 2-finger pinch-to-zoom (the desktop
            // viewer reserves 2 fingers for remote scroll, zoom on 3).
            twoFingerZoom = true,
        )
    }
}
