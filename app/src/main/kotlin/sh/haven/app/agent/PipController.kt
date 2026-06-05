package sh.haven.app.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import sh.haven.core.data.agent.PresentedMedia
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-scoped Picture-in-Picture state, bridging [MainActivity] (which owns the
 * PiP lifecycle) and the Compose tree.
 *
 * - [activePipMedia] is the one presented item currently eligible for PiP — set
 *   by `PresentationHost` when an APP_WINDOW, IMAGE or WEB overlay is shown,
 *   cleared on dismiss (AUDIO has no visual surface, so it is never set).
 *   MainActivity uses it to keep `PictureInPictureParams` current (aspect ratio,
 *   plus auto-enter for APP_WINDOW only) and to render the full-bleed PiP view —
 *   the live VNC frame for an app window, or the decoded image / PDF page / live
 *   WebView for image and web (#225).
 * - [isInPip] is pushed from `Activity.onPictureInPictureModeChanged` so the
 *   composition can swap to the minimal PiP UI (and so the biometric re-lock is
 *   suppressed while floating).
 */
@Singleton
class PipController @Inject constructor() {
    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    private val _activePipMedia = MutableStateFlow<PresentedMedia?>(null)
    val activePipMedia: StateFlow<PresentedMedia?> = _activePipMedia.asStateFlow()

    fun setInPip(value: Boolean) { _isInPip.value = value }

    fun setActivePipMedia(media: PresentedMedia?) { _activePipMedia.value = media }
}
