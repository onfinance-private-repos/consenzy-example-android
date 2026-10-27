package com.example.consenzy

import com.example.consenzy.TestSupport.ADULT
import com.example.consenzy.session.ConsenzySessions
import com.example.consenzy.ui.check.ConsentCheck
import com.example.consenzy.ui.consent.ConsentCollection
import com.example.consenzy.ui.guardian.GuardianConsent
import com.example.consenzy.ui.preferences.Preferences
import com.example.consenzy.ui.rights.RightsRequests
import `in`.onfinance.consent.core.ConsentStatus
import `in`.onfinance.consent.core.Language
import `in`.onfinance.consent.core.RequestType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The headless journey logic behind each screen, end to end against the in-app demo. */
class JourneysTest {
    private val clock = FakeClock()
    private val backend = TestSupport.demoBackend(clock)
    private val adult = runBlocking { ConsenzySessions.open(backend, ADULT) }

    @Test
    fun `journey 1 - notice with language fallback, then consent for the selected purposes only`(): Unit = runBlocking {
        val hi = ConsentCollection.loadNotice(adult.consent, Language.HI)
        assertFalse(ConsentCollection.isFallback(Language.HI, hi))
        val ta = ConsentCollection.loadNotice(adult.consent, Language.TA)
        assertTrue(ConsentCollection.isFallback(Language.TA, ta))

        val marketing = hi.purposes.first { it.code == "marketing" }
        val credit = hi.purposes.first { it.code == "credit_offers" }
        // Selection order does not matter; the notice order is kept.
        val granted = ConsentCollection.recordConsent(adult.consent, hi, linkedSetOf(credit.id, marketing.id))
        assertEquals(listOf("marketing", "credit_offers"), granted.map { it.purposeCode })
    }

    @Test
    fun `journey 2 - offers are sent only while marketing consent is active, with ledger evidence`(): Unit = runBlocking {
        assertTrue(ConsentCheck.sendOffers(adult) is ConsentCheck.Outcome.Blocked)

        val notice = ConsentCollection.loadNotice(adult.consent, Language.EN)
        val consent = ConsentCollection.recordConsent(adult.consent, notice, setOf(notice.purposes.first { it.code == "marketing" }.id)).single()

        val sent = ConsentCheck.sendOffers(adult)
        assertTrue(sent is ConsentCheck.Outcome.Sent)
        assertEquals(consent.id, sent.check.consentId)
        assertEquals("consent", sent.check.basis)
        assertEquals(64, sent.check.ledgerHash!!.length)
        assertNotNull(sent.check.ledgerEventId)

        Preferences.withdraw(adult.consent, consent.id, "No more offers")
        assertTrue(ConsentCheck.sendOffers(adult) is ConsentCheck.Outcome.Blocked)
    }

    @Test
    fun `journey 3 - preferences list active consents and withdraw with a reason`(): Unit = runBlocking {
        val notice = ConsentCollection.loadNotice(adult.consent, Language.EN)
        ConsentCollection.recordConsent(adult.consent, notice, notice.purposes.map { it.id }.toSet())
        val active = Preferences.activeConsents(adult.consent)
        assertEquals(3, active.size)
        assertEquals("Marketing messages", Preferences.purposeNames(adult.consent)["marketing"])

        val withdrawn = Preferences.withdraw(adult.consent, active.first().id, "  ")
        assertEquals(ConsentStatus.WITHDRAWN, withdrawn.status)
        assertEquals(null, withdrawn.withdrawalReason) // blank reason is not sent
        assertEquals(2, Preferences.activeConsents(adult.consent).size)
    }

    @Test
    fun `journey 4 - file access, correction, erasure, grievance and track them with the WhatsApp request`(): Unit = runBlocking {
        val access = RightsRequests.fileAccess(adult.consent, note = " ")
        val correction = RightsRequests.fileCorrection(adult.consent, "address_line_1", "12 MG Road", note = null)
        val erasure = RightsRequests.fileErasure(adult.consent, reason = "Closed my account")
        val grievance = RightsRequests.fileGrievance(adult.consent, "My erasure request was not completed", relatedRequestId = erasure.id)
        assertEquals(RequestType.CORRECTION, correction.requestType)
        assertEquals(RequestType.GRIEVANCE, grievance.requestType)

        val history = RightsRequests.history(adult.consent)
        assertEquals(5, history.size)
        assertEquals(setOf(access.id, correction.id, erasure.id, grievance.id), history.filter { it.channel == "app" }.map { it.id }.toSet())
        val whatsapp = history.single { it.channel == "whatsapp" }
        assertTrue(RightsRequests.summary(whatsapp).startsWith("erasure · in_progress · whatsapp · "))

        clock.advanceSeconds(61)
        assertTrue(RightsRequests.history(adult.consent).filter { it.channel == "app" }.all { !it.status.isOpen })
    }

    @Test
    fun `journey 5 - child refused, guardian consents, guardian files a nomination`(): Unit = runBlocking {
        val attempt = GuardianConsent.tryAsChild(backend)
        assertTrue(attempt.me.needsGuardianSession)
        assertNotNull(attempt.refusal)
        assertTrue(attempt.refusal!!.isGuardianRequired)

        val guardian = GuardianConsent.openGuardianSession(backend)
        val notice = guardian.consent.currentNotice()
        val granted = GuardianConsent.consentAsGuardian(guardian, notice, setOf(notice.purposes[1].id))
        assertEquals(listOf("analytics"), granted.map { it.purposeCode })

        val nomination = GuardianConsent.nominate(guardian, "Asha Rao", "mother", nomineePhone = "+919812345678", nomineeEmail = "")
        assertEquals(RequestType.NOMINATION, nomination.requestType)
        assertEquals("+919812345678", nomination.details["nominee_phone"].toString().trim('"'))
        guardian.logout()
    }
}
