package uz.jarvis

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(48, 96, 48, 48)
        }
        root.addView(TextView(this).apply {
            text = "JARVIS"
            textSize = 40f
            gravity = Gravity.CENTER
        })
        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 48)
        }
        root.addView(status)

        root.addView(bigButton("1. Ruxsatlarni berish") { askPermissions() })
        root.addView(bigButton("2. Boshqaruvni yoqish (Accessibility)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(bigButton("3. Jarvisni ishga tushirish") { startJarvis() })
        root.addView(bigButton("Jarvisni to'xtatish") {
            stopService(Intent(this, JarvisService::class.java))
            refresh()
        })
        root.addView(TextView(this).apply {
            textSize = 15f
            setPadding(0, 48, 0, 0)
            text = """
                Buyruqlar namunasi:
                • "Salom Jarvis" — uyg'otish
                • "Telegramni och", "Instagramga kir"
                • "Bonuga SMS yoz salom qalaysan"
                • "Onamga qo'ng'iroq qil"
                • "Orqaga", "Bosh ekran", "Pastga", "Tepaga"
                • "Yozish salom" — ochiq maydonga yozadi
                • "Yuborish ni bos" — ekrandagi tugmani bosadi
                • "Budilnik 7 30", "Kamerani och", "Soat necha"
                • "Rahmat" / "Xayr" — uxlatish
            """.trimIndent()
        })
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        minHeight = 160
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 }
    }

    private fun neededPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.SEND_SMS,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun askPermissions() {
        ActivityCompat.requestPermissions(this, neededPermissions(), 1)
    }

    private fun startJarvis() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askPermissions(); return
        }
        ContextCompat.startForegroundService(this, Intent(this, JarvisService::class.java))
        refresh()
    }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(rc, p, r)
        refresh()
    }

    private fun refresh() {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val acc = JarvisAccessibilityService.instance != null
        val run = JarvisService.running
        status.text = "Mikrofon: ${if (mic) "✅" else "❌"}   " +
                "Boshqaruv: ${if (acc) "✅" else "❌"}\n" +
                "Jarvis: ${if (run) "ishlayapti — \"Salom Jarvis\" deng" else "o'chiq"}"
    }
}
