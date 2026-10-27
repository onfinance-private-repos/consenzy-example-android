package com.example.consenzy.demo

import com.example.consenzy.FakeClock
import com.example.consenzy.TestSupport
import com.example.consenzy.TestSupport.ADULT
import com.example.consenzy.TestSupport.CHILD
import com.example.consenzy.session.ConsenzySessions
import `in`.onfinance.consent.core.ActingAs
import `in`.onfinance.consent.core.ConsentApiException
import `in`.onfinance.consent.core.ConsentSession
import `in`.onfinance.consent.core.ConsentSessionOptions
import `in`.onfinance.consent.core.ConsentStatus
import `in`.onfinance.consent.core.DataRequestDetails
import `in`.onfinance.consent.core.InvalidSessionTokenException
import `in`.onfinance.consent.core.Language
import `in`.onfinance.consent.core.RequestStatus
import `in`.onfinance.consent.core.RequestType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the real Consenzy SDK (`ConsentSession`) against the in-app demo transport ([DemoInterceptor]), so these tests
 * cover the same code path the app uses in its default mode: token handling, JSON, Idempotency-Key, errors.
 */
class DemoTransportTest {
    private val clock = FakeClock()
    private val backend = TestSupport.demoBackend(clock)

    private fun open(ref: String = ADULT, actingAs: ActingAs = ActingAs.DATA_PRINCIPAL) =
        runBlocking { ConsenzySessions.open(backend, ref, actingAs) }

    @Test
    fun `me reports the principal, acting_as and capabilities`(): Unit = runBlocking {
        val me = open().consent.me()
        assertEquals(ADULT, me.externalRef)
        assertEquals(ActingAs.DATA_PRINCIPAL, me.actingAs)
        assertTrue(me.canGiveConsent && me.canFileRequests && me.canReadRequests)
        assertFalse(me.needsGuardianSession)
    }

    @Test
    fun `notice in English and Hindi, falling back to English for an unpublished language`(): Unit = runBlocking {
        val session = open().consent
        val en = session.currentNotice(Language.EN)
        val hi = session.currentNotice(Language.HI)
        val ta = session.currentNotice(Language.TA)
        assertEquals("en", en.languageCode)
        assertEquals("hi", hi.languageCode)
        assertEquals("en", ta.languageCode) // fallback
        assertEquals(en.id, hi.id)
        assertEquals(listOf("marketing", "analytics", "credit_offers"), en.purposes.map { it.code })
        assertEquals(listOf("name", "email", "phone"), en.purposes[0].dataCategories)
        assertEquals(365, en.purposes[0].retentionDays)
        assertTrue(hi.title != en.title)
    }

    @Test
    fun `grant, list, withdraw`(): Unit = runBlocking {
        val session = open().consent
        val notice = session.currentNotice()
        val granted = session.giveConsent(notice.id, notice.purposes.take(2).map { it.id })
        assertEquals(listOf("marketing", "analytics"), granted.map { it.purposeCode })
        assertTrue(granted.all { it.status == ConsentStatus.ACTIVE && it.channel == "mobile_app" })

        assertEquals(2, session.listConsents(activeOnly = true).size)

        val withdrawn = session.withdrawConsent(granted[0].id, reason = "Too many messages")
        assertEquals(ConsentStatus.WITHDRAWN, withdrawn.status)
        assertEquals("Too many messages", withdrawn.withdrawalReason)
        assertNotNull(withdrawn.withdrawnAt)

        assertEquals(listOf("analytics"), session.listConsents(activeOnly = true).map { it.purposeCode })
        assertEquals(2, session.listConsents().size)

        try {
            session.withdrawConsent(granted[0].id)
            fail("withdrawing twice must fail")
        } catch (e: ConsentApiException) {
            assertTrue(e.isConflict)
        }
    }

    @Test
    fun `the same Idempotency-Key replays the first grant`(): Unit = runBlocking {
        val session = open().consent
        val notice = session.currentNotice()
        val key = ConsentSession.newIdempotencyKey()
        val first = session.giveConsent(notice.id, listOf(notice.purposes[0].id), idempotencyKey = key)
        val replay = session.giveConsent(notice.id, listOf(notice.purposes[0].id), idempotencyKey = key)
        assertEquals(first.map { it.id }, replay.map { it.id })
        assertEquals(1, session.listConsents().size)
    }

