package sh.haven.core.fido

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Guards the CTAP2 GetAssertion status -> exception mapping that drives the
 * #237 wrong-key retry: a NO_CREDENTIALS answer (wrong key) must surface as the
 * typed [FidoNoMatchingCredentialException] so [FidoAuthenticator.getAssertion]
 * re-prompts for the correct key, while every other failure stays a plain
 * [IOException] (no retry). If this mapping regresses, the retry silently turns
 * back into the original "wrong tap aborts the whole publickey method" bug.
 */
class Ctap2AssertionStatusTest {

    @Test
    fun `STATUS_OK maps to no error`() {
        assertNull(ctap2AssertionErrorForStatus(Ctap2Cbor.STATUS_OK))
    }

    @Test
    fun `NO_CREDENTIALS maps to the typed no-matching-credential exception`() {
        val e = ctap2AssertionErrorForStatus(Ctap2Cbor.STATUS_NO_CREDENTIALS)
        assertTrue(
            "expected FidoNoMatchingCredentialException, got ${e?.javaClass?.simpleName}",
            e is FidoNoMatchingCredentialException,
        )
        // Must remain an IOException so post-exhaustion the SSH/identity catch
        // sites still handle it exactly as before.
        assertTrue(e is IOException)
    }

    @Test
    fun `ACTION_TIMEOUT maps to a plain IOException, not the retry-triggering type`() {
        val e = ctap2AssertionErrorForStatus(Ctap2Cbor.STATUS_ACTION_TIMEOUT)
        assertTrue(e is IOException)
        assertTrue(
            "a touch timeout must NOT trigger the wrong-key retry",
            e !is FidoNoMatchingCredentialException,
        )
    }

    @Test
    fun `an unknown error status maps to a plain IOException, not a retry`() {
        val e = ctap2AssertionErrorForStatus(0x01)
        assertTrue(e is IOException)
        assertTrue(e !is FidoNoMatchingCredentialException)
    }
}
