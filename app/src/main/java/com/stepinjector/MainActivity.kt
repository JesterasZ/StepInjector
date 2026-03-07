package com.stepinjector

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.stepinjector.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var healthConnectClient: HealthConnectClient? = null

    private val STEPS_PER_MINUTE = 100L

    private val requiredPermissions = setOf(
        HealthPermission.getWritePermission(StepsRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    private val requestHcPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(requiredPermissions)) {
                updateStatus("Listo para inyectar pasos")
                toast("Permisos de Health Connect concedidos")
            } else {
                updateStatus("Sin permisos — la app no puede funcionar")
                toast("Permisos denegados. Activalos en Ajustes > Privacidad > Health Connect")
                binding.btnStart.isEnabled = false
            }
        }

    private val requestNotifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestNotificationPermissionIfNeeded()
        initHealthConnect()
        setupUI()
        observeInjectionProgress()
    }

    // ─── Notification permission (Android 13+) ────────────────────────────────

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // ─── Health Connect initialisation ────────────────────────────────────────

    private fun initHealthConnect() {
        when (HealthConnectClient.getSdkStatus(this)) {
            HealthConnectClient.SDK_UNAVAILABLE -> {
                updateStatus("Health Connect no esta disponible en este dispositivo")
                binding.btnStart.isEnabled = false
            }
            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                updateStatus("Necesitas instalar Health Connect\nBuscalo en Google Play Store")
                toast("Instala 'Health Connect' desde Play Store")
                binding.btnStart.isEnabled = false
            }
            else -> {
                healthConnectClient = HealthConnectClient.getOrCreate(this)
                checkPermissions()
            }
        }
    }

    private fun checkPermissions() {
        lifecycleScope.launch {
            val client = healthConnectClient ?: return@launch
            val granted = client.permissionController.getGrantedPermissions()
            if (granted.containsAll(requiredPermissions)) {
                updateStatus("Listo para inyectar pasos")
            } else {
                requestHcPermissions.launch(requiredPermissions)
            }
        }
    }

    // ─── UI setup ─────────────────────────────────────────────────────────────

    private fun setupUI() {
        binding.btnStop.isEnabled = false

        binding.btnStart.setOnClickListener { onStartPressed() }
        binding.btnStop.setOnClickListener  { onStopPressed() }

        binding.chip2k.setOnClickListener  { binding.etSteps.setText("2000") }
        binding.chip5k.setOnClickListener  { binding.etSteps.setText("5000") }
        binding.chip10k.setOnClickListener { binding.etSteps.setText("10000") }

        binding.etSteps.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val steps = s.toString().toLongOrNull()
                if (steps != null && steps > 0) {
                    val mins = (steps + STEPS_PER_MINUTE - 1) / STEPS_PER_MINUTE
                    binding.tvEstimatedDuration.text = "Duracion estimada: ${formatDuration(mins)}"
                } else {
                    binding.tvEstimatedDuration.text = "Duracion estimada: —"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Initial calculation for default pre-filled value
        val defaultSteps = binding.etSteps.text.toString().toLongOrNull() ?: 0L
        if (defaultSteps > 0) {
            val mins = (defaultSteps + STEPS_PER_MINUTE - 1) / STEPS_PER_MINUTE
            binding.tvEstimatedDuration.text = "Duracion estimada: ${formatDuration(mins)}"
        }
    }

    // ─── Service control ──────────────────────────────────────────────────────

    private fun onStartPressed() {
        if (healthConnectClient == null) {
            toast("Health Connect no esta disponible")
            return
        }

        val totalSteps = binding.etSteps.text.toString().toLongOrNull()
        if (totalSteps == null || totalSteps < 1) {
            toast("Introduce un numero de pasos valido")
            return
        }

        val intent = Intent(this, InjectionService::class.java).apply {
            putExtra(InjectionService.EXTRA_STEPS,     totalSteps)
            putExtra(InjectionService.EXTRA_REALTIME,  binding.rgMode.checkedRadioButtonId == R.id.rb_realtime)
            putExtra(InjectionService.EXTRA_VARIATION, binding.switchNatural.isChecked)
        }

        binding.btnStart.isEnabled    = false
        binding.btnStop.isEnabled     = true
        binding.progressBar.progress  = 0
        binding.tvInjected.text       = "0"
        binding.tvTotal.text          = "/ $totalSteps pasos"
        updateStatus("Iniciando inyeccion...")

        startForegroundService(intent)
    }

    private fun onStopPressed() {
        startService(Intent(this, InjectionService::class.java).apply {
            action = InjectionService.ACTION_STOP
        })
        setUIIdle()
        updateStatus("Inyeccion cancelada por el usuario")
    }

    // ─── Progress observer ────────────────────────────────────────────────────

    private fun observeInjectionProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                InjectionService.progress.collect { prog ->
                    prog ?: return@collect

                    binding.tvInjected.text      = "${prog.injected}"
                    binding.tvTotal.text         = "/ ${prog.total} pasos"
                    binding.progressBar.progress = ((prog.injected.toFloat() / prog.total) * 100).toInt()
                    updateStatus(prog.status)

                    if (prog.done || prog.error) {
                        if (prog.done) toast("!${prog.injected} pasos anadidos a Google Fit!")
                        setUIIdle()
                    }
                }
            }
        }
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun setUIIdle() {
        binding.btnStart.isEnabled = true
        binding.btnStop.isEnabled  = false
    }

    private fun updateStatus(msg: String) {
        runOnUiThread { binding.tvStatus.text = msg }
    }

    private fun toast(msg: String) {
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun formatDuration(mins: Long): String {
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "$mins min (~${h}h ${m}min)" else "$mins min"
    }
}
