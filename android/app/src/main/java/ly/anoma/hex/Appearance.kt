package ly.anoma.hex

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils

internal enum class VoiceTheme(val label: Int, private val nightMode: Int? = null) {
    System(R.string.theme_system),
    Light(R.string.theme_light, Configuration.UI_MODE_NIGHT_NO),
    Dark(R.string.theme_dark, Configuration.UI_MODE_NIGHT_YES),
    AndroidColors(R.string.theme_android),
    Ocean(R.string.theme_ocean, Configuration.UI_MODE_NIGHT_YES),
    Plum(R.string.theme_plum, Configuration.UI_MODE_NIGHT_YES),
    Sand(R.string.theme_sand, Configuration.UI_MODE_NIGHT_NO),
    Custom(R.string.theme_custom);

    fun context(base: Context, custom: ThemeColors): Context {
        val night = if (this == Custom) {
            if (ColorUtils.calculateLuminance(custom.background) < .179) Configuration.UI_MODE_NIGHT_YES
            else Configuration.UI_MODE_NIGHT_NO
        } else nightMode
        val actual = if (night == null) base else base.createConfigurationContext(Configuration().apply {
            uiMode = (base.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        })
        val colors = when (this) {
            AndroidColors -> {
                val resources = actual.resources
                val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                // Activities also resolve the system palette from attachBaseContext, before a theme exists.
                ThemeColors(
                    resources.getColor(if (dark) android.R.color.system_neutral1_900 else android.R.color.system_neutral1_50, null),
                    resources.getColor(if (dark) android.R.color.system_accent1_200 else android.R.color.system_accent1_300, null),
                )
            }
            Ocean -> ThemeColors(0xff18273a.toInt(), 0xff82d5ff.toInt())
            Plum -> ThemeColors(0xff302438.toInt(), 0xfff2adda.toInt())
            Sand -> ThemeColors(0xfffff3e3.toInt(), 0xffeeac73.toInt())
            Custom -> custom
            else -> null
        }
        return if (colors == null) actual else PaletteContext(actual, colors.palette())
    }
}

internal data class ThemeColors(val background: Int, val accent: Int) {
    fun palette(): Map<Int, Int> {
        val dark = ColorUtils.calculateLuminance(background) < .179
        val ink = if (dark) Color.WHITE else Color.BLACK
        val muted = blend(background, ink, .70f)
        val key = blend(background, if (dark) ink else Color.WHITE, if (dark) .11f else .75f)
        val modifier = blend(background, ink, if (dark) .17f else .10f)
        val accentInk = if (ColorUtils.calculateContrast(Color.BLACK, accent) > 5) Color.BLACK else Color.WHITE
        var brand = accent
        while (ColorUtils.calculateContrast(brand, background) < 4.5) brand = blend(brand, ink, .20f)
        val border = blend(background, ink, .23f)
        val pressed = blend(key, ink, .23f)
        return mapOf(
            R.color.page to if (dark) blend(background, Color.BLACK, .20f) else blend(background, Color.WHITE, .65f),
            R.color.ink to ink, R.color.quiet to muted, R.color.rule to border, R.color.sheet to modifier,
            R.color.keyboard to background, R.color.key to key, R.color.modifier to modifier, R.color.pressed to pressed,
            R.color.floating_shell to background, R.color.floating_outline to border, R.color.floating_key to key,
            R.color.floating_modifier to modifier, R.color.floating_enter to blend(key, accent, .22f),
            R.color.floating_grip to blend(background, brand, .56f), R.color.floating_pressed to pressed,
            R.color.floating_brand to brand, R.color.floating_accent to accent, R.color.floating_on_accent to accentInk,
            R.color.floating_swipe to brand, R.color.emoji_pressed to pressed,
        )
    }

    private fun blend(first: Int, second: Int, ratio: Float) = ColorUtils.blendARGB(first, second, ratio)
}

private class PaletteContext(base: Context, val palette: Map<Int, Int>) : ContextWrapper(base)

internal fun Context.voiceColor(id: Int): Int {
    var current = this
    while (current is ContextWrapper) {
        if (current is PaletteContext) return current.palette[id] ?: getColor(id)
        val base = current.baseContext
        if (base === current) break
        current = base
    }
    return getColor(id)
}

@Composable
internal fun voiceColorResource(id: Int) =
    androidx.compose.ui.graphics.Color(LocalContext.current.voiceColor(id))

internal class Appearance(context: Context) {
    private val preferences = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
    var theme: VoiceTheme
        get() = VoiceTheme.entries.firstOrNull { it.name == preferences.getString("theme", null) } ?: VoiceTheme.System
        set(value) { preferences.edit().putString("theme", value.name).apply() }

    val custom: ThemeColors
        get() = ThemeColors(preferences.getInt("customBackground", 0xff212d2d.toInt()),
            preferences.getInt("customAccent", 0xffa3f5d1.toInt()))

    fun context(base: Context) = theme.context(base, custom)

    fun saveCustom(colors: ThemeColors) {
        preferences.edit().putInt("customBackground", colors.background)
            .putInt("customAccent", colors.accent).putString("theme", VoiceTheme.Custom.name).apply()
    }

    fun observe(onChanged: () -> Unit): () -> Unit {
        var last = theme to custom
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val current = theme to custom
            if (last != current) {
                last = current
                onChanged()
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}
