package uz.jarvis

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** O'zbekcha ovozli buyruqlarni tushunib, bajaradi. */
class CommandProcessor(private val ctx: Context, private val say: (String) -> Unit) {

    private val acc get() = JarvisAccessibilityService.instance

    /** Mashhur ilovalar: aytilgan nom -> paket nomi */
    private val knownApps = mapOf(
        "telegram" to "org.telegram.messenger",
        "instagram" to "com.instagram.android",
        "insta" to "com.instagram.android",
        "youtube" to "com.google.android.youtube",
        "yutub" to "com.google.android.youtube",
        "whatsapp" to "com.whatsapp",
        "vatsap" to "com.whatsapp",
        "tiktok" to "com.zhiliaoapp.musically",
        "chrome" to "com.android.chrome",
        "xrom" to "com.android.chrome",
        "brauzer" to "com.android.chrome",
        "gmail" to "com.google.android.gm",
        "pochta" to "com.google.android.gm",
        "xarita" to "com.google.android.apps.maps",
        "karta" to "com.google.android.apps.maps",
        "facebook" to "com.facebook.katana",
        "click" to "air.com.ssdsoftwaresolutions.clickuz",
        "payme" to "uz.dida.payme",
        "yandex" to "com.yandex.browser",
        "spotify" to "com.spotify.music",
        "galereya" to "", "kamera" to "", "sozlamalar" to "", "kontaktlar" to "",
        "kalkulyator" to "", "soat" to ""
    )

