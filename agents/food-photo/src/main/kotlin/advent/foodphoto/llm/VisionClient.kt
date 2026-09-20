package advent.foodphoto.llm

fun interface VisionClient {
    fun describe(image: ByteArray, mimeType: String): String
}
