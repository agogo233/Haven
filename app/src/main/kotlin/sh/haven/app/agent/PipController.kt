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
 * - [activeAppWindow] is the one app window currently eligible for PiP — set by
 *   `PresentationHost` when an APP_WINDOW overlay is shown, cleared on dismiss.
 *   MainActivity uses it to keep `PictureInPictureParams` current (auto-enter +
 *   aspect ratio) and to render the full-bleed PiP view from the same
 *   [AppWindowConnectionStore] controller the overlay uses.
 * - [isInPip] is pushed from `Activity.onPictureInPictureModeChanged` so the
 *   composition can swap to the minimal PiP UI (and so the biometric re-lock is
 *   suppressed while floating).
 */
@Singleton
class PipController @Inject constructor() {
    private val _isInPip = MutableStateFlow(false)
    val isInPip: StateFlow<Boolean> = _isInPip.asStateFlow()

    private val _activeAppWindow = MutableStateFlow<PresentedMedia?>(null)
    val activeAppWindow: StateFlow<PresentedMedia?> = _activeAppWindow.asStateFlow()

    fun setInPip(value: Boolean) { _isInPip.value = value }

    fun setActiveAppWindow(media: PresentedMedia?) { _activeAppWindow.value = media }
}
