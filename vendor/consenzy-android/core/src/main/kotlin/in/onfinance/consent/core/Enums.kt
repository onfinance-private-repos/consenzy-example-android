// Copyright 2026 OnFinance. SPDX-License-Identifier: Apache-2.0
package `in`.onfinance.consent.core

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** An enum with a stable wire value. */
public interface WireEnum {
    public val wire: String
}

/**
 * Serialises a [WireEnum] by its wire value and maps values this SDK version does not know
 * to [fallback], so a newer server never crashes an older app.
 */
internal abstract class WireEnumSerializer<T>(
    name: String,
    private val values: Array<T>,
    private val fallback: T,
) : KSerializer<T> where T : Enum<T>, T : WireEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("in.onfinance.consent.$name", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: T) {
        require(value != fallback) { "cannot send ${value.name} to the server" }
        encoder.encodeString(value.wire)
    }
    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return values.firstOrNull { it.wire == raw } ?: fallback
    }
}

/** Who a principal session acts as (`acting_as`). */
@Serializable(with = ActingAs.Serializer::class)
public enum class ActingAs(override val wire: String) : WireEnum {
    DATA_PRINCIPAL("data_principal"),
    /** A parent or lawful guardian of a child or a person with disability (s.9, s.2(j)). */
    GUARDIAN("guardian"),
    UNKNOWN("unknown");

    internal object Serializer : WireEnumSerializer<ActingAs>("ActingAs", entries.toTypedArray(), UNKNOWN)
}

/** Lifecycle state of one consent artefact. */
@Serializable(with = ConsentStatus.Serializer::class)
public enum class ConsentStatus(override val wire: String) : WireEnum {
    ACTIVE("active"),
    WITHDRAWN("withdrawn"),
    SUPERSEDED("superseded"),
    UNKNOWN("unknown");

    internal object Serializer : WireEnumSerializer<ConsentStatus>("ConsentStatus", entries.toTypedArray(), UNKNOWN)
}

/** The Data Principal rights under DPDP Act ss.11-14. */
@Serializable(with = RequestType.Serializer::class)
public enum class RequestType(override val wire: String) : WireEnum {
    /** s.11: summary of personal data and processing. */
    ACCESS("access"),
    /** s.12(1): correction of inaccurate or misleading data. */
    CORRECTION("correction"),
    /** s.12(1): completion of incomplete data. */
    COMPLETION("completion"),
    /** s.12(1): updating of data. */
    UPDATING("updating"),
    /** s.12(3): erasure. */
    ERASURE("erasure"),
    /** s.13: grievance redressal. */
    GRIEVANCE("grievance"),
    /** s.14: nominate a person to exercise rights on death or incapacity. */
    NOMINATION("nomination"),
    UNKNOWN("unknown");

    internal object Serializer : WireEnumSerializer<RequestType>("RequestType", entries.toTypedArray(), UNKNOWN)

    public companion object {
        /** Request types a principal can file (excludes [UNKNOWN]). */
        public val fileable: List<RequestType> = entries.filter { it != UNKNOWN }
    }
}

/** Workflow state of a rights request. */
@Serializable(with = RequestStatus.Serializer::class)
public enum class RequestStatus(override val wire: String) : WireEnum {
    RECEIVED("received"),
    IN_PROGRESS("in_progress"),
    AWAITING_PRINCIPAL("awaiting_principal"),
    COMPLETED("completed"),
    REJECTED("rejected"),
    WITHDRAWN("withdrawn"),
    UNKNOWN("unknown");

    public val isOpen: Boolean get() = this == RECEIVED || this == IN_PROGRESS || this == AWAITING_PRINCIPAL

    internal object Serializer : WireEnumSerializer<RequestStatus>("RequestStatus", entries.toTypedArray(), UNKNOWN)
}

/**
 * English plus the 22 languages of the Eighth Schedule (DPDP s.5(3)): a notice must be
 * available in any of them on request. [nativeName] is the endonym used by language pickers.
 */
@Serializable(with = Language.Serializer::class)
public enum class Language(override val wire: String, public val englishName: String, public val nativeName: String) : WireEnum {
    EN("en", "English", "English"),
    AS("as", "Assamese", "অসমীয়া"),
    BN("bn", "Bengali", "বাংলা"),
    BRX("brx", "Bodo", "बड़ो"),
    DOI("doi", "Dogri", "डोगरी"),
    GU("gu", "Gujarati", "ગુજરાતી"),
    HI("hi", "Hindi", "हिन्दी"),
    KN("kn", "Kannada", "ಕನ್ನಡ"),
    KS("ks", "Kashmiri", "كٲشُر"),
    KOK("kok", "Konkani", "कोंकणी"),
    MAI("mai", "Maithili", "मैथिली"),
    ML("ml", "Malayalam", "മലയാളം"),
    MNI("mni", "Manipuri", "মৈতৈলোন্"),
    MR("mr", "Marathi", "मराठी"),
    NE("ne", "Nepali", "नेपाली"),
    OR("or", "Odia", "ଓଡ଼ିଆ"),
    PA("pa", "Punjabi", "ਪੰਜਾਬੀ"),
    SA("sa", "Sanskrit", "संस्कृतम्"),
    SAT("sat", "Santali", "ᱥᱟᱱᱛᱟᱲᱤ"),
    SD("sd", "Sindhi", "سنڌي"),
    TA("ta", "Tamil", "தமிழ்"),
    TE("te", "Telugu", "తెలుగు"),
    UR("ur", "Urdu", "اردو"),
    UNKNOWN("unknown", "Unknown", "Unknown");

    internal object Serializer : WireEnumSerializer<Language>("Language", entries.toTypedArray(), UNKNOWN)

    public companion object {
        /** Languages a notice can be requested in (excludes [UNKNOWN]). */
        public val supported: List<Language> = entries.filter { it != UNKNOWN }

        /** Look up by ISO code (e.g. "hi"); null when unsupported. */
        public fun fromCode(code: String?): Language? = supported.firstOrNull { it.wire == code }
    }
}
