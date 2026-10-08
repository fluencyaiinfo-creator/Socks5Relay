package com.socksrelay.vertex

/**
 * Parses a pasted proxy string in either of these formats:
 *   ip:port
 *   ip:port@username:password
 *
 * Deliberately lenient: extra whitespace is trimmed, and either the
 * username or password half can be empty (e.g. "host:port@user:" or
 * "host:port@:pass") without failing the whole parse.
 */
object ProxyStringParser {

    data class ParsedProxy(
        val host: String,
        val port: Int,
        val username: String? = null,
        val password: String? = null
    )

    /** Returns null if [input] doesn't look like a valid `host:port[@user:pass]` string. */
    fun parse(input: String): ParsedProxy? {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return null

        val atIndex = trimmed.indexOf('@')
        val hostPortPart: String
        var username: String? = null
        var password: String? = null

        if (atIndex >= 0) {
            hostPortPart = trimmed.substring(0, atIndex)
            val credentialsPart = trimmed.substring(atIndex + 1)
            val credentialSplit = credentialsPart.split(":", limit = 2)
            username = credentialSplit.getOrNull(0)?.takeIf { it.isNotEmpty() }
            password = credentialSplit.getOrNull(1)?.takeIf { it.isNotEmpty() }
        } else {
            hostPortPart = trimmed
        }

        // Host itself could theoretically contain ':' if it were IPv6, but
        // this scaffold's relay is IPv4-only end to end (see PacketRouter),
        // so a simple last-colon split on host:port is sufficient here.
        val lastColon = hostPortPart.lastIndexOf(':')
        if (lastColon <= 0 || lastColon == hostPortPart.length - 1) return null

        val host = hostPortPart.substring(0, lastColon).trim()
        val port = hostPortPart.substring(lastColon + 1).trim().toIntOrNull() ?: return null
        if (host.isEmpty() || port !in 1..65535) return null

        return ParsedProxy(host, port, username, password)
    }
}
