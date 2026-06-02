import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "design" / "ios_feasibility.md"
README = ROOT / "apps" / "ios" / "README.md"


class IosFeasibilityDocTest(unittest.TestCase):
    def test_ios_feasibility_doc_exists_and_covers_runtime_risks(self):
        text = DOC.read_text(encoding="utf-8")

        for required in [
            "Runtime must not require network access",
            "no Mac build server",
            "SFSpeechAudioBufferRecognitionRequest",
            "requiresOnDeviceRecognition = true",
            "supportsOnDeviceRecognition",
            "ANEMLL",
            "MLX Swift",
            "Gemma4-E2B",
            "Core ML",
            "Supertonic 3",
            "airplane mode",
            "Decision matrix",
        ]:
            self.assertIn(required, text)

    def test_ios_readme_points_to_feasibility_plan(self):
        text = README.read_text(encoding="utf-8")

        self.assertIn("iOS Feasibility Track", text)
        self.assertIn("docs/design/ios_feasibility.md", text)
        self.assertIn("do not start broad iOS app implementation", text)


if __name__ == "__main__":
    unittest.main()
