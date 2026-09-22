package advent.jevagent.dto

data class AnalysisRequest(val text: String = "")

data class AnalysisResponse(
    val endpoint: String,
    val requestJson: String,
    val responseJson: String?,
    val upstreamStatus: Int?,
    val durationMs: Long,
    val error: String? = null,
)

data class StatusResponse(val configured: Boolean, val model: String)

data class InputError(val error: String)
