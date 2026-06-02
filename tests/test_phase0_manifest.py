import json
from pathlib import Path
import unittest


class Phase0ManifestTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        manifest_path = self.root / "converter" / "phase0" / "model_sources.json"
        self.manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    def test_manifest_tracks_llm_and_tts_sources(self):
        components = {source["component"]: source for source in self.manifest["sources"]}

        self.assertEqual({"llm", "tts"}, set(components))
        self.assertEqual("downloaded", components["llm"]["status"])
        self.assertEqual("downloaded", components["tts"]["status"])
        self.assertEqual("google/gemma-4-E2B-it", components["llm"]["selected_model_id"])
        self.assertEqual("Supertone/supertonic-3", components["tts"]["model_id"])

    def test_manifest_keeps_large_artifacts_out_of_source_control(self):
        storage_policy = self.manifest["storage_policy"]

        self.assertEqual("models/original", storage_policy["local_root"])
        self.assertFalse(storage_policy["commit_large_artifacts"])

    def test_fetch_script_requires_license_acceptance_and_defaults_to_selected_llm(self):
        script = (self.root / "converter" / "phase0" / "fetch_sources.sh").read_text(
            encoding="utf-8"
        )

        self.assertIn("ACCEPT_MODEL_LICENSES", script)
        self.assertIn("google/gemma-4-E2B-it", script)

    def test_checksum_manifest_records_downloads(self):
        checksums = (self.root / "converter" / "phase0" / "checksums.sha256").read_text(
            encoding="utf-8"
        )

        self.assertIn("models/original/llm/google__gemma-4-E2B-it/model.safetensors", checksums)
        self.assertIn("models/original/tts/supertonic-3/onnx/vocoder.onnx", checksums)


if __name__ == "__main__":
    unittest.main()
