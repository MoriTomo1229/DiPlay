package com.shihab.diplay

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import java.util.concurrent.Executors

/** Debug-only transport probe. Does not load authentication assets or start CarPlay. */
class ConnectionDiagnosticsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    @Volatile private var manager: WifiP2pGroupManager? = null
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(TextView(this@ConnectionDiagnosticsActivity).apply {
                text = "Connection diagnostics — no CarPlay authentication. Wi-Fi test creates a temporary group and removes it when you stop or leave."
            })
            addView(Button(this@ConnectionDiagnosticsActivity).apply {
                text = "Test Wi-Fi Direct"
                setOnClickListener { startProbe() }
            })
            addView(Button(this@ConnectionDiagnosticsActivity).apply {
                text = "Stop Wi-Fi test"
                setOnClickListener { executor.execute { manager?.close(); manager = null; report("Probe stopped") } }
            })
            addView(ScrollView(this@ConnectionDiagnosticsActivity).apply { addView(output) })
        }
        setContentView(layout)
    }

    private fun startProbe() {
        if (running || manager != null) return
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
            else Manifest.permission.ACCESS_FINE_LOCATION
        if (checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(permission), 1)
            return
        }
        running = true
        output.text = ""
        executor.execute {
            val probe = WifiP2pGroupManager(this, ::report)
            manager = probe
            try {
                val info = probe.start(15_000)
                report("PROBE PASS group owner ready band=${info.bandLabel} frequencyMHz=${info.frequencyMHz} security=${info.security}")
            } catch (failure: Exception) {
                report("PROBE FAIL ${failure.javaClass.simpleName}: ${failure.message}")
                probe.close()
                manager = null
            } finally {
                runOnUiThread { running = false }
            }
        }
    }

    private fun report(message: String) {
        // The manager emits selected metadata only; never print the hotspot info object.
        Log.i("DiPlayProbe", message)
        runOnUiThread { output.append(message + "\n") }
    }

    override fun onDestroy() {
        manager?.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
