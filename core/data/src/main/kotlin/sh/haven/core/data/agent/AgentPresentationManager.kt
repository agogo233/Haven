package sh.haven.core.data.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** What kind of media the agent wants the user to perceive. */
enum class PresentedMediaKind {
    IMAGE,
    AUDIO,
    /**
     * A live, interactive single-app window: a GUI app running under a cage
     * kiosk in the guest, reached over VNC at [PresentedMedia.host]:[port].
     * The overlay embeds the VNC viewer rather than decoding a file.
     */
    APP_WINDOW,

    /**
     * HTML / SVG / PDF shown inline. HTML and SVG load from a loopback-served
     * URL ([PresentedMedia.url]) in an in-app WebView; a PDF is downloaded to
     * a cache file ([PresentedMedia.filePath]) and paged via PdfRenderer
     * (which needs a local fd). The rung between a static image ([IMAGE]) and
     * a live app ([APP_WINDOW]).
     */
    WEB,
}

/**
 * One thing an agent has pushed for the user to look at / listen to /
 * interact with. For IMAGE/AUDIO the bytes live in a cache file
 * ([filePath]) — cheap to keep in the StateFlow and what the image decoder
 * / audio player want. For APP_WINDOW there is no file: it carries the VNC
 * [host]/[port]/[sessionId] of a live cage-kiosk session instead.
 */
data class PresentedMedia(
    val id: Long,
    val kind: PresentedMediaKind,
    /** IMAGE/AUDIO: absolute cache-file path the UI reads/decodes/plays. WEB: a downloaded PDF cache file. */
    val filePath: String? = null,
    /** WEB: loopback-served URL for HTML/SVG content (a PDF uses [filePath] instead). */
    val url: String? = null,
    val mimeType: String? = null,
    /** Optional one-line caption shown above the media. */
    val caption: String? = null,
    /** Audio only: start playback as soon as the sheet appears. */
    val autoPlay: Boolean = false,
    /** APP_WINDOW: VNC endpoint + the DesktopManager session to stop on dismiss. */
    val host: String? = null,
    val port: Int? = null,
    val sessionId: String? = null,
    /** APP_WINDOW: open filling the whole screen (escaping the sheet) instead of the 420dp box. */
    val fullscreen: Boolean = false,
    /** APP_WINDOW: the cage output scale this window launched at (seeds the 3-finger scale gesture). */
    val scale: Float = 1f,
    /** APP_WINDOW: the resolution token ("auto" | "WxH"); "auto" lets fullscreen refit the cage to the screen. */
    val resolution: String = "auto",
    val presentedAt: Long = System.currentTimeMillis(),
)

/**
 * The agent → user "here, look at / listen to this" channel.
 *
 * Unlike [AgentUiCommandBus] (fire-and-forget navigation *commands* with
 * `replay = 0`), a presented image or sound is **state**: it must stay on
 * screen until the user dismisses it, and survive a recomposition or a
 * brief backgrounding. So this mirrors [AgentConsentManager]'s shape — a
 * `@Singleton` holding a [StateFlow] queue the top-of-tree host renders.
 *
 * It is deliberately *not* a consent gate. Showing an image is not a
 * destructive act, so [present] never suspends and never asks: the agent
 * calls it and returns immediately; the overlay is itself the user-facing
 * artifact, and is freely dismissible. The user keeps the wheel by
 * dismissing, not by pre-approving.
 *
 * ### Backing files
 *
 * Each entry owns a cache file written by the caller. [dismiss] deletes
 * it. To bound disk use against a chatty agent the queue is capped at
 * [MAX_QUEUE]; pushing past the cap drops (and deletes the file of) the
 * oldest entry.
 */
@Singleton
class AgentPresentationManager @Inject constructor() {

    private val nextId = AtomicLong(1)

    private val _pending = MutableStateFlow<List<PresentedMedia>>(emptyList())
    /** All currently-showing media, oldest first. Drives the presentation host. */
    val pending: StateFlow<List<PresentedMedia>> = _pending.asStateFlow()

    private val _minimizedIds = MutableStateFlow<Set<Long>>(emptySet())
    /**
     * Ids of [PresentedMediaKind.APP_WINDOW] entries the user has backgrounded
     * to an edge icon. They stay in [pending] (the cage + VNC keep running) but
     * the host skips rendering them; the edge dock renders an icon per id.
     * Only ever holds app-window ids — image/audio are never minimized.
     *
     * Invariant: at most one app window is *non*-minimized at a time (the one
     * shown full-overlay). [presentAppWindow] and [restore] enforce it.
     */
    val minimizedIds: StateFlow<Set<Long>> = _minimizedIds.asStateFlow()

