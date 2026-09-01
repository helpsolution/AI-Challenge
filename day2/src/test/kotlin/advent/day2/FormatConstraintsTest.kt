package advent.day2

import advent.day2.format.ComplianceChecker
import advent.day2.format.Constraints
import advent.day2.format.JsonStructure
import advent.day2.format.PromptBuilder
import advent.day2.format.ResponseFormat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import tools.jackson.databind.ObjectMapper
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
class FormatConstraintsTest {

    @Autowired lateinit var promptBuilder: PromptBuilder
    @Autowired lateinit var checker: ComplianceChecker
    @Autowired lateinit var objectMapper: ObjectMapper

    @Test
    fun `без ограничений system-prompt не собирается`() {
        assertNull(promptBuilder.build(Constraints.NONE))
    }

    @Test
    fun `в промпт попадают только включённые ограничения`() {
        val prompt = promptBuilder.build(
            Constraints(format = ResponseFormat.BULLETS, maxItems = 5, maxWords = 60),
        )!!

        assertTrue(prompt.contains("маркированный список"), prompt)
        assertTrue(prompt.contains("не больше 5"), prompt)
        assertTrue(prompt.contains("не более 60 слов"), prompt)
        assertFalse(prompt.contains("ЗАВЕРШЕНИЕ"), "маркер не включали — инструкции быть не должно")
    }

    @Test
    fun `инструкция завершения использует заданный маркер`() {
        val prompt = promptBuilder.build(
            Constraints(endMarkerInstruction = true, endMarker = "КОНЕЦ"),
        )!!
        assertTrue(prompt.contains("маркер КОНЕЦ"), prompt)
    }

    @Test
    fun `структурная подпись не зависит от значений, но зависит от формы`() {
        val a = JsonStructure.parse(objectMapper, """{"dish":"борщ","ingredients":[{"name":"свёкла","g":300}]}""")!!
        val b = JsonStructure.parse(objectMapper, """{"dish":"уха","ingredients":[{"name":"судак","g":500}]}""")!!
        val c = JsonStructure.parse(objectMapper, """{"dish":"уха","ingredients":["судак"]}""")!!

        assertEquals(JsonStructure.signature(a), JsonStructure.signature(b))
        assertNotEquals(JsonStructure.signature(a), JsonStructure.signature(c))
    }

    @Test
    fun `JSON в markdown-обёртке разбирается, но считается нарушением`() {
        val answer = "```json\n{\"dish\":\"борщ\"}\n```"
        val report = checker.check(answer, Constraints(format = ResponseFormat.JSON), "stop")

        assertTrue(report.jsonValid == true)
        assertTrue(report.violations.any { it.contains("markdown") }, report.violations.toString())
    }

    @Test
    fun `несовпадение со схемой видно по именам полей`() {
        val constraints = Constraints(
            format = ResponseFormat.JSON,
            jsonSchema = """{"dish":"string","ingredients":[{"name":"string","weightGrams":"number"}]}""",
        )
        val answer = """{"dish":"борщ","ingredients":[{"name":"свёкла","weight":300}]}"""

        val report = checker.check(answer, constraints, "stop")

        assertEquals(listOf("ingredients[].weightGrams"), report.schemaMissing)
        assertEquals(listOf("ingredients[].weight"), report.schemaExtra)
        assertFalse(report.passed)
    }

    @Test
    fun `свободный ответ нарушает лимит слов, а короткий проходит`() {
        val constraints = Constraints(maxWords = 5)

        val long = checker.check("одно два три четыре пять шесть", constraints, "stop")
        assertFalse(long.passed)
        assertTrue(long.violations.single().contains("Слов 6 при лимите 5"))

        val short = checker.check("одно два три", constraints, "stop")
        assertTrue(short.passed)
        assertEquals(3, short.words)
    }

    @Test
    fun `обрезанный по лимиту токенов ответ помечается нарушением`() {
        val report = checker.check("текст оборван на полу", Constraints(maxTokens = 10), "length")
        assertTrue(report.truncated)
        assertTrue(report.violations.any { it.contains("max_tokens") })
    }

    @Test
    fun `маркер засчитывается только в конце ответа`() {
        val constraints = Constraints(endMarkerInstruction = true, endMarker = "###")

        val heading = checker.check("### Шаг 1\nдлинный свободный текст", constraints, "stop")
        assertFalse(heading.endMarkerFound!!, "### в заголовке markdown — не маркер завершения")
        assertFalse(heading.passed)

        val proper = checker.check("короткий ответ\n###", constraints, "stop")
        assertTrue(proper.endMarkerFound!!)
        assertTrue(proper.passed)
    }

    @Test
    fun `срезанный стоп-последовательностью ответ не считается нарушением`() {
        val constraints = Constraints(stopSequence = "###", endMarkerInstruction = true, endMarker = "###")
        val report = checker.check("готовый ответ без маркера", constraints, "stop")

        assertFalse(report.endMarkerFound!!)
        assertTrue(report.passed, "маркер срезал сам провайдер — это ожидаемое поведение")
    }
}
