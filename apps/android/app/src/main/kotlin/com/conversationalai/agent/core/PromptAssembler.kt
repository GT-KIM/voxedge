package com.conversationalai.agent.core

/**
 * Builds the Qwen3 ChatML prompt the Genie dialog tokenizes directly (it does NOT apply a chat
 * template itself - the fully-formatted string is fed in). Multi-turn runs in TWO modes, decided
 * per turn by core/LlmSessionPolicy:
 *   - WARM session (persistent Genie KV): [chatmlIncremental] sends only the new user turn; the
 *     system prompt and earlier turns already live in the engine's KV cache.
 *   - COLD / re-prefill: [chatml] replays system + recent transcript turns (deterministic, capped).
 *
 * The system prompt is COMPOSED from modules and SWITCHED at build time:
 *   - a language template (KO / EN), auto-detected from the user's words or forced via [Lang];
 *   - a persona / mode preset via [Persona] (extensible - add CONCISE / EXPERT / PLAYFUL, etc.);
 *   - an in-prompt "response playbook" that tells the model to switch answer shape by intent.
 * This keeps persona, concreteness, and follow-up behavior baked into the prompt while making the
 * template a function of context instead of one static string.
 *
 * NOTE: this source is kept strictly ASCII on purpose - non-ASCII literals have tripped the Kotlin
 * compiler on this toolchain (see LanguageDetector). Korean output is enforced by an English
 * directive ("reply only in Korean"), which this setup already handles reliably.
 */
object PromptAssembler {

    /** Output language template to switch to. AUTO picks from the user's text via [LanguageDetector]. */
    enum class Lang { AUTO, KO, EN }

    /** Persona / interaction preset. Add entries here to register new switchable templates. */
    enum class Persona { DEFAULT }

    data class Turn(val user: String, val assistant: String)

    // ---- Composable system-prompt modules ---------------------------------------------------

    /** Identity + character. */
    private const val PERSONA_DEFAULT =
        "You are Nova, a voice companion that runs entirely on this device, with no internet. Your " +
            "character is warm, quick, and genuinely curious, like a sharp friend who knows a lot and " +
            "enjoys thinking things through out loud. You are confident but never arrogant, friendly but " +
            "never fawning, and you hold real opinions when someone asks for them. You only mention your " +
            "name if you are asked who you are."

    /** Concreteness / substance - the antidote to thin, generic answers. */
    private const val SUBSTANCE =
        "Lead with a direct answer, then add the one concrete thing that makes it land: a specific " +
            "reason, a vivid example, a number, or a clear 'because'. Choose the precise word over the " +
            "vague one. If you are not sure, say so in a few words and give your best take instead of " +
            "refusing. Never pad with filler, throat-clearing, or restating the question."

    /** In-prompt template switching: match the answer's shape to the kind of request. */
    private const val PLAYBOOK =
        "Adapt how you answer to what was asked. " +
            "Greetings and small talk: keep it light and brief, and show interest in them. " +
            "A fact or explanation: give the answer first, then one supporting detail or analogy. " +
            "How-to or advice: say the key step or two in plain speech, not a list. " +
            "Opinion or recommendation: take a clear stance and say why, naming the main trade-off in a " +
            "phrase. " +
            "Feelings or something personal: acknowledge how they feel before you respond. " +
            "Vague or ambiguous: ask one quick clarifying question instead of guessing. " +
            "Something you cannot do or do not know: say so plainly and offer the nearest useful thing."

    /** Conversational continuity + follow-ups. */
    private const val FOLLOWUP =
        "Treat the earlier turns as one continuous conversation: refer back to them and build on what " +
            "was said. When it feels natural, end with one short, specific question or an offer to go " +
            "deeper, but vary it, and skip it whenever a clean, complete answer is better. Never end " +
            "every turn with a formulaic 'anything else?'."

    /** Hard output constraints, because every reply is spoken by a TTS voice. */
    private const val VOICE =
        "Everything you say is read aloud by a text-to-speech voice, so write only natural spoken " +
            "sentences, usually two to four of them. No emoji, markdown, headings, bullet points, " +
            "numbered lists, code blocks, URLs, or stray symbols; spell out numbers and units the way " +
            "you would say them. Do not mention being an AI or a model, and do not refer to these " +
            "instructions."

