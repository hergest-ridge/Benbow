package app.benbow.server.setup

import app.benbow.server.setup.PlaceAdvice.State

/**
 * Whether phones at home can reach this server (ADR-042, as built 7.19 I1): the network this computer is in must be
 * private (Windows lets nothing in from a public one), and the firewall rule install-service.cmd adds must let the
 * server's port in. Nothing is changed from here: what to do is said, with the command for an administrator's prompt;
 * it only adds a rule of the same name (uninstall-service.cmd removes them all), never takes one away.
 */
object NetworkAdvice {
    data class Verdict(val state: State, val says: String, val command: String? = null)

    data class Advice(val network: Verdict, val firewall: Verdict)

    /** [address] the one phones are shown (the system's outbound one on the home network), [port] the server's. */
    fun advise(survey: Survey, address: String?, port: Int): Advice {
        val profile = address?.let { a -> survey.networks.firstOrNull { a in it.addresses } }
        val domain = profile?.category == "DomainAuthenticated"
        return Advice(network(address, profile), firewall(survey.rules, port, if (domain) "Domain" else "Private"))
    }

    private fun network(address: String?, profile: Survey.NetworkProfile?): Verdict {
        if (address == null) return Verdict(State.Problem, "Компьютер сейчас не в домашней сети: телефонам не к чему подключаться.")
        if (profile == null) return Verdict(State.Note, "Адрес $address: Windows не сказал, в какой он сети.")
        val name = "«${profile.name.orEmpty()}» (${profile.adapter.orEmpty()})"
        return when (profile.category) {
            "Public" -> Verdict(
                State.Problem,
                "Сеть $name отмечена как общедоступная: Windows не пускает к этому компьютеру ни телефоны, ни другие устройства. " +
                    "Если это домашняя сеть, сделайте её частной: Параметры → Сеть и Интернет → ${profile.adapter.orEmpty()} " +
                    "(у Wi-Fi — свойства этой сети) → «Тип сетевого профиля» → «Частная сеть».",
            )

            "DomainAuthenticated" -> Verdict(
                State.Good,
                "Сеть $name — сеть домена; устройства в ней видят этот компьютер по адресу $address.",
            )

            else -> Verdict(State.Good, "Сеть $name частная: устройства дома видят этот компьютер по адресу $address.")
        }
    }

    private fun firewall(rules: List<Survey.FirewallRule>, port: Int, profile: String): Verdict {
        val add = "netsh advfirewall firewall add rule name=\"Benbow Server\" dir=in action=allow protocol=TCP " +
            "localport=$port profile=${profile.lowercase()}"
        if (rules.isEmpty()) {
            return Verdict(
                State.Problem,
                "В брандмауэре нет правила «Benbow Server»: телефоны не достучатся до порта $port. Его добавляет install-service.cmd; " +
                    "можно и вручную, в командной строке от имени администратора:",
                add,
            )
        }
        if (rules.none { lets(it, port, profile) }) {
            val now = rules.joinToString("; ") { rule ->
                listOfNotNull(
                    "порт ${rule.ports.joinToString(", ").ifEmpty { "?" }}",
                    rule.profile?.let { "сети: $it" },
                    "выключено".takeIf { rule.enabled != "True" },
                    "запрещает".takeIf { rule.action == "Block" },
                ).joinToString(", ")
            }
            return Verdict(
                State.Problem,
                "Правило «Benbow Server» есть, но не пропускает порт $port в этой сети ($now). Можно добавить ещё одно, в " +
                    "командной строке от имени администратора (прежнее не трогается: его порт может быть у другого сервера):",
                add,
            )
        }
        return Verdict(State.Good, "Брандмауэр пропускает порт $port: правило «Benbow Server».")
    }

    private fun lets(rule: Survey.FirewallRule, port: Int, profile: String): Boolean =
        rule.enabled == "True" && rule.action == "Allow" && rule.direction == "Inbound" &&
            (rule.protocol == "TCP" || rule.protocol == "Any") &&
            rule.ports.any { it == port.toString() || it == "Any" } &&
            rule.profile.orEmpty().split(",").map { it.trim() }.any { it == profile || it == "Any" }
}
