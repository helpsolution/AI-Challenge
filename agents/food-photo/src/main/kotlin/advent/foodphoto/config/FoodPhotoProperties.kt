package advent.foodphoto.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "food-photo")
data class FoodPhotoProperties(val maxImageBytes: Long = 5L * 1024 * 1024)
