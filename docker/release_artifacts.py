"""Acquire maintainer-built release JARs; never execute unverified downloads."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parent.parent
manifest = json.loads((root / "docker/release-inputs.json").read_text())
tag = os.environ["RELEASE_TAG"]
if not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.]+)?", tag):
    raise SystemExit("Invalid release tag")
if tag != manifest["tag"]:
    raise SystemExit("Release tag does not match committed artifact manifest")
with tempfile.TemporaryDirectory() as tmp:
    for item in manifest["artifacts"]:
        subprocess.run(["gh", "release", "download", tag, "--repo", "SurStor/SurStor_Memory",
                        "--pattern", item["name"], "--dir", tmp], check=True)
        source = Path(tmp) / item["name"]
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        if digest != item["sha256"]:
            raise SystemExit("Release artifact checksum mismatch: " + item["name"])
        target = (root / item["path"]).resolve()
        if not target.is_relative_to(root):
            raise SystemExit("Invalid artifact destination")
        target.parent.mkdir(parents=True, exist_ok=True)
        source.replace(target)
print("Release artifact checksums verified")
