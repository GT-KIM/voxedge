// Resident Qwen3-4B Genie LLM engine (QAIRT Genie Dialog API) for the Android app, Phase 3 step 3.
//
// Loads the w4a16 Genie bundle ONCE (4 ctx-bins on the HTP/DSP via QnnHtp) and streams the
// assistant response token-by-token through GenieDialog_query's callback. The runtime side of the
// "prompt -> clause stream" path: tokens flow to the Kotlin ClauseSegmenter -> TTS.
//
// The genie_config.json in the bundle uses paths RELATIVE to the bundle dir (tokenizer.json,
// the 4 ctx-bin .bin files, htp_backend_ext_config.json). genie-t2t-run cd'd into the bundle; we
// instead patch those filenames to absolute (bundleDir/...) before GenieDialogConfig_createFromJson,
// so we never mutate the process CWD (the TTS engine resolves its DLCs by absolute path too).
//
// DSP/HTP runtime (ADSP_LIBRARY_PATH + the v79 skels in filesDir/qairt_dsp, LD_LIBRARY_PATH ->
// nativeLibDir) is already set up by MainActivity for the TTS engine; Genie reuses the same QnnHtp
// v79 backend, so no extra DSP provisioning is needed beyond shipping libGenie.so in jniLibs.
//
// Build: CMake target genie_llm -> libgenie_llm.so, linking the imported libGenie.so.

#include <jni.h>
#include <android/log.h>

#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>

#include "Genie/GenieDialog.h"

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "GenieLlm", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "GenieLlm", __VA_ARGS__)

namespace {

struct LlmEngine {
  GenieDialogConfig_Handle_t cfg = nullptr;
  GenieDialog_Handle_t dialog = nullptr;
  uint32_t ctxSize = 4096;  // context.size from genie_config.json (occupancy normalization)
};

// userData for the streaming query callback. GenieDialog_query is synchronous and invokes the
// callback on the SAME thread, so the JNIEnv* and the local sink ref captured here stay valid
// for the whole query — no AttachCurrentThread / global ref needed.
struct CbCtx {
  JNIEnv* env;
  jobject sink;       // TokenSink instance
  jmethodID onToken;  // void onToken(String, int)
  bool aborted = false;  // set when the callback sees GENIE_DIALOG_SENTENCE_ABORT
};

// nativeGenerate status codes (mirrored by GenieLlm.kt -> LlmEngine.Result).
constexpr jint GEN_OK = 0;
constexpr jint GEN_CONTEXT_EXCEEDED = 1;
constexpr jint GEN_ABORTED = 2;
constexpr jint GEN_ERROR = 3;

std::string readFile(const std::string& p) {
  std::ifstream f(p, std::ios::binary);
  std::stringstream ss;
  ss << f.rdbuf();
  return ss.str();
}

void replaceAll(std::string& s, const std::string& from, const std::string& to) {
  if (from.empty()) return;
  size_t pos = 0;
  while ((pos = s.find(from, pos)) != std::string::npos) {
    s.replace(pos, from.size(), to);
    pos += to.size();
  }
}

void queryCallback(const char* response,
                   const GenieDialog_SentenceCode_t code,
                   const void* userData) {
  auto* ctx = const_cast<CbCtx*>(reinterpret_cast<const CbCtx*>(userData));
  if (!ctx) return;
  if (code == GENIE_DIALOG_SENTENCE_ABORT) ctx->aborted = true;
  if (!response) return;
  jstring js = ctx->env->NewStringUTF(response);
  ctx->env->CallVoidMethod(ctx->sink, ctx->onToken, js, static_cast<jint>(code));
  ctx->env->DeleteLocalRef(js);
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeVersion(JNIEnv* env, jobject) {
  char buf[64];
  std::snprintf(buf, sizeof(buf), "genie %d.%d.%d", GENIE_API_VERSION_MAJOR,
                GENIE_API_VERSION_MINOR, GENIE_API_VERSION_PATCH);
  return env->NewStringUTF(buf);
}

// nativeInit(bundleDir) -> handle (0 on failure). Loads the Genie bundle once (resident).
extern "C" JNIEXPORT jlong JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeInit(JNIEnv* env, jobject, jstring jBundleDir) {
  const char* c = env->GetStringUTFChars(jBundleDir, nullptr);
  std::string bundle(c);
  env->ReleaseStringUTFChars(jBundleDir, c);

  std::string cfg = readFile(bundle + "/genie_config.json");
  if (cfg.empty()) {
    LOGE("genie_config.json missing/empty in %s", bundle.c_str());
    return 0;
  }
  // Patch bundle-relative asset filenames -> absolute paths (quoted, so we match the JSON token).
  auto absq = [&](const char* f) { return std::string("\"") + bundle + "/" + f + "\""; };
  replaceAll(cfg, "\"tokenizer.json\"", absq("tokenizer.json"));
  replaceAll(cfg, "\"htp_backend_ext_config.json\"", absq("htp_backend_ext_config.json"));
  for (int i = 1; i <= 4; ++i) {
    char fn[64];
    std::snprintf(fn, sizeof(fn), "qwen3_4b_instruct_2507_w4a16_part_%d_of_4.bin", i);
    replaceAll(cfg, std::string("\"") + fn + "\"", absq(fn));
  }

  auto* e = new LlmEngine();
  // context.size (first "size" key in the config) — used to turn the occupancy token count
  // into a percent. Defaults to 4096 if the parse fails.
  size_t sp = cfg.find("\"size\"");
  if (sp != std::string::npos) {
    sp = cfg.find(':', sp);
    if (sp != std::string::npos) {
      unsigned long v = std::strtoul(cfg.c_str() + sp + 1, nullptr, 10);
      if (v > 0) e->ctxSize = static_cast<uint32_t>(v);
    }
  }
  Genie_Status_t st = GenieDialogConfig_createFromJson(cfg.c_str(), &e->cfg);
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieDialogConfig_createFromJson failed: %d", st);
    delete e;
    return 0;
  }
  st = GenieDialog_create(e->cfg, &e->dialog);
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieDialog_create failed: %d", st);
    GenieDialogConfig_free(e->cfg);
    delete e;
    return 0;
  }
  LOGI("GenieDialog ready (bundle=%s)", bundle.c_str());
  return reinterpret_cast<jlong>(e);
}