    /**
     * Enqueue [filePath] for the user to see/hear. Non-blocking; returns
     * the assigned id so a caller could correlate a later [dismiss] if it
     * wanted to. The file at [filePath] must already exist and is owned by
     * this manager from here on — it is deleted on dismissal / eviction.
     */
    fun present(
        kind: PresentedMediaKind,
        filePath: String,
        mimeType: String,
        caption: String?,
        autoPlay: Boolean = false,
    ): Long = enqueue(
        PresentedMedia(
            id = nextId.getAndIncrement(),
            kind = kind,
            filePath = filePath,
            mimeType = mimeType,
            caption = caption,
            autoPlay = autoPlay,
        ),
    )

    /**
     * Enqueue web content for the user to view inline: HTML/SVG via a
     * loopback-served [url] in a WebView, or a PDF via a downloaded cache
     * [filePath] paged by PdfRenderer. Non-blocking; returns the assigned id.
     * A PDF [filePath] is owned by this manager from here on (deleted on
     * dismissal / eviction); a served [url] is reclaimed by the stream
     * server's TTL.
     */
    fun presentWeb(
        url: String?,
        filePath: String?,
        mimeType: String?,
        caption: String?,
    ): Long = enqueue(
        PresentedMedia(
            id = nextId.getAndIncrement(),
            kind = PresentedMediaKind.WEB,
            url = url,
            filePath = filePath,
            mimeType = mimeType,
            caption = caption,
        ),
    )

    /**
     * Enqueue a live [PresentedMediaKind.APP_WINDOW] backed by a cage-kiosk
     * VNC session at [host]:[port]. [sessionId] is the DesktopManager session
     * the UI stops when the window is dismissed. No cache file is involved.
     */
    fun presentAppWindow(
        host: String,
        port: Int,
        sessionId: String,
        caption: String?,
        fullscreen: Boolean = false,
        scale: Float = 1f,
        resolution: String = "auto",
    ): Long {
        // Auto-background the currently-focused app window so the new one takes
        // the single full-overlay slot; the previous one becomes an edge icon.
        currentFocusedAppWindowId()?.let { _minimizedIds.value = _minimizedIds.value + it }
        return enqueue(
            PresentedMedia(
                id = nextId.getAndIncrement(),
                kind = PresentedMediaKind.APP_WINDOW,
                caption = caption,
                host = host,
                port = port,
                sessionId = sessionId,
                fullscreen = fullscreen,
                scale = scale,
                resolution = resolution,
            ),
        )
    }

    /** The id of the app window currently shown full-overlay, or null. */
    private fun currentFocusedAppWindowId(): Long? =
        _pending.value.firstOrNull {
            it.kind == PresentedMediaKind.APP_WINDOW && it.id !in _minimizedIds.value
        }?.id

    /** Background an app window to an edge icon (keeps the cage + VNC alive). */
    fun minimize(id: Long) {
        _minimizedIds.value = _minimizedIds.value + id
    }

    /**
     * Restore a backgrounded app window to the full overlay. Backgrounds the
     * current focused window first (single-focus invariant), then un-minimizes
     * [id] and moves it to the front of [pending] so the host renders it.
     */
    fun restore(id: Long) {
        currentFocusedAppWindowId()?.let { focused ->
            if (focused != id) _minimizedIds.value = _minimizedIds.value + focused
        }
        _minimizedIds.value = _minimizedIds.value - id
        val item = _pending.value.firstOrNull { it.id == id } ?: return
        _pending.value = listOf(item) + _pending.value.filterNot { it.id == id }
    }

    private fun enqueue(item: PresentedMedia): Long {
        val next = _pending.value + item
        if (next.size > MAX_QUEUE) {
            // Evict and delete the backing file of the oldest entries so a
            // misbehaving agent can't fill the cache. (APP_WINDOW has no file.)
            val evicted = next.subList(0, next.size - MAX_QUEUE)
            evicted.forEach { it.filePath?.let { p -> runCatching { File(p).delete() } } }
            _pending.value = next.subList(next.size - MAX_QUEUE, next.size).toList()
        } else {
            _pending.value = next
        }
        return item.id
    }

    /**
     * Called by the UI when the user dismisses an item (taps Dismiss or
     * swipes the sheet away). Removes it from the queue and deletes its
     * backing cache file (IMAGE/AUDIO only). For APP_WINDOW the UI layer is
     * responsible for stopping the DesktopManager session — this manager
     * (core:data) doesn't depend on core:local.
     */
    fun dismiss(id: Long) {
        val current = _pending.value
        val item = current.firstOrNull { it.id == id }
        _pending.value = current.filterNot { it.id == id }
        _minimizedIds.value = _minimizedIds.value - id
        item?.filePath?.let { runCatching { File(it).delete() } }
    }

    private companion object {
        const val MAX_QUEUE = 8
    }
}
