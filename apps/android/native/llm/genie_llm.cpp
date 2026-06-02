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
};

// userData for the streaming query callback. GenieDialog_query is synchronous and invokes the
// callback on the SAME thread, so the JNIEnv* and the local sink ref captured here stay valid
// for the whole query — no AttachCurrentThread / global ref needed.
struct CbCtx {
  JNIEnv* env;
  jobject sink;       // TokenSink instance
  jmethodID onToken;  // void onToken(String, int)
};

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
  auto* ctx = reinterpret_cast<const CbCtx*>(userData);
  if (!ctx || !response) return;
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

// nativeGenerate(handle, prompt, sink) -> ok. prompt must be the fully ChatML-formatted query.
// Streams response chunks to sink.onToken(text, sentenceCode) synchronously, then resets the KV
// cache so each call is an independent single turn (multi-turn history comes with the controller).
extern "C" JNIEXPORT jboolean JNICALL
Java_com_conversationalai_agent_llm_GenieLlm_nativeGenerate(JNIEnv* env, jobject, jlong handle,
                                                            jstring jPrompt, jobject sink) {
  auto* e = reinterpret_cast<LlmEngine*>(handle);
  if (!e || !e->dialog) return JNI_FALSE;

  jclass cls = env->GetObjectClass(sink);
  jmethodID onToken = env->GetMethodID(cls, "onToken", "(Ljava/lang/String;I)V");
  env->DeleteLocalRef(cls);
  if (!onToken) {
    LOGE("TokenSink.onToken(String,int) not found");
    return JNI_FALSE;
  }

  const char* p = env->GetStringUTFChars(jPrompt, nullptr);
  CbCtx ctx{env, sink, onToken};
  Genie_Status_t st =
      GenieDialog_query(e->dialog, p, GENIE_DIALOG_SENTENCE_COMPLETE, queryCallback, &ctx);
  env->ReleaseStringUTFChars(jPrompt, p);

  GenieDialog_reset(e->dialog);  // independent single turn for now
  if (st != GENIE_STATUS_SUCCESS) {
    LOGE("GenieDialog_query failed: %d", st);
    return JNI_FALSE;
  }
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
