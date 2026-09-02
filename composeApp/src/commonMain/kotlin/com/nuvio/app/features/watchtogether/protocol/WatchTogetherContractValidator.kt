package com.nuvio.app.features.watchtogether.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

internal data class WatchTogetherValidationIssue(
    val path: String,
    val message: String,
)

internal data class WatchTogetherValidationResult(
    val issues: List<WatchTogetherValidationIssue>,
) {
    val isValid: Boolean
        get() = issues.isEmpty()
}

internal object WatchTogetherContractValidator {
    private const val MANIFEST_SCHEMA = "urn:watch-together:manifest:v1"
    private const val CLIENT_COMMAND_SCHEMA = "urn:watch-together:protocol:v1:client-command"
    private const val SERVER_MESSAGE_SCHEMA = "urn:watch-together:protocol:v1:server-message"

    private val opaqueIdPattern = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val serviceIdPattern = Regex("^[a-z0-9]+(?:[.-][a-z0-9]+)+$")
    private val canonicalOriginPattern = Regex("^https://[^@/?#]+/?$")
    private val secureHttpUrlPattern = Regex("^https://(?![^/?#]*@)[^?#]+$")
    private val secureWebSocketUrlPattern = Regex("^wss://(?![^/?#]*@)[^?#]+$")

    fun validate(manifest: WatchTogetherServiceManifest): WatchTogetherValidationResult =
        collect {
            optionalSchema("\$schema", manifest.schema, MANIFEST_SCHEMA)
            equal("schemaVersion", manifest.schemaVersion, WATCH_TOGETHER_MANIFEST_VERSION)
            check("id", manifest.id.length <= 128 && serviceIdPattern.matches(manifest.id)) {
                "must be a lower-case reverse-domain-style service identifier"
            }
            textLength("name", manifest.name, 1, 80)
            textLength("description", manifest.description, 1, 500)
            matches("canonicalOrigin", manifest.canonicalOrigin, canonicalOriginPattern)
            check("protocolVersions", manifest.protocolVersions.isNotEmpty()) {
                "must contain at least one version"
            }
            unique("protocolVersions", manifest.protocolVersions)
            matches(
                "endpoints.relayWebSocketUrl",
                manifest.endpoints.relayWebSocketUrl,
                secureWebSocketUrlPattern,
            )
            optionalSecureHttp("endpoints.accountLinkUrl", manifest.endpoints.accountLinkUrl)
            optionalSecureHttp("endpoints.statusUrl", manifest.endpoints.statusUrl)
            optionalSecureHttp("endpoints.supportUrl", manifest.endpoints.supportUrl)
            textLength("operator.name", manifest.operator.name, 1, 120)
            matches("operator.websiteUrl", manifest.operator.websiteUrl, secureHttpUrlPattern)
            optionalSecureHttp("operator.contactUrl", manifest.operator.contactUrl)
            matches("privacy.policyUrl", manifest.privacy.policyUrl, secureHttpUrlPattern)
            check("privacy.contentBlind", manifest.privacy.contentBlind) {
                "must be true"
            }
            unique(
                "privacy.operationalDataCategories",
                manifest.privacy.operationalDataCategories,
            )
            when (manifest.authentication.host.mode) {
                WatchTogetherHostAuthenticationMode.None -> {
                    check(
                        "authentication.host.accountRequired",
                        !manifest.authentication.host.accountRequired,
                    ) { "must be false when host authentication mode is none" }
                }

                WatchTogetherHostAuthenticationMode.EmailOtpDeviceLink -> {
                    check(
                        "authentication.host.accountRequired",
                        manifest.authentication.host.accountRequired,
                    ) { "must be true for email OTP device linking" }
                    check(
                        "endpoints.accountLinkUrl",
                        manifest.endpoints.accountLinkUrl != null,
                    ) { "is required for email OTP device linking" }
                }
            }
            check(
                "authentication.guest.accountRequired",
                !manifest.authentication.guest.accountRequired,
            ) { "must be false for room-credential guests" }
            check("capabilities", manifest.capabilities.isNotEmpty()) {
                "must contain at least one capability"
            }
            unique("capabilities", manifest.capabilities)
        }

