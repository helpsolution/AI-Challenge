package advent.sandbox.booking

import org.springframework.http.HttpStatus
import java.util.UUID

sealed class BookingError(message: String, val status: HttpStatus) : RuntimeException(message)

class InvalidBooking(message: String) : BookingError(message, HttpStatus.BAD_REQUEST)

class BookingConflict(message: String) : BookingError(message, HttpStatus.CONFLICT)

class BookingForbidden(message: String) : BookingError(message, HttpStatus.FORBIDDEN)

class BookingNotFound(id: UUID) : BookingError("Бронь $id не найдена", HttpStatus.NOT_FOUND)
