// Supertonic resident on-device TTS engine (SNPE C++ UserBuffer path), Phase 3 Stage 2 item 2.
//
// Loads the 3 short-chunk graph-prepared DLCs ONCE (text_encoder, vector_estimator, vocoder),
// keeps them resident on the HTP/DSP, and synthesizes one clause per request — the runtime side
// of the `tts.chunk_request -> tts.audio_chunk` contract (shared/mcp/conversation_events.schema.json).
//
// Uses the UserBuffer path (not ITensor) because text_ids is Int_32 in the DLC (ITensor is
// float32-only). Each input gets an encoding matching its DLC dtype:
//   text_ids -> UserBufferEncodingIntN(32); everything else (float) -> UserBufferEncodingFloat.
// Outputs are float UserBuffers sized from getInputOutputBufferAttributes.
//
// CRITICAL — INPUT LAYOUT: every multi-dim input must be supplied in the DLC's layout, which
// QAIRT (axes_to_spatial_first_order) produced by TRANSPOSING the last two axes vs ONNX:
//   style_ttl [1,256,50], text_mask [1,64,1], noisy_latent [1,128,144], latent_mask [1,128,1].
// text_ids [1,64] int32 is unchanged. The engine just memcpy's the .raw bytes into UserBuffers
// sized from the DLC, so the .raw files MUST already be in DLC layout — produce them with
// tools/tts/prep_static_tts_inputs.py --layout dlc. Feeding ONNX order silently scrambles the
// conditioning tensors and destroys speaker identity / audio quality (content still survives).
// text_emb passes text_encoder->vector_estimator device-side (self-consistent; no host transpose).
//
// CLI harness: reads per-clause input .raw files from a dir, writes audio.raw, prints timing JSON.
// The same class is what the Android app links via JNI.
//
// Build: clang++ --target=aarch64-linux-android34 -std=c++17 -O2 -fPIE -pie -I<QAIRT>/include/SNPE \
//        supertonic_tts_engine.cpp -L<QAIRT>/lib/aarch64-android -lSNPE -o supertonic_tts_engine

#include <cstdio>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <chrono>
#include <fstream>
#include <map>
#include <memory>
#include <string>
#include <vector>

#include "SNPE/SNPE.hpp"
#include "SNPE/SNPEFactory.hpp"
#include "SNPE/SNPEBuilder.hpp"
#include "DlContainer/IDlContainer.hpp"
#include "DlSystem/DlEnums.hpp"
#include "DlSystem/PlatformConfig.hpp"
#include "DlSystem/IUserBuffer.hpp"
#include "DlSystem/IUserBufferFactory.hpp"
#include "DlSystem/UserBufferMap.hpp"
#include "DlSystem/IBufferAttributes.hpp"
#include "DlSystem/TensorShape.hpp"
#include "DlSystem/RuntimeList.hpp"
#include "DlSystem/StringList.hpp"

using namespace std::chrono;
using zdl::DlSystem::TensorShape;

static std::vector<uint8_t> readRawBytes(const std::string& p) {
  std::ifstream f(p, std::ios::binary | std::ios::ate);
  if (!f) { fprintf(stderr, "missing %s\n", p.c_str()); return {}; }
  std::streamsize n = f.tellg(); f.seekg(0);
  std::vector<uint8_t> v(n);
  f.read(reinterpret_cast<char*>(v.data()), n);
  return v;
}
static double ms_since(steady_clock::time_point t0) {
  return duration_cast<microseconds>(steady_clock::now() - t0).count() / 1000.0;
}

// computeStrides: tightly-packed byte strides for a shape, given element size.
static TensorShape stridesFor(const TensorShape& dims, size_t elemSize) {
  std::vector<size_t> s(dims.rank());
  size_t acc = elemSize;
  for (int i = (int)dims.rank() - 1; i >= 0; --i) { s[i] = acc; acc *= dims[i]; }
  return TensorShape(s.data(), s.size());
}
static size_t numElems(const TensorShape& d) {
  size_t n = 1; for (size_t i = 0; i < d.rank(); ++i) n *= d[i]; return n;
}

struct Net {
  std::unique_ptr<zdl::DlContainer::IDlContainer> container;
  std::unique_ptr<zdl::SNPE::SNPE> snpe;
};

