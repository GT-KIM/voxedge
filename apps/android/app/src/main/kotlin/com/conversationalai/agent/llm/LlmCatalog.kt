package com.conversationalai.agent.llm

/** Which runtime executes the model. */
enum class LlmBackend { GENIE, LITERT }

/**
 * One selectable on-device LLM. [relPath] is where the model is provisioned under the app's
 * filesDir (adb push — see MODELS.md): a bundle DIRECTORY for Genie, a single .litertlm FILE for
 * LiteRT-LM.
 */
data class LlmModelSpec(
    val id: String,
    val displayName: String,
    val backend: LlmBackend,
    val relPath: String,
    val contextSize: Int,
    val sampling: LlmSampling,
)

/**
 * Code-as-registry of the models this app knows how to run. Selection is persisted by id
 * (RuntimeInitializer) and falls back to whatever is actually provisioned on the device. A JSON
 * manifest + in-app downloader can replace this once asset delivery moves off adb.
 */
object LlmCatalog {

    /** Primary, measured path: Qwen3-4B w4a16 Genie bundle on the HTP/NPU (SM8750). */
    val QWEN3_4B_GENIE = LlmModelSpec(
        id = "qwen3-4b-genie",
        displayName = "Qwen3 4B (Genie NPU)",
        backend = LlmBackend.GENIE,
        relPath = "llm_bundle",
        contextSize = 4096,
        sampling = LlmSampling(),   // temp 0.6 / top-k 20 / top-p 0.8 (bundle-aligned, temp lowered)
    )

    /** Gemma 4 E2B via LiteRT-LM (.litertlm, mixed 2/4/8-bit). Sampling starts at the Gemma-family
     *  recommendation (temp 1.0 is too hot for a spoken assistant; 0.7 as the starting point). */
    val GEMMA4_E2B_LITERT = LlmModelSpec(
        id = "gemma4-e2b-litert",
        displayName = "Gemma 4 E2B (LiteRT-LM)",
        backend = LlmBackend.LITERT,
        relPath = "llm_litert/gemma-4-E2B-it.litertlm",
        contextSize = 4096,
        sampling = LlmSampling(temp = 0.7f, topK = 64, topP = 0.95f),
    )

    val ALL = listOf(QWEN3_4B_GENIE, GEMMA4_E2B_LITERT)
    val DEFAULT = QWEN3_4B_GENIE

    fun byId(id: String?): LlmModelSpec = ALL.firstOrNull { it.id == id } ?: DEFAULT

    /** Whether [spec]'s model files exist under [filesDir] (Genie: non-empty bundle dir;
     *  LiteRT: the .litertlm file). */
    fun isProvisioned(filesDir: java.io.File, spec: LlmModelSpec): Boolean {
        val f = java.io.File(filesDir, spec.relPath)
        return when (spec.backend) {
            LlmBackend.GENIE -> f.isDirectory && f.list()?.isNotEmpty() == true
            LlmBackend.LITERT -> f.isFile
        }
    }
}
