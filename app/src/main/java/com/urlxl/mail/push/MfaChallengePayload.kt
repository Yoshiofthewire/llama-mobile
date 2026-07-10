package com.urlxl.mail.push

data class MfaChallengePayload(
    val challengeId: String,
)

object MfaChallengePayloadParser {
    private const val TYPE_MFA_CHALLENGE = "mfa_challenge"

    fun parse(data: Map<String, String>): MfaChallengePayload? {
        if (data["type"] != TYPE_MFA_CHALLENGE) return null
        val challengeId = data["challengeId"].orEmpty().trim()
        if (challengeId.isBlank()) return null
        return MfaChallengePayload(challengeId = challengeId)
    }

    fun parse(bundle: android.os.Bundle): MfaChallengePayload? {
        if (bundle.getString("type") != TYPE_MFA_CHALLENGE) return null
        val challengeId = bundle.getString("challengeId").orEmpty().trim()
        if (challengeId.isBlank()) return null
        return MfaChallengePayload(challengeId = challengeId)
    }
}
