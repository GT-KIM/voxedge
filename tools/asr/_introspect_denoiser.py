import sherpa_onnx
print("sherpa_onnx", getattr(sherpa_onnx, "__version__", "?"))
names = [n for n in dir(sherpa_onnx) if ("enois" in n.lower() or "nhanc" in n.lower())]
print("denoiser symbols:", names)
for n in names:
    obj = getattr(sherpa_onnx, n)
    if hasattr(obj, "from_gtcrn") or hasattr(obj, "from_dpdfnet"):
        print(n, "factories:", [m for m in dir(obj) if m.startswith("from_")])
