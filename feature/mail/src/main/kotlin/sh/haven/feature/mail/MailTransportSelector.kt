package sh.haven.feature.mail

import sh.haven.core.mail.MailSessionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the [MailBackend] for a profile, mirroring `feature/sftp`'s
 * TransportSelector. Engine-agnostic: it pairs the session's engine [MailClient]
 * (Proton or IMAP) with the one [RfcMailBackend], since both engines expose the
 * same RFC822 read surface.
 */
@Singleton
class MailTransportSelector @Inject constructor(
    private val mailSessionManager: MailSessionManager,
) {
    /** A backend for [profileId], or null if the profile has no connected session. */
    fun resolve(profileId: String): MailBackend? {
        val sessionId = mailSessionManager.getSessionIdForProfile(profileId) ?: return null
        val client = mailSessionManager.clientForSession(sessionId) ?: return null
        return RfcMailBackend(client, sessionId)
    }
}
