package com.socksrelay.vertex.net

import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.socksrelay.vertex.log.AppLog
import java.net.DatagramSocket
import java.net.Socket

/**
 * `VpnService.protect(Socket)` / `protect(DatagramSocket)` are the
 * documented way to exempt a socket from your own tunnel, but on a real
 * (non-trivial) share of devices/ROMs they unreliably return `false` even
 * when the VPN is genuinely established and active — this is a known,
 * long-standing issue several real-world open-source Android VPN clients
 * have had to work around, not something specific to this scaffold's setup.
 *
 * The workaround used here — duplicate the socket's underlying file
 * descriptor via the *public* API `ParcelFileDescriptor.fromSocket()` /
 * `fromDatagramSocket()`, call the low-level `protect(int fd)` overload on
 * that duplicate, then close the duplicate — is measurably more reliable
 * across devices than calling the Socket/DatagramSocket overloads directly.
 *
 * This works because `protect()` marks the underlying kernel socket
 * structure (via a fwmark), which is shared by every file descriptor that
 * refers to it — the original and any `dup()`'d copy alike. So marking the
 * duplicate protects the original just as effectively, and closing the
 * duplicate afterward only releases that one extra fd number; the original
 * socket your code keeps using is untouched.
 */
object VpnProtect {
    private const val TAG = "VpnProtect"

    fun protect(vpnService: VpnService, socket: Socket): Boolean {
        return try {
            val pfd = ParcelFileDescriptor.fromSocket(socket)
            try {
                val ok = vpnService.protect(pfd.fd)
                if (!ok) {
                    AppLog.e(TAG, "protect(fd) returned false for a TCP socket",
                        "Even the low-level fd-based protect() failed. This can mean: (1) the VPN " +
                            "interface isn't fully active yet, (2) another VPN app currently owns the " +
                            "tunnel, or (3) — very commonly — your device has \"Block connections without " +
                            "VPN\" (a.k.a. VPN lockdown/always-on) enabled for some app in Settings > " +
                            "Network & internet > VPN. That setting explicitly forbids ANY traffic from " +
                            "bypassing the tunnel, which breaks protect() by design. Check that setting " +
                            "for every VPN app listed, not just this one.")
                }
                ok
            } finally {
                pfd.close()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception while protecting TCP socket: ${e.message}")
            false
        }
    }

    fun protect(vpnService: VpnService, datagramSocket: DatagramSocket): Boolean {
        return try {
            val pfd = ParcelFileDescriptor.fromDatagramSocket(datagramSocket)
            try {
                val ok = vpnService.protect(pfd.fd)
                if (!ok) {
                    AppLog.e(TAG, "protect(fd) returned false for a UDP (DNS) socket",
                        "Same underlying cause as TCP protect() failures — see the hint on those errors, " +
                            "in particular the \"Block connections without VPN\" device setting.")
                }
                ok
            } finally {
                pfd.close()
            }
        } catch (e: Exception) {
            AppLog.e(TAG, "Exception while protecting UDP socket: ${e.message}")
            false
        }
    }
}
