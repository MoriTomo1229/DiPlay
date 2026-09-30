package com.shihab.diplay

import android.Manifest
import android.app.Activity
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.shilapi.xcertplay.network.WifiP2pGroupManager
import com.shilapi.xcertplay.transport.IphoneUsbHost
import com.shilapi.xcertplay.transport.IphoneUsbMatcher
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors

/** Debug-only transport probe. Does not load authentication assets or start CarPlay. */
class ConnectionDiagnosticsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var output: TextView
    @Volatile private var manager: WifiP2pGroupManager? = null
    private var running = false
    private lateinit var connectionDetails: TextView
    private var usbAttachmentReceiver: Closeable? = null
    private val usbHost by lazy {
        IphoneUsbHost(this, getSystemService(UsbManager::class.java),
            IphoneUsbMatcher.appleVendor(), diagnostic = ::report)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The joining credentials are shown locally only, never logged or screen-captured.
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        connectionDetails = TextView(this).apply { setTextIsSelectable(true) }
        output = TextView(this).apply { setTextIsSelectable(true) }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
            addView(TextView(this@ConnectionDiagnosticsActivity).apply {
                text = "Connection diagnostics: Wi-Fi association and USB discovery only. Full CarPlay requires provisioned authentication. Wi-Fi test removes its temporary group when you stop or leave."
            })
            addView(Button(this@ConnectionDiagnosticsActivity).apply {
                text = "Test Wi-Fi Direct"
                setOnClickListener { startProbe() }
            })
            addView(Button(this@ConnectionDiagnosticsActivity).apply {
                text = "Stop Wi-Fi test"
                setOnClickListener {
                    manager?.close(); manager = null
                    connectionDetails.text = ""
                    report("Probe stopped")
                }
            })
            addView(Button(this@ConnectionDiagnosticsActivity).apply {
                text = "Inspect USB devices"
                setOnClickListener {
                    usbHost.discover()
                }
            })
            addView(Button(this@ConnectionDiagnosticsActivity).apply {
                text = "Wait for iPhone USB"
                setOnClickListener { waitForUsb() }
            })
            addView(connectionDetails)
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
                runOnUiThread {
                    if (manager === probe && !isDestroyed) {
                        connectionDetails.text = "Ready for an iPhone Wi-Fi association test.\n" +
                            "Network: ${info.ssid}\nPassword: ${info.passphrase}\n" +
                            "This is a Wi-Fi transport test, not a CarPlay session."
                    }
                }
            } catch (failure: Exception) {
                report("PROBE FAIL ${failure.javaClass.simpleName}: ${failure.message}")
                probe.close()
                manager = null
            } finally {
                runOnUiThread { running = false }
            }
        }
    }

    private fun waitForUsb() {
        if (usbAttachmentReceiver == null) {
            usbAttachmentReceiver = usbHost.registerAttachReceiver {
                usbHost.discover()
                report("USB PROBE detected Apple device; discovery complete. Permission and CarPlay setup are separate steps.")
            }
        }
        if (usbHost.discover().isEmpty()) {
            report("USB PROBE waiting for iPhone. Disconnect the PC and attach the iPhone with a data-capable cable or OTG adapter; reconnect the PC to retrieve this log.")
        } else {
            report("USB PROBE detected Apple device; discovery complete. Permission and CarPlay setup are separate steps.")
        }
    }

    @Synchronized
    private fun report(message: String) {
        // The manager emits selected metadata only; never print the hotspot info object.
        File(filesDir, "connection-probe.log").appendText(message + "\n")
        Log.i("DiPlayProbe", message)
        runOnUiThread { output.append(message + "\n") }
    }

    override fun onDestroy() {
        usbAttachmentReceiver?.close()
        manager?.close()
        executor.shutdownNow()
        super.onDestroy()
    }
}