// nativeGenerate(handle, prompt, sink, rewind) -> GEN_* status. prompt must be ChatML-formatted:
// the FULL transcript on a cold/re-prefilled session, or ONLY the new user turn on a warm
// session — the KV cache PERSISTS across calls (no reset here). rewind=true sends the query with
// GENIE_DIALOG_SENTENCE_REWIND: Genie prefix-matches the prompt against the cached KV and
// re-prefills only the divergent tail (SDK "KV$ Rewind" tutorial) — this makes post-barge-in /
// post-eviction re-prefills cost ~the delta instead of the whole transcript. Session validity
// policy lives in Kotlin (GenieLlm.warm + core/LlmSessionPolicy).
extern "C" JNIEXPORT jint JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeGenerate(JNIEnv* env, jobject, jlong handle,
                                                            jstring jPrompt, jobject sink,
                                                            jboolean rewind) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (!e || !e->dialog) return GEN_ERROR;

  jclass cls = env->GetObjectClass(sink);
  jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;I)V");
  env->DeleteLocalRef(cls);
  if (!onToken) {
    LOGE("TokenSink.onToken(String,int) not found");
    return GEN_ERROR;
  }

  const char* p = env->GetStringUTFChars(jPrompt, nullptr);
  CbCtx ctx{env, sink, onToken};
  Genie_Status_t st = GenieDialog_query(
      e->dialog, p,
      rewind ? GENIE_DIALOG_SENTENCE_REWIND : GENIE_DIALOG_SENTENCE_COMPLETE,
      queryCallback, &ctx);
  env->ReleaseStringUTFChars(jPrompt, p);

  if (ctx.aborted || st == GENIE_STATUS_WARNING_ABORTED) return GEN_ABORTED;
  if (st == GENIE_STATUS_WARNING_CONTEXT_EXCEEDED) {
    LOGI("GenieDialog_query: context exceeded");
    return GEN_CONTEXT_EXCEEDED;
  }
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieDialog_query failed: %d", st);
    return GEN_ERROR;
  }
  return GEN_OK;
}

// Drop the dialog's accumulated KV cache (session). Next query must re-send the full transcript.
extern "C" JNIEXPORT void JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeReset(JNIEnv*, jobject, jlong handle) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (e && e->dialog) GenieDialog_reset(e->dialog);
}

