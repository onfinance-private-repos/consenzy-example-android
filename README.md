# Consenzy example: Android (Kotlin, Jetpack Compose)

An example Android app for the **Consenzy** DPDP Act consent SDK (`in.onfinance:consenzy-android` and
`in.onfinance:consenzy-android-ui`). It shows the journeys a bank or fintech app needs: collecting consent against a
published notice, checking consent before using data, a preferences centre with withdrawal, Data Principal rights
requests with status tracking, and consent by a parent or guardian for a minor.

It runs with **zero setup** in its default in-app demo mode, and can also talk to the shared
[example session server](https://github.com/never2average/consenzy-example-session-server), in that server's demo mode
or against a real Consenzy organisation.

[![CI](https://github.com/never2average/consenzy-example-android/actions/workflows/ci.yml/badge.svg)](https://github.com/never2average/consenzy-example-android/actions/workflows/ci.yml)

## Journeys

Every screen has a **headless** variant (your own Compose UI over the SDK's suspend API, with the SDK calls collected
in a small object at the top of the file) and, where the SDK has one, the **drop-in** Compose component.

| # | Journey | What it shows | Source |
|---|---|---|---|
| 0 | Sign-in, home, logout | Pick the mode and a synthetic customer (adult, or a child as data principal or as guardian). Shows `acting_as`, capabilities and the base URL the SDK uses. Log out calls `ConsentSession.logout()`, which revokes the `cps_` token. | [`HomeScreen.kt`](app/src/main/kotlin/com/example/consenzy/ui/home/HomeScreen.kt), [`AppViewModel.kt`](app/src/main/kotlin/com/example/consenzy/AppViewModel.kt) |
| 1 | Consent collection | `currentNotice(language)` with an English / Hindi / Tamil switch (Tamil is not published, so the server falls back to English and the screen says so). The itemised notice: title, body, and for each purpose its description, data categories and retention. Purpose switches start off; `giveConsent(notice.id, purposeIds)` records exactly the ones switched on. Drop-in: `ConsentSheet`. | [`ConsentCollectionScreen.kt`](app/src/main/kotlin/com/example/consenzy/ui/consent/ConsentCollectionScreen.kt) |
| 2 | Check before use | "Send me offers" asks **your backend** to check `marketing` (`POST /consent-check`, which calls Consenzy's `POST /v1/consents/check`) and only proceeds when `allowed`, showing `consent_id`, `ledger_event_id` and `ledger_hash` as the evidence to store. | [`ConsentCheckScreen.kt`](app/src/main/kotlin/com/example/consenzy/ui/check/ConsentCheckScreen.kt) |
| 3 | Preferences centre | `listConsents(activeOnly = true)` and `withdrawConsent(id, reason)` behind a confirmation dialog with an optional reason. Drop-in: `ConsentManagerScreen`. | [`PreferencesScreen.kt`](app/src/main/kotlin/com/example/consenzy/ui/preferences/PreferencesScreen.kt) |
| 4 | Rights requests | `fileDataRequest` with `DataRequestDetails.Access`, `Correction`, `Erasure` and `Grievance` (optionally linked to an earlier request), then status tracking with `listDataRequests()`: status, `isOpen`, channel (including the request that arrived on **WhatsApp**), channel reference and statutory `dueAt`, with Refresh. Drop-in: `RightsRequestForm`. | [`RightsRequestsScreen.kt`](app/src/main/kotlin/com/example/consenzy/ui/rights/RightsRequestsScreen.kt) |
| 5 | Guardian consent (s.9) and nomination (s.14) | For the minor `CUST-DEMO-CHILD-7`: a session minted as the child is refused with HTTP 422 (`ConsentApiException.isGuardianRequired`); a session minted with `acting_as = guardian` records consent; the guardian files a nomination with `DataRequestDetails.Nomination`. | [`GuardianScreen.kt`](app/src/main/kotlin/com/example/consenzy/ui/guardian/GuardianScreen.kt) |

Supporting code:

| What | Source |
|---|---|
| Session / token provider: mint a `cps_` session through the backend, build `ConsentSession` with a `sessionProvider` that returns a fresh token each time | [`session/ConsenzySessions.kt`](app/src/main/kotlin/com/example/consenzy/session/ConsenzySessions.kt) |
| The backend contract (`POST /session`, `POST /consent-check`, `GET /demo-customers`) | [`session/SessionBackend.kt`](app/src/main/kotlin/com/example/consenzy/session/SessionBackend.kt) |
| Session server client | [`session/SessionServerBackend.kt`](app/src/main/kotlin/com/example/consenzy/session/SessionServerBackend.kt) |
| In-app demo (demo only): fake transport, in-memory store, local "backend", fixtures | [`demo/`](app/src/main/kotlin/com/example/consenzy/demo), [`assets/demo-fixtures.json`](app/src/main/assets/demo-fixtures.json) |
| Tests | [`app/src/test/`](app/src/test/kotlin/com/example/consenzy) |

## Run it

Requirements: Android Studio (Ladybug or later) or the Android SDK command-line tools, and JDK 17.

### 1. In-app demo (default, zero setup)

Open the project in Android Studio and run the `app` configuration, or:

```bash
./gradlew installDebug
```

Pick **In-app demo**, sign in as a demo customer, and walk through the journeys. Nothing leaves the device: an OkHttp
[`DemoInterceptor`](app/src/main/kotlin/com/example/consenzy/demo/DemoInterceptor.kt) is passed to the SDK through
`ConsentSessionOptions(okHttpClient = …)` and answers `/v1/me/*` from synthetic data, so the **real** SDK code runs
(`cps_` token checks, JSON, Idempotency-Key, retries, 401 refresh, error parsing) and only the bytes on the wire are
fake. The demo data ([`demo-fixtures.json`](app/src/main/assets/demo-fixtures.json)) is an unchanged copy of the
session server's `src/fixtures.json`, and [`DemoStore.kt`](app/src/main/kotlin/com/example/consenzy/demo/DemoStore.kt)
ports its `src/mock.js`: notices in English and Hindi with English fallback, grant with supersede and Idempotency-Key
replay, withdrawal, rights requests with validation and a status that moves from `received` to `in_progress` (20 s)
to `completed` (60 s), the guardian rule, capabilities and logout. Demo data lives in memory until the app process ends.

### 2. Session server in demo mode

Run the [example session server](https://github.com/never2average/consenzy-example-session-server) on your machine:

```bash
git clone https://github.com/never2average/consenzy-example-session-server
cd consenzy-example-session-server
npm ci && npm start          # demo mode on http://localhost:8787
```

Then pick **Session server** in the app. The app calls `http://10.0.2.2:8787` (the host machine as seen from the
Android emulator) for `POST /session` and `POST /consent-check`, and gives the SDK the `base_url` the server returns.
In demo mode that is the server itself, which serves its own mock of `/v1/me`.

To use another URL (a physical device on your network, or a deployed backend):

```bash
./gradlew installDebug -PconsenzySessionServerUrl=http://192.168.1.20:8787
```

Plain `http://` is only for this local development setup. Debug builds include a
[network security config](app/src/debug/res/xml/network_security_config.xml) that allows cleartext to `10.0.2.2`,
`localhost` and `127.0.0.1` only, and the app sets `ConsentSessionOptions(allowCleartext = true)` only in debug builds
when the base URL is `http://`. Add your LAN address to that file if you use a physical device. Release builds are
HTTPS-only.

### 3. A real Consenzy organisation (session server in live mode)

The app does not change. Point the session server at your organisation:

1. In the Consenzy portal, register the app under **Configuration → Applications** and issue an API key for the
   session server under **Settings → API Keys** with the `sessions:mint` and `consents:check` scopes.
2. Create principals for the external references you want to test, or set `ALLOWED_EXTERNAL_REFS` on the server.
3. Start the server in live mode (see its README):

   ```bash
   CONSENZY_MODE=live
   CONSENZY_BASE_URL=https://consenzy.onfinance.ai     # or your own deployment
   CONSENZY_API_KEY=cdm_…                             # on the server only, never in the app
   ```

`POST /session` then returns `base_url = https://consenzy.onfinance.ai` (or your deployment), and the SDK talks to
Consenzy directly over HTTPS with the short-lived `cps_` token. For the guardian journey, verify the guardian once in
Consenzy (`POST /v1/principals/{id}/guardian-verification`) and only mint `acting_as = guardian` after your backend has
authenticated the parent.

## Architecture: why there are no API keys in the app

```
app                            your backend (holds cdm_… key)        Consenzy
───                            ──────────────────────────────        ────────
sign in (your login)  ───────▶ POST /session        ───────────────▶ POST /v1/principal-sessions
                      ◀─────── cps_… token + base_url ◀─────────────
"send me offers"      ───────▶ POST /consent-check  ───────────────▶ POST /v1/consents/check
ConsentSession (cps_… only) ─────────────────────────────────────────▶ /v1/me/* (notice, consents, requests)
```

- **Org API keys (`cdm_…`) never ship in an app.** Anything in an APK can be extracted. An org key can mint sessions
  for any customer and check anyone's consent. The SDK refuses a `cdm_` key with `InvalidSessionTokenException`
  before any request.
- **The app holds only a short-lived principal session token (`cps_…`)**, minted by your backend after it has
  authenticated the customer. A `cps_` token can act for that one customer only, expires (15 minutes here), carries
  capabilities (`consents:write`, `requests:read`, `requests:write`) and is revoked by `logout()`.
- **`sessionProvider` must return a fresh token each time.** The SDK calls it lazily and again (once) on HTTP 401.
  [`ConsenzySessions.open`](app/src/main/kotlin/com/example/consenzy/session/ConsenzySessions.kt) hands out the token
  from sign-in once, then asks the backend for a new one.
- **Consent checks happen on the backend**, next to the processing they authorise, and the backend keeps
  `consent_id` and `ledger_hash` as evidence. The app only asks the backend to do something ("send me offers").
- **Guardians.** For a child or a person with disability, consent must come from a parent or lawful guardian (s.9).
  Your backend authenticates the guardian and mints `acting_as = guardian`; a session minted as the child is refused.

This example's session server takes `external_ref` from the request so you can pick demo customers. A production
backend must derive the customer from its own authenticated user instead and never trust the client to say who it is.

## Tests and CI

```bash
./gradlew assembleDebug testDebugUnitTest
```

The unit tests run the real SDK (`ConsentSession`) against the in-app demo transport:

- [`DemoTransportTest.kt`](app/src/test/kotlin/com/example/consenzy/demo/DemoTransportTest.kt): notices and language
  fallback; grant → list → withdraw; Idempotency-Key replay; supersede; filing every request type; the WhatsApp request
  and status progression with a fake clock; server-side validation errors; the guardian refusal and guardian grant;
  capability enforcement; logout and token refresh on 401; rejection of `cdm_` keys.
- [`JourneysTest.kt`](app/src/test/kotlin/com/example/consenzy/JourneysTest.kt): the headless logic of journeys 1 to 5.
- [`SessionServerBackendTest.kt`](app/src/test/kotlin/com/example/consenzy/session/SessionServerBackendTest.kt): the
  session server contract against MockWebServer, including the SDK using the returned `base_url` and minting a fresh
  token after a 401.

[GitHub Actions](.github/workflows/ci.yml) builds the debug APK and runs the unit tests on every push to `main`, on
`v*` tags and on pull requests, and uploads the APK as a build artifact.

## Vendored SDK

The Consenzy Android SDK is not yet on Maven Central, so a copy of its source (core and Compose UI, version 0.1.0) is
vendored in [`vendor/consenzy-android/`](vendor/consenzy-android) and built as the Gradle projects `:consenzy-core` and
`:consenzy-ui` (see [`settings.gradle.kts`](settings.gradle.kts)). It is Apache-2.0 licensed, Copyright 2026
OnFinance, with its own [LICENSE](vendor/consenzy-android/LICENSE) and [NOTICE](vendor/consenzy-android/NOTICE). Once
the SDK is published, replace `implementation(project(":consenzy-ui"))` in
[`app/build.gradle.kts`](app/build.gradle.kts) with:

```kotlin
implementation("in.onfinance:consenzy-android:0.1.0")
implementation("in.onfinance:consenzy-android-ui:0.1.0")
```

and delete the vendor directory (details in [`vendor/consenzy-android/README.md`](vendor/consenzy-android/README.md)).

The package is `in.onfinance.consent`. `in` is a Kotlin keyword, so imports need backticks:
``import `in`.onfinance.consent.core.ConsentSession``.

## Licence

The example code is MIT licensed, Copyright (c) 2026 never2average (see [LICENSE](LICENSE)). The vendored SDK under
`vendor/consenzy-android/` is Apache-2.0, Copyright 2026 OnFinance. The Consenzy server, API and portal are
proprietary OnFinance software and are not part of this repository. All demo data is synthetic.
