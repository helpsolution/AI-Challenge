package advent.jevagent.agent

import advent.jevagent.config.JevProperties
import advent.jevagent.dto.AnalysisResponse
import advent.jevagent.dto.StatusResponse
import advent.jevagent.prompt.SupportPromptBuilder
import advent.jevagent.transport.JevClient
import org.springframework.stereotype.Component

/** Coordinates one support-message decision without owning HTTP or prompt wording. */
@Component
class SupportAnalysisAgent(
    private val promptBuilder: SupportPromptBuilder,
    private val jevClient: JevClient,
    private val properties: JevProperties,
) {
    fun status(): StatusResponse = StatusResponse(jevClient.configured, properties.model)

    fun analyze(message: String): AnalysisResponse {
        val requestJson = promptBuilder.build(message)
        val result = jevClient.send(requestJson)
        return AnalysisResponse(
            endpoint = jevClient.endpoint,
            requestJson = requestJson,
            responseJson = result.responseJson,
            upstreamStatus = result.status,
            durationMs = result.durationMs,
            error = result.error,
        )
    }
}
