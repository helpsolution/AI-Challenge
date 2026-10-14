package advent.crm.mcp

import kotlinx.serialization.Serializable

// Выгрузка CRM: время — ISO-8601 в UTC, как его хранит CRM.
@Serializable
data class CrmExport(
    val exportedAt: String,
    val workspaces: List<Workspace>,
    val customers: List<Customer>,
    val subscriptions: List<Subscription>,
    val loginEvents: List<LoginEvent>,
    val tickets: List<Ticket>,
)

@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val plan: String,
    val owner: String,
    val admins: List<String>,
    val emailDomain: String?,
    val ssoOnly: Boolean,
    val ssoOnlySince: String?,
)

@Serializable
data class Customer(
    val id: String,
    val name: String,
    val email: String,
    val workspaceId: String,
    val role: String,
    val timezone: String,
    val status: String,
    val lockedUntil: String?,
    val emailConfirmed: Boolean,
    val twoFactor: String,
    val signInMethods: List<String>,
    val devices: List<Device>,
    val createdAt: String,
)

@Serializable
data class Device(val platform: String, val client: String, val lastSeenAt: String)

@Serializable
data class Subscription(
    val workspaceId: String,
    val plan: String,
    val seats: Int,
    val billing: String,
    val status: String,
    val paidUntil: String?,
    val readOnlySince: String?,
    val card: String?,
    val payments: List<Payment>,
)

@Serializable
data class Payment(val at: String, val amountRub: Int, val result: String, val reason: String?)

@Serializable
data class LoginEvent(
    val customerId: String,
    val at: String,
    val client: String,
    val method: String,
    val result: String,
    val detail: String?,
)

@Serializable
data class Ticket(
    val id: Int,
    val customerId: String,
    val subject: String,
    val status: String,
    val channel: String,
    val createdAt: String,
    val messages: List<TicketMessage>,
)

@Serializable
data class TicketMessage(val from: String, val at: String, val text: String)
