package dev.peytonli.tapdrop.service

import android.content.Context
import android.content.SharedPreferences
import dev.peytonli.tapdrop.core.TapConfig

data class TapPreferenceState(
    val enabled: Boolean = true,
    val config: TapConfig = TapConfig(),
    val dryRun: Boolean = false,
)

class TapSettings(context: Context) {
    val preferences: SharedPreferences =
        context.getSharedPreferences("tapdrop_magnet", Context.MODE_PRIVATE)

    fun snapshot() = TapPreferenceState(enabled(), config(), dryRun())

    fun enabled(): Boolean = preferences.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun dryRun(): Boolean = preferences.getBoolean(KEY_DRY_RUN, false)

    fun setDryRun(dryRun: Boolean) {
        preferences.edit().putBoolean(KEY_DRY_RUN, dryRun).apply()
    }

    fun config() = TapConfig(
        deltaUt = preferences.getFloat(KEY_DELTA, 150f),
        absoluteUt = preferences.getFloat(KEY_ABSOLUTE, 250f),
        bumpMs2 = preferences.getFloat(KEY_BUMP, 2.5f),
        windowMs = preferences.getFloat(KEY_WINDOW, 350f).toLong(),
    )

    fun setConfig(config: TapConfig) {
        preferences.edit()
            .putFloat(KEY_DELTA, config.deltaUt)
            .putFloat(KEY_ABSOLUTE, config.absoluteUt)
            .putFloat(KEY_BUMP, config.bumpMs2)
            .putFloat(KEY_WINDOW, config.windowMs.toFloat())
            .apply()
    }

    companion object {
        const val KEY_ENABLED = "magnetTapEnabled"
        const val KEY_DRY_RUN = "tapDryRun"
        const val KEY_DELTA = "tapDeltaUt"
        const val KEY_ABSOLUTE = "tapAbsoluteUt"
        const val KEY_BUMP = "tapBumpMs2"
        const val KEY_WINDOW = "tapWindowMs"
    }
}
