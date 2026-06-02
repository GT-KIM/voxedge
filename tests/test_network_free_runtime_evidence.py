import re
import unittest
from pathlib import Path
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "apps" / "android"
MANIFEST = ANDROID / "app" / "src" / "main" / "AndroidManifest.xml"
MAIN_SRC = ANDROID / "app" / "src" / "main"
NATIVE_SRC = ANDROID / "native"
DOC = ROOT / "docs" / "demo" / "network_free_runtime_evidence.md"


class NetworkFreeRuntimeEvidenceTest(unittest.TestCase):
    def test_android_manifest_has_no_network_permissions(self):
        ns = {"android": "http://schemas.android.com/apk/res/android"}
        root = ElementTree.parse(MANIFEST).getroot()
        permissions = {
            node.attrib[f"{{{ns['android']}}}name"]
            for node in root.findall("uses-permission")
        }

        self.assertIn("android.permission.RECORD_AUDIO", permissions)
        self.assertNotIn("android.permission.INTERNET", permissions)
        self.assertNotIn("android.permission.ACCESS_NETWORK_STATE", permissions)
        self.assertNotIn("android.permission.CHANGE_NETWORK_STATE", permissions)

    def test_android_runtime_source_has_no_app_network_api(self):
        forbidden_patterns = [
            r"\bSocket\b",
            r"\bURL\s*\(",
            r"\bHttpURLConnection\b",
            r"\bURLConnection\b",
            r"\bOkHttp\b",
            r"\bRetrofit\b",
            r"\bWebSocket\b",
            r"http://",
            r"https://",
        ]
        for root in [MAIN_SRC, NATIVE_SRC]:
            for path in root.rglob("*"):
                if path.suffix.lower() not in {".kt", ".java", ".cpp", ".cc", ".c", ".h", ".hpp"}:
                    continue
                text = path.read_text(encoding="utf-8", errors="ignore")
                for pattern in forbidden_patterns:
                    self.assertIsNone(
                        re.search(pattern, text),
                        f"network-like token {pattern!r} found in {path}",
                    )

    def test_asr_llm_tts_use_local_runtime_artifacts(self):
        runtime = (MAIN_SRC / "kotlin" / "com" / "conversationalai" / "agent" / "ui" / "RuntimeInitializer.kt").read_text(encoding="utf-8")
        asr = (MAIN_SRC / "kotlin" / "com" / "conversationalai" / "agent" / "asr" / "OfflineAsr.kt").read_text(encoding="utf-8")
        llm = (MAIN_SRC / "kotlin" / "com" / "conversationalai" / "agent" / "llm" / "GenieLlm.kt").read_text(encoding="utf-8")
        tts = (MAIN_SRC / "kotlin" / "com" / "conversationalai" / "agent" / "tts" / "SupertonicTts.kt").read_text(encoding="utf-8")
        genie_native = (NATIVE_SRC / "llm" / "genie_llm.cpp").read_text(encoding="utf-8")
        tts_native = (NATIVE_SRC / "tts_engine" / "supertonic_tts_engine.cpp").read_text(encoding="utf-8")

        for required in [
            'File(context.filesDir, "tts_dlc")',
            'File(context.filesDir, "llm_bundle")',
            'File(context.filesDir, "asr/model.int8.onnx")',
            'File(context.filesDir, "asr_dolphin")',
            'File(context.filesDir, "vad/silero_vad.onnx")',
        ]:
            self.assertIn(required, runtime)

        self.assertIn("OfflineRecognizer", asr)
        self.assertIn('provider = "cpu"', asr)
        self.assertNotIn("SpeechRecognizer", asr)

        self.assertIn('System.loadLibrary("genie_llm")', llm)
        self.assertIn("GenieDialog_query", genie_native)
        self.assertIn("bundle + \"/genie_config.json\"", genie_native)
        self.assertIn("qwen3_4b_instruct_2507_w4a16_part_", genie_native)

        self.assertIn('System.loadLibrary("supertonic_tts")', tts)
        self.assertIn("nativeSynthesize", tts)
        self.assertIn("text_encoder.dlc", tts_native)
        self.assertIn("vector_estimator.dlc", tts_native)
        self.assertIn("vocoder.dlc", tts_native)

    def test_evidence_doc_covers_claims_and_remaining_dynamic_proof(self):
        text = DOC.read_text(encoding="utf-8")

        for required in [
            "Network-Free Runtime Evidence",
            "android.permission.INTERNET",
            "OfflineAsr",
            "sherpa-onnx",
            "GenieLlm",
            "llm_bundle",
            "SupertonicTts",
            "tts_dlc",
            "airplane mode",
            "Remaining Evidence To Add",
            "packet-capture",
        ]:
            self.assertIn(required, text)


if __name__ == "__main__":
    unittest.main()
