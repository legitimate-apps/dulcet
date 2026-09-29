package com.legitimateapps.dulcet

import com.legitimateapps.dulcet.core.AndroidLibraryChangeField
import com.legitimateapps.dulcet.core.AndroidLibraryChangeOutcome
import com.legitimateapps.dulcet.core.AndroidLibraryEntity
import com.legitimateapps.dulcet.core.AndroidLibraryEntityKind
import com.legitimateapps.dulcet.core.DomainError
import com.legitimateapps.dulcet.library.outcomeLine
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals

/**
 * What the album screens say about a held change (§16.20). Its error is the account check's — the
 * `ping` that followed a refusal — so the line names sign-in, a refusal of access or the server, and
 * never says anything about the item: an error code such as 70 answered the ping, not the change.
 */
@RunWith(RobolectricTestRunner::class)
class HeldChangeCopyTest {
    private val resources = RuntimeEnvironment.getApplication().resources

    private fun line(error: DomainError): String? = resources.outcomeLine(
        AndroidLibraryChangeOutcome.Held(AndroidLibraryEntity(AndroidLibraryEntityKind.Album, "album"), AndroidLibraryChangeField.Favourite, error),
    )

    private val kept = "It's kept, and will be sent once your server accepts it."

    @Test
    fun aHeldChangeSaysWhyInTermsOfTheAccountNeverTheItem() {
        assertEquals("That change isn't sent yet — your server is busy. It will be sent later.",
            line(DomainError.Server.Busy(retryAfter = null)))
        assertEquals("That change isn't sent yet — your server didn't accept your sign-in. $kept",
            line(DomainError.Auth.InvalidCredentials))
        for (refusal in listOf(DomainError.Auth.Forbidden, DomainError.Auth.TokenAuthUnsupported,
            DomainError.Server.HttpStatus(403), DomainError.Server.HttpStatus(407))) {
            assertEquals("That change isn't sent yet — your server refused access. $kept", line(refusal), "$refusal")
        }
        for (other in listOf(DomainError.Server.HttpStatus(500), DomainError.Server.Known(70))) {
            assertEquals("That change isn't sent yet — your server couldn't answer. $kept", line(other), "$other")
        }
    }
}
