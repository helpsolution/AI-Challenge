package advent.files.agent

import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import kotlin.test.*

class DocumentationChecksTest {
    private val json = JsonMapper.builder().build()

    @Test fun `no drift report is a valid result without forced documentation changes`() {
        val result = json.readTree("""{"readFiles":["README.md","src/app.py"],"changes":[{"path":"docs/sync-report.md","content":"Расхождений нет"}],"sourceChangedPaths":[],"patchApplicable":true}""")
        assertTrue(DocumentationChecks.warnings(result).isEmpty())
    }

    @Test fun `missing code reading report and invalid patch prevent completion`() {
        val result = json.readTree("""{"readFiles":["README.md"],"changes":[],"sourceChangedPaths":[],"patchApplicable":false,"patchError":"bad patch"}""")
        assertEquals(3, DocumentationChecks.warnings(result).size)
    }

    @Test fun `changed source is not reported as a current completed sync`() {
        val result = json.readTree("""{"readFiles":["src/App.kt"],"changes":[{"path":"docs/sync-report.md","content":"Report"}],"sourceChangedPaths":["src/App.kt"],"patchApplicable":true}""")
        assertEquals(1, DocumentationChecks.warnings(result).size)
    }
}
