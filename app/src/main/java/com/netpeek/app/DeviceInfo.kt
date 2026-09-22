package com.netpeek.app

data class DeviceInfo(
    val ip: String,
    val hostname: String = "Unknown",
    val mdnsName: String? = null,
    val upnpName: String? = null,        // NEW
    val macAddress: String? = null,
    val manufacturer: String? = null,
    val openPorts: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    val systemInfo: String = "Unknown"
) {
    val displayPorts: String
        get() = if (openPorts.isEmpty()) "None" else openPorts.sorted().joinToString(", ")

    val displayName: String
        get() = when {
            !hostname.isNullOrBlank() && hostname != "Unknown" && hostname != ip -> hostname
            !mdnsName.isNullOrBlank() -> mdnsName
            !upnpName.isNullOrBlank() -> upnpName
            else -> "Unknown"
        }
}