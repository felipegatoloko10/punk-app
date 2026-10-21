package com.punk.voice

import java.text.Normalizer

enum class WakeAction { IGNORE, ARM, SEND }

data class WakePhraseResult(val action: WakeAction, val text: String = "")

object WakePhraseParser {
    private val prefix = Regex("^(?:ei[\\s,.:;!?-]+)?punk\\b[\\s,.:;!?-]*(.*)$", RegexOption.IGNORE_CASE)

    fun parse(text: String, enabled: Boolean, armedUntil: Long, now: Long = System.currentTimeMillis()): WakePhraseResult {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return WakePhraseResult(WakeAction.IGNORE)
        if (!enabled || now < armedUntil) return WakePhraseResult(WakeAction.SEND, trimmed)
        val normalized = Normalizer.normalize(trimmed, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
        val command = prefix.matchEntire(normalized)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        if (!prefix.matches(normalized)) return WakePhraseResult(WakeAction.IGNORE)
        return if (command.isBlank()) WakePhraseResult(WakeAction.ARM) else WakePhraseResult(WakeAction.SEND, command)
    }
}
