package com.socksrelay.vertex.net

import com.socksrelay.vertex.log.AppLog
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * A *real* connectivity test — not just "can I open a TCP socket to the
 * proxy port", but the full chain: SOCKS5 handshake (+ auth if configured)
 * -> proxy connects out to a real, well-known website -> we send an actual
 * HTTP request through the tunnel -> we check we got a real HTTP response
 * back. It then does a second lookup against a public IP geolocation API
 * *through the same proxy* to report exactly which IP/location/timezone
 * your traffic is actually exiting from — the most convincing possible
 * proof the proxy is really being used (rather than, say, your own
 * connection accidentally answering the test).
 *
 * This runs as a plain socket from the app process. If you run this while
 * NOT connected to the VPN, it goes straight out over your normal network
 * connection, completely independent of any tunnel code — the cleanest
 * possible test of "does my proxy work at all". If you run it while the VPN
 * IS connected, this app's traffic is captured by its own tunnel like any
 * other app's traffic and gets relayed the normal way through
 * `PacketRouter` — which still exercises the proxy correctly, just via the
 * full pipeline rather than in isolation. For the clearest signal, run this
 * test *before* connecting.
 */
object ProxyTester {
    private const val TAG = "ProxyTester"
    private const val CONNECTIVITY_TEST_HOST = "example.com"
    private const val CONNECTIVITY_TEST_PORT = 80
    private const val GEO_HOST = "ip-api.com"
    private const val GEO_PORT = 80
    private const val GEO_PATH = "/json/?fields=status,message,query,country,regionName,city,zip,timezone,isp"

    data class GeoInfo(
        val ip: String? = null,
        val country: String? = null,
        val region: String? = null,
        val city: String? = null,
        val zip: String? = null,
        val timezone: String? = null,
        val isp: String? = null
    )

    data class Result(
        val success: Boolean,
        val message: String,
        val elapsedMs: Long,
        val geo: GeoInfo? = null
    )

    /** Blocking — call from a background thread, never the UI thread. */
    fun test(
        protocol: ProxyProtocol,
        socksHost: String,
        socksPort: Int,
        username: String?,
        password: String?
    ): Result {
        val start = System.currentTimeMillis()
        AppLog.i(TAG, "===== Starting proxy test =====")
        AppLog.i(TAG, "Target proxy: [$protocol] $socksHost:$socksPort" +
            if (!username.isNullOrEmpty()) " (with username/password)" else " (no auth)")

        if (socksHost.isBlank()) {
            AppLog.e(TAG, "No proxy host entered", "Fill in the SOCKS5 host field before testing.")
            return Result(false, "No host entered", System.currentTimeMillis() - start)
        }

        val connectivityOk = try {
            checkConnectivity(protocol, socksHost, socksPort, username, password)
        } catch (e: SocketTimeoutException) {
            val elapsed = System.currentTimeMillis() - start
            AppLog.e(TAG, "Proxy test FAILED: connection timed out after ${elapsed}ms",
                "The proxy host/port didn't respond at all within 10 seconds. Most likely causes: " +
                    "wrong IP/hostname, wrong port, the proxy isn't running, or a firewall between " +
                    "your phone and the proxy is silently dropping the connection.")
            return Result(false, "Timed out reaching proxy", elapsed)
        } catch (e: IOException) {
            val elapsed = System.currentTimeMillis() - start
            AppLog.e(TAG, "Proxy test FAILED: ${e.message}")
            return Result(false, e.message ?: "Unknown error", elapsed)
        }

        if (!connectivityOk) {
            return Result(false, "Unexpected response from test site", System.currentTimeMillis() - start)
        }

        // Basic connectivity is proven at this point. The geolocation
        // lookup is "nice to have" evidence of exactly where traffic is
        // exiting from — if it fails, don't fail the whole test over it.
        val geo = try {
            lookupGeoInfo(protocol, socksHost, socksPort, username, password)
        } catch (e: Exception) {
            AppLog.w(TAG, "Geolocation lookup failed (proxy still works): ${e.message}",
                "This doesn't affect the pass/fail result — it just means we couldn't fetch extra " +
                    "details about the proxy's exit IP right now (ip-api.com may be rate-limiting, or " +
                    "temporarily unreachable).")
            null
        }

        val elapsed = System.currentTimeMillis() - start
        val summary = if (geo != null) {
            "${geo.ip ?: "?"} — ${listOfNotNull(geo.city, geo.region, geo.country).joinToString(", ")}"
        } else {
            "connected (location lookup unavailable)"
        }
        AppLog.success(TAG, "===== Proxy test PASSED in ${elapsed}ms =====",
            "Your SOCKS5 proxy is reachable, authenticates correctly, and can reach the real " +
                "internet. If the VPN still shows 'no internet' after this passes, the problem " +
                "is in the VPN's packet routing, not the proxy — check the Logs screen while " +
                "connected and browsing for errors from PacketRouter or SocksVpnService.")
        return Result(true, summary, elapsed, geo)
    }