#if defined(TTS_ENGINE_AS_LIBRARY)
#include <android/log.h>
#define ENGLOG(...) __android_log_print(ANDROID_LOG_ERROR, "SupertonicTTS", __VA_ARGS__)
#else
#define ENGLOG(...) fprintf(stderr, __VA_ARGS__)
#endif

static Net loadDlc(const std::string& path) {
  Net n;
  n.container = zdl::DlContainer::IDlContainer::open(zdl::DlSystem::String(path.c_str()));
  if (!n.container) {
    ENGLOG("open failed: %s | err=%s", path.c_str(), zdl::SNPE::SNPEFactory::getLastError());
    return n;
  }
  bool dspAvail = zdl::SNPE::SNPEFactory::isRuntimeAvailable(
      zdl::DlSystem::Runtime_t::DSP, zdl::DlSystem::RuntimeCheckOption_t::UNSIGNEDPD_CHECK);
  ENGLOG("loadDlc %s: container ok, DSP(unsignedPD) available=%d", path.c_str(), (int)dspAvail);
  // Unsigned-PD HTP: our skel libs are unsigned, so the SNPE process must run on the unsigned PD.
  zdl::DlSystem::PlatformConfig platformConfig;
  bool optOk = platformConfig.setPlatformOptions("unsignedPD:ON");
  zdl::SNPE::SNPEBuilder b(n.container.get());
  zdl::DlSystem::RuntimeList rl; rl.add(zdl::DlSystem::Runtime_t::DSP);
  b.setRuntimeProcessorOrder(rl);
  b.setPerformanceProfile(zdl::DlSystem::PerformanceProfile_t::BURST);
  b.setUseUserSuppliedBuffers(true);
  b.setPlatformConfig(platformConfig);
  ENGLOG("unsignedPD option set ok=%d valid=%d", (int)optOk, (int)platformConfig.isOptionsValid());
  n.snpe = b.build();
  if (!n.snpe) ENGLOG("build failed: %s | err=%s", path.c_str(), zdl::SNPE::SNPEFactory::getLastError());
  return n;
}

// A resident UserBuffer slot: owns backing bytes + the IUserBuffer + encoding.
struct Slot {
  std::vector<uint8_t> bytes;
  std::unique_ptr<zdl::DlSystem::IUserBuffer> ub;
  bool isInt = false;
};

// Build a UserBufferMap over the given tensor names (inputs or outputs) for a net.
// Float by default; names in intNames get IntN(32).
static void buildMap(zdl::SNPE::SNPE* snpe,
                     const zdl::DlSystem::StringList& names,
                     const std::vector<std::string>& intNames,
                     std::map<std::string, Slot>& slots,
                     zdl::DlSystem::UserBufferMap& map) {
  auto& ubf = zdl::SNPE::SNPEFactory::getUserBufferFactory();
  for (const char* nm : names) {
    auto attrOpt = snpe->getInputOutputBufferAttributes(nm);
    const TensorShape dims = (*attrOpt)->getDims();
    bool isInt = false;
    for (auto& s : intNames) if (s == nm) isInt = true;
    size_t elemSize = isInt ? 4 : sizeof(float);   // int32 or float32
    size_t bytes = numElems(dims) * elemSize;
    Slot slot; slot.isInt = isInt; slot.bytes.assign(bytes, 0);
    TensorShape st = stridesFor(dims, elemSize);
    if (isInt) {
      zdl::DlSystem::UserBufferEncodingIntN enc(32);
      slot.ub = ubf.createUserBuffer(slot.bytes.data(), bytes, st, &enc);
    } else {
      zdl::DlSystem::UserBufferEncodingFloat enc;
      slot.ub = ubf.createUserBuffer(slot.bytes.data(), bytes, st, &enc);
    }
    map.add(nm, slot.ub.get());
    slots.emplace(nm, std::move(slot));
  }
}

static void fillFloat(Slot& s, const std::vector<uint8_t>& src) {
  std::memcpy(s.bytes.data(), src.data(), std::min(s.bytes.size(), src.size()));
}

