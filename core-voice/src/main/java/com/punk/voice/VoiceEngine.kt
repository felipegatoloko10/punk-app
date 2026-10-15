package com.punk.voice

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
}
