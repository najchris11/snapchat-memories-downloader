"""Static checks for the candidate winget manifests. NOT a substitute for `winget validate`.

Needs `jsonschema` and `pyyaml`, which the repo does not depend on, so this is deliberately not a
`test_*.py` file: `python3 -m unittest discover -s scripts` must keep running with the stdlib only.

    python3 -m venv /tmp/wgvenv && /tmp/wgvenv/bin/pip install jsonschema pyyaml
    /tmp/wgvenv/bin/python distribution/winget/validate_manifests.py [--msi PATH]

Checks, in order: schema (pinned, hash-verified copies of the winget-cli schemas), cross-file
consistency, agreement with evidence.json (the MSI's own properties), then a negative control that
mutates the loaded manifests in memory and requires every mutation to be rejected.
"""

import argparse
import copy
import hashlib
import json
import sys
import urllib.request
from pathlib import Path

import jsonschema
import yaml

HERE = Path(__file__).resolve().parent
PACKAGE_DIR = HERE / "manifests/n/NajDev/SnapVault/1.0.15"
EVIDENCE = json.loads((HERE / "evidence.json").read_text())
KINDS = {"version": "", "installer": ".installer", "defaultLocale": ".locale.en-US"}


class _StringDates(yaml.SafeLoader):
    """Keep `ReleaseDate: 2026-10-06` a string; the schema types it as a string."""


_StringDates.yaml_implicit_resolvers = {
    ch: [(tag, rx) for tag, rx in rs if tag != "tag:yaml.org,2002:timestamp"]
    for ch, rs in yaml.SafeLoader.yaml_implicit_resolvers.items()
}


def load_manifests(directory=PACKAGE_DIR):
    return {k: yaml.load((directory / f"NajDev.SnapVault{s}.yaml").read_text(), _StringDates)
            for k, s in KINDS.items()}


def fetch_schema(kind, version):
    schema = EVIDENCE["schema"]
    url = (schema["source"].replace("{commit}", schema["wingetCliCommit"])
           .replace("{version}", version).replace("{kind}", kind))
    body = urllib.request.urlopen(url, timeout=30).read()
    want = schema["sha256"][version][kind]
    if hashlib.sha256(body).hexdigest() != want:
        raise SystemExit(f"schema hash mismatch for {kind} {version} ({url})")
    return json.loads(body)


def schema_errors(manifests, schemas):
    errors = []
    for kind, doc in manifests.items():
        validator = jsonschema.Draft7Validator(schemas[kind], format_checker=jsonschema.FormatChecker())
        errors += [f"{kind}: {'/'.join(map(str, e.absolute_path))}: {e.message}"
                   for e in validator.iter_errors(doc)]
    return errors


def consistency_errors(m):
    version, installer, locale = m["version"], m["installer"], m["defaultLocale"]
    msi, props = EVIDENCE["msi"], EVIDENCE["msi"]["properties"]
    errors = []

    def expect(label, got, want):
        if got != want:
            errors.append(f"{label}: {got!r} != {want!r}")

    for key in ("PackageIdentifier", "PackageVersion", "ManifestVersion"):
        expect(f"{key} across files", len({d[key] for d in m.values()}), 1)
    expect("PackageVersion vs MSI ProductVersion", version["PackageVersion"], props["ProductVersion"])
    expect("DefaultLocale vs locale file", version["DefaultLocale"], locale["PackageLocale"])
    expect("PackageName vs MSI ProductName", locale["PackageName"], props["ProductName"])
    expect("ProductCode vs MSI", installer.get("ProductCode"), props["ProductCode"])
    expect("Scope vs MSI ALLUSERS", installer.get("Scope"), "machine" if props["ALLUSERS"] == "1" else "user")
    arp = installer.get("AppsAndFeaturesEntries", [])
    expect("ARP entry count", len(arp), 1)
    for entry in arp[:1]:
        expect("ARP DisplayName", entry.get("DisplayName"), props["ProductName"])
        expect("ARP Publisher vs MSI Manufacturer", entry.get("Publisher"), props["Manufacturer"])
        expect("ARP DisplayVersion", entry.get("DisplayVersion"), props["ProductVersion"])
        expect("ARP ProductCode", entry.get("ProductCode"), props["ProductCode"])
        expect("ARP UpgradeCode", entry.get("UpgradeCode"), props["UpgradeCode"])
    installers = installer.get("Installers", [])
    expect("installer count", len(installers), 1)
    for entry in installers[:1]:
        expect("InstallerUrl", entry.get("InstallerUrl"), msi["url"])
        expect("InstallerSha256", str(entry.get("InstallerSha256")).lower(), msi["sha256"])
        expect("Architecture vs MSI Template", entry.get("Architecture"),
               "x64" if EVIDENCE["msi"]["summaryInformation"]["Template"].startswith("x64") else None)
    expect("ReleaseDate vs release publish date", installer.get("ReleaseDate"), EVIDENCE["release"]["publishedAt"][:10])
    expect("ReleaseNotesUrl", locale.get("ReleaseNotesUrl"), EVIDENCE["release"]["url"])
    expect("LicenseUrl pinned to tag", locale.get("LicenseUrl", "").split("/blob/")[-1].split("/")[0], EVIDENCE["release"]["tag"])
    return errors


