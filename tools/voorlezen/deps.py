#!/usr/bin/env python3
"""Vastgezette voorleesbibliotheken; controle vóór uitpakken, alleen de benodigde native bestanden."""
import hashlib, json, os, pathlib, subprocess, sys, tarfile
root=pathlib.Path(__file__).resolve().parent
out=pathlib.Path(sys.argv[1]); out.mkdir(parents=True,exist_ok=True)
manifest=json.loads((root/'deps.json').read_text())
for name,(url,digest) in manifest['files'].items():
    target=out/name
    if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
        tmp=out/(name+'.part')
        subprocess.run(['curl','--fail','--location','--silent','--show-error','--retry','2','--max-time','180','--output',str(tmp),url],check=True)
        if hashlib.sha256(tmp.read_bytes()).hexdigest()!=digest:
            tmp.unlink(); raise SystemExit('Voorleesbibliotheek: controlegetal klopt niet')
        tmp.replace(target)
with tarfile.open(out/'android.tar.bz2','r:bz2') as archive:
    for abi in ('arm64-v8a','x86_64'):
        for lib in ('libonnxruntime.so','libsherpa-onnx-jni.so'):
            entry=archive.getmember('./jniLibs/'+abi+'/'+lib)
            if not entry.isfile() or entry.size>80*1024*1024: raise SystemExit('Onverwachte native bibliotheek')
            target=out/'lib'/abi/lib; target.parent.mkdir(parents=True,exist_ok=True)
            content=archive.extractfile(entry).read()
            if not target.is_file() or hashlib.sha256(target.read_bytes()).digest()!=hashlib.sha256(content).digest():
                target.write_bytes(content)
