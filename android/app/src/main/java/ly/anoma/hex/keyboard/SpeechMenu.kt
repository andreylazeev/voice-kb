package ly.anoma.hex.keyboard

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import ly.anoma.hex.voiceColor
import ly.anoma.hex.R
import ly.anoma.hex.VoiceTheme
import ly.anoma.hex.customThemeEditor
import ly.anoma.hex.themePicker
import ly.anoma.hex.label
import ly.anoma.hex.speech.DictationLanguage
import ly.anoma.hex.speech.Style
import ly.anoma.hex.useInter
import kotlin.math.roundToInt

/** Stays inside the IME window; opening a choice never changes the active editor. */
internal class SpeechMenu(context: Context, private val ime: HexKeyboard,
                          private val title: (String) -> Unit, private val close: () -> Unit) : ScrollView(context) {
    private enum class Page(val title: Int) {
        Home(R.string.keyboard_menu), Cleanup(R.string.settings_clean), Language(R.string.keyboard_menu_language),
        Layout(R.string.keyboard_switch_layout), Modes(R.string.keyboard_modes), Feedback(R.string.keyboard_menu_feedback),
        Theme(R.string.settings_theme), CustomTheme(R.string.theme_custom), Accents(R.string.keyboard_menu_accents),
    }
    private val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var page = Page.Home
    private val prefs get() = ime.preferences
    init { addView(body); isFillViewport = true }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).roundToInt()
    private fun s(id: Int) = context.getString(id)
    private fun more(value: String) = context.getString(R.string.keyboard_menu_more_value, value)
    private fun onOff(on: Boolean) = s(if (on) R.string.keyboard_on else R.string.keyboard_off)
    private val chosen get() = s(R.string.keyboard_menu_chosen)
    fun home() = show(Page.Home)
    fun language() = show(Page.Language)
    fun accents(values: List<String>, pick: (String) -> Unit) {
        page = Page.Accents; title(s(page.title)); body.removeAllViews(); scrollTo(0, 0)
        values.forEach { v -> row(v, "") { pick(v) } }
    }
    /** More characters came from a key, so going back leaves the menu. */
    fun back() { when (page) {
        Page.Home, Page.Accents -> close()
        Page.CustomTheme -> show(Page.Theme)
        else -> home()
    } }

    private fun show(next: Page) {
        page = next; title(s(next.title)); body.removeAllViews(); scrollTo(0, 0)
        when (next) {
            Page.Home -> {
                row(s(R.string.settings_clean), more(s(prefs.style.label))) { show(Page.Cleanup) }
                row(s(R.string.keyboard_modes), more(s(ime.panelSettings.mode.title))) { show(Page.Modes) }
                row(s(R.string.settings_theme), more(s(ime.appearance.theme.label))) { show(Page.Theme) }
                row(s(R.string.keyboard_menu_language), more(prefs.language?.label ?: s(R.string.keyboard_menu_automatic))) { show(Page.Language) }
                row(s(R.string.keyboard_switch_layout), more(ime.layoutLanguage.label)) { show(Page.Layout) }
                row(s(R.string.keyboard_menu_feedback), s(R.string.keyboard_menu_arrow)) { show(Page.Feedback) }
                // The system's own switcher sits in the navigation bar; this reaches the next keyboard without it.
                row(s(R.string.keyboard_next), "") { close(); ime.nextIme() }
            }
            Page.Feedback -> {
                val f = ime.feedback
                row(s(R.string.keyboard_menu_popups), onOff(f.popups)) { f.popups = !f.popups; show(next) }
                row(s(R.string.keyboard_menu_haptics), onOff(f.haptics)) { f.haptics = !f.haptics; show(next) }
            }
            Page.Cleanup -> listOf(Style.Polished, Style.Verbatim).forEach { value -> row(s(value.label), if (prefs.style == value) chosen else "") {
                prefs.style = value; home()
            } }
            Page.Language -> (listOf(null) + DictationLanguage.supported).forEach { value ->
                row(value?.label ?: s(R.string.keyboard_menu_automatic), if (prefs.language == value) chosen else "") {
                    prefs.language = value; ime.nextEnglish = null; close()
                }
            }
            Page.Layout -> listOf(ly.anoma.hex.speech.Spoken.Russian, ly.anoma.hex.speech.Spoken.English,
                ly.anoma.hex.speech.Spoken.Ukrainian).forEach { value ->
                row(value.label, if (ime.layoutLanguage == value) chosen else "") {
                    ime.feedback.layout = value
                    close()
                }
            }
            Page.Modes -> {
                val current = ime.panelSettings
                KeyboardMode.entries.forEach { mode ->
                    row(s(mode.title), if (current.mode == mode) chosen else "") { ime.chooseMode(mode); close() }
                }
                if (current.mode == KeyboardMode.Floating) {
                    val level = (current.opacity * 100).toInt()
                    row(s(R.string.mode_opacity), "$level%") {
                        val nextOpacity = if (current.opacity > .95f) .68f else if (current.opacity < .75f) .84f else 1f
                        ime.updatePanel(current.copy(opacity = nextOpacity))
                        show(Page.Modes)
                    }
                }
            }
            Page.Theme -> body.addView(themePicker(context, ime.appearance) { theme ->
                if (theme == VoiceTheme.Custom) show(Page.CustomTheme)
                else {
                    close()
                    ime.appearance.theme = theme
                }
            })
            Page.CustomTheme -> body.addView(customThemeEditor(context, ime.appearance.custom) { colors ->
                close()
                ime.appearance.saveCustom(colors)
            })
            Page.Accents -> Unit
        }
    }

    private fun row(label: String, value: String, click: () -> Unit) {
        val line = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16), dp(4), dp(16), dp(4))
            minimumHeight = dp(44); isFocusable = true
            contentDescription = if (value.isEmpty()) label else label + ", " + value.trimEnd(' ', '›')
            setOnClickListener { click() }
        }
        fun text(s: String, weight: Int, color: Int) = TextView(context).apply {
            text = s; textSize = 15f; useInter(weight); includeFontPadding = false
            setTextColor(context.voiceColor(color)); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        line.addView(text(label, 500, R.color.ink), LinearLayout.LayoutParams(0, -2, 1f))
        line.addView(text(value, 450, R.color.quiet))
        body.addView(line)
    }
}
