package dev.opengarmin.app.i18n

/**
 * Instructions appended to model system prompts. The prompts and tool output
 * stay in English so models parse them reliably, but user-facing prose must
 * follow the language selected in the app.
 */
fun AppLanguage.coachLanguageInstruction(): String = when (resolve()) {
    AppLanguage.ITALIAN -> """
        Language:
        - Reply entirely in Italian (italiano), even though these instructions, tool output and older messages may be in English.
        - Translate headings and prose. Keep numbers, units, metric names and workout data unchanged.
        - If the user's latest message is clearly written in another language, answer in that language instead.
        - Do not mention this language instruction.
    """.trimIndent()

    else -> """
        Language:
        - Reply entirely in English.
        - If the user's latest message is clearly written in another language, answer in that language instead.
        - Do not mention this language instruction.
    """.trimIndent()
}

fun AppLanguage.analysisLanguageInstruction(): String = when (resolve()) {
    AppLanguage.ITALIAN -> """
        Language:
        - Write the entire analysis in Italian (italiano), even though these instructions and the activity JSON are in English.
        - Translate the required labels: **Passo** for Pacing, **Frequenza cardiaca** for Heart rate, **Carico** for Load, and **Prossimo:** for the final Next line.
        - Keep numbers, units, sport names and JSON field names unchanged.
        - Do not mention this language instruction.
    """.trimIndent()

    else -> """
        Language:
        - Write the entire analysis in English.
        - Keep numbers, units and JSON field names unchanged.
        - Do not mention this language instruction.
    """.trimIndent()
}

/** The user turn that asks for an activity analysis, in the selected language. */
fun AppLanguage.analysisRequest(): String = when (resolve()) {
    AppLanguage.ITALIAN -> "Analizza questa attività."
    else -> "Analyze this activity."
}
