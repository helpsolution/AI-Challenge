package advent.crm.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Serializable data class CustomerSummary(val id: String, val name: String, val email: String, val workspace: String,
    val plan: String, val role: String)
@Serializable data class CustomerList(val customers: List<CustomerSummary>)
@Serializable data class WorkspaceView(val name: String, val plan: String, val owner: String, val admins: List<String>,
    val ssoOnly: Boolean, val ssoOnlySince: String?)
@Serializable data class DeviceView(val platform: String, val client: String, val lastSeenAt: String)
@Serializable data class CustomerProfile(val id: String, val name: String, val email: String, val role: String,
    val status: String, val lockedUntil: String?, val emailConfirmed: Boolean, val twoFactor: String,
    val signInMethods: List<String>, val devices: List<DeviceView>, val workspace: WorkspaceView,
    val timezone: String, val createdAt: String)
@Serializable data class PaymentView(val at: String, val amountRub: Int, val result: String, val reason: String?)
@Serializable data class SubscriptionView(val plan: String, val seats: Int, val billing: String, val status: String,
    val paidUntil: String?, val readOnlySince: String?, val owner: String, val card: String?,
    val payments: List<PaymentView>?, val note: String?)
@Serializable data class LoginEventView(val at: String, val client: String, val method: String, val result: String,
    val detail: String?)
@Serializable data class LoginEvents(val events: List<LoginEventView>, val total: Int)
@Serializable data class TicketSummary(val id: Int, val subject: String, val status: String, val channel: String,
    val createdAt: String, val updatedAt: String, val messages: Int)
@Serializable data class TicketList(val tickets: List<TicketSummary>)
@Serializable data class MessageView(val from: String, val at: String, val text: String)
@Serializable data class TicketView(val id: Int, val subject: String, val status: String, val channel: String,
    val createdAt: String, val messages: List<MessageView>)

class CrmRepository(file: Path, private val clock: Clock = Clock.systemUTC()) {
    private val data: CrmExport
    private val tickets: MutableList<Ticket>

    init {
        val export = Json.decodeFromString<CrmExport>(Files.readString(file))
        // Выгрузка — снимок на момент exportedAt. Сдвигаем всё время к моменту запуска,
        // чтобы блокировки и «сегодняшние» попытки входа оставались актуальными в любой день демо.
        val shift = Duration.between(Instant.parse(export.exportedAt), clock.instant()).truncatedTo(ChronoUnit.MINUTES)
        data = export.shifted(shift)
        tickets = data.tickets.toMutableList()
    }

    fun customers() = CustomerList(data.customers.map {
        val workspace = workspace(it)
        CustomerSummary(it.id, it.name, it.email, workspace.name, workspace.plan, it.role)
    })

    fun profile(customerId: String): CustomerProfile {
        val customer = customer(customerId)
        val zone = ZoneId.of(customer.timezone)
        val workspace = workspace(customer)
        val lockExpired = customer.lockedUntil?.let { !Instant.parse(it).isAfter(clock.instant()) } ?: true
        return CustomerProfile(customer.id, customer.name, customer.email, customer.role,
            if (customer.status == "locked" && lockExpired) "active" else customer.status,
            customer.lockedUntil?.takeUnless { lockExpired }?.let { local(it, zone) },
            customer.emailConfirmed, customer.twoFactor, customer.signInMethods,
            customer.devices.map { DeviceView(it.platform, it.client, local(it.lastSeenAt, zone)) },
            WorkspaceView(workspace.name, workspace.plan, workspace.owner, workspace.admins, workspace.ssoOnly,
                workspace.ssoOnlySince?.let { local(it, zone) }),
            customer.timezone, local(customer.createdAt, zone))
    }

    // Платёжные данные в продукте видит только владелец пространства — CRM отдаёт остальным то же самое.
    fun subscription(customerId: String): SubscriptionView {
        val customer = customer(customerId)
        val zone = ZoneId.of(customer.timezone)
        val workspace = workspace(customer)
        val subscription = data.subscriptions.single { it.workspaceId == workspace.id }
        val owner = customer.role == "owner"
        return SubscriptionView(subscription.plan, subscription.seats, subscription.billing, subscription.status,
            subscription.paidUntil?.let { local(it, zone) }, subscription.readOnlySince?.let { local(it, zone) },
            workspace.owner,
            subscription.card.takeIf { owner },
            subscription.payments.takeIf { owner }?.map { PaymentView(local(it.at, zone), it.amountRub, it.result, it.reason) },
            if (owner) null else "Карта и история платежей видны только владельцу пространства (${workspace.owner})")
    }

