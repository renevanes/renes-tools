#!/usr/bin/env python3
"""Bepaal een ongebruikt versienummer; controleer de vaste identiteit van een publicatie."""
import json
import re
import subprocess
import sys


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def nummer(value):
    if not isinstance(value, str) or not re.fullmatch(r"(?:0|[1-9][0-9]*)\.(?:0|[1-9][0-9]*)", value):
        raise ValueError("Ongeldig versienummer")
    return tuple(map(int, value.split(".")))


def versie(commit):
    data = {}
    for line in git("show", f"{commit}:VERSION").splitlines():
        if not line or line.startswith("#"):
            continue
        key, value = line.split("=", 1)
        if key not in ("VERSION_NAME", "VERSION_CODE", "NATIVE_LEVEL") or key in data:
            raise ValueError("Ongeldig VERSION-bestand")
        data[key] = value
    name = nummer(data["VERSION_NAME"])
    for key in ("VERSION_CODE", "NATIVE_LEVEL"):
        if not re.fullmatch(r"0|[1-9][0-9]*", data[key]):
            raise ValueError("Ongeldige versiecode of APK-versie")
        data[key] = int(data[key])
    return name, data["VERSION_CODE"], data["NATIVE_LEVEL"]


def hoogste(base):
    name, code, native = versie(base)
    # Ook eerder gepubliceerde nummers blijven verbruikt als VERSION per ongeluk wordt teruggezet.
    history = git("log", "--format=", "--no-ext-diff", "--no-textconv", "-p", base, "--", "VERSION")
    for line in history.splitlines():
        if re.fullmatch(r"\+VERSION_NAME=[0-9]+\.[0-9]+", line):
            name = max(name, nummer(line.split("=", 1)[1]))
        elif re.fullmatch(r"\+VERSION_CODE=[0-9]+", line):
            code = max(code, int(line.split("=", 1)[1]))
        elif re.fullmatch(r"\+NATIVE_LEVEL=[0-9]+", line):
            native = max(native, int(line.split("=", 1)[1]))
    for entry in json.loads(git("show", f"{base}:changelog.json")):
        name = max(name, nummer(entry["version"]))
    refs = git("for-each-ref", "--format=%(refname)", "refs/tags/versie-*", "refs/tags/versiecode-*")
    for ref in refs.splitlines():
        if ref.startswith("refs/tags/versie-"):
            name = max(name, nummer(ref.removeprefix("refs/tags/versie-")))
        elif re.fullmatch(r"refs/tags/versiecode-[1-9][0-9]*", ref):
            code = max(code, int(ref.rsplit("-", 1)[1]))
        else:
            raise ValueError("Ongeldige versieregistratie")
    return name, code, native


def main():
    action, base, *args = sys.argv[1:]
    if not re.fullmatch(r"[0-9a-f]{40}", base):
        raise ValueError("Ongeldige basiscommit")
    name, code, native = hoogste(base)
    if action == "volgende" and len(args) == 1 and args[0] in ("0", "1"):
        major, minor = name
        if args[0] == "1":
            major, minor = major + 1, 0
        else:
            minor += 1
        if code >= 2147483647:
            raise ValueError("Geen hogere Android-versiecode beschikbaar")
        print(f"{major}.{minor} {code + 1} {native}")
    elif action == "controleer" and len(args) == 3:
        commit, label, counter = args
        if not re.fullmatch(r"[0-9a-f]{40}", commit) or not re.fullmatch(r"[1-9][0-9]*", counter):
            raise ValueError("Ongeldige publicatie")
        target = nummer(label)
        if target <= name or int(counter) <= code:
            raise ValueError("Dit versienummer of deze versiecode is al gebruikt; bepaal een nieuwe versie")
        planned = versie(commit)
        meta = json.loads(git("show", f"{commit}:update/update.json"))
        log = json.loads(git("show", f"{commit}:changelog.json"))
        if (planned[:2] != (target, int(counter)) or meta.get("versionName") != label
                or type(meta.get("versionCode")) is not int or meta["versionCode"] != int(counter)
                or type(meta.get("nativeLevel")) is not int or meta["nativeLevel"] != planned[2]
                or not log or log[0].get("version") != label):
            raise ValueError("VERSION, wijzigingslog en ondertekende update hebben verschillende versies")
    else:
        raise ValueError("Ongeldige opdracht voor de versiecontrole")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, IndexError, TypeError, subprocess.CalledProcessError) as error:
        print(f"Publicatie gestopt: {error}", file=sys.stderr)
        sys.exit(1)
