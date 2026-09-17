package advent.day14.web

import advent.day14.inspection.AgentInspection
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/sessions/{sessionId}/inspection")
class InspectionController(private val inspection: AgentInspection) {
    @GetMapping
    fun view(@PathVariable sessionId: Long) = mapOf(
        "state" to inspection.state(sessionId),
        "agent" to inspection.properties,
        "invariants" to advent.day14.invariant.InvariantPolicy.DEFAULT,
        "traces" to inspection.store.list(sessionId),
    )

    @GetMapping("/traces/{traceId}")
    fun trace(@PathVariable sessionId: Long, @PathVariable traceId: String) =
        inspection.store.get(sessionId, traceId)
}
