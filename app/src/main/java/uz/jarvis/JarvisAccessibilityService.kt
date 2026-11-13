package uz.jarvis

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Boshqa ilovalarni boshqarish: orqaga, bosh ekran, aylantirish, bosish, yozish. */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        var instance: JarvisAccessibilityService? = null
    }

    override fun onServiceConnected() { instance = this }
    override fun onDestroy() { instance = null; super.onDestroy() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)

    fun scroll(forward: Boolean): Boolean {
        val node = findScrollable(rootInActiveWindow) ?: return false
        return node.performAction(
            if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        )
    }

    private fun findScrollable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isScrollable) return n
        for (i in 0 until n.childCount) findScrollable(n.getChild(i))?.let { return it }
        return null
    }

    /** Ekranda shu matn (yoki tavsif) bor elementni topib bosadi. */
    fun clickText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val q = text.lowercase().trim()
        val target = findByText(root, q) ?: return false
        var node: AccessibilityNodeInfo? = target
        while (node != null && !node.isClickable) node = node.parent
        return node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) ?: false
    }

    private fun findByText(n: AccessibilityNodeInfo?, q: String): AccessibilityNodeInfo? {
        if (n == null) return null
        val t = (n.text?.toString() ?: "").lowercase()
        val d = (n.contentDescription?.toString() ?: "").lowercase()
        if ((t.isNotEmpty() && t.contains(q)) || (d.isNotEmpty() && d.contains(q))) return n
        for (i in 0 until n.childCount) findByText(n.getChild(i), q)?.let { return it }
        return null
    }

    /** Fokusdagi (yoki birinchi) yozish maydoniga matn kiritadi. */
    fun typeText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val field = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditable(root)
        ?: return false
        val old = field.text?.toString()?.takeUnless { field.isShowingHintText } ?: ""
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                if (old.isBlank()) text else "$old $text"
            )
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun findEditable(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (n == null) return null
        if (n.isEditable) return n
        for (i in 0 until n.childCount) findEditable(n.getChild(i))?.let { return it }
        return null
    }

    /** Ekrandagi matnni o'qib berish uchun. */
    fun screenText(): String {
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || sb.length > 600) return
            n.text?.let { if (it.isNotBlank()) sb.append(it).append(". ") }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(rootInActiveWindow)
        return sb.toString()
    }
}
