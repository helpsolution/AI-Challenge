package advent.foodphoto.agent

import advent.foodphoto.config.FoodPhotoProperties
import advent.foodphoto.llm.LlmException
import advent.foodphoto.llm.VisionClient
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

data class FoodDescription(
    val dishName: String?,
    val description: String,
    val likelyIngredients: List<String>,
)

@Service
class FoodPhotoAgent(
    private val properties: FoodPhotoProperties,
    private val visionClient: VisionClient,
    private val objectMapper: ObjectMapper,
) {
    fun describe(image: ByteArray, declaredMimeType: String?): FoodDescription {
        require(image.isNotEmpty()) { "Изображение пустое" }
        require(image.size <= properties.maxImageBytes) {
            "Изображение должно быть не больше ${properties.maxImageBytes / 1024 / 1024} МБ"
        }
        val actualMimeType = detectMimeType(image)
            ?: throw IllegalArgumentException("Поддерживаются только JPEG, PNG и WebP")
        require(declaredMimeType == actualMimeType || declaredMimeType == "application/octet-stream") {
            "Тип файла не совпадает с содержимым изображения"
        }

        val answer = visionClient.describe(image, actualMimeType)
        val parsed = try {
            objectMapper.readValue(answer, FoodDescription::class.java)
        } catch (e: Exception) {
            throw LlmException("Модель вернула некорректное описание блюда", e)
        }
        if (parsed.description.isBlank() || parsed.likelyIngredients.any { it.isBlank() }) {
            throw LlmException("Модель вернула неполное описание блюда")
        }
        return parsed
    }

    private fun detectMimeType(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte() -> "image/jpeg"
        bytes.size >= 8 && bytes.sliceArray(0..7).contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) -> "image/png"
        bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
        else -> null
    }
}
