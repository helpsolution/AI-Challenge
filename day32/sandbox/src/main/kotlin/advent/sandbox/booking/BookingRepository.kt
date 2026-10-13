package advent.sandbox.booking

import org.springframework.stereotype.Repository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Repository
class BookingRepository {
    private val bookings = ConcurrentHashMap<UUID, Booking>()

    fun save(booking: Booking): Booking = booking.also { bookings[it.id] = it }

    fun find(id: UUID): Booking? = bookings[id]

    fun delete(id: UUID) {
        bookings.remove(id)
    }

    fun findByRoom(roomId: String): List<Booking> = bookings.values.filter { it.roomId == roomId }.sortedBy { it.start }
}
