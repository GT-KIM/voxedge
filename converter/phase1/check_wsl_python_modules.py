#!/usr/bin/env python3
import importlib
import importlib.util


for name in ["onnx", "onnxsim", "onnxoptimizer", "onnxruntime", "numpy", "google.protobuf"]:
    spec = importlib.util.find_spec(name)
    if not spec:
        print(f"{name}: missing")
        continue

    module = importlib.import_module(name)
    version = getattr(module, "__version__", "unknown")
    print(f"{name}: available {version}")
