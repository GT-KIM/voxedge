import sherpa_onnx
fac = [m for m in dir(sherpa_onnx.OfflineRecognizer) if m.startswith("from_")]
print("OfflineRecognizer factories:", fac)
