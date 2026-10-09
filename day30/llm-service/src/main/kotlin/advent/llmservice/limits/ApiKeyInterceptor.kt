package advent.llmservice.limits

import advent.llmservice.api.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.servlet.HandlerInterceptor
import java.security.MessageDigest

const val CLIENT = "client"

/** `Authorization: Bearer <ключ>` → имя клиента в атрибуте запроса. Без известного ключа — 401. */
class ApiKeyInterceptor(private val clients: Map<String, String>) : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val header = request.getHeader("Authorization")
            ?: throw ApiException.unauthorized("Нет ключа доступа: передайте заголовок Authorization: Bearer <ключ>")
        val key = header.removePrefix("Bearer").trim().toByteArray()
        // Сравнение за постоянное время: по скорости отказа ключ не подобрать.
        val client = clients.entries.firstOrNull { MessageDigest.isEqual(it.key.toByteArray(), key) }?.value
            ?: throw ApiException.unauthorized("Ключ доступа не подошёл")
        request.setAttribute(CLIENT, client)
        return true
    }
}
