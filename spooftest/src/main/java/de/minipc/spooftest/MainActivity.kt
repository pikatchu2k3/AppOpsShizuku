package de.minipc.spooftest

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.graphics.TypefaceCompat
import com.google.android.gms.ads.identifier.AdvertisingIdClient
import java.security.MessageDigest

class MainActivity : AppCompatActivity() {

    private lateinit var container: LinearLayout
    private val TAG = "SpoofTest"

    private val requestPhoneState = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadValues()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Spoof Test"

        val scroll = ScrollView(this).apply {
            isFillViewport = true
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        scroll.addView(container)
        setContentView(scroll)

        loadValues()
    }

    override fun onResume() {
        super.onResume()
        loadValues()
    }

    private fun loadValues() {
        container.removeAllViews()

        addRow("--- Build ---", "")
        addRow("MODEL", Build.MODEL)
        addRow("MANUFACTURER", Build.MANUFACTURER)
        addRow("BRAND", Build.BRAND)
        addRow("DEVICE", Build.DEVICE)
        addRow("PRODUCT", Build.PRODUCT)
        addRow("HARDWARE", Build.HARDWARE)
        addRow("SERIAL", try { Build.SERIAL } catch (e: Exception) { "N/A (${e.javaClass.simpleName})" })
        addRow("FINGERPRINT", Build.FINGERPRINT)
        addRow("VERSION.RELEASE", Build.VERSION.RELEASE)
        addRow("SDK_INT", Build.VERSION.SDK_INT.toString())

        addRow("--- Android-ID ---", "")
        addRow("ANDROID_ID", try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "null"
        } catch (e: Exception) { "N/A (${e.javaClass.simpleName})" })

        addRow("--- IMEI ---", "")
        val phoneStateGranted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (phoneStateGranted) {
            addRow("IMEI", getImei())
            addRow("IMSI", try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                @Suppress("DEPRECATION")
                tm.subscriberId ?: "null"
            } catch (e: Exception) { "N/A (${e.javaClass.simpleName})" })
            addRow("Line1Number", try {
                val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                tm.line1Number ?: "null"
            } catch (e: Exception) { "N/A (${e.javaClass.simpleName})" })
        } else {
            addRow("IMEI/IMSI/Line1", "Permission fehlt — fordere an")
            requestPhoneState.launch(Manifest.permission.READ_PHONE_STATE)
        }

        addRow("--- GSF-ID ---", "")
        addRow("GSF (raw)", getGsfId())

        addRow("--- Advertising-ID ---", "")
        addRow("AAID", "lade...")
        Thread {
            val aaid = try {
                AdvertisingIdClient.getAdvertisingIdInfo(applicationContext).id ?: "null"
            } catch (e: Exception) {
                "N/A (${e.javaClass.simpleName}: ${e.message?.take(30)})"
            }
            runOnUiThread {
                for (i in 0 until container.childCount) {
                    val row = container.getChildAt(i) as? LinearLayout
                    val title = row?.getChildAt(0) as? TextView
                    if (title?.text?.toString() == "AAID") {
                        (row.getChildAt(1) as TextView).text = aaid
                        break
                    }
                }
            }
        }.start()

        addRow("--- WLAN-MAC ---", "")
        addRow("WLAN MAC", try {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wm.connectionInfo?.macAddress ?: "null"
        } catch (e: Exception) { "N/A (${e.javaClass.simpleName})" })

        addRow("--- Bluetooth-MAC ---", "")
        addRow("BT MAC", try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            bm.adapter?.address ?: "null (kein Adapter)"
        } catch (e: Exception) { "N/A (${e.javaClass.simpleName})" })

        addRow("--- App-Signatur ---", "")
        addRow("SHA-256", getSignatureSha256())

        val btn = Button(this).apply {
            text = "Aktualisieren"
            setOnClickListener { loadValues() }
        }
        container.addView(btn)
    }

    private fun addRow(title: String, value: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 8, 0, 8)
        }
        val tvTitle = TextView(this).apply {
            text = title
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textSize = 14f
        }
        val tvValue = TextView(this).apply {
            text = value
            textSize = 13f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        row.addView(tvTitle)
        row.addView(tvValue)
        container.addView(row)
    }

    private fun getImei(): String {
        return try {
            val tm = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                tm.imei ?: "null"
            } else {
                @Suppress("DEPRECATION")
                tm.deviceId ?: "null"
            }
        } catch (e: Exception) {
            "N/A (${e.javaClass.simpleName})"
        }
    }

    private fun getGsfId(): String {
        val uris = listOf(
            "content://com.google.android.gsf.gservices",
            "content://com.google.android.gsf.Gservices"
        )
        for (uriStr in uris) {
            try {
                val uri = android.net.Uri.parse(uriStr)
                contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val sb = StringBuilder()
                        for (i in 0 until c.columnCount) {
                            sb.append("${c.getColumnName(i)}=${c.getString(i)}; ")
                        }
                        return "$uriStr -> $sb"
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "GSF $uriStr failed: ${e.message}")
            }
        }
        return "N/A (kein Zugriff)"
    }

    @Suppress("DEPRECATION")
    private fun getSignatureSha256(): String {
        return try {
            val pm = packageManager
            val sigs: List<android.content.pm.Signature>? =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                        .signingInfo?.apkContentsSigners?.toList()
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                        .signatures?.toList()
                }
            val sig = sigs?.firstOrNull() ?: return "null"
            val md = MessageDigest.getInstance("SHA-256")
            val hash = md.digest(sig.toByteArray())
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "N/A (${e.javaClass.simpleName})"
        }
    }
}