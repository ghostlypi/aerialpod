#!/usr/bin/env python3
"""Regenerate python3-deps.json: AerialPod's PyPI dependencies as flatpak sources.

flatpak-builder builds offline, so every wheel has to be listed up front with
its URL and hash. Wheels with compiled code (cryptography, cffi, dbus-fast, …)
are listed once per architecture and tagged `only-arches`, so the same manifest
builds natively on x86_64 and aarch64 without a Rust or C toolchain.

PySide6 is not here: it comes from io.qt.PySide.BaseApp, built against the
runtime's own Qt.

    packaging/flatpak/gen-python-deps.py          # after changing pyproject deps
    packaging/flatpak/gen-python-deps.py --python 3.13

Run it whenever pyproject.toml's dependencies change or the runtime's Python
version moves, then commit the JSON.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
import tempfile
import tomllib
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
ARCHES = ["x86_64", "aarch64"]
# Build-only: lets `pip install .` build the app's own wheel offline.
BUILD_REQUIREMENTS = ["hatchling"]
# Provided by the BaseApp; must not be pulled from PyPI.
PROVIDED = {"pyside6"}


def requirements() -> list[str]:
    project = tomllib.loads((ROOT / "pyproject.toml").read_text())["project"]
    reqs = []
    for req in project["dependencies"]:
        name = re.split(r"[<>=!~;\[ ]", req, maxsplit=1)[0]
        if name.lower() not in PROVIDED:
            reqs.append(req)
    return reqs + BUILD_REQUIREMENTS


def download(arch: str, python: str, reqs: list[str], dest: Path) -> list[Path]:
    # pip only accepts exact platform tags, so offer every manylinux level the
    # runtime's glibc can load.
    platforms = [f"manylinux_2_{minor}_{arch}" for minor in range(17, 42)]
    platforms.append(f"manylinux2014_{arch}")
    nodot = python.replace(".", "")
    cmd = [sys.executable, "-m", "pip", "download", "--quiet", "--dest", str(dest),
           "--only-binary=:all:", "--implementation", "cp",
           "--python-version", python, "--abi", f"cp{nodot}", "--abi", "abi3",
           "--abi", "none"]
    for platform in platforms:
        cmd += ["--platform", platform]
    subprocess.run(cmd + reqs, check=True)
    return sorted(dest.glob("*.whl"))


def pypi_url(wheel: Path) -> str:
    name, version = wheel.name.split("-")[:2]
    with urllib.request.urlopen(f"https://pypi.org/pypi/{name}/{version}/json") as resp:
        info = json.load(resp)
    for entry in info["urls"]:
        if entry["filename"] == wheel.name:
            return entry["url"]
    raise SystemExit(f"{wheel.name}: not found on PyPI")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--python", default="3.13",
                        help="the runtime's Python version (default: %(default)s)")
    args = parser.parse_args()

    reqs = requirements()
    # wheel filename -> (sha256, set of arches it was chosen for)
    chosen: dict[str, tuple[Path, str, set[str]]] = {}
    with tempfile.TemporaryDirectory() as tmp:
        for arch in ARCHES:
            for wheel in download(arch, args.python, reqs, Path(tmp) / arch):
                digest = hashlib.sha256(wheel.read_bytes()).hexdigest()
                chosen.setdefault(wheel.name, (wheel, digest, set()))[2].add(arch)

        sources = []
        for filename in sorted(chosen):
            wheel, digest, arches = chosen[filename]
            source = {"type": "file", "url": pypi_url(wheel), "sha256": digest}
            if arches != set(ARCHES):
                source["only-arches"] = sorted(arches)
            sources.append(source)

    names = sorted({re.split(r"[<>=!~;\[ ]", r, maxsplit=1)[0] for r in reqs})
    module = {
        "name": "python3-deps",
        "buildsystem": "simple",
        "build-commands": [
            "pip3 install --no-index --find-links=\"file://${PWD}\" "
            "--prefix=${FLATPAK_DEST} --no-build-isolation " + " ".join(names)
        ],
        "sources": sources,
    }
    out = HERE / "python3-deps.json"
    out.write_text(json.dumps(module, indent=4) + "\n")
    print(f"wrote {out.relative_to(ROOT)}: {len(sources)} wheels for {', '.join(ARCHES)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
