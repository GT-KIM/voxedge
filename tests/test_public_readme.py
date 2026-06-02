import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
README = ROOT / "README.md"


class PublicReadmeTest(unittest.TestCase):
    """Guardrails so the public README stays a sharp, HONEST proof — not marketing drift."""

    def setUp(self):
        self.text = README.read_text(encoding="utf-8")

    def test_leads_with_the_killer_point(self):
        for required in [
            "voxedge",
            "Airplane mode on. You speak",
            "no network",
            "measured, honest reference",
        ]:
            self.assertIn(required, self.text)

    def test_latency_claim_is_precise_not_overstated(self):
        # The ~0.6 s is recognized-text -> first audio, and the README must say what it excludes,
        # so it can't be read as "0.6 s after you stop speaking".
        self.assertIn("Recognized text", self.text)
        self.assertIn("VAD endpoint", self.text)
        self.assertIn("docs/design/latency_budget.md", self.text)

    def test_is_honest_about_what_does_not_work(self):
        for required in [
            "What works / what doesn't",
            "experimental, off by default",   # barge-in
            "not built",                       # iOS
            "single SoC",                      # portability
            "not GPT-class",                   # LLM quality
            "not solved",                      # ASR under music
        ]:
            self.assertIn(required, self.text)

    def test_models_are_bring_your_own_not_redistributed(self):
        self.assertIn("not redistributed", self.text)
        self.assertIn("MODELS.md", self.text)

    def test_names_the_real_stack(self):
        for required in [
            "sherpa-onnx",
            "Qualcomm Genie",
            "Qwen3-4B",
            "Supertonic",
            "Dolphin",
        ]:
            self.assertIn(required, self.text)

    def test_states_a_license(self):
        self.assertIn("Apache-2.0", self.text)

    def test_internal_links_resolve(self):
        for rel in ["docs/teardown.md", "MODELS.md", "LICENSE", "docs/design/latency_budget.md"]:
            self.assertTrue((ROOT / rel).exists(), f"README links a missing file: {rel}")


if __name__ == "__main__":
    unittest.main()
