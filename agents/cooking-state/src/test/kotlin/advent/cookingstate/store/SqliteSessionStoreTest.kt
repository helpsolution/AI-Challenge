package advent.cookingstate.store

import advent.cookingstate.agent.CookingContext
import advent.cookingstate.agent.CookingState
import advent.cookingstate.agent.Recipe
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.nio.file.Files

class SqliteSessionStoreTest {
    @Test
    fun `restores state after reconnect and rejects stale write`() {
        val file = Files.createTempFile("cooking-state-", ".db")
        val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
        try {
            val firstDb = SingleConnectionDataSource("jdbc:sqlite:$file", true)
            val first = SqliteSessionStore(JdbcTemplate(firstDb), mapper)
            val created = first.create()
            val updated = created.copy(state = CookingState.COOKING,
                context = CookingContext(ingredients = listOf("яйца"), timeMinutes = 15,
                    recipe = Recipe("Омлет", listOf("Взбей яйца", "Приготовь омлет")), currentStepIndex = 1), version = 1)
            assertEquals(true, first.save(0, updated))
            firstDb.destroy()

            val secondDb = SingleConnectionDataSource("jdbc:sqlite:$file", true)
            val second = SqliteSessionStore(JdbcTemplate(secondDb), mapper)
            assertEquals(CookingState.COOKING, second.find(created.id)?.state)
            assertEquals(listOf("яйца"), second.find(created.id)?.context?.ingredients)
            assertEquals(1, second.find(created.id)?.context?.currentStepIndex)
            assertEquals("Омлет", second.find(created.id)?.context?.recipe?.name)
            assertFalse(second.save(0, updated.copy(version = 2)))
            secondDb.destroy()
        } finally {
            Files.deleteIfExists(file)
        }
    }
}
