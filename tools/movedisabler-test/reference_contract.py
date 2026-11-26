#!/usr/bin/env python3
"""Run the actual reference check method with API doubles, not a copied predicate."""
import argparse
import hashlib
from pathlib import Path
import re
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--out", type=Path, required=True)
args = parser.parse_args()
source = Path("references/anticheats/Intave/src/main/java/de/jpx3/intave/check/other/"
              "inventoryclickanalysis/OnMoveCheck.java")
original = source.read_text()
# Only remove namespace/import declarations; keep the checked implementation verbatim.
body = re.sub(r"^(?:package|import) [^\n]+;\n", "", original, flags=re.M)
args.out.mkdir(parents=True, exist_ok=True)
target = args.out / "OnMoveCheck.java"
target.write_text(body)
print("[reference-contract] OnMoveCheck sha256=" + hashlib.sha256(source.read_bytes()).hexdigest(), flush=True)
subprocess.run(["javac", "-source", "8", "-target", "8", "-Xlint:-options", "-encoding", "UTF-8",
                "-d", str(args.out), str(target), "tools/movedisabler-test/OnMoveReferenceTest.java"], check=True)
subprocess.run(["java", "-cp", str(args.out), "OnMoveReferenceTest"], check=True)
