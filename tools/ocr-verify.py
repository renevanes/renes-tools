#!/usr/bin/env python3
"""Controleert de gebundelde offline OCR-bestanden vóór iedere bouw."""
import hashlib
import json
import struct
from pathlib import Path

root = Path(__file__).resolve().parents[1]
meta = json.loads((root / "app/ocr/metadata.json").read_text())
abis = ("arm64-v8a", "armeabi-v7a", "x86", "x86_64")
libs = ("libjpeg.so", "libleptonica.so", "libpngx.so", "libtesseract.so")
expected = {"app/ocr/eng.traineddata", "app/ocr/tesseract4android.jar"}
expected.update(f"app/lib/{abi}/{lib}" for abi in abis for lib in libs)
assert set(meta["files"]) == expected, "Onvolledige OCR-bundel"
for name, digest in meta["files"].items():
    data = (root / name).read_bytes()
    assert hashlib.sha256(data).hexdigest() == digest, f"OCR-controlegetal wijkt af: {name}"
    if name.endswith(".so"):
        assert data[:4] == b"\x7fELF", f"Ongeldige OCR-bibliotheek: {name}"
        if data[4] == 2:
            offset = struct.unpack_from("<Q", data, 32)[0]
            size, count = struct.unpack_from("<HH", data, 54)
            for i in range(count):
                ph = offset + i * size
                if struct.unpack_from("<I", data, ph)[0] == 1:
                    assert struct.unpack_from("<Q", data, ph + 48)[0] >= 16384, f"16 KB-uitlijning ontbreekt: {name}"
for name in ("wrapper", "tesseract", "tessdata", "leptonica", "libpng", "libjpeg"):
    assert (root / f"app/ocr/licenses/{name}.txt").stat().st_size > 100, f"OCR-licentie ontbreekt: {name}"
print("Offline OCR: controlegetallen, licenties, vier processorsoorten en 16 KB-uitlijning kloppen")
