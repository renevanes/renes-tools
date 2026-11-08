#!/usr/bin/env python3
"""Vastgezette voorleesbibliotheken; controle vóór uitpakken, alleen de benodigde native bestanden."""
import hashlib, json, pathlib, subprocess, sys, tarfile, zipfile
root=pathlib.Path(__file__).resolve().parent
out=pathlib.Path(sys.argv[1]); out.mkdir(parents=True,exist_ok=True)
manifest=json.loads((root/'deps.json').read_text())
for name,(url,digest) in manifest['files'].items():
    target=out/name
    if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest()!=digest:
        tmp=out/(name+'.part')
        subprocess.run(['curl','--fail','--location','--proto','=https','--proto-redir','=https','--silent','--show-error','--retry','2','--max-time','180','--output',str(tmp),url],check=True)
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
            # ONNX Runtime-versie in deps.json moet kloppen (onderhoud-controle.py zoekt er kwetsbaarheden bij).
            if lib=='libonnxruntime.so' and b'VERS_'+manifest['onnxruntime'].encode()+b'\x00' not in content:
                raise SystemExit('ONNX Runtime-versie klopt niet met deps.json')
            if not target.is_file() or hashlib.sha256(target.read_bytes()).digest()!=hashlib.sha256(content).digest():
                target.write_bytes(content)

# Alleen de zelfstandige Java-8 TAR/BZip2-codecs en hun zeven tekencoderingen.
# Ants overige ZIP-/bouwclasses bevatten API's die Android 24 niet heeft.
encoding_names = ("FallbackZipEncoding", "NioZipEncoding", "Simple8BitZipEncoding",
                  "Simple8BitZipEncoding$Simple8BitChar", "ZipEncoding",
                  "ZipEncodingHelper", "ZipEncodingHelper$SimpleEncodingHolder")
allowed = {"org/apache/tools/zip/"+name+".class" for name in encoding_names}
with zipfile.ZipFile(out/'ant.jar') as source:
    allowed.update(n for n in source.namelist() if n.startswith(('org/apache/tools/tar/', 'org/apache/tools/bzip2/')) and n.endswith('.class'))
    payload = {n:source.read(n) for n in sorted(allowed)}
    if len(payload)!=22 or any(b'java/nio/file/' in data for data in payload.values()):
        raise SystemExit('Onverwachte of niet Android-24-geschikte archiefcodecs')
    temporary=out/'ant-codecs.jar.new'
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED) as dest:
        for name,data in payload.items():
            dest.writestr(zipfile.ZipInfo(name, (1980,1,1,0,0,0)), data)
    temporary.replace(out/'ant-codecs.jar')
