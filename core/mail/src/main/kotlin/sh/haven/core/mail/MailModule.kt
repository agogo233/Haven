package sh.haven.core.mail

import dagger.Binds
import dagger.MapKey
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoMap

/** Dagger map key for the per-[MailEngine] [MailClient] registry. */
@MapKey
annotation class MailEngineKey(val value: MailEngine)

/**
 * Binds the mail engines into a `Map<MailEngine, MailClient>` that
 * [MailSessionManager] routes by the session's engine. Proton is the v1 engine;
 * the JVM IMAP/SMTP engine (Stage 2a) adds one more `@IntoMap` binding here.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MailModule {
    @Binds
    @IntoMap
    @MailEngineKey(MailEngine.PROTON)
    abstract fun bindProtonClient(impl: ProtonMailClient): MailClient
}
