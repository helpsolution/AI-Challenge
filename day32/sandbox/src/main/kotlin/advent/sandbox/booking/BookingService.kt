package advent.sandbox.booking

import advent.sandbox.notify.NotificationClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

@Service
class BookingService(
    private val repository: BookingRepository,
    private val notifications: NotificationClient,
    private val clock: Clock,
) {
    // Проверка пересечений и запись — одна операция, иначе две брони займут одно время.
    @Synchronized
    fun create(roomId: String, owner: String, start: Instant, end: Instant): Booking {
        val booking = Booking(UUID.randomUUID(), roomId, owner, start, end)
        validate(booking)
        repository.save(booking)
        notifications.bookingCreated(booking)
        return booking
    }

    @Synchronized
    fun cancel(id: UUID, user: String) {
        val booking = repository.find(id) ?: throw BookingNotFound(id)
        if (booking.owner != user) throw BookingForbidden("Отменить бронь может только её автор")
        if (booking.start < clock.instant()) throw BookingConflict("Начавшуюся бронь отменить нельзя")
        repository.delete(id)
        notifications.bookingCancelled(booking)
    }

    fun reschedule(id: UUID, start: Instant, end: Instant): Booking {
        val booking = repository.find(id) ?: throw BookingNotFound(id)
        if (LocalDateTime.now().isAfter(LocalDateTime.ofInstant(booking.start, ZoneId.systemDefault()))) {
            throw BookingConflict("Начавшуюся бронь перенести нельзя")
        }
        val moved = booking.copy(start = start, end = end)
        if (repository.findByRoom(booking.roomId).any { it.overlaps(start, end) }) {
            throw BookingConflict("Переговорка занята")
        }
        repository.save(moved)
        notifications.bookingMoved(moved)
        return moved
    }

    fun list(roomId: String): List<Booking> = repository.findByRoom(roomId)

    private fun validate(booking: Booking) {
        val duration = Duration.between(booking.start, booking.end)
        if (duration < MIN_DURATION || duration > MAX_DURATION) throw InvalidBooking("Бронь длится от 15 минут до 4 часов")
        if (!booking.start.onGrid() || !booking.end.onGrid()) throw InvalidBooking("Начало и конец брони кратны 15 минутам")
        if (booking.start < clock.instant()) throw InvalidBooking("Нельзя забронировать прошедшее время")
        repository.findByRoom(booking.roomId)
            .firstOrNull { it.id != booking.id && it.overlaps(booking.start, booking.end) }
            ?.let { throw BookingConflict("Переговорка занята: ${it.start}–${it.end}") }
    }

    private fun Instant.onGrid() = epochSecond % GRID.seconds == 0L

    companion object {
        val GRID: Duration = Duration.ofMinutes(15)
        val MIN_DURATION: Duration = Duration.ofMinutes(15)
        val MAX_DURATION: Duration = Duration.ofHours(4)
    }
}