def mutations(m):
    def mutate(kind, fn):
        copy_ = copy.deepcopy(m)
        fn(copy_[kind])
        return copy_
    return {
        "malformed InstallerSha256": mutate("installer", lambda d: d["Installers"][0].update(InstallerSha256="not-a-hash")),
        "unknown Scope": mutate("installer", lambda d: d.update(Scope="everyone")),
        "no Installers": mutate("installer", lambda d: d.pop("Installers")),
        "unknown InstallerType": mutate("installer", lambda d: d.update(InstallerType="rpm")),
        "PackageIdentifier without publisher": mutate("version", lambda d: d.update(PackageIdentifier="SnapVault")),
        "valid-looking wrong sha": mutate("installer", lambda d: d["Installers"][0].update(InstallerSha256="0" * 64)),
        "wrong ProductCode": mutate("installer", lambda d: d.update(ProductCode="{00000000-0000-0000-0000-000000000000}")),
        "version drift in one file": mutate("defaultLocale", lambda d: d.update(PackageVersion="1.0.14")),
        "per-user scope": mutate("installer", lambda d: d.update(Scope="user")),
    }


def check_all(m, schemas):
    return schema_errors(m, schemas) + consistency_errors(m)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--msi", help="local copy of the MSI: re-verify size and sha256")
    args = parser.parse_args()
    failed = False

    if args.msi:
        data = Path(args.msi).read_bytes()
        ok = (len(data) == EVIDENCE["msi"]["sizeBytes"]
              and hashlib.sha256(data).hexdigest() == EVIDENCE["msi"]["sha256"])
        print(f"MSI size+sha256 match evidence: {ok}")
        failed |= not ok

    manifests = load_manifests()
    for version in sorted(EVIDENCE["schema"]["sha256"]):
        schemas = {k: fetch_schema(k, version) for k in KINDS}
        probe = copy.deepcopy(manifests)
        if version != EVIDENCE["schema"]["manifestVersion"]:  # cross-check only: re-stamp the version
            for doc in probe.values():
                doc["ManifestVersion"] = version
        errors = schema_errors(probe, schemas)
        print(f"schema {version}: {'OK' if not errors else 'FAIL'}")
        for e in errors:
            print("  ", e)
        failed |= bool(errors)
        if version == EVIDENCE["schema"]["manifestVersion"]:
            primary = schemas

    errors = consistency_errors(manifests)
    print(f"consistency with MSI evidence: {'OK' if not errors else 'FAIL'}")
    for e in errors:
        print("  ", e)
    failed |= bool(errors)

    print("negative control (each mutation must be rejected):")
    for name, mutated in mutations(manifests).items():
        rejected = check_all(mutated, primary)
        print(f"  {'rejected' if rejected else 'NOT REJECTED'}: {name}" + (f"  -> {rejected[0]}" if rejected else ""))
        failed |= not rejected
    print("FAILED" if failed else "ALL CHECKS PASSED")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