    fun validate(command: WatchTogetherClientCommand): WatchTogetherValidationResult =
        collect {
            optionalSchema("\$schema", command.schema, CLIENT_COMMAND_SCHEMA)
            equal("protocolVersion", command.protocolVersion, WATCH_TOGETHER_PROTOCOL_VERSION)
            opaqueId("messageId", command.messageId)
            opaqueId("roomId", command.roomId)
            opaqueId("roundId", command.roundId)
            opaqueId("participantId", command.participantId)
            opaqueId("sessionId", command.sessionId)
            positiveSafeInteger("sequence", command.sequence)
            nonNegativeSafeInteger("sentAtMs", command.sentAtMs)

            when (command.type) {
                WatchTogetherClientCommandType.PlaybackPause,
                WatchTogetherClientCommandType.PlaybackResume,
                -> check("payload", command.payload.isEmpty()) { "must be empty" }

                WatchTogetherClientCommandType.PlaybackSeek -> {
                    decode<WatchTogetherSeekPayload>("payload", command.payload)?.let { payload ->
                        nonNegativeSafeInteger("payload.positionMs", payload.positionMs)
                    }
                }

                WatchTogetherClientCommandType.ParticipantReadiness -> {
                    decode<WatchTogetherReadinessPayload>("payload", command.payload)
                        ?.durationMs
                        ?.let { durationMs -> duration("payload.durationMs", durationMs) }
                }
            }
        }

    fun validate(message: WatchTogetherServerMessage): WatchTogetherValidationResult =
        collect {
            optionalSchema("\$schema", message.schema, SERVER_MESSAGE_SCHEMA)
            equal("protocolVersion", message.protocolVersion, WATCH_TOGETHER_PROTOCOL_VERSION)
            opaqueId("messageId", message.messageId)
            opaqueId("roomId", message.roomId)
            nonNegativeSafeInteger("revision", message.revision)
            nonNegativeSafeInteger("relayTimeMs", message.relayTimeMs)

            when (message.type) {
                WatchTogetherServerMessageType.CommandAccepted -> {
                    positiveSafeInteger("revision", message.revision)
                    decode<WatchTogetherCommandAcceptedPayload>("payload", message.payload)
                        ?.let { payload ->
                            opaqueId("payload.commandMessageId", payload.commandMessageId)
                        }
                }

                WatchTogetherServerMessageType.CommandRejected -> {
                    decode<WatchTogetherCommandRejectedPayload>("payload", message.payload)
                        ?.let { payload ->
                            opaqueId("payload.commandMessageId", payload.commandMessageId)
                        }
                }

                WatchTogetherServerMessageType.StateSnapshot -> {
                    positiveSafeInteger("revision", message.revision)
                    decode<WatchTogetherStateSnapshotPayload>("payload", message.payload)
                        ?.state
                        ?.let { state ->
                            validateState(state, "payload.state")
                            equal("payload.state.roomId", state.roomId, message.roomId)
                            equal(
                                "payload.state.protocolVersion",
                                state.protocolVersion,
                                message.protocolVersion,
                            )
                            equal("payload.state.revision", state.revision, message.revision)
                            check(
                                "relayTimeMs",
                                message.relayTimeMs >= state.round.playback.anchorRelayTimeMs,
                            ) { "cannot precede the canonical playback anchor" }
                        }
                }
            }
        }

    fun validate(state: WatchTogetherRoomState): WatchTogetherValidationResult =
        collect { validateState(state, path = "") }

    private fun Collector.validateState(
        state: WatchTogetherRoomState,
        path: String,
    ) {
        fun field(name: String): String = if (path.isEmpty()) name else "$path.$name"

        equal(field("protocolVersion"), state.protocolVersion, WATCH_TOGETHER_PROTOCOL_VERSION)
        opaqueId(field("roomId"), state.roomId)
        positiveSafeInteger(field("revision"), state.revision)
        nonNegativeSafeInteger(field("createdAtMs"), state.createdAtMs)
        positiveSafeInteger(field("expiresAtMs"), state.expiresAtMs)
        check(field("expiresAtMs"), state.expiresAtMs > state.createdAtMs) {
            "must follow createdAtMs"
        }
        check(field("capacity"), state.capacity in 2..8) { "must be between 2 and 8" }
        opaqueId(field("hostParticipantId"), state.hostParticipantId)
        positiveSafeInteger(field("admission.inviteGeneration"), state.admission.inviteGeneration)
        check(
            field("participants"),
            state.participants.isNotEmpty() &&
                state.participants.size <= state.capacity &&
                state.participants.size <= 8,
        ) { "must contain between one participant and the configured capacity" }
        unique(
            field("participants.participantId"),
            state.participants.map(WatchTogetherParticipantState::participantId),
        )

        val hosts = state.participants.filter { it.role == WatchTogetherParticipantRole.Host }
        check(field("participants"), hosts.size == 1) { "must contain exactly one host" }
        check(
            field("hostParticipantId"),
            hosts.singleOrNull()?.participantId == state.hostParticipantId,
        ) { "must identify the participant whose role is host" }

        state.participants.forEachIndexed { index, participant ->
            val participantPath = field("participants[$index]")
            opaqueId("$participantPath.participantId", participant.participantId)
            displayName("$participantPath.displayName", participant.displayName)
            opaqueId("$participantPath.readiness.roundId", participant.readiness.roundId)
            equal(
                "$participantPath.readiness.roundId",
                participant.readiness.roundId,
                state.round.roundId,
            )
            participant.readiness.durationMs?.let { durationMs ->
                duration("$participantPath.readiness.durationMs", durationMs)
            }
        }

        opaqueId(field("round.roundId"), state.round.roundId)
        positiveSafeInteger(field("round.generation"), state.round.generation)
        nonNegativeSafeInteger(
            field("round.playback.anchorPositionMs"),
            state.round.playback.anchorPositionMs,
        )
        nonNegativeSafeInteger(
            field("round.playback.anchorRelayTimeMs"),
            state.round.playback.anchorRelayTimeMs,
        )
        equal(field("round.playback.rate"), state.round.playback.rate, 1)
    }

