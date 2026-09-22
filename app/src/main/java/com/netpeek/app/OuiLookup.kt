package com.netpeek.app

object OuiLookup {

    // Common IoT / network equipment OUIs (first 3 bytes of MAC)
    private val ouiMap = mapOf(
        "00:11:32" to "Synology",
        "00:17:88" to "Philips Hue",
        "00:1A:22" to "Unknown IoT",
        "00:1E:C2" to "Apple",
        "00:22:6C" to "Linksys",
        "00:24:E4" to "Withings",
        "00:50:C2" to "IEEE Registration",
        "04:CF:8C" to "Xiaomi",
        "14:91:82" to "Belkin",
        "18:B4:30" to "Nest",
        "20:F8:5E" to "TP-Link",
        "24:0A:C4" to "Espressif",
        "28:6C:07" to "Xiaomi",
        "2C:3A:E8" to "Espressif",
        "30:AE:A4" to "Espressif",
        "34:CE:00" to "Xiaomi",
        "3C:71:BF" to "Espressif",
        "44:61:32" to "ecobee",
        "48:3F:DA" to "Espressif",
        "50:C7:BF" to "TP-Link",
        "54:60:09" to "Google",
        "5C:CF:7F" to "Espressif",
        "60:01:94" to "Espressif",
        "64:16:66" to "Nest",
        "68:C6:3A" to "Espressif",
        "70:EE:50" to "Netatmo",
        "78:11:DC" to "XIAOMI",
        "7C:2F:80" to "Huawei",
        "84:0D:8E" to "Espressif",
        "84:F3:EB" to "Espressif",
        "8C:AA:B5" to "Espressif",
        "A0:20:A6" to "Espressif",
        "A4:CF:12" to "Espressif",
        "AC:67:B2" to "Espressif",
        "B8:27:EB" to "Raspberry Pi",
        "BC:DD:C2" to "Espressif",
        "C8:2B:96" to "Espressif",
        "CC:50:E3" to "Espressif",
        "D8:A0:1D" to "Espressif",
        "DC:4F:22" to "Espressif",
        "E0:98:06" to "Espressif",
        "E8:DB:84" to "Espressif",
        "EC:FA:BC" to "Espressif",
        "F0:08:D1" to "Espressif",
        "F4:CF:A2" to "Espressif",
        "FC:F5:C4" to "Espressif",
        // Add more as you discover them
        "00:0C:29" to "VMware",
        "00:15:5D" to "Microsoft",
        "00:1C:42" to "Parallels",
        "08:00:27" to "VirtualBox",
        "52:54:00" to "QEMU/KVM",
        "4c:a9:19" to "IOT Front",
        "38:A5:C9" to "IOT Back",
        "c4:82:e1" to "IOT HINEN"
    )

    fun getManufacturer(mac: String?): String? {
        if (mac.isNullOrBlank()) return null
        val clean = mac.uppercase().replace("-", ":")
        val oui = clean.take(8) // XX:XX:XX
        return ouiMap[oui] ?: "Unknown"
    }
}