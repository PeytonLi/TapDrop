package dev.peytonli.tapdrop.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.app.KeyguardManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import dev.peytonli.tapdrop.core.TapDetector
import dev.peytonli.tapdrop.core.TapDetectorState
import dev.peytonli.tapdrop.core.TapTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class TapSensorSnapshot(
    val sensorAvailable: Boolean = false,
    val listening: Boolean = false,
    val detector: TapDetectorState = TapDetectorState(),
    val triggers: List<TapTrigger> = emptyList(),
)

object TapSensorStatus {
    private val mutable = MutableStateFlow(TapSensorSnapshot())
    val state: StateFlow<TapSensorSnapshot> = mutable.asStateFlow()

    @Synchronized
    internal fun update(snapshot: TapSensorSnapshot) {
        mutable.value = snapshot
    }
}

class TapSensorController(
    context: Context,
    private val onTrigger: (TapTrigger) -> Unit,
) : SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = appContext.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val settings = TapSettings(appContext)
    private val detector = TapDetector(settings.config())
    private val magneticSensor = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED)
        ?: manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    private val linearSensor = manager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val fallbackAccelerometer = if (linearSensor == null) manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
    private var enabled = settings.enabled()
    private var peerOnline = false
    private var screenInteractive = powerManager.isInteractive
    private var unlocked = !keyguardManager.isKeyguardLocked
    private var listening = false
    private var gravity: FloatArray? = null
    private var closed = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenInteractive = false
                    unlocked = false
                }
                Intent.ACTION_SCREEN_ON -> {
                    screenInteractive = powerManager.isInteractive
                    unlocked = !keyguardManager.isKeyguardLocked
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenInteractive = powerManager.isInteractive
                    unlocked = true
                }
            }
            updateListening()
        }
    }

    init {
        settings.preferences.registerOnSharedPreferenceChangeListener(this)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(appContext, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        publish()
    }

    fun setPeerOnline(online: Boolean) {
        peerOnline = online
        updateListening()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        enabled = settings.enabled()
        detector.updateConfig(settings.config())
        updateListening()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val trigger = when (event.sensor.type) {
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> {
                val biasOffset = if (event.values.size >= 6) 3 else 0
                val x = event.values[0] - if (biasOffset == 3) event.values[3] else 0f
                val y = event.values[1] - if (biasOffset == 3) event.values[4] else 0f
                val z = event.values[2] - if (biasOffset == 3) event.values[5] else 0f
                detector.onMagnetic(event.timestamp, magnitude(x, y, z))
            }
            Sensor.TYPE_MAGNETIC_FIELD ->
                detector.onMagnetic(event.timestamp, magnitude(event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_LINEAR_ACCELERATION ->
                detector.onAccel(event.timestamp, magnitude(event.values[0], event.values[1], event.values[2]))
            Sensor.TYPE_ACCELEROMETER -> detector.onAccel(event.timestamp, highPassMagnitude(event.values))
            else -> null
        }
        publish(trigger)
        if (trigger != null) onTrigger(trigger)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    fun close() {
        if (closed) return
        closed = true
        manager.unregisterListener(this)
        settings.preferences.unregisterOnSharedPreferenceChangeListener(this)
        runCatching { appContext.unregisterReceiver(screenReceiver) }
        listening = false
        publish()
    }

    private fun updateListening() {
        if (closed) return
        val shouldListen = enabled && peerOnline && screenInteractive && unlocked &&
            magneticSensor != null && (linearSensor != null || fallbackAccelerometer != null)
        if (shouldListen == listening) return
        if (!shouldListen) {
            manager.unregisterListener(this)
            listening = false
        } else {
            val magneticStarted = manager.registerListener(this, magneticSensor!!, SensorManager.SENSOR_DELAY_GAME)
            val accelerationSensor = linearSensor ?: fallbackAccelerometer ?: return
            val accelerationStarted = manager.registerListener(
                this,
                accelerationSensor,
                SensorManager.SENSOR_DELAY_GAME,
            )
            listening = magneticStarted && accelerationStarted
            if (!listening) manager.unregisterListener(this)
        }
        publish()
    }

    private fun highPassMagnitude(values: FloatArray): Float {
        var priorGravity = gravity
        if (priorGravity == null) {
            priorGravity = values.copyOfRange(0, 3)
            gravity = priorGravity
            return 0f
        }
        val highPass = FloatArray(3)
        for (axis in 0..2) {
            highPass[axis] = values[axis] - priorGravity[axis]
            priorGravity[axis] = 0.8f * priorGravity[axis] + 0.2f * values[axis]
        }
        return magnitude(highPass[0], highPass[1], highPass[2])
    }

    private fun publish(trigger: TapTrigger? = null) {
        val previous = TapSensorStatus.state.value
        val triggers = if (trigger == null) previous.triggers
        else (listOf(trigger) + previous.triggers).take(10)
        TapSensorStatus.update(
            TapSensorSnapshot(
                sensorAvailable = magneticSensor != null && (linearSensor != null || fallbackAccelerometer != null),
                listening = listening,
                detector = detector.state,
                triggers = triggers,
            ),
        )
    }

    private fun magnitude(x: Float, y: Float, z: Float): Float = kotlin.math.sqrt(x * x + y * y + z * z)
}
