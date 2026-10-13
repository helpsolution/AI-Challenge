package advent.sandbox.notify

import advent.sandbox.booking.Booking
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException

@ConfigurationProperties("notify")
data class NotifyProperties(val url: String, val token: String)

@Component
class NotificationClient(builder: RestClient.Builder, private val properties: NotifyProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client = builder
        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer ${properties.token}")
        .build()

    fun bookingCreated(booking: Booking) = send("booking.created", booking)

    fun bookingCancelled(booking: Booking) = send("booking.cancelled", booking)

    // Уведомление — побочный эффект: его сбой не отменяет бронь.
    private fun send(event: String, booking: Booking) {
        if (properties.url.isBlank()) return
        try {
            client.post().uri(properties.url).contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("event" to event, "booking" to booking))
                .retrieve().toBodilessEntity()
        } catch (e: RestClientException) {
            log.warn("Уведомление {} о брони {} не отправлено: {}", event, booking.id, e.message)
        }
    }
}