    private fun collect(block: Collector.() -> Unit): WatchTogetherValidationResult =
        Collector().apply(block).result()

    private class Collector {
        private val issues = mutableListOf<WatchTogetherValidationIssue>()

        fun result(): WatchTogetherValidationResult = WatchTogetherValidationResult(issues.toList())

        fun check(path: String, condition: Boolean, message: () -> String) {
            if (!condition) issues += WatchTogetherValidationIssue(path, message())
        }

        fun <T> equal(path: String, actual: T, expected: T) {
            check(path, actual == expected) { "must equal $expected" }
        }

        fun opaqueId(path: String, value: String) {
            check(path, opaqueIdPattern.matches(value)) {
                "must be an opaque identifier between 8 and 128 characters"
            }
        }

        fun nonNegativeSafeInteger(path: String, value: Long) {
            check(path, value in 0L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
                "must be a non-negative safe integer"
            }
        }

        fun positiveSafeInteger(path: String, value: Long) {
            check(path, value in 1L..WATCH_TOGETHER_MAX_SAFE_INTEGER) {
                "must be a positive safe integer"
            }
        }

        fun duration(path: String, value: Long) {
            nonNegativeSafeInteger(path, value)
            check(path, value % 1_000L == 0L) { "must be rounded to a whole second" }
        }

        fun displayName(path: String, value: String) {
            val count = value.unicodeCodePointCount()
            check(
                path,
                count in 1..40 &&
                    value.any { !it.isWhitespace() } &&
                    value.none { it.code in 0..31 || it.code == 127 },
            ) { "must contain 1 to 40 visible characters" }
        }

        fun textLength(path: String, value: String, minimum: Int, maximum: Int) {
            check(path, value.unicodeCodePointCount() in minimum..maximum) {
                "must contain between $minimum and $maximum characters"
            }
        }

        fun matches(path: String, value: String, pattern: Regex) {
            check(path, pattern.matches(value)) { "has an invalid secure URL or identifier format" }
        }

        fun optionalSecureHttp(path: String, value: String?) {
            value?.let { matches(path, it, secureHttpUrlPattern) }
        }

        fun optionalSchema(path: String, value: String?, expected: String) {
            value?.let { equal(path, it, expected) }
        }

        fun <T> unique(path: String, values: List<T>) {
            check(path, values.size == values.toSet().size) { "must not contain duplicates" }
        }

        inline fun <reified T> decode(path: String, payload: JsonObject): T? =
            try {
                WatchTogetherJson.decodeFromJsonElement(payload)
            } catch (error: SerializationException) {
                issues += WatchTogetherValidationIssue(
                    path,
                    "does not match the message type: ${error.message}",
                )
                null
            } catch (error: IllegalArgumentException) {
                issues += WatchTogetherValidationIssue(
                    path,
                    "does not match the message type: ${error.message}",
                )
                null
            }
    }
}

private fun String.unicodeCodePointCount(): Int {
    var count = 0
    var index = 0
    while (index < length) {
        val character = this[index]
        index += if (
            character.isHighSurrogate() &&
            index + 1 < length &&
            this[index + 1].isLowSurrogate()
        ) {
            2
        } else {
            1
        }
        count += 1
    }
    return count
}
