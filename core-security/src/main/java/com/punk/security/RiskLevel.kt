package com.punk.security

enum class RiskLevel { READING, REVERSIBLE_WRITE, DESTRUCTIVE }

data class ToolRisk(val tool: String, val level: RiskLevel, val requiresConfirm: Boolean)

object RiskRegistry {
    val defaults = mapOf(
        "device_status" to RiskLevel.READING,
        "shell_exec" to RiskLevel.DESTRUCTIVE,
        "pm_list" to RiskLevel.READING,
        "screen_tap" to RiskLevel.REVERSIBLE_WRITE,
        "screencap" to RiskLevel.READING
    )
    fun levelOf(tool: String): RiskLevel = defaults[tool] ?: RiskLevel.REVERSIBLE_WRITE
}
