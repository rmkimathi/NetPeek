package com.netpeek.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val scanner = NetworkScanner(application)

    private val _cidr = MutableStateFlow("Detecting…")
    val cidr: StateFlow<String> = _cidr.asStateFlow()

    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _progress = MutableStateFlow(0 to 0)
    val progress: StateFlow<Pair<Int, Int>> = _progress.asStateFlow()

    init {
        viewModelScope.launch {
            _cidr.value = scanner.detectLocalCidr()
        }
    }

    fun startScan() {
        if (_isScanning.value) return

        viewModelScope.launch {
            _isScanning.value = true
            _devices.value = emptyList()
            _progress.value = 0 to 0

            try {
                val result = scanner.scanNetwork(
                    cidr = _cidr.value,
                    onProgress = { current, total ->
                        _progress.value = current to total

                        // Critical: clear the scanning flag when done
                        if (total > 0 && current >= total) {
                            _isScanning.value = false
                        }
                    },
                    onDeviceFound = { device ->
                        _devices.value = (_devices.value + device)
                            .distinctBy { it.ip }
                            .sortedBy { it.ip }
                    }
                )
                _devices.value = result
            } catch (e: Exception) {
                // ignore
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun forceStopScan() {
        _isScanning.value = false
    }
}