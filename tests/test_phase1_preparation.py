import json
from pathlib import Path
import unittest


class Phase1PreparationTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        manifest_path = self.root / "converter" / "phase1" / "model_preparation.json"
        self.manifest = json.loads(manifest_path.read_text(encoding="utf-8"))

    def test_phase1_targets_sm8750_int8_burst(self):
        target = self.manifest["target"]

        self.assertEqual("android", target["platform"])
        self.assertEqual("SM8750", target["soc"])
        self.assertEqual("int8", target["quantization"])
        self.assertEqual("burst", target["runtime_power_profile"])

    def test_qairt_required_tools_are_recorded(self):
        tools = set(self.manifest["qairt"]["observed_tools"])

        self.assertIn("bin/x86_64-linux-clang/snpe-onnx-to-dlc", tools)
        self.assertIn("bin/x86_64-linux-clang/snpe-dlc-quantize", tools)
        self.assertIn("bin/x86_64-linux-clang/snpe-dlc-graph-prepare", tools)
        self.assertIn("bin/x86_64-linux-clang/qnn-onnx-converter", tools)
        self.assertIn("bin/x86_64-linux-clang/qnn-model-lib-generator", tools)
        self.assertIn("bin/x86_64-linux-clang/qnn-context-binary-generator", tools)

    def test_tts_components_are_prepared_for_dlc_conversion(self):
        models = {model["component"]: model for model in self.manifest["models"]}
        tts = models["tts"]
        component_names = {component["name"] for component in tts["onnx_components"]}

        self.assertEqual("int8_graph_prepared_with_random_calibration", tts["preparation_status"])
        self.assertEqual(
            {"duration_predictor", "text_encoder", "vector_estimator", "vocoder"},
            component_names,
        )
        self.assertEqual(
            "random_generated_pipeline_only_representative_required",
            tts["calibration_status"],
        )
        self.assertEqual("pipeline_smoke_test_only", tts["random_calibration"]["purpose"])

    def test_llm_primary_qwen3_genie_path_is_explicit(self):
        models = {model["component"]: model for model in self.manifest["models"]}
        llm = models["llm"]
        qwen = llm["qualcomm_ai_hub"]

        self.assertEqual("qualcomm/Qwen3-4B-Instruct-2507", llm["model_id"])
        self.assertEqual("genie_bundle_downloaded_and_validated_host_side", llm["preparation_status"])
        self.assertEqual("qualcomm_ai_hub_genie_bundle", llm["source_format"])
        self.assertEqual("w4a16", llm["source_dtype"])
        self.assertEqual("GENIE", qwen["runtime"])
        self.assertEqual("2.45.0", qwen["minimum_qnn_sdk_version"])
        self.assertEqual("2.46.0.260424", qwen["local_qairt_version"])
        self.assertEqual("Snapdragon 8 Elite Mobile", qwen["target_chipset"])
        self.assertEqual("SM8750", qwen["target_soc"])
        self.assertEqual(4096, qwen["default_context_length"])
        self.assertEqual("custom_export_required_for_2048_or_1024", qwen["fallback_context_length"])
        self.assertEqual(128, qwen["prompt_processor_sequence_length"])
        self.assertIn(4096, qwen["supported_context_lengths"])
        self.assertEqual("APACHE-2.0", qwen["license"])
        self.assertEqual("chatml", llm["runtime_contract"]["prompt_format"])
        self.assertEqual(
            "android_device_smoke_test_with_genie-t2t-run",
            llm["next_artifact_required"],
        )
        self.assertEqual(
            "pending_no_connected_android_device",
            llm["device_smoke_test"]["status"],
        )
        self.assertEqual(2532949886, llm["prepared_artifacts"]["download_zip_bytes"])
        self.assertEqual(4096, llm["prepared_artifacts"]["validated_context_size"])
        self.assertEqual(4, len(llm["prepared_artifacts"]["context_binary_parts"]))

    def test_gemma4_research_branch_is_not_primary(self):
        models = {model["component"]: model for model in self.manifest["models"]}
        gemma = models["llm_research_gemma4"]
        direct_qnn = gemma["direct_qnn"]
        component_names = {component["name"] for component in direct_qnn["components"]}

        self.assertEqual("google/gemma-4-E2B-it", gemma["model_id"])
        self.assertEqual("direct_qnn_dry_run_blocked_by_decoder_rewrite", gemma["preparation_status"])
        self.assertEqual("safetensors", gemma["source_format"])
        self.assertEqual("onnx-community/gemma-4-E2B-it-ONNX", gemma["qnn_source"]["model_id"])
        self.assertEqual({"embed_tokens", "decoder_model_merged"}, component_names)
        self.assertEqual(
            "blocked_by_unsupported_fused_decoder_ops",
            direct_qnn["qnn_dry_run_status"]["decoder_model_merged"],
        )
        self.assertIn(
            "com.microsoft::GroupQueryAttention",
            direct_qnn["qnn_dry_run_status"]["decoder_blockers"],
        )
        self.assertIn(
            "SimplifiedLayerNormalization_to_primitive_rms_norm",
            direct_qnn["rewrite_status"]["completed_rewrites"],
        )
        self.assertIn("inputs_embeds", direct_qnn["expected_runtime_inputs"])
        self.assertIn("per_layer_inputs", direct_qnn["expected_runtime_inputs"])
        self.assertIn("past_key_values", direct_qnn["expected_runtime_inputs"])

    def test_phase1_scripts_reference_required_tools(self):
        prepare_script = (self.root / "converter" / "phase1" / "prepare_qairt_workspace.sh").read_text(
            encoding="utf-8"
        )
        runtime_script = (self.root / "converter" / "phase1" / "fetch_qairt_runtime_libs.sh").read_text(
            encoding="utf-8"
        )
        venv_script = (self.root / "converter" / "phase1" / "setup_qairt_wsl_venv.sh").read_text(
            encoding="utf-8"
        )
        tts_script = (self.root / "converter" / "phase1" / "convert_tts_onnx_to_dlc.sh").read_text(
            encoding="utf-8"
        )
        gemma_venv_script = (
            self.root / "converter" / "phase1" / "setup_gemma4_onnx_wsl_venv.sh"
        ).read_text(encoding="utf-8")
        gemma_export_script = (
            self.root / "converter" / "phase1" / "export_gemma4_text_onnx.py"
        ).read_text(encoding="utf-8")
        gemma_fetch_script = (
            self.root / "converter" / "phase1" / "fetch_gemma4_onnx_community.sh"
        ).read_text(encoding="utf-8")
        gemma_contract_script = (
            self.root / "converter" / "phase1" / "inspect_gemma4_qnn_contract.py"
        ).read_text(encoding="utf-8")
        gemma_rewrite_script = (
            self.root / "converter" / "phase1" / "rewrite_gemma4_onnx_for_qnn.py"
        ).read_text(encoding="utf-8")
        gemma_qnn_script = (
            self.root / "converter" / "phase1" / "convert_gemma4_onnx_to_qnn_contexts.sh"
        ).read_text(encoding="utf-8")
        gemma_qnn_plan = (
            self.root / "converter" / "phase1" / "gemma4_direct_qnn_plan.md"
        ).read_text(encoding="utf-8")
        qwen_fetch_script = (
            self.root / "converter" / "phase1" / "fetch_qwen3_4b_instruct_2507_genie_bundle.sh"
        ).read_text(encoding="utf-8")
        qwen_validation_script = (
            self.root / "converter" / "phase1" / "validate_qwen3_genie_bundle.py"
        ).read_text(encoding="utf-8")
        qwen_plan = (
            self.root / "converter" / "phase1" / "qwen3_4b_instruct_2507_genie_plan.md"
        ).read_text(encoding="utf-8")
        inspect_script = (self.root / "converter" / "phase1" / "inspect_onnx_inputs.py").read_text(
            encoding="utf-8"
        )
        diagnose_script = (self.root / "converter" / "phase1" / "diagnose_tts_graph.py").read_text(
            encoding="utf-8"
        )
        static_script = (self.root / "converter" / "phase1" / "prepare_tts_static_onnx.py").read_text(
            encoding="utf-8"
        )
        rewrite_script = (self.root / "converter" / "phase1" / "rewrite_tts_onnx_for_qairt.py").read_text(
            encoding="utf-8"
        )
        calibration_script = (
            self.root / "converter" / "phase1" / "generate_random_tts_calibration.py"
        ).read_text(encoding="utf-8")
        probe_script = (self.root / "converter" / "phase1" / "create_minimal_qairt_probe_models.py").read_text(
            encoding="utf-8"
        )

        self.assertIn("snpe-onnx-to-dlc", prepare_script)
        self.assertIn("snpe-dlc-quantize", prepare_script)
        self.assertIn("snpe-dlc-graph-prepare", prepare_script)
        self.assertIn("PYTHONPATH", prepare_script)
        self.assertIn("libc++1-14", runtime_script)
        self.assertIn("libunwind-14", runtime_script)
        self.assertIn("dpkg-deb", runtime_script)
        self.assertIn("onnx==1.19.1", venv_script)
        self.assertIn("numpy==1.26.4", venv_script)
        self.assertIn("virtualenv", venv_script)
        self.assertIn("snpe-onnx-to-dlc", tts_script)
        self.assertIn("snpe-dlc-quantize", tts_script)
        self.assertIn("snpe-dlc-graph-prepare", tts_script)
        self.assertIn("--htp_socs=sm8750", tts_script)
        self.assertIn("--optimization_level=3", tts_script)
        self.assertIn("--vtcm_override=0", tts_script)
        self.assertIn("PYTHONPATH", tts_script)
        self.assertIn("TTS_TEXT_LENGTH", tts_script)
        self.assertIn("TTS_LATENT_LENGTH", tts_script)
        self.assertIn("qualcomm/Qwen3-4B-Instruct-2507", qwen_fetch_script)
        self.assertIn("release_assets.json", qwen_fetch_script)
        self.assertIn("genie_config.json", qwen_fetch_script)
        self.assertIn("qualcomm-snapdragon-8-elite", qwen_fetch_script)
        self.assertIn("QWEN3_FETCH_MODE", qwen_fetch_script)
        self.assertIn("zipfile", qwen_fetch_script)
        self.assertIn("w4a16", qwen_fetch_script)
        self.assertIn("genie_config.json", qwen_validation_script)
        self.assertIn("qwen3_genie_bundle_validation.json", qwen_validation_script)
        self.assertIn("bin_part_count", qwen_validation_script)
        self.assertIn("GENIE", qwen_plan)
        self.assertIn("4096", qwen_plan)
        self.assertIn("ChatML", qwen_plan)
        self.assertIn("transformers", gemma_venv_script)
        self.assertIn("optimum[onnxruntime]", gemma_venv_script)
        self.assertIn("AutoModelForCausalLM", gemma_export_script)
        self.assertIn("gemma4_text_prefill_no_cache.onnx", gemma_export_script)
        self.assertIn("use_cache=False", gemma_export_script)
        self.assertIn("external_data=True", gemma_export_script)
        self.assertIn("onnx-community/gemma-4-E2B-it-ONNX", gemma_fetch_script)
        self.assertIn("embed_tokens_fp16.onnx", gemma_fetch_script)
        self.assertIn("decoder_model_merged_fp16.onnx", gemma_fetch_script)
        self.assertIn("inputs_embeds", gemma_contract_script)
        self.assertIn("per_layer_inputs", gemma_contract_script)
        self.assertIn("past_key_values", gemma_contract_script)
        self.assertIn("SimplifiedLayerNormalization", gemma_rewrite_script)
        self.assertIn("ReduceMean", gemma_rewrite_script)
        self.assertIn("text_only_embed_tokens", gemma_rewrite_script)
        self.assertIn("remove_output_cast", gemma_rewrite_script)
        self.assertIn("remove_input_cast", gemma_rewrite_script)
        self.assertIn("external_data", gemma_rewrite_script)
        self.assertIn("os.link", gemma_rewrite_script)
        self.assertIn("qnn-onnx-converter", gemma_qnn_script)
        self.assertIn("qnn-model-lib-generator", gemma_qnn_script)
        self.assertIn("qnn-context-binary-generator", gemma_qnn_script)
        self.assertIn("qnn-net-run", gemma_qnn_script)
        self.assertIn("target-agnostic", gemma_qnn_script)
        self.assertIn("TARGET_SOC_MODEL", gemma_qnn_script)
        self.assertIn("SM8750", gemma_qnn_script)
        self.assertIn("v79", gemma_qnn_script)
        self.assertIn("soc_model", gemma_qnn_script)
        self.assertIn("perf_profile", gemma_qnn_script)
        self.assertIn("burst", gemma_qnn_script)
        self.assertIn("GEMMA4_QNN_MODE", gemma_qnn_script)
        self.assertIn("direct QNN", gemma_qnn_plan)
        self.assertIn("onnx.load", inspect_script)
        self.assertIn("INTERESTING_OPS", diagnose_script)
        self.assertIn("version_converter.convert_version", static_script)
        self.assertIn("TTS_FORCE_OPSET_WITHOUT_CONVERSION", static_script)
        self.assertIn("onnxsim", rewrite_script)
        self.assertIn("rewrite_report.json", rewrite_script)
        self.assertIn("random_tts_calibration", calibration_script)
        self.assertIn("pipeline_smoke_test_only", calibration_script)
        self.assertIn("np.int32", calibration_script)
        self.assertIn("rank3_transpose", probe_script)


if __name__ == "__main__":
    unittest.main()
