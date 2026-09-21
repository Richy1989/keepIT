"""Fails on NuGet packages with a high or critical advisory.

Reads `dotnet list package --vulnerable --include-transitive --format json` on stdin, prints every
advisory it finds (lower severities too, as information) and exits 1 if any is High or Critical.
`dotnet list` itself always exits 0, which is why this script exists.
"""
import json
import sys

BLOCKING = {"high", "critical"}

report = json.load(sys.stdin)
findings = set()
for project in report.get("projects", []):
    # A project without vulnerable packages has no "frameworks" key at all.
    for framework in project.get("frameworks", []):
        for kind in ("topLevelPackages", "transitivePackages"):
            for package in framework.get(kind, []):
                for advisory in package.get("vulnerabilities", []):
                    findings.add((advisory["severity"], package["id"], package["resolvedVersion"],
                                  advisory["advisoryurl"], kind == "transitivePackages"))

for severity, package, version, url, transitive in sorted(findings):
    via = " (transitive)" if transitive else ""
    print(f"{severity}: {package} {version}{via} {url}")

blocking = [f for f in findings if f[0].lower() in BLOCKING]
if blocking:
    print(f"::error::{len(blocking)} NuGet advisory(ies) rated high or critical")
    sys.exit(1)
print(f"No high or critical NuGet advisories ({len(findings)} lower-rated).")
