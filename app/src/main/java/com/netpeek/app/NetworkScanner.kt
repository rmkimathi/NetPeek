package com.netpeek.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.FileReader
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.pow

class NetworkScanner(private val context: Context) {

    private val TAG = "NetPeekScanner"

    private val COMMON_PORTS = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP",
        53 to "DNS", 80 to "HTTP", 110 to "POP3", 139 to "NetBIOS",
        143 to "IMAP", 443 to "HTTPS", 445 to "SMB", 554 to "RTSP", 993 to "IMAPS",
        995 to "POP3S", 1883 to "MQTT", 3306 to "MySQL", 3389 to "RDP", 5000 to "UPnP/Flask",
        5432 to "PostgreSQL", 5900 to "VNC", 8000 to "HTTP-Alt2", 8080 to "HTTP-Alt",
        8123 to "Home Assistant", 8443 to "HTTPS-Alt", 8554 to "RTSP-Alt", 9000 to "UDP-Lite",
        9090 to "Cockpit", 32400 to "Plex", 49152 to "UPnP"
    )

    // Cache of mDNS names discovered during scan
    private val mdnsCache = ConcurrentHashMap<String, String>()
    private val upnpCache = ConcurrentHashMap<String, String>()   // IP → friendly name

    suspend fun detectLocalCidr(): String = withContext(Dispatchers.IO) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return@withContext "192.168.1.0/24"
            val lp: LinkProperties = cm.getLinkProperties(network) ?: return@withContext "192.168.1.0/24"

            for (linkAddress in lp.linkAddresses) {
                val addr = linkAddress.address
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val prefix = linkAddress.prefixLength
                    val networkAddr = calculateNetworkAddress(addr.hostAddress!!, prefix)
                    return@withContext "$networkAddr/$prefix"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "CIDR detection failed", e)
        }
        "192.168.1.0/24"
    }

    private fun calculateNetworkAddress(ip: String, prefix: Int): String {
        val parts = ip.split(".").map { it.toInt() }
        val mask = (0xFFFFFFFF.toInt() shl (32 - prefix))
        val ipInt = (parts[0] shl 24) or (parts[1] shl 16) or (parts[2] shl 8) or parts[3]
        val networkInt = ipInt and mask
        return "${(networkInt ushr 24) and 0xFF}." +
                "${(networkInt ushr 16) and 0xFF}." +
                "${(networkInt ushr 8) and 0xFF}." +
                "${networkInt and 0xFF}"
    }

    suspend fun scanNetwork(
        cidr: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
        onDeviceFound: (DeviceInfo) -> Unit = {}
    ): List<DeviceInfo> = withContext(Dispatchers.IO) {

        // Start background discoveries
        val mdnsJob = launch { discoverMdns() }
        val ssdpJob = launch { discoverSsdp() }

        val (baseIp, prefix) = parseCidr(cidr)
        val hostCount = 2.0.pow(32 - prefix).toInt() - 2
        val maxHosts = minOf(hostCount, 254)

        val results = ConcurrentHashMap<String, DeviceInfo>()
        val jobs = mutableListOf<Job>()
        var scanned = 0

        for (i in 1..maxHosts) {
            val ip = incrementIp(baseIp, i)
            val job = launch {
                val device = probeHost(ip)
                if (device != null) {
                    results[ip] = device
                    withContext(Dispatchers.Main) { onDeviceFound(device) }
                }
                scanned++
                withContext(Dispatchers.Main) { onProgress(scanned, maxHosts) }
            }
            jobs.add(job)
            if (i % 32 == 0) delay(40)
        }

        jobs.joinAll()

        // Stop discoveries
        mdnsJob.cancel()
        ssdpJob.cancel()

        // Enrich with mDNS + UPnP names
        results.forEach { (ip, device) ->
            var updated = device
            mdnsCache[ip]?.let {
                if (updated.mdnsName == null) updated = updated.copy(mdnsName = it)
            }
            upnpCache[ip]?.let {
                if (updated.upnpName == null) updated = updated.copy(upnpName = it)
            }
            results[ip] = updated
        }

        // This last line is the return value
        results.values.sortedBy { ipToLong(it.ip) }
    }

    private suspend fun probeHost(ip: String): DeviceInfo? {
        if (!isHostAlive(ip)) return null

        // Hostname
        val hostname = try {
            InetAddress.getByName(ip).canonicalHostName.takeIf { it != ip } ?: "Unknown"
        } catch (e: Exception) {
            "Unknown"
        }

        // MAC + Manufacturer (best effort)
        val mac = getMacAddress(ip)
        val manufacturer = OuiLookup.getManufacturer(mac)

        // Names from discovery
        val mdnsName = mdnsCache[ip]
        val upnpName = upnpCache[ip]

        // Port scanning
        val openPorts = mutableListOf<Int>()
        val services = mutableListOf<String>()

        for ((port, serviceName) in COMMON_PORTS) {
            if (isPortOpen(ip, port, timeoutMs = 320)) {
                openPorts.add(port)
                val banner = grabBanner(ip, port)
                val detected = if (banner.isNotBlank()) "$serviceName ($banner)" else serviceName
                services.add(detected)
            }
        }

        val sysInfo = buildSystemInfo(openPorts, services, hostname, manufacturer)

        return DeviceInfo(
            ip = ip,
            hostname = hostname,
            mdnsName = mdnsName,
            upnpName = upnpName,
            macAddress = mac,
            manufacturer = manufacturer,
            openPorts = openPorts,
            services = services,
            systemInfo = sysInfo
        )
    }

    // ---------- MAC address (best effort) ----------
    private fun getMacAddress(ip: String): String? {
        // Method 1: /proc/net/arp (works on many devices, restricted on some)
        try {
            BufferedReader(FileReader("/proc/net/arp")).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val parts = line!!.split(Regex("\\s+"))
                    if (parts.size >= 4 && parts[0] == ip) {
                        val mac = parts[3]
                        if (mac.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) &&
                            mac != "00:00:00:00:00:00") {
                            return mac.uppercase()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "ARP read failed for $ip: ${e.message}")
        }
        return null
    }

    // ---------- mDNS / NSD discovery ----------
    private suspend fun discoverMdns() = withContext(Dispatchers.Main) {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager

        val serviceTypes = listOf(
            "_http._tcp.", "_https._tcp.", "_ssh._tcp.", "_smb._tcp.",
            "_airplay._tcp.", "_googlecast._tcp.", "_hap._tcp.", "_homekit._tcp.",
            "_printer._tcp.", "_ipp._tcp.", "_androidtvremote2._tcp.",
            "_nvstream_dbd._tcp.", "_spotify-connect._tcp.", "_sonos._tcp.",
            "_rfb._tcp.", "_adb._tcp.", "_companion-link._tcp.",
            "_services._dns-sd._udp."
        )

        val listeners = mutableListOf<NsdManager.DiscoveryListener>()

        for (type in serviceTypes) {
            val listener = object : NsdManager.DiscoveryListener {
                override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
                override fun onDiscoveryStarted(serviceType: String?) {}
                override fun onDiscoveryStopped(serviceType: String?) {}
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}

                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    serviceInfo ?: return
                    try {
                        nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                            override fun onResolveFailed(serviceInfo: NsdServiceInfo?, errorCode: Int) {}
                            override fun onServiceResolved(resolved: NsdServiceInfo?) {
                                resolved ?: return
                                val host = resolved.host?.hostAddress ?: return
                                val name = resolved.serviceName?.trim()
                                if (!name.isNullOrBlank()) {
                                    mdnsCache[host] = name
                                }
                            }
                        })
                    } catch (_: Exception) {}
                }
            }
            try {
                nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                listeners.add(listener)
            } catch (_: Exception) {}
        }

        delay(10000) // give mDNS more time

        listeners.forEach {
            try { nsdManager.stopServiceDiscovery(it) } catch (_: Exception) {}
        }
    }

    // ---------- helpers (unchanged logic) ----------
    private fun isHostAlive(ip: String): Boolean {
        val quickPorts = listOf(80, 443, 22, 445, 135, 139, 8080, 554, 9000)
        for (p in quickPorts) {
            if (isPortOpen(ip, p, 180)) return true
        }
        return try {
            InetAddress.getByName(ip).isReachable(500)
        } catch (e: Exception) { false }
    }