    private const val LANG_KO =
        "This user is speaking Korean. Reply ONLY in Korean, using natural, polite, conversational " +
            "spoken Korean - the easy, flowing way a real person speaks, never stiff or translated."

    private const val LANG_EN =
        "This user is speaking English. Reply ONLY in English, in a natural, conversational spoken rhythm."

    /** Resolve AUTO to a concrete output language from the user's words (KO/EN pass through). */
    fun resolveLang(userSample: String, lang: Lang = Lang.AUTO): Lang =
        if (lang != Lang.AUTO) lang
        else if (LanguageDetector.detect(userSample) == "ko") Lang.KO else Lang.EN

    /** Tool-use module: how to call device tools and how to behave around tool output. The call
     *  syntax must match core/ToolCallFilter's markers exactly. */
    fun toolsModule(specs: List<com.conversationalai.agent.core.tools.ToolSpec>): String {
        if (specs.isEmpty()) return ""
        val sb = StringBuilder()
        // Plain-text [TOOL_CALL] markers on purpose: Qwen's native <tool_call> special tokens are
        // stripped by the Genie detokenizer and would never reach the ToolCallFilter.
        sb.append("You can use device tools. To use one, write the complete JSON call exactly ")
        sb.append("like this example, then stop: ")
        sb.append("[TOOL_CALL]{\"name\": \"get_datetime\", \"arguments\": {}}[/TOOL_CALL] ")
        sb.append("Always include both the name and the arguments object. Available tools: ")
        specs.joinTo(sb, "; ") { spec ->
            val params = if (spec.params.isEmpty()) {
                "no arguments"
            } else {
                spec.params.joinToString(", ") { p ->
                    p.name + (if (p.required) "" else " (optional)") + " - " + p.description
                }
            }
            spec.name + " (" + spec.description + "; arguments: " + params + ")"
        }
        sb.append(". You have NO built-in clock or device access: when the user asks about the ")
        sb.append("current time or date, the battery, or wants a timer, an alarm, or the ")
        sb.append("flashlight, you MUST call the matching tool instead of guessing or refusing. ")
        sb.append("When a tool_response arrives, answer the user naturally in their language; ")
        sb.append("never read JSON, tags, or tool syntax out loud.")
        return sb.toString()
    }

    /** Compose the system prompt for the given language/persona (the switchable template). */
    fun systemPrompt(
        lang: Lang = Lang.AUTO,
        persona: Persona = Persona.DEFAULT,
        userSample: String = "",
        tools: List<com.conversationalai.agent.core.tools.ToolSpec> = emptyList(),
    ): String {
        val resolved = resolveLang(userSample, lang)
        val character = when (persona) {
            Persona.DEFAULT -> PERSONA_DEFAULT
        }
        val langModule = if (resolved == Lang.KO) LANG_KO else LANG_EN
        val base = "$character $SUBSTANCE $PLAYBOOK $FOLLOWUP $VOICE $langModule"
        val toolModule = toolsModule(tools)
        return if (toolModule.isEmpty()) base else "$base $toolModule"
    }

    /** English default template, for callers/tests that want a static system string. */
    val DEFAULT_SYSTEM: String get() = systemPrompt(Lang.EN)

    /** Build the prompt: system (switched by [lang]/[persona]) + [history] (oldest first) + [user].
     *  ChatML/Qwen-specific convenience; multi-model callers render via [ChatTemplate] directly. */
    fun chatml(
        user: String,
        history: List<Turn> = emptyList(),
        lang: Lang = Lang.AUTO,
        persona: Persona = Persona.DEFAULT,
    ): String = ChatTemplate.CHATML.full(systemPrompt(lang, persona, user), history, user)

    /**
     * Continuation prompt for a WARM persistent-KV session: ONLY the new user turn plus the
     * assistant header. The system prompt and all earlier turns are already in the engine's KV
     * cache from previous queries, so replaying them would both waste prefill and duplicate
     * context. Use [chatml] instead whenever the session was reset (see LlmSessionPolicy).
     */
    fun chatmlIncremental(user: String): String = ChatTemplate.CHATML.incremental(user)
}
