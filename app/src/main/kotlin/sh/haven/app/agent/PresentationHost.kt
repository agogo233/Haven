package sh.haven.app.agent

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.media.MediaPlayer
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
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
import sh.haven.core.data.agent.AgentPresentationManager
import sh.haven.core.data.agent.PresentedMedia
import sh.haven.core.data.agent.PresentedMediaKind
import sh.haven.core.local.DesktopManager
import sh.haven.feature.vnc.VncSessionContent
import java.io.File
import javax.inject.Inject

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

    /** Tell the PiP layer which app window (if any) is currently on screen. */
    fun setActiveAppWindow(media: PresentedMedia?) = pipController.setActiveAppWindow(media)

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
            pipController.setActiveAppWindow(null)
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
    val upstream = pending.firstOrNull()

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
        // Tell the PiP layer which app window is on screen (for auto-enter +
        // the floating view). Only an APP_WINDOW is PiP-eligible; cleared
        // when nothing is shown. NOT cleared on dispose — an overlay→PiP
        // transition disposes this host but the window must stay PiP-active.
        viewModel.setActiveAppWindow(u?.takeIf { it.kind == PresentedMediaKind.APP_WINDOW })
    }
    val current = displayed ?: return

    ModalBottomSheet(
        onDismissRequest = { viewModel.dismiss(current) },
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
                PresentedMediaKind.APP_WINDOW -> {
                    val controller = viewModel.controllerFor(current)
                    if (controller != null) {
                        AppWindowContent(controller, onDismiss = { viewModel.dismiss(current) })
                    } else {
                        Text("App window is missing its connection details.")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
            ) {
                if (current.kind == PresentedMediaKind.APP_WINDOW) {
                    val context = LocalContext.current
                    OutlinedButton(onClick = {
                        (context.findActivity() as? MainActivity)?.enterPipForAppWindow()
                    }) {
                        Text("PiP")
                    }
                }
                Button(onClick = { viewModel.dismiss(current) }) {
                    Text("Dismiss")
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
        Image(
            bitmap = bmp,
            contentDescription = media.caption ?: "Image shared by agent",
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp),
            // FillWidth scales the bitmap up/down to the card width (height
            // follows aspect ratio, clamped by heightIn) so a small image
            // is shown prominently rather than as a tiny centred dot, while
            // a large screenshot is fit to width. Tall images crop centred.
            contentScale = ContentScale.FillWidth,
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
            Text(if (playing) "Pause" else "Play")
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = if (ready) File(path).name else "Preparing audio…",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun AppWindowContent(controller: AppWindowVncController, onDismiss: () -> Unit) {
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
            onTypeChar = { c -> controller.typeText(c.toString()) },
            onTypeText = { s -> controller.typeText(s) },
            onKeyDown = { sym -> controller.key(sym, true) },
            onKeyUp = { sym -> controller.key(sym, false) },
            onDisconnect = onDismiss,
            // App windows expect ordinary 2-finger pinch-to-zoom (the desktop
            // viewer reserves 2 fingers for remote scroll, zoom on 3).
            twoFingerZoom = true,
        )
    }
}
