package advent.foodphoto.agent

import advent.foodphoto.config.FoodPhotoProperties
import advent.foodphoto.llm.VisionClient
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule

class FoodPhotoAgentTest {
    private val mapper = JsonMapper.builder().addModule(kotlinModule()).build()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)

    @Test
    fun `returns structured food description from one vision call`() {
        var calls = 0
        val agent = FoodPhotoAgent(FoodPhotoProperties(), VisionClient { bytes, mimeType ->
            calls++
            assertEquals("image/jpeg", mimeType)
            assertEquals(jpeg.toList(), bytes.toList())
            """{"dishName":"Паста","description":"На тарелке видна паста.","likelyIngredients":["паста"]}"""
        }, mapper)

        val result = agent.describe(jpeg, "image/jpeg")

        assertEquals("Паста", result.dishName)
        assertEquals(listOf("паста"), result.likelyIngredients)
        assertEquals(1, calls)
    }

    @Test
    fun `rejects wrong file before calling model`() {
        val agent = FoodPhotoAgent(FoodPhotoProperties(), VisionClient { _, _ ->
            error("Модель не должна вызываться")
        }, mapper)

        assertThrows(IllegalArgumentException::class.java) {
            agent.describe("not an image".toByteArray(), "image/jpeg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            agent.describe(jpeg, "image/png")
        }
    }
}
