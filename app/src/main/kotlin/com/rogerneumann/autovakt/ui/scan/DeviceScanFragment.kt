package com.rogerneumann.autovakt.ui.scan

import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rogerneumann.autovakt.databinding.FragmentDeviceScanBinding
import com.rogerneumann.autovakt.obd2.TransportDelegate
import com.rogerneumann.autovakt.service.OBD2ForegroundService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class DeviceScanFragment : BottomSheetDialogFragment() {

    private lateinit var binding: FragmentDeviceScanBinding
    private val viewModel: DeviceScanViewModel by viewModels()
    private val adapter = DeviceScanAdapter()

    @Inject
    lateinit var sharedPreferences: SharedPreferences

    @Inject
    lateinit var transportDelegate: TransportDelegate

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentDeviceScanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupListeners()
        observeScanState()

        // Start scanning when fragment is created
        viewModel.startScan()
    }

    private fun setupRecyclerView() {
        binding.rvDevices.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDevices.adapter = adapter

        adapter.onDeviceSelected = { device ->
            selectDevice(device)
        }
    }

    private fun setupListeners() {
        binding.btnCancel.setOnClickListener {
            dismiss()
        }

        binding.btnRefresh.setOnClickListener {
            viewModel.startScan()
        }

        binding.swipeRefresh.setOnRefreshListener {
            viewModel.startScan()
        }
    }

    private fun observeScanState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanState.collect { state ->
                adapter.submitList(state.devices)
                binding.swipeRefresh.isRefreshing = state.isScanning

                if (state.isScanning) {
                    binding.tvStatus.text = "Scanning for devices..."
                    binding.tvStatus.visibility = View.VISIBLE
                    binding.pbLoading.visibility = View.VISIBLE
                } else {
                    binding.pbLoading.visibility = View.GONE
                    if (state.devices.isEmpty() && state.error == null) {
                        binding.tvStatus.text = "No devices found"
                        binding.tvStatus.visibility = View.VISIBLE
                    } else if (state.error != null) {
                        binding.tvStatus.text = state.error
                        binding.tvStatus.visibility = View.VISIBLE
                    } else {
                        binding.tvStatus.visibility = View.GONE
                    }
                }
            }
        }
    }

    private fun selectDevice(device: ScannedDevice) {
        try {
            viewModel.stopScan()

            sharedPreferences.edit().apply {
                putString("saved_device_address", device.address)
                putString("saved_device_type", device.type.name)
                apply()
            }

            ContextCompat.startForegroundService(
                requireContext(),
                Intent(requireContext(), OBD2ForegroundService::class.java)
            )

            Toast.makeText(
                requireContext(),
                "Connecting to ${device.name}…",
                Toast.LENGTH_SHORT
            ).show()

            dismiss()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                "Error connecting to device: ${e.message}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopScan()
    }
}