    fun execute(raw: String) {
        val original = raw.trim()
        val c = norm(original)

        try {
            when {
                // --- Navigatsiya ---
                c.matches(Regex("^(orqaga|ortga|nazad|qaytish|orqaga qayt).*")) ->
                    needAcc { it.back(); say("Orqaga") }
                c.contains("bosh ekran") || c.contains("glavniy") || c == "uyga" || c.contains("asosiy ekran") ->
                    needAcc { it.home(); say("Bosh ekran") }
                c.startsWith("pastga") || c.contains("pastga tushir") || c.contains("vniz") ->
                    needAcc { say(if (it.scroll(true)) "Pastga" else "Aylantirib bo'lmadi") }
                c.startsWith("tepaga") || c.startsWith("yuqoriga") || c.contains("vverx") ->
                    needAcc { say(if (it.scroll(false)) "Tepaga" else "Aylantirib bo'lmadi") }
                c.contains("oxirgi ilovalar") || c.contains("ochiq ilovalar") ->
                    needAcc { it.recents(); say("Ochiq ilovalar") }
                c.contains("bildirishnoma") || c.contains("uvedomlen") ->
                    needAcc { it.notifications(); say("Bildirishnomalar") }

                // --- Vaqt ---
                c.contains("soat necha") || c.contains("vaqt necha") ->
                    say("Soat " + SimpleDateFormat("HH:mm", Locale.US).format(Date()))
                c.contains("bugun nechanchi") || c.startsWith("sana") ->
                    say("Bugun " + SimpleDateFormat("dd.MM.yyyy", Locale.US).format(Date()))

                // --- Budilnik: "budilnik 7 30", "soat 7 ga budilnik qoy" ---
                c.contains("budilnik") || c.contains("uygotgich") -> setAlarm(c)

                // --- SMS ---
                c.contains("sms") || c.contains("xabar yoz") || c.contains("smska") -> sendSms(original, c)

                // --- Qo'ng'iroq ---
                c.contains("qongiroq") || c.contains("zvonok") || c.contains("tel qil") ||
                        c.contains("telefon qil") || c.contains("pozvoni") -> call(c)

                // --- Ekranga yozish: "yozish salom", "yoz salom" ---
                c.startsWith("yozish ") || c.startsWith("yoz ") || c.startsWith("yozgin ") ->
                    needAcc {
                        val text = original.substringAfter(" ").trim()
                        say(if (it.typeText(text)) "Yozdim" else "Yozish maydoni topilmadi")
                    }

                // --- Tugma bosish: "yuborish ni bos", "bos yuborish" ---
                c.endsWith(" bos") || c.endsWith(" bosing") || c.startsWith("bos ") ->
                    needAcc {
                        val target = c.removePrefix("bos ").replace(Regex("\\s*(ni|ini)?\\s*(bos|bosing)$"), "").trim()
                        say(if (it.clickText(target)) "Bosdim" else "$target topilmadi")
                    }

                // --- Ekranni o'qish ---
                c.contains("ekranni oqi") || c.contains("nima yozilgan") ->
                    needAcc { say(it.screenText().ifBlank { "Ekranda matn yo'q" }) }

                // --- Kamera ---
                c.contains("kamera") || c.contains("rasmga ol") || c.contains("surat ol") -> {
                    startIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); say("Kamera ochildi")
                }

                // --- Ilova ochish: "telegramni och", "instagramga kir" ---
                Regex("(och|ochgin|oching|kir|kirgin|ishga tushir|zapusti|otkroy)$").containsMatchIn(c) ||
                        c.startsWith("och ") -> openApp(c)

                // --- Salomlashish ---
                c.startsWith("salom") || c.startsWith("assalomu") -> say("Salom! Buyuring.")
                c.contains("qalaysan") || c.contains("yaxshimisan") -> say("Zo'r, rahmat. Sizga nima yordam kerak?")

                else -> say("Tushunmadim. Qaytadan ayting.")
            }
        } catch (e: Exception) {
            say("Xatolik bo'ldi")
        }
    }

    // ================== Yordamchi funksiyalar ==================

    private fun norm(s: String) = s.lowercase()
        .replace(Regex("[ʻʼ’‘`'´]"), "")
        .replace(Regex("[,.!?]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun needAcc(block: (JarvisAccessibilityService) -> Unit) {
        val a = acc
        if (a == null) say("Avval sozlamalarda Jarvis boshqaruvini yoqing")
        else block(a)
    }

    private fun startIntent(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }

    private fun stripSuffix(w: String): String =
        w.replace(Regex("(ning|niki|ni|ga|ka|qa|dan|da|ni)$"), "")

    // ---------- Ilova ochish ----------
    private fun openApp(c: String) {
        val name = c.replace(Regex("^och\\s+"), "")
            .replace(Regex("\\s*(ni|ga|ka|qa)?\\s*(och|ochgin|oching|kir|kirgin|ishga tushir|zapusti|otkroy)$"), "")
            .trim()
        val n = stripSuffix(name)
        if (n.isBlank()) { say("Qaysi ilovani ochay?"); return }

        when {
            n.contains("galereya") -> { startIntent(Intent(Intent.ACTION_VIEW).setType("image/*")); say("Galereya"); return }
            n.contains("sozlama") || n.contains("nastroyk") -> { startIntent(Intent(android.provider.Settings.ACTION_SETTINGS)); say("Sozlamalar"); return }
            n.contains("kontakt") -> { startIntent(Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)); say("Kontaktlar"); return }
            n.contains("kamera") -> { startIntent(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)); say("Kamera"); return }
        }

        val pm = ctx.packageManager
        // 1) Ma'lum ilovalar ro'yxatidan
        knownApps.entries.firstOrNull { n.contains(it.key) && it.value.isNotEmpty() }?.let { e ->
            pm.getLaunchIntentForPackage(e.value)?.let { startIntent(it); say("${e.key.replaceFirstChar { it.uppercase() }} ochildi"); return }
        }
        // 2) O'rnatilgan ilovalar nomidan qidirish
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val match = apps.firstOrNull { norm(it.loadLabel(pm).toString()) == n }
            ?: apps.firstOrNull { norm(it.loadLabel(pm).toString()).contains(n) }
            ?: apps.firstOrNull { n.contains(norm(it.loadLabel(pm).toString())) && it.loadLabel(pm).length > 2 }
        if (match != null) {
            pm.getLaunchIntentForPackage(match.activityInfo.packageName)?.let {
                startIntent(it); say("${match.loadLabel(pm)} ochildi"); return
            }
        }
        say("$n ilovasi topilmadi")
    }

    // ---------- Kontakt qidirish ----------
    private data class Contact(val name: String, val phone: String)

    private fun findContact(spoken: String): Contact? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val key = stripSuffix(spoken.trim())
        if (key.length < 2) return null
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            null, null, null
        ) ?: return null
        var exact: Contact? = null
        var partial: Contact? = null
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(0) ?: continue
                val phone = it.getString(1) ?: continue
                val nn = norm(name)
                if (nn == key || nn.split(" ").contains(key)) { exact = Contact(name, phone); break }
                if (partial == null && (nn.contains(key) || cyrToLat(nn).contains(key))) partial = Contact(name, phone)
            }
        }
        return exact ?: partial
    }

    /** Kontaktlar kirillcha yozilgan bo'lsa ham topish uchun */
    private fun cyrToLat(s: String): String {
        val m = mapOf('а' to "a",'б' to "b",'в' to "v",'г' to "g",'д' to "d",'е' to "e",'ё' to "yo",'ж' to "j",
            'з' to "z",'и' to "i",'й' to "y",'к' to "k",'л' to "l",'м' to "m",'н' to "n",'о' to "o",'п' to "p",
            'р' to "r",'с' to "s",'т' to "t",'у' to "u",'ф' to "f",'х' to "x",'ц' to "ts",'ч' to "ch",'ш' to "sh",
            'щ' to "sh",'ъ' to "",'ы' to "i",'ь' to "",'э' to "e",'ю' to "yu",'я' to "ya",'ў' to "o",'қ' to "q",
            'ғ' to "g",'ҳ' to "h")
        return s.map { m[it] ?: it.toString() }.joinToString("")
    }

    // ---------- SMS: "Bonuga sms yoz salom qalaysan" ----------
    private fun sendSms(original: String, c: String) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            say("SMS yuborishga ruxsat yo'q"); return
        }
        val words = c.split(" ")
        val kwIndex = words.indexOfFirst { it == "sms" || it == "smska" || it == "xabar" }
        val before = words.take(kwIndex).joinToString(" ")
        val after = words.drop(kwIndex + 1).dropWhile { it.startsWith("yoz") || it == "yubor" || it == "jonat" }

        // Ism SMS so'zidan oldin ("Bonuga sms yoz ...") yoki keyin ("sms yoz Bonuga ...")
        var who = before
        var bodyWords = after
        if (who.isBlank() && after.isNotEmpty()) {
            who = after.first(); bodyWords = after.drop(1)
        }
        val contact = findContact(who)
        if (contact == null) { say("$who kontaktlarda topilmadi"); return }

        // Matnni asl ko'rinishda (apostroflar bilan) olishga harakat
        val origWords = original.split(Regex("\\s+"))
        val body = if (bodyWords.isNotEmpty() && origWords.size >= bodyWords.size)
            origWords.takeLast(bodyWords.size).joinToString(" ") else bodyWords.joinToString(" ")
        if (body.isBlank()) { say("Nima deb yozay? Qaytadan ayting: ism, sms yoz, keyin matn"); return }

        val sms = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
        else @Suppress("DEPRECATION") SmsManager.getDefault()
        sms.sendMultipartTextMessage(contact.phone, null, sms.divideMessage(body), null, null)
        say("${contact.name}ga yuborildi: $body")
    }

    // ---------- Qo'ng'iroq: "Onamga qo'ng'iroq qil" ----------
    private fun call(c: String) {
        val who = c.replace(Regex("\\s*(qongiroq|zvonok|tel|telefon|pozvoni).*$"), "")
            .replace(Regex("^(pozvoni|qongiroq qil)\\s*"), "").trim()
        val contact = findContact(who)
        if (contact == null) { say("$who kontaktlarda topilmadi"); return }
        val action = if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED)
            Intent.ACTION_CALL else Intent.ACTION_DIAL
        say("${contact.name}ga qo'ng'iroq qilyapman")
        startIntent(Intent(action, Uri.parse("tel:" + contact.phone)))
    }

    // ---------- Budilnik ----------
    private fun setAlarm(c: String) {
        val nums = Regex("\\d+").findAll(c).map { it.value.toInt() }.toList()
        if (nums.isEmpty()) { say("Soat nechaga qo'yay? Masalan: budilnik 7 30"); return }
        val h = nums[0].coerceIn(0, 23)
        val m = nums.getOrNull(1)?.coerceIn(0, 59) ?: 0
        startIntent(Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        say("Budilnik soat $h:${"%02d".format(m)} ga qo'yildi")
    }
}
