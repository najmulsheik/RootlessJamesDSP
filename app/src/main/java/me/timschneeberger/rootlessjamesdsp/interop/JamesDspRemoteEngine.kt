package me.timschneeberger.rootlessjamesdsp.interop

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.audiofx.AudioEffect
import android.media.audiofx.AudioEffectHidden
import android.widget.Toast
import me.timschneeberger.rootlessjamesdsp.interop.structure.EelVmVariable
import me.timschneeberger.rootlessjamesdsp.utils.AudioEffectExtensions.getParameterInt
import me.timschneeberger.rootlessjamesdsp.utils.AudioEffectExtensions.setParameterCharBuffer
import me.timschneeberger.rootlessjamesdsp.utils.AudioEffectExtensions.setParameterFloatArray
import me.timschneeberger.rootlessjamesdsp.utils.AudioEffectExtensions.setParameterImpulseResponseBuffer
import me.timschneeberger.rootlessjamesdsp.utils.Constants
import me.timschneeberger.rootlessjamesdsp.utils.ContextExtensions.registerLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.ContextExtensions.unregisterLocalReceiver
import me.timschneeberger.rootlessjamesdsp.utils.toShort
import timber.log.Timber
import java.util.*
import kotlin.math.roundToInt

class JamesDspRemoteEngine(
    context: Context,
    val sessionId: Int,
    val priority: Int,
    callbacks: JamesDspWrapper.JamesDspCallbacks? = null,
) : JamesDspBaseEngine(context, callbacks) {

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Constants.ACTION_SAMPLE_RATE_UPDATED -> syncWithPreferences(arrayOf(Constants.PREF_CONVOLVER))
                Constants.ACTION_PREFERENCES_UPDATED -> syncWithPreferences()
                Constants.ACTION_SERVICE_RELOAD_LIVEPROG -> syncWithPreferences(arrayOf(Constants.PREF_LIVEPROG))
                Constants.ACTION_SERVICE_HARD_REBOOT_CORE -> rebootEngine()
                Constants.ACTION_SERVICE_SOFT_REBOOT_CORE -> { clearCache(); syncWithPreferences() }
            }
        }
    }

    var effect = createEffect()

    override var enabled: Boolean
        set(value) { effect.enabled = value }
        get() = effect.enabled

    override var sampleRate: Float
        get() {
            super.sampleRate = effect.getParameterInt(20001)?.toFloat() ?: -0f
            return super.sampleRate
        }
        set(_){}

    init {
        syncWithPreferences()

        val filter = IntentFilter()
        filter.addAction(Constants.ACTION_PREFERENCES_UPDATED)
        filter.addAction(Constants.ACTION_SAMPLE_RATE_UPDATED)
        filter.addAction(Constants.ACTION_SERVICE_RELOAD_LIVEPROG)
        filter.addAction(Constants.ACTION_SERVICE_HARD_REBOOT_CORE)
        filter.addAction(Constants.ACTION_SERVICE_SOFT_REBOOT_CORE)
        context.registerLocalReceiver(broadcastReceiver, filter)
    }

    private fun createEffect(): AudioEffectHidden {
        return try {
            AudioEffectHidden(EFFECT_TYPE_CUSTOM, EFFECT_JAMESDSP, priority, sessionId)
        } catch (e: Exception) {
            Timber.e("Failed to create JamesDSP effect")
            Timber.e(e)
            throw IllegalStateException(e)
        }
    }

    private fun checkEngine() {
        if (!isPidValid) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine probably crashed or detached.")
            Toast.makeText(context, "Engine crashed. Rebooting JamesDSP.", Toast.LENGTH_SHORT).show()
            rebootEngine()
        }

        if (isSampleRateAbnormal) {
            Timber.e("PID ($pid) for session $sessionId invalid. Engine crashed.")
            Toast.makeText(context, "Abnormal sampling rate. Rebooting JamesDSP.", Toast.LENGTH_SHORT).show()
            rebootEngine()
        }
    }

    private fun rebootEngine() {
        try {
            effect.release()
            effect = createEffect()
        }
        catch (ex: IllegalStateException) {
            Timber.e("Failed to re-instantiate JamesDSP effect")
            Timber.e(ex.cause)
            return
        }
    }

    override fun syncWithPreferences(forceUpdateNamespaces: Array<String>?) {
        checkEngine()
        super.syncWithPreferences(forceUpdateNamespaces)
    }

    override fun close() {
        context.unregisterLocalReceiver(broadcastReceiver)
        effect.release()
        super.close()
    }

    override fun setOutputControl(threshold: Float, release: Float, postGain: Float): Boolean {
        return effect.setParameterFloatArray(
            1500,
            floatArrayOf(threshold, release, postGain)
        ) == AudioEffect.SUCCESS
    }

    override fun setCompressor(
        enable: Boolean,
        maxAttack: Float,
        maxRelease: Float,
        adaptSpeed: Float,
    ): Boolean {
        return (effect.setParameterFloatArray(
            115,
            floatArrayOf(maxAttack, maxRelease, adaptSpeed)
        ) == AudioEffect.SUCCESS) and (effect.setParameter(1200, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setReverb(enable: Boolean, preset: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(128, preset.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1203, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeed(enable: Boolean, mode: Int): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(188, mode.toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1208, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setCrossfeedCustom(enable: Boolean, fcut: Int, feed: Int): Boolean {
        throw UnsupportedOperationException()
    }

    override fun setBassBoost(enable: Boolean, maxGain: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(112, maxGain.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1201, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setStereoEnhancement(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(137, level.roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1204, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setVacuumTube(enable: Boolean, level: Float): Boolean {
        var ret = true
        if (enable)
            ret = effect.setParameter(150, (level * 1000).roundToInt().toShort()) == AudioEffect.SUCCESS
        return ret and (effect.setParameter(1206, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setFirEqualizerInternal(
        enable: Boolean,
        filterType: Int,
        interpolationMode: Int,
        bands: DoubleArray,
    ): Boolean {
        var ret = true

        if (enable) {
            val properties = floatArrayOf(
                if(filterType == 1) 1.0f else -1.0f,
                if(interpolationMode == 1) 1.0f else -1.0f
            )

            val bandsF = FloatArray(bands.size)
            bands.forEachIndexed { i, x -> bandsF[i] = x.toFloat() }

            ret = effect.setParameterFloatArray(116, properties + bandsF) == AudioEffect.SUCCESS
        }

        return ret and (effect.setParameter(1202, enable.toShort()) == AudioEffect.SUCCESS)
    }

    override fun setVdcInternal(enable: Boolean, vdc: String): Boolean {
        if(enable)
            effect.setParameterCharBuffer(12001, 10009, vdc)
        return effect.setParameter(1212, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setConvolverInternal(
        enable: Boolean,
        impulseResponse: FloatArray,
        irChannels: Int,
        irFrames: Int,
    ): Boolean {
        if(enable)
            effect.setParameterImpulseResponseBuffer(12000, 10004, impulseResponse, irChannels)
        return effect.setParameter(1205, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setGraphicEqInternal(enable: Boolean, bands: String): Boolean {
        if(enable)
            effect.setParameterCharBuffer(12001, 10006, bands)
        return effect.setParameter(1210, enable.toShort()) == AudioEffect.SUCCESS
    }

    override fun setLiveprogInternal(enable: Boolean, name: String, path: String): Boolean {
        if(enable)
            effect.setParameterCharBuffer(12001, 10010, path)
        return effect.setParameter(1213, enable.toShort()) == AudioEffect.SUCCESS
    }

    // Feature support
    override fun supportsEelVmAccess(): Boolean { return false }
    override fun supportsCustomCrossfeed(): Boolean { return false }

    // EEL VM utilities (unavailable)
    override fun enumerateEelVariables(): ArrayList<EelVmVariable> { return arrayListOf() }
    override fun manipulateEelVariable(name: String, value: Float): Boolean { return false }
    override fun freezeLiveprogExecution(freeze: Boolean) {}

    // Status
    val pid: Int
        get() = effect.getParameterInt(20002) ?: -1
    val isPidValid: Boolean
        get() = pid > 0
    val isSampleRateAbnormal: Boolean
        get() = sampleRate <= 0
    val paramCommitCount: Int
        get() = effect.getParameterInt(19998) ?: -1
    val isPresetInitialized: Boolean
        get() = paramCommitCount > 0
    val bufferLength: Int
        get() = effect.getParameterInt(19999) ?: -1
    val allocatedBlockLength: Int
        get() = effect.getParameterInt(20000) ?: -1

    companion object {
        private val EFFECT_TYPE_CUSTOM = UUID.fromString("f98765f4-c321-5de6-9a45-123459495ab2")
        private val EFFECT_JAMESDSP = UUID.fromString("f27317f4-c984-4de6-9a90-545759495bf2")

        fun isPluginInstalled(): Boolean {
            return try {
                AudioEffect
                    .queryEffects()
                    .orEmpty()
                    .filter { it.uuid == EFFECT_JAMESDSP }
                    .firstOrNull { it.name.contains("JamesDSP") } != null
            } catch (e: Exception) {
                Timber.e("isPluginInstalled: exception raised")
                Timber.e(e)
                false
            }
        }
    }
}