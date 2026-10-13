package advent.sandbox.booking

import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class BookingRequest(val start: Instant, val end: Instant)

// Пользователь приходит в заголовке X-User: аутентификация живёт за пределами песочницы.
@RestController
@RequestMapping("/api/rooms/{roomId}")
class BookingController(private val bookings: BookingService, private val availability: RoomAvailability) {

    @GetMapping("/bookings")
    fun list(@PathVariable roomId: String): List<Booking> = bookings.list(roomId)

    @PostMapping("/bookings")
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@PathVariable roomId: String, @RequestHeader("X-User") user: String, @RequestBody request: BookingRequest): Booking =
        bookings.create(roomId, user, request.start, request.end)

    @DeleteMapping("/bookings/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@PathVariable id: UUID, @RequestHeader("X-User") user: String) = bookings.cancel(id, user)

    @GetMapping("/free")
    fun free(
        @PathVariable roomId: String,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) date: LocalDate,
        @RequestParam(defaultValue = "60") minutes: Long,
    ): List<Slot> = availability.freeSlots(roomId, date, Duration.ofMinutes(minutes))

    @ExceptionHandler
    fun handle(error: BookingError): ProblemDetail = ProblemDetail.forStatusAndDetail(error.status, error.message)
}