    /** Opens a tunnel to a well-known site and confirms a real HTTP response comes back. */
    private fun checkConnectivity(
        protocol: ProxyProtocol,
        socksHost: String,
        socksPort: Int,
        username: String?,
        password: String?
    ): Boolean {
        val socket = ProxyClient.connect(
            protocol = protocol,
            protectSocket = { true }, // no-op: only meaningful for sockets opened inside SocksVpnService
            proxyHost = socksHost,
            proxyPort = socksPort,
            destinationHost = CONNECTIVITY_TEST_HOST,
            destPort = CONNECTIVITY_TEST_PORT,
            username = username?.ifEmpty { null },
            password = password?.ifEmpty { null }
        )
        return try {
            AppLog.i(TAG, "Sending test HTTP request through the tunnel...")
            socket.getOutputStream().write(
                "HEAD / HTTP/1.1\r\nHost: $CONNECTIVITY_TEST_HOST\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()
            socket.soTimeout = 8000

            val response = String(socket.getInputStream().readBytes(), Charsets.US_ASCII)
            val statusLine = response.lineSequence().firstOrNull()?.trim().orEmpty()

            if (statusLine.startsWith("HTTP/")) {
                AppLog.success(TAG, "Received real HTTP response: \"$statusLine\"")
                true
            } else {
                AppLog.e(TAG, "Got a response but it doesn't look like HTTP: \"${statusLine.take(80)}\"",
                    "The proxy connected somewhere, but what answered doesn't look like a normal web " +
                        "server. This can happen if the proxy is redirecting/intercepting traffic.")
                false
            }
        } catch (e: IOException) {
            AppLog.e(TAG, "Proxy test FAILED while reading response: ${e.message}",
                "The SOCKS5 handshake succeeded, but no usable HTTP response came back through the " +
                    "tunnel in time. The proxy may be able to connect but not actually relay data.")
            false
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Asks a public IP geolocation API (through the proxy) exactly where the traffic is exiting from. */
    private fun lookupGeoInfo(
        protocol: ProxyProtocol,
        socksHost: String,
        socksPort: Int,
        username: String?,
        password: String?
    ): GeoInfo? {
        AppLog.i(TAG, "Looking up proxy exit IP / location via $GEO_HOST...")
        val socket = ProxyClient.connect(
            protocol = protocol,
            protectSocket = { true },
            proxyHost = socksHost,
            proxyPort = socksPort,
            destinationHost = GEO_HOST,
            destPort = GEO_PORT,
            username = username?.ifEmpty { null },
            password = password?.ifEmpty { null }
        )
        return try {
            socket.getOutputStream().write(
                "GET $GEO_PATH HTTP/1.1\r\nHost: $GEO_HOST\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.US_ASCII)
            )
            socket.getOutputStream().flush()
            socket.soTimeout = 8000

            val response = String(socket.getInputStream().readBytes(), Charsets.US_ASCII)
            val bodyStart = response.indexOf("\r\n\r\n")
            if (bodyStart < 0) {
                AppLog.w(TAG, "Geo lookup response had no body")
                return null
            }
            var body = response.substring(bodyStart + 4)

            // ip-api.com replies with chunked transfer-encoding by default;
            // if so, strip the hex chunk-size lines rather than pulling in
            // a full HTTP client just for this one request.
            if (response.contains("Transfer-Encoding: chunked", ignoreCase = true)) {
                body = dechunk(body)
            }

            val json = JSONObject(body)
            if (json.optString("status") != "success") {
                AppLog.w(TAG, "Geo lookup API returned an error: ${json.optString("message")}")
                return null
            }

            val geo = GeoInfo(
                ip = json.optString("query").ifEmpty { null },
                country = json.optString("country").ifEmpty { null },
                region = json.optString("regionName").ifEmpty { null },
                city = json.optString("city").ifEmpty { null },
                zip = json.optString("zip").ifEmpty { null },
                timezone = json.optString("timezone").ifEmpty { null },
                isp = json.optString("isp").ifEmpty { null }
            )
            AppLog.success(TAG, "Proxy exits as ${geo.ip} in ${listOfNotNull(geo.city, geo.region, geo.country).joinToString(", ")} (${geo.timezone})")
            geo
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Minimal HTTP chunked-transfer-encoding decoder — just enough for this one small JSON response. */
    private fun dechunk(chunkedBody: String): String {
        val out = StringBuilder()
        var rest = chunkedBody
        while (true) {
            val lineEnd = rest.indexOf("\r\n")
            if (lineEnd < 0) break
            val sizeLine = rest.substring(0, lineEnd).trim()
            val size = sizeLine.toIntOrNull(16) ?: break
            if (size == 0) break
            val chunkStart = lineEnd + 2
            val chunkEnd = (chunkStart + size).coerceAtMost(rest.length)
            out.append(rest, chunkStart, chunkEnd)
            rest = if (chunkEnd + 2 <= rest.length) rest.substring(chunkEnd + 2) else ""
        }
        return out.toString()
    }
}
