package com.example.advancedvoice.feature.conversation.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.advancedvoice.R
import com.example.advancedvoice.databinding.FragmentFirstBinding
import com.example.advancedvoice.feature.conversation.presentation.ConversationViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConversationFragment : Fragment(R.layout.fragment_first) {

    companion object { private const val TAG = "ConversationFragment" }

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private val vm: ConversationViewModel by viewModels()

    private var pendingStartPermission = false

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            Log.i(TAG, "Permission RECORD_AUDIO result: $granted, pending=$pendingStartPermission")
            if (granted && pendingStartPermission) {
                pendingStartPermission = false
                startListeningNow()
            } else if (!granted) {
                Toast.makeText(requireContext(), getString(R.string.error_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentFirstBinding.bind(view)

        val adapter = ConversationAdapter {}
        binding.conversationRecyclerView.adapter = adapter

        viewLifecycleOwner.lifecycleScope.launch {
            vm.conversation.collect { list ->
                adapter.submitList(list) {
                    if (list.isNotEmpty()) {
                        binding.conversationRecyclerView.post {
                            binding.conversationRecyclerView.smoothScrollToPosition(list.size - 1)
                        }
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.controls.collectLatest { c ->
                binding.speakButton.text = c.speakButtonText
                binding.speakButton.isEnabled = c.speakEnabled
                binding.stopButton.isEnabled = c.stopEnabled
                binding.clearButton.isEnabled = c.clearEnabled

                val color = when {
                    c.speakButtonText.contains("Hearing") -> 0xFF81C784.toInt() // Light Green
                    c.speakButtonText.contains("Listening") -> 0xFFF44336.toInt() // Red
                    c.speakButtonText.contains("Speaking") -> 0xFF4CAF50.toInt() // Green
                    c.speakButtonText.contains("Processing") -> 0xFF42A5F5.toInt() // Blue
                    c.speakButtonText.contains("Generating") -> 0xFF4CAF50.toInt() // Green
                    else -> 0xFFFFFFFF.toInt() // Default White
                }
                binding.speakButton.setTextColor(color)
            }
        }

        binding.clearButton.setOnClickListener { vm.clearConversation() }
        binding.stopButton.setOnClickListener { vm.stopAll() }
        binding.speakButton.setOnClickListener { startListeningSafely() }

        // FIX: The fragment now only tells the ViewModel to set itself up.
        // It no longer contains any logic for creating controllers.
        vm.setupSttSystemFromPreferences()
    }

    private fun startListeningSafely() {
        val granted = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "startListeningSafely: granted=$granted")
        if (granted) {
            startListeningNow()
        } else {
            pendingStartPermission = true
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListeningNow() {
        Log.i(TAG, "startListeningNow() called.")
        vm.startListening()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}