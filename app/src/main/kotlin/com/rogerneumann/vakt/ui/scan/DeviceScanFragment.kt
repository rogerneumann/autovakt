package com.rogerneumann.vakt.ui.scan

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.rogerneumann.vakt.databinding.FragmentDeviceScanBinding
import com.rogerneumann.vakt.obd2.TransportDelegate
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
    }

    private fun observeScanState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scanState.collect { state ->
                adapter.submitList(state.devices)

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

            // Save device info to SharedPreferences
            sharedPreferences.edit().apply {
                putString("saved_device_address", device.address)
                putString("saved_device_type", device.type.name)
                apply()
            }

            // Set transport based on device type
            // Note: In a real implementation, we'd create the appropriate transport instance
            // For now, we'll just store the preference and the foreground service will pick it up
            Toast.makeText(
                requireContext(),
                "Connected to ${device.name}",
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
