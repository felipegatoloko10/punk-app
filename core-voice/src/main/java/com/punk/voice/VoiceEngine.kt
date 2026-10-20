package com.punk.voice

import android.content.Context

interface VoiceEngine {
    suspend fun startListening(onPartial: (String)->Unit, onFinal: (String)->Unit)
    suspend fun stopListening()
    suspend fun speak(text: String, onDone: ()->Unit = {})
    fun isListening(): Boolean
    fun amplitude(): Float
}

class NoopVoiceEngine : VoiceEngine {
    override suspend fun startListening(onPartial:(String)->Unit, onFinal:(String)->Unit) {}
    override suspend fun stopListening() {}
    override suspend fun speak(text:String, onDone:()->Unit){ onDone() }
    override fun isListening()=false
    override fun amplitude()=0f
}

object VoiceManager {
    var engine: VoiceEngine = NoopVoiceEngine()
    private var androidVoiceEngine: AndroidVoiceEngine? = null

    fun cancelAll() { androidVoiceEngine?.cancelListening(); androidVoiceEngine?.stopSpeaking() }
    fun release() { androidVoiceEngine?.destroy(); androidVoiceEngine = null; engine = NoopVoiceEngine() }

    fun getOrCreateAndroidEngine(context: Context): AndroidVoiceEngine {
        val current = androidVoiceEngine
        if (current != null) return current
        val newEngine = AndroidVoiceEngine(context.applicationContext)
        androidVoiceEngine = newEngine
        engine = newEngine
        return newEngine
    }
}
