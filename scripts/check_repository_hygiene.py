#!/usr/bin/env python3
"""Repository entry-point and resource hygiene checks; conservative, no auto-deletion."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
JAVA = MAIN / "java"
CPP = MAIN / "cpp"
RES = MAIN / "res"
errors = []

def check(condition, message):
    if not condition:
        errors.append(message)

kotlin = {p: p.read_text(encoding="utf-8") for p in JAVA.rglob("*.kt")}
xml = {p: p.read_text(encoding="utf-8") for p in RES.rglob("*.xml")}
manifest = (MAIN / "AndroidManifest.xml").read_text(encoding="utf-8")
cmake = (CPP / "CMakeLists.txt").read_text(encoding="utf-8")
source_text = "\n".join(kotlin.values())

# Only check resources referenced by generated R names; framework/manifest
# resources are accounted for separately.
for p in (RES / "drawable").glob("*.xml"):
    check(bool(re.search(r"\\bR\\.drawable\\." + re.escape(p.stem) + r"\\b", source_text))
          or ("@drawable/" + p.stem) in manifest
          or any(("@drawable/" + p.stem) in body for body in xml.values()),
          f"Orphan drawable: {p.relative_to(ROOT)}")

strings = xml.get(RES / "values/strings.xml", "")
for name in re.findall(r'<string\\s+name="([^"]+)"', strings):
    check(bool(re.search(r"\\bR\\.string\\." + re.escape(name) + r"\\b", source_text))
          or ("@string/" + name) in manifest
          or any(("@string/" + name) in body for body in xml.values()),
          f"Orphan string: {name}")

# Verify declared Android components actually exist; avoid mistaking implicit
# JNI entry points or native code for unused implementation.
package = "com.tvplayair.receiver"
for name in re.findall(r'<(?:activity|activity-alias|service|receiver)[^>]*?android:name="([^"]+)"', manifest, re.S):
    if name.startswith(".") and name != ".ProjectionHome":
        target = JAVA / (package.replace(".", "/") + name.replace(".", "/") + ".kt")
        check(target.exists(), f"Missing manifest component: {name}")

# All production translation units must be accounted for by CMake.
for p in CPP.rglob("*"):
    if p.suffix not in (".cpp", ".c"):
        continue
    check(p.relative_to(CPP).as_posix() in cmake,
          f"Native source not listed in CMake: {p.relative_to(CPP)}")

if errors:
    for error in errors:
        print("ERROR:", error, file=sys.stderr)
    sys.exit(1)
print(f"Resource and entry-point hygiene OK: {len(kotlin)} Kotlin files, "
      f"{len(xml)} XML resources")