    @Test
    fun `a new grant supersedes the active consent for the same purpose`(): Unit = runBlocking {
        val session = open().consent
        val notice = session.currentNotice()
        val first = session.giveConsent(notice.id, listOf(notice.purposes[0].id)).single()
        clock.advanceSeconds(1)
        val second = session.giveConsent(notice.id, listOf(notice.purposes[0].id)).single()
        val all = session.listConsents()
        assertEquals(ConsentStatus.SUPERSEDED, all.single { it.id == first.id }.status)
        assertEquals(listOf(second.id), session.listConsents(activeOnly = true).map { it.id })
    }

    @Test
    fun `rights requests of every type are filed with channel app`(): Unit = runBlocking {
        val session = open().consent
        val access = session.fileDataRequest(DataRequestDetails.Access(note = "Please send a summary"))
        val correction = session.fileDataRequest(RequestType.CORRECTION, DataRequestDetails.Correction.of(mapOf("address_line_1" to "12 MG Road")))
        val erasure = session.fileDataRequest(DataRequestDetails.Erasure(reason = "Closed my account"))
        val grievance = session.fileDataRequest(DataRequestDetails.Grievance("Not completed", relatedRequestId = access.id))
        val nomination = session.fileDataRequest(DataRequestDetails.Nomination("Asha Rao", "spouse", nomineePhone = "+919812345678"))

        assertEquals(
            listOf(RequestType.ACCESS, RequestType.CORRECTION, RequestType.ERASURE, RequestType.GRIEVANCE, RequestType.NOMINATION),
            listOf(access, correction, erasure, grievance, nomination).map { it.requestType },
        )
        listOf(access, correction, erasure, grievance, nomination).forEach {
            assertEquals("app", it.channel)
            assertEquals(RequestStatus.RECEIVED, it.status)
        }
        assertEquals("12 MG Road", (correction.details["fields"] as JsonObject)["address_line_1"]!!.jsonPrimitive.content)
        assertEquals(access.id, grievance.details["related_request_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun `request list includes the seeded WhatsApp erasure and statuses progress with time`(): Unit = runBlocking {
        val session = open().consent
        val seeded = session.listDataRequests().single()
        assertEquals(RequestType.ERASURE, seeded.requestType)
        assertEquals("whatsapp", seeded.channel)
        assertEquals("wamid.DEMO-0001", seeded.channelReference)
        assertEquals(RequestStatus.IN_PROGRESS, seeded.status)
        assertTrue(seeded.status.isOpen)

        val filed = session.fileDataRequest(DataRequestDetails.Access())
        assertEquals(RequestStatus.RECEIVED, session.listDataRequests().first { it.id == filed.id }.status)
        assertEquals(filed.id, session.listDataRequests().first().id) // newest first

        clock.advanceSeconds(21)
        assertEquals(RequestStatus.IN_PROGRESS, session.listDataRequests().first { it.id == filed.id }.status)

        clock.advanceSeconds(40)
        val done = session.listDataRequests().first { it.id == filed.id }
        assertEquals(RequestStatus.COMPLETED, done.status)
        assertFalse(done.status.isOpen)
        assertNotNull(done.closedAt)
        assertNotNull(done.response)
        // The seeded request has a fixed status.
        assertEquals(RequestStatus.IN_PROGRESS, session.listDataRequests().first { it.id == seeded.id }.status)
    }

    @Test
    fun `server-side validation errors arrive as field errors`(): Unit = runBlocking {
        val session = open().consent
        try {
            // The SDK allows a 1000-character note; the demo server (like the session server's mock) allows 500.
            session.fileDataRequest(DataRequestDetails.Access(note = "x".repeat(600)))
            fail("expected 422")
        } catch (e: ConsentApiException) {
            assertTrue(e.isValidationError)
            assertEquals(listOf("body", "details", "note"), e.fieldErrors.single().location)
        }
    }

    @Test
    fun `a child's own session is refused with isGuardianRequired, a guardian session works`(): Unit = runBlocking {
        val child = open(CHILD).consent
        val me = child.me()
        assertTrue(me.actsThroughGuardian)
        assertTrue(me.needsGuardianSession)
        val notice = child.currentNotice()
        try {
            child.giveConsent(notice.id, listOf(notice.purposes[0].id))
            fail("a data_principal session for a child must be refused")
        } catch (e: ConsentApiException) {
            assertEquals(422, e.status)
            assertTrue(e.isGuardianRequired)
            assertEquals(DemoStore.GUARDIAN_REQUIRED, e.detail)
        }

        val guardian = open(CHILD, ActingAs.GUARDIAN).consent
        val gm = guardian.me()
        assertTrue(gm.isGuardianSession)
        assertFalse(gm.needsGuardianSession)
        val granted = guardian.giveConsent(notice.id, listOf(notice.purposes[0].id))
        assertEquals(1, granted.size)
        assertEquals(1, child.listConsents(activeOnly = true).size) // same principal
    }

    @Test
    fun `a guardian session cannot be minted for an adult`(): Unit = runBlocking {
        try {
            open(ADULT, ActingAs.GUARDIAN)
            fail("expected a refusal")
        } catch (e: com.example.consenzy.session.BackendException) {
            assertEquals(422, e.status)
        }
    }

    @Test
    fun `capabilities are enforced`(): Unit = runBlocking {
        val store = backend.store
        val token = (store.mint(ADULT, capabilities = listOf("requests:read")).body as JsonObject)["token"]!!.jsonPrimitive.content
        val session = ConsentSession(
            baseUrl = DemoBackend.BASE_URL,
            sessionProvider = { token },
            options = ConsentSessionOptions(okHttpClient = backend.sdkHttpClient, maxRetries = 0),
        )
        assertFalse(session.me().canGiveConsent)
        assertEquals(1, session.listDataRequests().size)
        val notice = session.currentNotice()
        try {
            session.giveConsent(notice.id, listOf(notice.purposes[0].id))
            fail("expected 403")
        } catch (e: ConsentApiException) {
            assertTrue(e.isForbidden)
        }
        try {
            session.fileDataRequest(DataRequestDetails.Access())
            fail("expected 403")
        } catch (e: ConsentApiException) {
            assertTrue(e.isForbidden)
        }
    }

    @Test
    fun `logout revokes the token and the next call mints a fresh one`(): Unit = runBlocking {
        val mints = AtomicInteger()
        val store = backend.store
        val session = ConsentSession(
            baseUrl = DemoBackend.BASE_URL,
            sessionProvider = {
                mints.incrementAndGet()
                (store.mint(ADULT).body as JsonObject)["token"]!!.jsonPrimitive.content
            },
            options = ConsentSessionOptions(okHttpClient = backend.sdkHttpClient),
        )
        session.me()
        assertEquals(1, mints.get())
        session.logout()
        session.me()
        assertEquals(2, mints.get())
    }

    @Test
    fun `an expired token is refreshed once through the session provider`(): Unit = runBlocking {
        val signedIn = open()
        signedIn.consent.me()
        clock.advanceSeconds(901) // demo sessions live 900 s
        val oldToken = signedIn.minted.token
        assertNull(backend.store.session(oldToken))
        assertEquals(ADULT, signedIn.consent.me().externalRef) // 401 → sessionProvider mints a new token → retry
    }

    @Test
    fun `the SDK rejects an org API key before any request`(): Unit = runBlocking {
        val session = ConsentSession(
            baseUrl = DemoBackend.BASE_URL,
            sessionProvider = { "cdm_" + "not_a_real_key" },
            options = ConsentSessionOptions(okHttpClient = backend.sdkHttpClient),
        )
        try {
            session.me()
            fail("expected InvalidSessionTokenException")
        } catch (e: InvalidSessionTokenException) {
            assertTrue(e.message!!.contains("cdm_"))
        }
    }

    @Test
    fun `unknown routes and missing tokens`() {
        val interceptor = DemoInterceptor(backend.store)
        val noAuth = okhttp3.Request.Builder().url("${DemoBackend.BASE_URL}/v1/me").build()
        assertEquals(401, interceptor.handle(noAuth).status)
        val token = (backend.store.mint(ADULT).body as JsonObject)["token"]!!.jsonPrimitive.content
        val unknown = okhttp3.Request.Builder().url("${DemoBackend.BASE_URL}/v1/me/nope").header("Authorization", "Bearer $token").build()
        assertEquals(404, interceptor.handle(unknown).status)
    }
}