// The CLI main() is excluded when this file is compiled into the Android JNI library
// (TTS_ENGINE_AS_LIBRARY); the standalone benchmark binary still builds it (build_tts_engine.sh).
#ifndef TTS_ENGINE_AS_LIBRARY
int main(int argc, char** argv) {
  if (argc < 4) { fprintf(stderr, "usage: %s <dlc_dir> <input_dir> <out.raw> [K=6] [repeats=1]\n", argv[0]); return 2; }
  std::string dlcDir = argv[1], inDir = argv[2], outPath = argv[3];
  int K = argc > 4 ? atoi(argv[4]) : 6;
  int repeats = argc > 5 ? atoi(argv[5]) : 1;
  auto P = [&](const std::string& d, const std::string& f){ return d + "/" + f; };

  auto t0 = steady_clock::now();
  Net te = loadDlc(P(dlcDir, "text_encoder.dlc"));
  Net ve = loadDlc(P(dlcDir, "vector_estimator.dlc"));
  Net vo = loadDlc(P(dlcDir, "vocoder.dlc"));
  double load_ms = ms_since(t0);
  if (!te.snpe || !ve.snpe || !vo.snpe) return 1;

  // --- build resident input/output maps for each net ---
  std::map<std::string, Slot> teIn, teOut, veIn, veOut, voIn, voOut;
  zdl::DlSystem::UserBufferMap teInM, teOutM, veInM, veOutM, voInM, voOutM;
  buildMap(te.snpe.get(), *te.snpe->getInputTensorNames(),  {"text_ids"}, teIn, teInM);
  buildMap(te.snpe.get(), *te.snpe->getOutputTensorNames(), {},          teOut, teOutM);
  buildMap(ve.snpe.get(), *ve.snpe->getInputTensorNames(),  {},          veIn, veInM);
  buildMap(ve.snpe.get(), *ve.snpe->getOutputTensorNames(), {},          veOut, veOutM);
  buildMap(vo.snpe.get(), *vo.snpe->getInputTensorNames(),  {},          voIn, voInM);
  buildMap(vo.snpe.get(), *vo.snpe->getOutputTensorNames(), {},          voOut, voOutM);

  // --- load per-clause input data from disk (resident) ---
  auto text_ids   = readRawBytes(P(inDir, "text_ids.raw"));       // int32
  auto text_mask  = readRawBytes(P(inDir, "text_mask.raw"));
  auto style_ttl  = readRawBytes(P(inDir, "style_ttl.raw"));
  auto noisy      = readRawBytes(P(inDir, "noisy_latent.raw"));
  auto latent_mask= readRawBytes(P(inDir, "latent_mask.raw"));
  auto total_step = readRawBytes(P(inDir, "total_step.raw"));
  std::vector<std::vector<uint8_t>> cur(K);
  for (int k = 0; k < K; ++k) cur[k] = readRawBytes(P(inDir, "current_step_" + std::to_string(k) + ".raw"));

  auto setIf = [](std::map<std::string,Slot>& m, const char* n, const std::vector<uint8_t>& d){
    auto it = m.find(n); if (it != m.end()) fillFloat(it->second, d);
  };

  double te_ms=0, ve_ms=0, vo_ms=0;
  std::vector<float> audio;
  const std::string teOutName = (*te.snpe->getOutputTensorNames()).at(0);
  const std::string veOutName = (*ve.snpe->getOutputTensorNames()).at(0);
  const std::string voOutName = (*vo.snpe->getOutputTensorNames()).at(0);

  for (int r = 0; r < repeats; ++r) {
    // 1) text_encoder
    setIf(teIn, "text_ids", text_ids);
    setIf(teIn, "text_mask", text_mask);
    setIf(teIn, "style_ttl", style_ttl);
    auto t = steady_clock::now();
    if (!te.snpe->execute(teInM, teOutM)) { fprintf(stderr, "te exec failed\n"); return 1; }
    if (r==repeats-1) te_ms = ms_since(t);
    auto& embBytes = teOut.at(teOutName).bytes;   // text_emb (float)

    // 2) K-step flow matching (xt feedback)
    setIf(veIn, "text_emb", embBytes);
    setIf(veIn, "style_ttl", style_ttl);
    setIf(veIn, "text_mask", text_mask);
    setIf(veIn, "latent_mask", latent_mask);
    setIf(veIn, "total_step", total_step);
    // init xt = noisy
    fillFloat(veIn.at("noisy_latent"), noisy);
    auto vt = steady_clock::now();
    for (int k = 0; k < K; ++k) {
      setIf(veIn, "current_step", cur[k]);
      if (!ve.snpe->execute(veInM, veOutM)) { fprintf(stderr, "ve exec failed @%d\n", k); return 1; }
      // feed output back into noisy_latent for next step
      veIn.at("noisy_latent").bytes = veOut.at(veOutName).bytes;
    }
    if (r==repeats-1) ve_ms = ms_since(vt);

    // 3) vocoder
    fillFloat(voIn.at("latent"), veOut.at(veOutName).bytes);
    auto ot = steady_clock::now();
    if (!vo.snpe->execute(voInM, voOutM)) { fprintf(stderr, "vo exec failed\n"); return 1; }
    if (r==repeats-1) vo_ms = ms_since(ot);
    auto& ab = voOut.at(voOutName).bytes;
    audio.resize(ab.size()/sizeof(float));
    std::memcpy(audio.data(), ab.data(), ab.size());
  }

  std::ofstream of(outPath, std::ios::binary);
  of.write(reinterpret_cast<char*>(audio.data()), audio.size()*sizeof(float));

  float peak=0, sq=0; for (float v : audio){ float a=v<0?-v:v; if(a>peak)peak=a; sq+=v*v; }
  float rms = audio.empty()?0:std::sqrt(sq/audio.size());
  printf("{\"load_ms\":%.1f,\"text_enc_ms\":%.1f,\"ve_total_ms\":%.1f,\"ve_per_step_ms\":%.1f,"
         "\"vocoder_ms\":%.1f,\"synth_ms\":%.1f,\"audio_samples\":%zu,\"peak\":%.4f,\"rms\":%.4f,"
         "\"K\":%d,\"repeats\":%d}\n",
         load_ms, te_ms, ve_ms, ve_ms/std::max(1,K), vo_ms, te_ms+ve_ms+vo_ms,
         audio.size(), peak, rms, K, repeats);
  return 0;
}
#endif  // TTS_ENGINE_AS_LIBRARY

