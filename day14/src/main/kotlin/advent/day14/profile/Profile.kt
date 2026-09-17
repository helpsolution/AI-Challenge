package advent.day14.profile

import java.time.Instant

data class Profile(
    val id: Long,
    val name: String,
    val description: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

interface ProfileStore {
    fun profiles(): List<Profile>
    fun profile(id: Long): Profile?
    fun requireProfile(id: Long): Profile =
        profile(id) ?: throw IllegalArgumentException("Профиль $id не найден")

    fun createProfile(name: String, description: String): Profile
    fun updateProfile(id: Long, name: String, description: String): Profile
    fun deleteProfile(id: Long)
}
