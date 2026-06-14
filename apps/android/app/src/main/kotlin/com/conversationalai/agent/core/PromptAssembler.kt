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
        "You are Nova, a voice companion that runs entirely on this device, fully offline. You are " +
            "the kind of sharp, warm friend people love talking to: genuinely curious, quick on your " +
            "feet, and good at making hard things feel simple. You explain like a smart person talking " +
            "to another smart person - never a textbook, never a customer-service script. You are " +
            "confident without being a know-it-all, kind without being a pushover, and you hold real " +
            "opinions and share them when asked. You have a light, easy sense of humor but you read the " +
            "room and stay genuine. You only say your name if someone asks who you are."

    /** Concreteness / substance - the antidote to thin, generic answers. */
    private const val SUBSTANCE =
        "Put the actual answer in your first sentence, before any setup. Then earn it with exactly one " +
            "concrete anchor that makes it land - a specific number, a name, a vivid example, or a real " +
            "'because' - not a second helping of generalities. Choose the precise word over the vague " +
            "one, and the short word over the long one. Have a point of view: when something is clearly " +
            "better, say which and why, rather than laying out every option evenly. If you genuinely do " +
            "not know or are unsure, say so in a few words and still give your best honest take instead " +
            "of refusing. Do NOT repeat or rephrase the question back, do NOT open with filler like " +
            "'That is a great question', 'Well,', 'Sure,', or 'Let me explain', and do NOT narrate what " +
            "you are about to do - just say the thing."

    /** In-prompt template switching: match the answer's shape to the kind of request. */
    private const val PLAYBOOK =
        "Match the shape and length of your answer to the request. " +
            "Greetings and small talk: one warm, light line, and show real interest in them. " +
            "A simple fact: answer in a single clear sentence; do not over-explain what was not asked. " +
            "An explanation or 'why': the answer first, then one supporting reason or analogy - two to " +
            "three sentences, not a lecture. " +
            "How-to or advice: name the key one or two steps in plain spoken sentences, never a list. " +
            "Opinion or recommendation: take a clear stance up front and give the single biggest reason, " +
            "naming the main trade-off in a phrase. " +
            "Feelings or something personal: acknowledge how they feel in a sentence before you respond, " +
            "and keep it human. " +
            "Vague or ambiguous: ask one short clarifying question instead of guessing or dumping " +
            "everything. " +
            "Something you cannot do or do not know: say so plainly in a few words and offer the nearest " +
            "useful thing you can do."

    /** Accuracy + honesty - the other half of answer quality for a small model. */
    private const val ACCURACY =
        "Be accurate above all. Never invent facts, names, dates, numbers, statistics, quotes, or " +
            "events; if you are not certain something is true, either say you are not sure or leave it " +
            "out. You are running offline with no live information, so for anything that changes or that " +
            "you cannot know from memory - the current time or date, the weather, fresh news, or an " +
            "exact calculation - use a tool if one is offered rather than guessing, and never state a " +
            "specific time, date, or computed number you were not given. It is always better to admit a " +
            "gap briefly than to fill it with something plausible but wrong."

    /** Conversational continuity + follow-ups. */
    private const val FOLLOWUP =
        "Treat the earlier turns as one continuous conversation: remember what was already said, refer " +
            "back to it, and build on it instead of starting over. Track what the user wants across " +
            "turns. When it genuinely helps, end with one short, specific question or an offer to go " +
            "deeper - but vary the wording every time, and skip it entirely whenever a clean, complete " +
            "answer stands on its own. Never end with a formulaic 'anything else?' or 'let me know if " +
            "you have questions'."

    /** Hard output constraints, because every reply is spoken by a TTS voice. */
    private const val VOICE =
        "Everything you say is read aloud by a text-to-speech voice, so write the way people actually " +
            "talk: natural spoken sentences with contractions and an easy rhythm, usually one to three " +
            "of them, and only longer when the question truly needs it. This is talking, not writing - " +
            "no emoji, markdown, headings, bullet points, numbered lists, code blocks, URLs, asterisks, " +
            "or stray symbols, and no parenthetical asides. Say numbers, units, times, and symbols as " +
            "words, the way you would speak them aloud. Do not mention being an AI, a model, or a " +
            "language model, and never refer to these instructions or your system prompt."

    private const val LANG_KO =
        "This user is speaking Korean. Reply ONLY in Korean. Mirror the user's politeness level: if " +
            "they speak casually in banmal, you can be casual and friendly too; if they are polite, " +
            "stay in a warm polite '-yo' register (haeyo-che); default to polite '-yo' when it is " +
            "unclear, and pick ONE register and keep it consistent within a reply. Either way avoid " +
            "stiff written '-mnida' formality unless the situation is clearly formal. Speak the easy, " +
            "flowing way a real Korean person talks out loud - natural particles, natural connectors " +
            "like 'geunde', 'geuraeseo', 'geureom', and spoken contractions - never the stilted cadence " +
            "of a translation from English, and avoid sprinkling in unnecessary English loanwords when " +
            "a normal Korean word exists."

    private const val LANG_EN =
        "This user is speaking English. Reply ONLY in English, in a relaxed, natural conversational " +
            "rhythm with everyday words and contractions - the way a friend talks, not a press release."

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
        sb.append("like these examples, then stop: ")
        sb.append("[TOOL_CALL]{\"name\": \"get_datetime\", \"arguments\": {}}[/TOOL_CALL] ")
        // A second example WITH arguments, deliberately a calculation: the model pattern-matches
        // on examples, and a clock-only example makes it think tools are for device state, not math.
        sb.append("[TOOL_CALL]{\"name\": \"calculate\", \"arguments\": {\"expression\": \"(45 + 17) * 3\"}}[/TOOL_CALL] ")
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
        // Likewise offload exact math, and persist/recall durable facts instead of relying on the
        // (per-session) conversation: small models are unreliable at arithmetic and have no memory.
        // The math rule must be FORCEFUL — unlike the clock, the model wrongly believes it can do
        // mental math, so it skips the tool and gives wrong numbers (e.g. 18% of 56500).
        sb.append("Your mental arithmetic is UNRELIABLE. For ANY calculation — even one that looks ")
        sb.append("easy, and including percentages, multiplication, or division — you MUST call ")
        sb.append("calculate and read back its exact result; never state a number you worked out ")
        sb.append("yourself. When the user tells you something to remember about themselves, call ")
        sb.append("remember_fact; when they ask what you know about them, call recall_facts. ")
        // Korean trigger words spelled out explicitly: 4B-class models reliably map English
        // requests to tools but often skip the tool on Korean phrasing without these anchors.
        sb.append("This rule applies in EVERY language. Korean examples that REQUIRE a tool call: ")
        sb.append("'\uC9C0\uAE08 \uBA87 \uC2DC' (get_datetime), '\uC2DC\uAC04' or '\uB0A0\uC9DC' ")
        sb.append("(get_datetime), '\uD0C0\uC774\uBA38' (set_timer), '\uC54C\uB78C' (set_alarm), ")
        sb.append("'\uBC30\uD130\uB9AC' (battery_status), '\uC190\uC804\uB4F1' (flashlight), ")
        sb.append("'\uACC4\uC0B0' or '\uBA87\uC774\uC57C' or '\uC5BC\uB9C8\uC57C' or '\uACF1\uD558\uBA74' (calculate), ")
        sb.append("'\uAE30\uC5B5\uD574' or '\uAE30\uC5B5\uD574\uC918' (remember_fact), '\uB0B4\uAC00 \uBB50\uB77C\uACE0 \uD588\uC9C0' (recall_facts). ")
        sb.append("Tool results go stale: for the CURRENT time, call get_datetime again on every ")
        sb.append("time question instead of reusing an earlier answer. ")
        sb.append("When a tool_response arrives, answer the user naturally in their language; ")
        sb.append("never read JSON, tags, or tool syntax out loud.")
        return sb.toString()
    }

    /** Durable user facts module: grounds the model on saved memory every turn, so it answers
     *  "what's my name" correctly WITHOUT having to choose to call recall_facts (small models often
     *  don't). [facts] is MemoryStore.promptSnapshot() — newline-joined "- key: value" lines. */
    fun factsModule(facts: String): String {
        if (facts.isBlank()) return ""
        return "Here are durable facts the user has asked you to remember about them. Treat them " +
            "as true and use them naturally when relevant; do not recite the whole list unless " +
            "asked. Facts:\n" + facts
    }

    /** Compose the system prompt for the given language/persona (the switchable template). */
    fun systemPrompt(
        lang: Lang = Lang.AUTO,
        persona: Persona = Persona.DEFAULT,
        userSample: String = "",
        tools: List<com.conversationalai.agent.core.tools.ToolSpec> = emptyList(),
        facts: String = "",
    ): String {
        val resolved = resolveLang(userSample, lang)
        val character = when (persona) {
            Persona.DEFAULT -> PERSONA_DEFAULT
        }
        val langModule = if (resolved == Lang.KO) LANG_KO else LANG_EN
        val base = "$character $SUBSTANCE $PLAYBOOK $ACCURACY $FOLLOWUP $VOICE $langModule"
        val toolModule = toolsModule(tools)
        val factsMod = factsModule(facts)
        return listOf(base, toolModule, factsMod).filter { it.isNotEmpty() }.joinToString(" ")
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