// Percent (0..100) of the context window occupied by the live session, or -1 if unavailable.
// GENIE_DIALOG_PARAM_CONTEXT_OCCUPANCY reports occupied TOKENS (verified on-device 2026-06-10:
// ~774 after one ~3.2KB-prompt turn), so normalize by context.size here.
extern "C" JNIEXPORT jint JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeContextOccupancy(JNIEnv*, jobject,
                                                                    jlong handle) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (!e || !e->dialog) return -1;
  Genie_DataType_t dt;
  Genie_Value_t val;
  Genie_Status_t st =
      GenieDialog_getValue(e->dialog, GENIE_DIALOG_PARAM_CONTEXT_OCCUPANCY, nullptr, &dt, &val);
  if (st != GENIE_STATUS_SUCCESS || dt != GENIE_DATATYPE_UINT_32) return -1;
  uint64_t pct = static_cast<uint64_t>(val.uint32Value) * 100u / e->ctxSize;
  return static_cast<jint>(pct > 100 ? 100 : pct);
}

// Cap the number of generated tokens per query (spoken-UX/latency/thermal bound; ~120 tok is a
// few spoken sentences at 22 tok/s). Applies to subsequent queries on the live dialog.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeSetMaxTokens(JNIEnv*, jobject, jlong handle,
                                                                jint maxTokens) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (!e || !e->dialog || maxTokens <= 0) return JNI_FALSE;
  Genie_Status_t st = GenieDialog_setMaxNumTokens(e->dialog, static_cast<uint32_t>(maxTokens));
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieDialog_setMaxNumTokens(%d) failed: %d", maxTokens, st);
    return JNI_FALSE;
  }
  LOGI("max generated tokens = %d", maxTokens);
  return JNI_TRUE;
}

// Apply new sampling params to the dialog's live sampler. The JSON shape matches the "sampler"
// section of genie_config.json (seed kept at the bundle's 42 for run-to-run comparability).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeSetSampling(JNIEnv*, jobject, jlong handle,
                                                               jfloat temp, jint topK,
                                                               jfloat topP, jfloat repetitionPenalty) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (!e || !e->dialog) return JNI_FALSE;
  GenieSampler_Handle_t sampler = nullptr;
  Genie_Status_t st = GenieDialog_getSampler(e->dialog, &sampler);
  if (st != GENIE_STATUS_SUCCESS || !sampler) {
    LOGE("GenieDialog_getSampler failed: %d", st);
    return JNI_FALSE;
  }
  // Shape per ${QAIRT}/examples/Genie/configs/sampler.json: a {"sampler": {...}} wrapper with
  // "type":"basic" is required — the bare sampler object fails with GENIE_STATUS_ERROR_JSON_SCHEMA
  // (-8), verified on-device 2026-06-10. The optional "token-penalty" block (schema from
  // examples/Genie/configs/llama2-7b-htp.json) curbs the repetition small models fall into; it is
  // only added when repetitionPenalty > 1.0 so a value of 1.0 cleanly disables it.
  // Only repetition-penalty (no frequency/presence): a frequency penalty is especially disruptive
  // for particle-heavy Korean. penalize-last-n=64 keeps the window local to recent loops.
  char penalty[160] = "";
  if (repetitionPenalty > 1.0001f) {
    std::snprintf(penalty, sizeof(penalty),
                  ",\"token-penalty\":{\"version\":1,\"penalize-last-n\":64,"
                  "\"repetition-penalty\":%.3f}",
                  static_cast<double>(repetitionPenalty));
  }
  char json[384];
  std::snprintf(json, sizeof(json),
                "{\"sampler\":{\"type\":\"basic\",\"version\":1,\"seed\":42,"
                "\"temp\":%.3f,\"top-k\":%d,\"top-p\":%.3f,\"greedy\":false%s}}",
                static_cast<double>(temp), static_cast<int>(topK), static_cast<double>(topP), penalty);
  GenieSamplerConfig_Handle_t cfg = nullptr;
  st = GenieSamplerConfig_createFromJson(json, &cfg);
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieSamplerConfig_createFromJson failed: %d (%s)", st, json);
    return JNI_FALSE;
  }
  st = GenieSampler_applyConfig(sampler, cfg);
  GenieSamplerConfig_free(cfg);
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieSampler_applyConfig failed: %d", st);
    return JNI_FALSE;
  }
  LOGI("sampler updated: %s", json);
  return JNI_TRUE;
}

// nativeAbort: signal the in-flight GenieDialog_query to stop (barge-in). Safe to call from another
// thread while a query is running; the blocking query returns early.
extern "C" JNIEXPORT void JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeAbort(JNIEnv*, jobject, jlong handle) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (e && e->dialog) GenieDialog_signal(e->dialog, GENIE_DIALOG_ACTION_ABORT);
}

extern "C" JNIEXPORT void JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeRelease(JNIEnv*, jobject, jlong handle) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (!e) return;
  if (e->dialog) GenieDialog_free(e->dialog);
  if (e->cfg) GenieDialogConfig_free(e->cfg);
  delete e;
}
