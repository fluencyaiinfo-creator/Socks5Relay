package com.socksrelay.vertex.net

enum class ProxyProtocol {
    HTTP, SOCKS4, SOCKS5;

    companion object {
        /** Case-insensitive parse with SOCKS5 as the sane default for unrecognized text. */
        fun fromString(value: String?): ProxyProtocol = when (value?.trim()?.uppercase()) {
            "HTTP", "HTTPS" -> HTTP
            "SOCKS4", "SOCKS4A" -> SOCKS4
            "SOCKS5" -> SOCKS5
            else -> SOCKS5
        }
    }
}
