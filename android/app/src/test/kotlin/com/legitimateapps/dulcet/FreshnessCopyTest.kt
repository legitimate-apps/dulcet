package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.AndroidLibraryCachedReason
import com.legitimateapps.dulcet.core.AndroidLibraryFreshness
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.ageWords
import com.legitimateapps.dulcet.library.freshnessLine
import com.legitimateapps.dulcet.library.reasonThenAge
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** One sentence on every surface: what happened, then how old, written out, and no age under a minute. */
@RunWith(RobolectricTestRunner::class)
class FreshnessCopyTest {
    private val resources = RuntimeEnvironment.getApplication().resources
    private val now = 1_800_000_000_000L
    private val twoHoursAgo = now - 2 * 3_600_000L

    /** The words as a person reads them; [aNumberStaysWithItsUnitAndThePhraseWithItself] holds where the line may break. */
    private fun line(asOf: Long?, reason: AndroidLibraryCachedReason) =
        resources.freshnessLine(AndroidLibraryFreshness.Cached(asOf, reason), now)?.replace('\u00A0', ' ')

    @Test fun aNumberStaysWithItsUnitAndThePhraseWithItself() {
        val shown = resources.freshnessLine(AndroidLibraryFreshness.Cached(twoHoursAgo, AndroidLibraryCachedReason.Offline), now)
        assertEquals("Couldn't reach your server. Last\u00A0updated 2\u00A0hours\u00A0ago", shown)
    }

    @Test fun anAgeIsWrittenOutAndNotSaidUnderAMinute() {
        assertEquals("2 hours ago", resources.ageWords(twoHoursAgo, now))
        assertEquals("1 minute ago", resources.ageWords(now - 60_000L, now))
        assertNull(resources.ageWords(now - 59_999L, now))
        assertNull(resources.ageWords(now + 5_000L, now), "a clock that moved back has no age to state")
    }

    @Test fun theFailureLeadsAndTheAgeFollows() {
        val unreachable = AndroidLibraryCachedReason.Failed(DomainError.Transport.Unreachable)
        assertEquals("Couldn't reach your server. Last updated 2 hours ago", line(twoHoursAgo, unreachable))
        assertEquals("Couldn't reach your server. Last updated 2 hours ago", line(twoHoursAgo, AndroidLibraryCachedReason.Offline))
        assertEquals("Your server didn't answer in time. Last updated 2 hours ago",
            line(twoHoursAgo, AndroidLibraryCachedReason.Failed(DomainError.Transport.Timeout)))
        assertEquals("Something went wrong on this device. Last updated 2 hours ago", line(twoHoursAgo, AndroidLibraryCachedReason.InternalFailure))
    }

    @Test fun everyReasonReadsAsALeadingSentence() {
        assertEquals("Your library has changed. Last updated 2 hours ago", line(twoHoursAgo, AndroidLibraryCachedReason.Stale))
        assertEquals("Checking your server. Last updated 2 hours ago", line(twoHoursAgo, AndroidLibraryCachedReason.Revalidating))
        assertEquals("The rest of this list is still to load. Last updated 2 hours ago", line(twoHoursAgo, AndroidLibraryCachedReason.Owed))
    }

    @Test fun underAMinuteTheReasonStandsAlone() {
        assertEquals("Couldn't reach your server", line(now - 5_000L, AndroidLibraryCachedReason.Offline))
    }

    @Test fun anUnknownAgeSaysSo() {
        assertEquals("Couldn't reach your server. Showing what this device had saved, age unknown", line(null, AndroidLibraryCachedReason.Offline))
    }

    @Test fun aPhraseThatIsAlreadyASentenceGetsOneFullStop() {
        assertEquals("This address now belongs to a different server. Your saved content is kept offline. Last updated 2 hours ago",
            line(twoHoursAgo, AndroidLibraryCachedReason.Failed(com.legitimateapps.dulcet.core.DomainError.Protocol.ServerIdentityChanged)))
        assertEquals("One", resources.reasonThenAge("one.", null))
    }
}