    fun loginEvents(customerId: String, limit: Int): LoginEvents {
        val customer = customer(customerId)
        val zone = ZoneId.of(customer.timezone)
        val events = data.loginEvents.filter { it.customerId == customerId }.sortedByDescending { Instant.parse(it.at) }
        return LoginEvents(events.take(limit.coerceIn(1, 50)).map {
            LoginEventView(local(it.at, zone), it.client, it.method, it.result, it.detail)
        }, events.size)
    }

    @Synchronized
    fun tickets(customerId: String, status: String): TicketList {
        val zone = ZoneId.of(customer(customerId).timezone)
        return TicketList(tickets.filter { it.customerId == customerId && (status == "all" || it.status == status) }
            .sortedByDescending { it.id }
            .map { TicketSummary(it.id, it.subject, it.status, it.channel, local(it.createdAt, zone),
                local(it.messages.last().at, zone), it.messages.size) })
    }

    @Synchronized
    fun ticket(customerId: String, ticketId: Int): TicketView {
        val zone = ZoneId.of(customer(customerId).timezone)
        // Чужое обращение неотличимо от несуществующего: номер не подтверждает, что такое обращение есть.
        val ticket = tickets.singleOrNull { it.id == ticketId && it.customerId == customerId }
            ?: throw IllegalArgumentException("Обращение #$ticketId не найдено у этого клиента")
        return view(ticket, zone)
    }

    @Synchronized
    fun createTicket(customerId: String, subject: String, message: String): TicketView {
        val zone = ZoneId.of(customer(customerId).timezone)
        require(subject.isNotBlank() && message.isNotBlank()) { "Нужны тема и текст обращения" }
        val now = clock.instant().truncatedTo(ChronoUnit.SECONDS).toString()
        val ticket = Ticket(tickets.maxOf { it.id } + 1, customerId, subject.trim(), "open", "assistant", now,
            listOf(TicketMessage("customer", now, message.trim())))
        tickets += ticket
        return view(ticket, zone)
    }

    private fun view(ticket: Ticket, zone: ZoneId) = TicketView(ticket.id, ticket.subject, ticket.status, ticket.channel,
        local(ticket.createdAt, zone), ticket.messages.map { MessageView(it.from, local(it.at, zone), it.text) })

    private fun customer(id: String) = data.customers.singleOrNull { it.id == id }
        ?: throw IllegalArgumentException("Клиент $id не найден")

    private fun workspace(customer: Customer) = data.workspaces.single { it.id == customer.workspaceId }

    private fun local(at: String, zone: ZoneId): String = Instant.parse(at).atZone(zone).format(LOCAL)

    private companion object {
        val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX")
    }
}

private fun CrmExport.shifted(by: Duration): CrmExport {
    fun t(at: String) = Instant.parse(at).plus(by).toString()
    return copy(
        exportedAt = t(exportedAt),
        workspaces = workspaces.map { it.copy(ssoOnlySince = it.ssoOnlySince?.let(::t)) },
        customers = customers.map { customer ->
            customer.copy(lockedUntil = customer.lockedUntil?.let(::t), createdAt = t(customer.createdAt),
                devices = customer.devices.map { it.copy(lastSeenAt = t(it.lastSeenAt)) })
        },
        subscriptions = subscriptions.map { subscription ->
            subscription.copy(paidUntil = subscription.paidUntil?.let(::t), readOnlySince = subscription.readOnlySince?.let(::t),
                payments = subscription.payments.map { it.copy(at = t(it.at)) })
        },
        loginEvents = loginEvents.map { it.copy(at = t(it.at)) },
        tickets = tickets.map { ticket ->
            ticket.copy(createdAt = t(ticket.createdAt), messages = ticket.messages.map { it.copy(at = t(it.at)) })
        },
    )
}