// ---------------------------------------------------------------------------
// JNI bridge (Android). Built when TTS_ENGINE_AS_LIBRARY is defined (CMake).
// Resident engine: load the 3 short-chunk DLCs once, synthesize one clause per call.
// Inputs arrive from Kotlin already in DLC layout (last two axes transposed vs ONNX);
// text_ids is int32. See docs/design/android_app_architecture.md §3.
// ---------------------------------------------------------------------------
#ifdef TTS_ENGINE_AS_LIBRARY
#include <jni.h>
#include <android/log.h>
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "SupertonicTTS", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SupertonicTTS", __VA_ARGS__)

namespace {

struct Engine {
  Net dp, te, ve, vo;
  std::map<std::string, Slot> dpIn, dpOut, teIn, teOut, veIn, veOut, voIn, voOut;
  zdl::DlSystem::UserBufferMap dpInM, dpOutM, teInM, teOutM, veInM, veOutM, voInM, voOutM;
  std::string dpOutName, teOutName, veOutName, voOutName;
  bool ok = false;
  bool hasDp = false;
};

// Copy raw bytes from a jarray into a named float slot.
void setFloat(std::map<std::string, Slot>& m, const char* n, const float* data, size_t count) {
  auto it = m.find(n);
  if (it == m.end()) return;
  std::memcpy(it->second.bytes.data(), data,
              std::min(it->second.bytes.size(), count * sizeof(float)));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_conversationalai_agent_tts_SupertonicTts_nativeVersion(JNIEnv* env, jobject) {
  return env->NewStringUTF("supertonic-tts-jni 0.2 (resident UserBuffer engine)");
}

// nativeInit(dlcDir) -> handle (0 on failure). Loads 3 DLCs once + builds resident UserBuffer maps.
extern "C" JNIEXPORT jlong JNICALL
Java_com_conversationalai_agent_tts_SupertonicTts_nativeInit(JNIEnv* env, jobject, jstring jDlcDir) {
  const char* dir = env->GetStringUTFChars(jDlcDir, nullptr);
  std::string d(dir);
  env->ReleaseStringUTFChars(jDlcDir, dir);
  auto* e = new Engine();
  auto P = [&](const std::string& f){ return d + "/" + f; };
  e->te = loadDlc(P("text_encoder.dlc"));
  e->ve = loadDlc(P("vector_estimator.dlc"));
  e->vo = loadDlc(P("vocoder.dlc"));
  if (!e->te.snpe || !e->ve.snpe || !e->vo.snpe) { LOGE("DLC load failed in %s", d.c_str()); delete e; return 0; }
  // duration_predictor is optional: if present, it sizes the latent mask (kills the noise tail).
  e->dp = loadDlc(P("duration_predictor.dlc"));
  if (e->dp.snpe) {
    buildMap(e->dp.snpe.get(), *e->dp.snpe->getInputTensorNames(),  {"text_ids"}, e->dpIn, e->dpInM);
    buildMap(e->dp.snpe.get(), *e->dp.snpe->getOutputTensorNames(), {},           e->dpOut, e->dpOutM);
    e->dpOutName = (*e->dp.snpe->getOutputTensorNames()).at(0);
    e->hasDp = true;
    LOGI("duration_predictor loaded (out=%s)", e->dpOutName.c_str());
  } else {
    LOGI("duration_predictor not present; latent mask = full");
  }
  buildMap(e->te.snpe.get(), *e->te.snpe->getInputTensorNames(),  {"text_ids"}, e->teIn, e->teInM);
  buildMap(e->te.snpe.get(), *e->te.snpe->getOutputTensorNames(), {},          e->teOut, e->teOutM);
  buildMap(e->ve.snpe.get(), *e->ve.snpe->getInputTensorNames(),  {},          e->veIn, e->veInM);
  buildMap(e->ve.snpe.get(), *e->ve.snpe->getOutputTensorNames(), {},          e->veOut, e->veOutM);
  buildMap(e->vo.snpe.get(), *e->vo.snpe->getInputTensorNames(),  {},          e->voIn, e->voInM);
  buildMap(e->vo.snpe.get(), *e->vo.snpe->getOutputTensorNames(), {},          e->voOut, e->voOutM);
  e->teOutName = (*e->te.snpe->getOutputTensorNames()).at(0);
  e->veOutName = (*e->ve.snpe->getOutputTensorNames()).at(0);
  e->voOutName = (*e->vo.snpe->getOutputTensorNames()).at(0);
  e->ok = true;
  LOGI("nativeInit ok: %s", d.c_str());
  return reinterpret_cast<jlong>(e);
}

// nativeSynthesize: one clause. All float arrays in DLC layout; textIds int32.
// Returns float[] PCM (44.1 kHz mono), or null on failure.
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_conversationalai_agent_tts_SupertonicTts_nativeSynthesize(
    JNIEnv* env, jobject, jlong handle,
    jintArray jTextIds, jfloatArray jTextMask, jfloatArray jStyleTtl, jfloatArray jStyleDp,
    jfloatArray jNoisy, jfloatArray jLatentMask, jint K) {
  auto* e = reinterpret_cast<Engine*>(handle);
  if (!e || !e->ok) return nullptr;

  // text_ids -> int slot (UserBufferEncodingIntN(32))
  {
    auto it = e->teIn.find("text_ids");
    if (it != e->teIn.end()) {
      jint* ids = env->GetIntArrayElements(jTextIds, nullptr);
      jsize n = env->GetArrayLength(jTextIds);
      std::memcpy(it->second.bytes.data(), ids,
                  std::min(it->second.bytes.size(), (size_t)n * sizeof(int32_t)));
      env->ReleaseIntArrayElements(jTextIds, ids, JNI_ABORT);
    }
  }
  auto floats = [&](jfloatArray a, std::vector<float>& out){
    jsize n = env->GetArrayLength(a);
    out.resize(n);
    env->GetFloatArrayRegion(a, 0, n, out.data());
  };
  std::vector<float> textMask, styleTtl, styleDp, noisy, latentMask;
  floats(jTextMask, textMask); floats(jStyleTtl, styleTtl); floats(jStyleDp, styleDp);
  floats(jNoisy, noisy); floats(jLatentMask, latentMask);

  const int LAT = (int)latentMask.size();         // 128
  int activeFrames = LAT;                          // default: whole chunk (no dp)

  // 0) duration_predictor (optional): duration[sec] -> active latent frames; rebuild mask so the
  //    silent tail past the utterance is zeroed (otherwise the vocoder synthesizes a noise tail).
  if (e->hasDp) {
    // dp inputs: text_ids (int32, already to be set below), style_dp [1,16,8], text_mask [1,64,1].
    {
      auto it = e->dpIn.find("text_ids");
      if (it != e->dpIn.end()) {
        jint* ids = env->GetIntArrayElements(jTextIds, nullptr);
        jsize n = env->GetArrayLength(jTextIds);
        std::memcpy(it->second.bytes.data(), ids,
                    std::min(it->second.bytes.size(), (size_t)n * sizeof(int32_t)));
        env->ReleaseIntArrayElements(jTextIds, ids, JNI_ABORT);
      }
    }
    setFloat(e->dpIn, "style_dp", styleDp.data(), styleDp.size());
    setFloat(e->dpIn, "text_mask", textMask.data(), textMask.size());
    if (e->dp.snpe->execute(e->dpInM, e->dpOutM)) {
      const float* dur = reinterpret_cast<const float*>(e->dpOut.at(e->dpOutName).bytes.data());
      // helper.py: speed=1.05; latent_len = ceil(duration/speed * sr / (base_chunk*ccf))
      //            = ceil(duration/1.05 * 44100 / 3072)
      float seconds = dur[0] / 1.05f;
      int frames = (int)std::ceil(seconds * 44100.0f / 3072.0f);
      if (frames < 1) frames = 1;
      if (frames > LAT) frames = LAT;
      activeFrames = frames;
      // rebuild latent_mask: ones up to activeFrames (DLC layout [1,LAT,1] == length-LAT vector)
      for (int i = 0; i < LAT; ++i) latentMask[i] = (i < activeFrames) ? 1.0f : 0.0f;
      // mask the noisy latent too (DLC layout [1,LAT,144]: frame index is the outer dim)
      for (int f = 0; f < LAT; ++f) {
        if (f >= activeFrames) {
          for (int c = 0; c < 144; ++c) noisy[(size_t)f * 144 + c] = 0.0f;
        }
      }
      LOGI("dp: duration=%.3fs -> activeFrames=%d/%d", (double)dur[0], activeFrames, LAT);
    } else {
      LOGE("dp exec failed; using full latent");
    }
  }

  // 1) text_encoder
  setFloat(e->teIn, "text_mask", textMask.data(), textMask.size());
  setFloat(e->teIn, "style_ttl", styleTtl.data(), styleTtl.size());
  if (!e->te.snpe->execute(e->teInM, e->teOutM)) { LOGE("te exec failed"); return nullptr; }
  auto& emb = e->teOut.at(e->teOutName).bytes;

  // 2) K-step flow matching (xt feedback). total_step + current_step from K.
  setFloat(e->veIn, "text_emb", reinterpret_cast<float*>(emb.data()), emb.size()/sizeof(float));
  setFloat(e->veIn, "style_ttl", styleTtl.data(), styleTtl.size());
  setFloat(e->veIn, "text_mask", textMask.data(), textMask.size());
  setFloat(e->veIn, "latent_mask", latentMask.data(), latentMask.size());
  { float ts = (float)K; setFloat(e->veIn, "total_step", &ts, 1); }
  setFloat(e->veIn, "noisy_latent", noisy.data(), noisy.size());
  for (int k = 0; k < K; ++k) {
    float cs = (float)k; setFloat(e->veIn, "current_step", &cs, 1);
    if (!e->ve.snpe->execute(e->veInM, e->veOutM)) { LOGE("ve exec failed @%d", k); return nullptr; }
    e->veIn.at("noisy_latent").bytes = e->veOut.at(e->veOutName).bytes;
  }

  // 3) vocoder
  e->voIn.at("latent").bytes = e->veOut.at(e->veOutName).bytes;
  if (!e->vo.snpe->execute(e->voInM, e->voOutM)) { LOGE("vo exec failed"); return nullptr; }
  auto& ab = e->voOut.at(e->voOutName).bytes;
  jsize total = (jsize)(ab.size() / sizeof(float));
  // Trim to the active utterance (kills the silent/noise tail). 1 latent frame ~= 3072 samples.
  jsize samplesPerFrame = (LAT > 0) ? (total / LAT) : total;
  jsize samples = (e->hasDp) ? std::min(total, (jsize)activeFrames * samplesPerFrame) : total;
  jfloatArray out = env->NewFloatArray(samples);
  env->SetFloatArrayRegion(out, 0, samples, reinterpret_cast<float*>(ab.data()));
  return out;
}

extern "C" JNIEXPORT void JNICALL
Java_com_conversationalai_agent_tts_SupertonicTts_nativeRelease(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<Engine*>(handle);
}
#endif  // TTS_ENGINE_AS_LIBRARY
