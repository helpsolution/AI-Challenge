package advent.sandbox.booking

import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class Slot(val start: Instant, val end: Instant)

@Service
class RoomAvailability(private val repository: BookingRepository) {
    // Свободные окна нужной длины на сетке 15 минут в пределах суток UTC.
    fun freeSlots(roomId: String, date: LocalDate, length: Duration): List<Slot> {
        val bookings = repository.findByRoom(roomId)
        val dayStart = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        val dayEnd = dayStart.plus(Duration.ofDays(1))
        return generateSequence(dayStart) { it.plus(BookingService.GRID) }
            .takeWhile { !it.plus(length).isAfter(dayEnd) }
            .map { Slot(it, it.plus(length)) }
            .filter { slot -> bookings.none { it.overlaps(slot.start, slot.end) } }
            .toList()
    }
}
