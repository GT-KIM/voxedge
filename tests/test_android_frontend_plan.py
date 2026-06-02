import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DOC = ROOT / "docs" / "design" / "android_frontend_expansion_plan.md"


class AndroidFrontendPlanTest(unittest.TestCase):
    def test_frontend_plan_exists_and_keeps_scope_clear(self):
        text = DOC.read_text(encoding="utf-8")

        for required in [
            "Android Frontend Expansion Plan",
            "Jetpack Compose + Material 3",
            "not a landing page",
            "offline/runtime readiness",
            "ConversationUiState",
            "ConversationAction",
            "TranscriptTimeline",
            "MicControl",
            "DebugRuntimePanel",
            "Do not redesign the model runtime",
            "Do not add network features",
        ]:
            self.assertIn(required, text)

    def test_first_refactor_is_state_action_boundary(self):
        text = DOC.read_text(encoding="utf-8")

        self.assertIn("Start with Step 1", text)
        self.assertIn("Change `ConversationScreen` signature", text)
        self.assertIn("state: ConversationUiState", text)
        self.assertIn("onAction: (ConversationAction) -> Unit", text)


if __name__ == "__main__":
    unittest.main()
