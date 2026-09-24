package com.mdmesh.core.command.handlers

import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.policy.PolicyOutcome
import com.mdmesh.policy.TogglePolicy
import com.mdmesh.policy.dns.DnsPolicy
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.ProtocolJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * `policy.apply` — applies a policy to a value.
 * Boolean toggles (e.g. `{ "policy": "wifi", "value": false }`) and String policies
 * (e.g. `{ "policy": "dns", "value": "dns.google" }`).
 */
class PolicyApplyHandler(
    private val toggles: Map<String, TogglePolicy>,
    private val dnsPolicy: DnsPolicy? = null,
) : CommandHandler {

    override val type: String = "policy.apply"

    @Serializable
    private data class Payload(
        val policy: String,
        val value: JsonElement? = null,
    )

    override suspend fun handle(command: CommandEnvelope): CommandResult {
        val payload = command.payload
            ?: return CommandResults.failed(command, "policy.apply requires a payload")

        val parsed = runCatching {
            ProtocolJson.json.decodeFromJsonElement(Payload.serializer(), payload)
        }.getOrElse { return CommandResults.failed(command, "bad payload: ${it.message}") }

        if (parsed.policy == "dns") {
            val policy = dnsPolicy
                ?: return CommandResults.unsupported(command, "dns policy not supported on this device")

            val hostStr = when (val v = parsed.value) {
                is JsonPrimitive -> {
                    if (v.isString) {
                        v.content
                    } else if (v.booleanOrNull == false) {
                        null
                    } else {
                        v.content
                    }
                }
                else -> null
            }

            return when (val outcome = policy.setDnsHost(hostStr)) {
                PolicyOutcome.Applied -> CommandResults.done(command)
                PolicyOutcome.Unsupported -> CommandResults.unsupported(command)
                is PolicyOutcome.Failed -> CommandResults.failed(command, outcome.reason)
            }
        }

        val toggle = toggles[parsed.policy]
            ?: return CommandResults.unsupported(command, "policy not supported: ${parsed.policy}")

        val boolVal = when (val v = parsed.value) {
            is JsonPrimitive -> v.booleanOrNull
            else -> null
        } ?: return CommandResults.failed(command, "boolean value required for toggle policy ${parsed.policy}")

        return when (val outcome = toggle.setEnabled(boolVal)) {
            PolicyOutcome.Applied -> CommandResults.done(command)
            PolicyOutcome.Unsupported -> CommandResults.unsupported(command)
            is PolicyOutcome.Failed -> CommandResults.failed(command, outcome.reason)
        }
    }
}
