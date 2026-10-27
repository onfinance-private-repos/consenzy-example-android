package com.example.consenzy.session

import `in`.onfinance.consent.core.ActingAs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** "Session server" mode against a fake session server: the app's side of the POST /session and /consent-check contract. */
class SessionServerBackendTest {
    private lateinit var server: MockWebServer
    private lateinit var backend: SessionServerBackend

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        backend = SessionServerBackend(server.url("/").toString())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun sessionJson(token: String, actingAs: String = "data_principal") = """
        {"token":"$token","session_id":"s1","principal_id":"p1","expires_at":"2026-10-27T10:15:00.000Z",
         "acting_as":"$actingAs","capabilities":["consents:write","requests:read","requests:write"],
         "application_id":null,"base_url":"${server.url("/").toString().trimEnd('/')}","mode":"demo"}
    """.trimIndent()

    @Test
    fun `mints a session as guardian and parses the response`(): Unit = runBlocking {
        server.enqueue(json(sessionJson("cps_demo_one", "guardian")))
        val minted = backend.mintSession("CUST-DEMO-CHILD-7", ActingAs.GUARDIAN)
        assertEquals("cps_demo_one", minted.token)
        assertEquals("guardian", minted.actingAs)
        assertEquals(3, minted.capabilities.size)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/session", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("CUST-DEMO-CHILD-7", body["external_ref"]!!.jsonPrimitive.content)
        assertEquals("guardian", body["acting_as"]!!.jsonPrimitive.content)
        assertEquals(null, request.getHeader("Authorization")) // the app sends no org key
    }

    @Test
    fun `consent check parses ConsentCheckOut`(): Unit = runBlocking {
        server.enqueue(json("""{"allowed":true,"basis":"consent","consent_id":"c1","ledger_event_id":42,"ledger_hash":"abc","expires_at":null}"""))
        val check = backend.consentCheck("CUST-DEMO-0001", "marketing")
        assertTrue(check.allowed)
        assertEquals("c1", check.consentId)
        assertEquals(42L, check.ledgerEventId)
        assertEquals("/consent-check", server.takeRequest().path)

        server.enqueue(json("""{"allowed":false,"basis":null,"consent_id":null,"ledger_event_id":null,"ledger_hash":null,"expires_at":null}"""))
        assertFalse(backend.consentCheck("CUST-DEMO-0001", "marketing").allowed)
    }

    @Test
    fun `errors carry the server's detail`(): Unit = runBlocking {
        server.enqueue(json("""{"detail":"unknown customer"}""", code = 403))
        try {
            backend.mintSession("SOMEONE-ELSE")
            fail("expected BackendException")
        } catch (e: BackendException) {
            assertEquals(403, e.status)
            assertEquals("unknown customer", e.message)
        }
    }

    @Test
    fun `the SDK uses the minted token against the returned base_url, and a 401 mints a fresh one`(): Unit = runBlocking {
        server.enqueue(json(sessionJson("cps_demo_first")))
        val signedIn = ConsenzySessions.open(backend, "CUST-DEMO-0001")
        server.takeRequest() // POST /session

        val me = """{"principal_id":"p1","external_ref":"CUST-DEMO-0001","preferred_language":"en",
            "acts_through_guardian":false,"guardian_verified":false,"acting_as":"data_principal",
            "capabilities":["consents:write","requests:read","requests:write"]}"""
        server.enqueue(json(me))
        assertEquals("CUST-DEMO-0001", signedIn.consent.me().externalRef)
        val first = server.takeRequest()
        assertEquals("/v1/me", first.path)
        assertEquals("Bearer cps_demo_first", first.getHeader("Authorization"))

        server.enqueue(json("""{"detail":"invalid or expired session token"}""", code = 401))
        server.enqueue(json(sessionJson("cps_demo_second")))
        server.enqueue(json(me))
        signedIn.consent.me()
        assertEquals("Bearer cps_demo_first", server.takeRequest().getHeader("Authorization"))
        assertEquals("/session", server.takeRequest().path)
        assertEquals("Bearer cps_demo_second", server.takeRequest().getHeader("Authorization"))
    }
}
