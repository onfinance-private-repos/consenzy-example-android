// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import `in`.onfinance.consent.core.Principal
import `in`.onfinance.consent.core.RequestStatus
import `in`.onfinance.consent.core.RequestType

/**
 * Every user-visible string of the components. Defaults are English; pass a translated copy
 * (e.g. built from your app's string resources) to localise the chrome. The notice title,
 * body and purpose texts come from the server in the language picked.
 */
@Immutable
public data class ConsentStrings(
    val languageLabel: String = "Language",
    val chooseLanguage: String = "Choose notice language",
    val purposesHeading: String = "Choose what you agree to",
    val purposesHint: String = "Each purpose is off until you switch it on. You can withdraw consent at any time.",
    val dataCategories: String = "Data used",
    val retention: (Int) -> String = { days -> "Kept for up to $days days" },
    val acceptSelected: String = "Accept selected",
    val rejectAll: String = "Reject all",
    val selectAtLeastOne: String = "Switch on at least one purpose, or choose Reject all.",
    val loading: String = "Loading",
    val retry: String = "Try again",
    val poweredBy: String = "Consent managed by Consenzy",
    val purposeSwitchDescription: (String) -> String = { name -> "Consent for $name" },
    /** Shown on the sheet and manager when the session was minted with `acting_as=guardian`. */
    val actingAsGuardian: (Principal) -> String = {
        "You are acting as guardian for this account holder. Consent you give or withdraw here is recorded " +
            "as given by their parent or lawful guardian."
    },
    /** Shown instead of active consent buttons when the session lacks `consents:write`. */
    val consentNotPermitted: String = "You can read this notice, but consent cannot be given or withdrawn in this session.",
    // consent manager
    val managerTitle: String = "Your consents",
    val noActiveConsents: String = "You have no active consents.",
    val givenOn: (String) -> String = { date -> "Given on $date" },
    val withdraw: String = "Withdraw",
    val withdrawDescription: (String) -> String = { name -> "Withdraw consent for $name" },
    val withdrawConfirmTitle: String = "Withdraw consent?",
    val withdrawConfirmBody: (String) -> String = { name ->
        "We will stop processing your data for \"$name\". This does not affect processing already done."
    },
    val withdrawReasonLabel: String = "Reason (optional)",
    val cancel: String = "Cancel",
    // rights requests
    val rightsTitle: String = "Your data rights",
    val requestTypeLabel: String = "What would you like to do?",
    val requestTypeName: (RequestType) -> String = ::defaultRequestTypeName,
    val requestStatusName: (RequestStatus) -> String = ::defaultRequestStatusName,
    val noteLabel: String = "Details (optional)",
    val correctionFieldLabel: String = "Which detail (e.g. address)",
    val correctionValueLabel: String = "Correct value",
    val erasureReasonLabel: String = "Reason (optional)",
    val grievanceLabel: String = "Describe your grievance",
    val nomineeNameLabel: String = "Nominee's full name",
    val nomineeRelationshipLabel: String = "Relationship to you",
    val nomineeEmailLabel: String = "Nominee's email",
    val nomineePhoneLabel: String = "Nominee's phone (+91…)",
    val submitRequest: String = "Submit request",
    val requestSubmitted: (String) -> String = { due -> "Request received. We will respond by $due." },
    val previousRequests: String = "Previous requests",
    val dueBy: (String) -> String = { due -> "Due by $due" },
    /**
     * Where a previous request was received (`DataRequest.channel`: app, web, email, whatsapp,
     * sms, phone, branch, other), e.g. "via WhatsApp". Requests filed through your backend on
     * other channels appear in the same list.
     */
    val requestChannelName: (String) -> String = ::defaultRequestChannelName,
    /** Shown instead of the form when the session lacks `requests:write`. */
    val requestsNotPermitted: String = "Data requests cannot be filed in this session.",
    // breach notices
    val breachNoticesTitle: String = "Data breach notices",
    val breachNoticesIntro: String = "We are required to tell you about personal data breaches that affect you.",
    val noBreachNotices: String = "There are no data breach notices for you.",
    val breachReceivedOn: (String) -> String = { date -> "Received on $date" },
    val breachDataAffected: (String) -> String = { categories -> "Data affected: $categories" },
    val breachAcknowledge: String = "I have read this",
    val breachAcknowledgeDescription: (String) -> String = { subject -> "I have read this: $subject" },
    val breachAcknowledgedOn: (String) -> String = { date -> "You read this on $date" },
    val breachUnread: String = "Unread",
    val breachBadgeDescription: (Int) -> String = { n ->
        if (n == 1) "1 unread data breach notice" else "$n unread data breach notices"
    },
    // errors
    val networkError: String = "Could not connect. Check your internet connection and try again.",
    val sessionError: String = "Your session has expired. Please sign in again.",
    val genericError: String = "Something went wrong. Please try again.",
    val notPermitted: String = "This action is not available in this session.",
    /** HTTP 422: a child or a person with disability, and the session is not a guardian session. */
    val guardianRequired: String = "A parent or guardian must give this consent.",
    val guardianVerificationPending: String =
        "The parent or guardian must be verified before this consent can be given.",
) {
    /** Human-readable message for [error]. */
    public fun errorMessage(error: ConsentError): String = when (error) {
        ConsentError.Network -> networkError
        is ConsentError.Session -> sessionError
        is ConsentError.Api -> if (error.status == 401) sessionError else if (error.status in 400..499) error.detail else genericError
        ConsentError.NothingSelected -> selectAtLeastOne
        is ConsentError.NotPermitted -> notPermitted
        is ConsentError.GuardianRequired -> if (error.verificationPending) guardianVerificationPending else guardianRequired
        is ConsentError.Invalid -> error.message
        is ConsentError.Unexpected -> genericError
    }
}

