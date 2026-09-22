package com.netpeek.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.netpeek.app.databinding.ActivityMainBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import android.content.Intent
import android.view.Menu
import android.view.MenuItem

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: ScanViewModel by viewModels()
    private val adapter = DeviceAdapter()

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                startActivity(Intent(this, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.topAppBar)

        binding.recyclerDevices.layoutManager = LinearLayoutManager(this)
        binding.recyclerDevices.adapter = adapter

        binding.btnScan.setOnClickListener {
            viewModel.startScan()
        }

        lifecycleScope.launch {
            viewModel.cidr.collectLatest { cidr ->
                binding.cidrLabel.text = "IP Range: $cidr"
            }
        }

        lifecycleScope.launch {
            viewModel.isScanning.collectLatest { scanning ->
                binding.btnScan.isEnabled = !scanning
                binding.btnScan.text = if (scanning) "Scanning…" else "Scan Network"
                binding.progressBar.visibility = if (scanning) View.VISIBLE else View.GONE
                binding.progressText.visibility = if (scanning) View.VISIBLE else View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.progress.collectLatest { (current, total) ->
                if (total > 0) {
                    binding.progressBar.max = total
                    binding.progressBar.progress = current
                    binding.progressText.text = "Scanned $current / $total hosts"
                }
            }
        }

        lifecycleScope.launch {
            viewModel.devices.collectLatest { list ->
                adapter.submitList(list)
            }
        }
    }
}

class DeviceAdapter : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private var items = listOf<DeviceInfo>()

    fun submitList(newList: List<DeviceInfo>) {
        items = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_device, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount() = items.size

    class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val ip: TextView = itemView.findViewById(R.id.ipAddress)
        private val hostname: TextView = itemView.findViewById(R.id.hostname)
        private val mdnsName: TextView = itemView.findViewById(R.id.mdnsName)
        private val upnpName: TextView = itemView.findViewById(R.id.upnpName)   // ← add this
        private val manufacturer: TextView = itemView.findViewById(R.id.manufacturer)
        private val macAddress: TextView = itemView.findViewById(R.id.macAddress)
        private val ports: TextView = itemView.findViewById(R.id.ports)
        private val services: TextView = itemView.findViewById(R.id.services)
        private val sys: TextView = itemView.findViewById(R.id.systemInfo)

        fun bind(d: DeviceInfo) {
            ip.text = d.ip
            hostname.text = "Hostname: ${d.displayName}"

            if (!d.mdnsName.isNullOrBlank()) {
                mdnsName.visibility = View.VISIBLE
                mdnsName.text = "mDNS: ${d.mdnsName}"
            } else {
                mdnsName.visibility = View.GONE
            }

            // UPnP  ← this is the part you asked about
            if (!d.upnpName.isNullOrBlank()) {
                upnpName.visibility = View.VISIBLE
                upnpName.text = "UPnP: ${d.upnpName}"
            } else {
                upnpName.visibility = View.GONE
            }

            if (!d.manufacturer.isNullOrBlank() && d.manufacturer != "Unknown") {
                manufacturer.visibility = View.VISIBLE
                manufacturer.text = "Manufacturer: ${d.manufacturer}"
            } else {
                manufacturer.visibility = View.GONE
            }

            if (!d.macAddress.isNullOrBlank()) {
                macAddress.visibility = View.VISIBLE
                macAddress.text = "MAC: ${d.macAddress}"
            } else {
                macAddress.visibility = View.GONE
            }

            ports.text = "Ports open: ${d.displayPorts}"
            services.text = "Services: ${d.services.joinToString(", ").ifEmpty { "None detected" }}"
            sys.text = "System: ${d.systemInfo}"
        }
    }
}