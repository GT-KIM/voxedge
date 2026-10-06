"""Platform ASR is a TEST option behind the shared AsrEngine boundary: on-device recognizer only,
gated on an installed language pack, selected from diagnostics, never persisted. The owned
sherpa-onnx engine stays the default product path. Offline; no device."""

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
AGENT = ROOT / "apps" / "android" / "app" / "src" / "main" / "kotlin" / "com" / "conversationalai" / "agent"
ASR = AGENT / "asr"
UI = AGENT / "ui"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class PlatformAsrOptionTest(unittest.TestCase):
    def test_platform_engine_is_on_device_only_and_gated_on_language_packs(self):
        src = read(ASR / "PlatformAsr.kt")
        self.assertIn("class PlatformAsr(", src)
        self.assertIn(": AsrEngine", src)
        self.assertIn("SpeechRecognizer.createOnDeviceSpeechRecognizer(context)", src)
        self.assertNotIn("SpeechRecognizer.createSpeechRecognizer(", src)
        self.assertIn("isOnDeviceRecognitionAvailable", src)
        self.assertIn("checkRecognitionSupport", src)
        self.assertIn("installedOnDeviceLanguages", src)
        self.assertIn("EXTRA_PREFER_OFFLINE, true", src)
        # The loop's captured utterance is fed in; the recognizer does not open its own mic.
        self.assertIn("EXTRA_AUDIO_SOURCE", src)
        self.assertIn("EXTRA_AUDIO_SOURCE_SAMPLING_RATE", src)

    def test_owned_engine_is_the_default_and_the_switch_is_not_persisted(self):
        switch = read(ASR / "SwitchableAsr.kt")
        self.assertIn("var usePlatform: Boolean = false", switch)
        self.assertIn("if (usePlatform && platform != null) platform else owned", switch)
        store = read(UI / "SettingsStore.kt")
        settings = read(UI / "AppSettings.kt")
        for text in (store, settings):
            self.assertNotIn("platformAsr", text)
            self.assertNotIn("platform_asr", text)
        route = read(UI / "ConversationRoute.kt")
        self.assertIn("var platformAsr by remember { mutableStateOf(false) }", route)

    def test_controller_sees_only_the_switch_and_the_owned_engine_stays_untouched(self):
        main = read(UI / "MainActivity.kt")
        self.assertIn("asrSwitch = SwitchableAsr(owned = asr, platform = platformAsr)", main)
        self.assertIn("asr = asrSwitch, llm = llm", main)
        self.assertIn("platformAsr.checkAvailability()", main)
        self.assertIn("usable", main)
        # The owned engine source is unchanged by the option (no SpeechRecognizer inside it).
        self.assertNotIn("SpeechRecognizer", read(ASR / "OfflineAsr.kt"))

    def test_diagnostics_exposes_the_toggle_and_a_headless_comparison_hook(self):
        actions = read(UI / "ConversationAction.kt")
        self.assertIn("data object TogglePlatformAsr", actions)
        screen = read(UI / "ConversationScreen.kt")
        self.assertIn("ConversationAction.TogglePlatformAsr", screen)
        self.assertIn("Platform ASR (test", screen)
        main = read(UI / "MainActivity.kt")
        self.assertIn('EXTRA_DEBUG_ASR_WAV = "debug_asr_wav"', main)
        self.assertIn('EXTRA_DEBUG_ASR_ENGINE = "debug_asr_engine"', main)


if __name__ == "__main__":
    unittest.main()