internal fun defaultRequestTypeName(type: RequestType): String = when (type) {
    RequestType.ACCESS -> "Get a summary of my data"
    RequestType.CORRECTION -> "Correct my data"
    RequestType.COMPLETION -> "Complete my data"
    RequestType.UPDATING -> "Update my data"
    RequestType.ERASURE -> "Erase my data"
    RequestType.GRIEVANCE -> "Raise a grievance"
    RequestType.NOMINATION -> "Nominate someone"
    RequestType.UNKNOWN -> "Other"
}

internal fun defaultRequestStatusName(status: RequestStatus): String = when (status) {
    RequestStatus.RECEIVED -> "Received"
    RequestStatus.IN_PROGRESS -> "In progress"
    RequestStatus.AWAITING_PRINCIPAL -> "Waiting for you"
    RequestStatus.COMPLETED -> "Completed"
    RequestStatus.REJECTED -> "Rejected"
    RequestStatus.WITHDRAWN -> "Withdrawn"
    RequestStatus.UNKNOWN -> "Unknown"
}

internal fun defaultRequestChannelName(channel: String): String = when (channel) {
    "app" -> "via the app"
    "web" -> "via the website"
    "email" -> "via email"
    "whatsapp" -> "via WhatsApp"
    "sms" -> "via SMS"
    "phone" -> "by phone"
    "branch" -> "at a branch"
    else -> "via another channel"
}

/**
 * Theme applied to the components. Defaults to the surrounding [MaterialTheme], so the SDK
 * picks up the app's theme; pass explicit values to brand the consent UI separately.
 * No remote fonts or images are ever loaded.
 */
@Immutable
public data class ConsentTheme(
    val colorScheme: ColorScheme? = null,
    val typography: Typography? = null,
    val shapes: Shapes? = null,
)

@Composable
internal fun ApplyConsentTheme(theme: ConsentTheme, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = theme.colorScheme ?: MaterialTheme.colorScheme,
        typography = theme.typography ?: MaterialTheme.typography,
        shapes = theme.shapes ?: MaterialTheme.shapes,
        content = content,
    )
}

/** First 10 characters of an ISO-8601 timestamp (yyyy-MM-dd); avoids java.time on API < 26. */
internal fun isoDate(timestamp: String?): String = timestamp?.take(10).orEmpty()