private suspend fun discoverSsdp() = withContext(Dispatchers.IO) {
    try {
        val socket = DatagramSocket()
        socket.soTimeout = 2000
        socket.broadcast = true

        val searchMessage = """
            M-SEARCH * HTTP/1.1
            HOST: 239.255.255.250:1900
            MAN: "ssdp:discover"
            MX: 3
            ST: ssdp:all
            
        """.trimIndent().replace("\n", "\r\n") + "\r\n"

        val sendData = searchMessage.toByteArray()
        val address = InetAddress.getByName("239.255.255.250")
        val packet = DatagramPacket(sendData, sendData.size, address, 1900)

        // Send a few times
        repeat(3) {
            socket.send(packet)
            delay(300)
        }

        val buffer = ByteArray(2048)
        val endTime = System.currentTimeMillis() + 6000

        while (System.currentTimeMillis() < endTime) {
            try {
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                val data = String(response.data, 0, response.length)
                val ip = response.address.hostAddress ?: continue

                // Extract friendly name or server
                val friendly = Regex("friendlyName[:>]\\s*(.+)", RegexOption.IGNORE_CASE)
                    .find(data)?.groupValues?.get(1)?.trim()
                val server = Regex("SERVER:\\s*(.+)", RegexOption.IGNORE_CASE)
                    .find(data)?.groupValues?.get(1)?.trim()

                val name = friendly ?: server
                if (!name.isNullOrBlank()) {
                    upnpCache[ip] = name.take(60)
                }
            } catch (_: SocketTimeoutException) {
                // continue until timeout
            } catch (_: Exception) {
                break
            }
        }
        socket.close()
    } catch (e: Exception) {
        Log.w(TAG, "SSDP discovery failed: ${e.message}")
    }
}

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (e: Exception) { false }
    }

    private fun grabBanner(ip: String, port: Int): String {
        return try {
            Socket().use { socket ->
                socket.soTimeout = 700
                socket.connect(InetSocketAddress(ip, port), 500)
                val reader = socket.getInputStream().bufferedReader()
                val line = reader.readLine()?.take(80)?.trim() ?: ""
                line.replace(Regex("[\\x00-\\x1F]"), "")
            }
        } catch (e: Exception) { "" }
    }

    private fun buildSystemInfo(
        ports: List<Int>,
        services: List<String>,
        hostname: String,
        manufacturer: String?
    ): String {
        val mfr = manufacturer?.takeIf { it != "Unknown" }
        return when {
            mfr != null -> mfr
            ports.contains(445) || services.any { it.contains("SMB") } -> "Samba"
            ports.contains(22) && hostname.contains("raspberry", true) -> "Raspberry Pi / Linux"
            ports.contains(9090) -> "Linux (Cockpit)"
            ports.contains(32400) -> "Plex Media Server"
            ports.contains(8123) -> "Home Assistant"
            ports.contains(554) -> "IP Camera / RTSP"
            ports.contains(3306) -> "MySQL Server"
            ports.contains(5432) -> "PostgreSQL Server"
            ports.isNotEmpty() -> "Active host"
            else -> "Reachable"
        }
    }

    private fun parseCidr(cidr: String): Pair<String, Int> {
        val parts = cidr.split("/")
        return parts[0] to (parts.getOrNull(1)?.toIntOrNull() ?: 24)
    }

    private fun incrementIp(base: String, offset: Int): String {
        val parts = base.split(".").map { it.toInt() }.toMutableList()
        var carry = offset
        for (i in 3 downTo 0) {
            val sum = parts[i] + carry
            parts[i] = sum % 256
            carry = sum / 256
        }
        return parts.joinToString(".")
    }

    private fun ipToLong(ip: String): Long {
        return ip.split(".").fold(0L) { acc, s -> (acc shl 8) + s.toInt() }
    }
}
