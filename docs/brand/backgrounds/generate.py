"""Generate editable masters and PNG stencils (opaque black) with VectorCraft CLI 0.24.x.

Run python3 docs/brand/backgrounds/generate.py from any directory.
Set KEEPIT_OUTPUT to use a staging directory (default is the repository).
"""
import json
import os
from pathlib import Path
import subprocess

HERE = Path(__file__).resolve().parent
ROOT = Path(os.environ.get("KEEPIT_OUTPUT", HERE.parents[2])).resolve()
MASTERS = ROOT / "docs/brand/backgrounds"
TILES = ROOT / "backgrounds"
NAMES = """lined squared dots waves groceries code work study money coffee food baking
cocktails fruit sport running cycling yoga party birthday celebration travel beach garden pets""".split()


def cli(*args):
    result = subprocess.run(["vectorcraft-cli", "--safe-mode", *map(str, args)],
                            capture_output=True, text=True,
                            env={**os.environ, "SOURCE_DATE_EPOCH": "0", "VECTORCRAFT_NO_PREFS": "1"})
    if result.returncode:
        raise RuntimeError(result.stderr or result.stdout)
    for line in result.stdout.splitlines():
        step = json.loads(line)
        report = step.get("result", step)
        for field in ("warnings", "importWarnings", "missingLinks", "modifiedLinks"):
            if report.get(field):
                raise RuntimeError(f"{step.get('path', args)}: {report[field]}")
        if report.get("ok") is False:
            raise RuntimeError(str(report))


def build(name):
    source = MASTERS / f"{name}.vectorcraft"
    library = MASTERS / "icons.vectorcraft"
    inputs = ["--in", library] if name not in ["icons", *NAMES[:4]] else []
    script = {"path": str(HERE / "compose.js"), "args": {"name": name}}
    cli("run", *inputs, "--cmd", "script.run", "--params", json.dumps(script),
        "--export", source)
    if name == "icons":
        return
    options = {"artboard": 0, "ppi": 72, "includeArtboardFill": False,
               "background": "transparent", "path": str(TILES / f"{name}.png")}
    cli("run", "--in", source, "--cmd", "document.export", "--params", json.dumps(options))


if __name__ == "__main__":
    MASTERS.mkdir(parents=True, exist_ok=True)
    TILES.mkdir(parents=True, exist_ok=True)
    for pattern in ["icons", *NAMES]:
        build(pattern)
