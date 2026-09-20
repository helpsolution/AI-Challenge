package advent.foodphoto.web

import advent.foodphoto.agent.FoodDescription
import advent.foodphoto.agent.FoodPhotoAgent
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api")
@Tag(name = "Еда по фото")
class FoodPhotoController(private val agent: FoodPhotoAgent) {
    @PostMapping(
        "/food/describe",
        consumes = [MediaType.MULTIPART_FORM_DATA_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    @Operation(summary = "Описать еду на фотографии")
    fun describe(
        @Parameter(description = "Фотография JPEG, PNG или WebP, не более 5 МБ")
        @RequestPart("image") image: MultipartFile,
    ): FoodDescription = agent.describe(image.bytes, image.contentType)
}
