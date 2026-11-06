#!/usr/bin/env python3
"""Verify built APKs and prepare ColorDuo download/publication artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
DEVELOPMENT_CERT = "b4e3a12d03957e441c3ccf2e4e2be55de0db35fc5eb9aa8ac49a0a9d7298c41f"
VERSION_PATTERN = re.compile(r"\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?")


def validate_version(version, code):
    if not VERSION_PATTERN.fullmatch(version):
        raise ValueError("Version must be major.minor.patch with an optional prerelease suffix")
    if not re.fullmatch(r"[1-9][0-9]*", str(code)) or int(code) > 2_100_000_000:
        raise ValueError("versionCode must be an integer from 1 to 2100000000")
    return int(code)


def release_notes(version, changelog=None):
    text = changelog if changelog is not None else (ROOT / "docs/CHANGELOG.md").read_text()
    match = re.search(rf"^## {re.escape(version)}[ \t]*\n(.*?)(?=^## |\Z)", text, re.M | re.S)
    if not match or not match[1].strip():
        raise ValueError(f"Missing nonempty changelog entry for {version}")
    return match[1].strip() + "\n"


def github_prefix(owner):
    # Xposed-Modules-Repo/submission/src/bot.ts: normalizeUsername/checkPages.
    return "io.github." + owner.lower().replace("-", "_") + "."


def module_metadata(apk, flavor="modern", manifest=None):
    if flavor not in ("modern", "legacy"):
        raise ValueError("Unknown Xposed API flavor")
    metadata = {}
    if manifest is not None:
        ns = "{http://schemas.android.com/apk/res/android}"
        root = ET.fromstring(manifest)
        metadata = {item.get(ns + "name"): item.get(ns + "value", item.get(ns + "resource"))
                    for item in root.findall("application/meta-data")}
    with zipfile.ZipFile(apk) as archive:
        names = set(archive.namelist())
        if flavor == "legacy":
            if any(name.startswith("META-INF/xposed/") for name in names):
                raise ValueError("Legacy APK must not contain modern module metadata")
            if metadata.get("xposedmodule") != "true" or metadata.get("libxposed") is not None:
                raise ValueError("Legacy APK must declare only the legacy framework entry")
            if metadata.get("xposedminversion") != "82" or not re.fullmatch(
                    r"@(?:array/xposed_scope|ref/0x[0-9a-fA-F]+)", metadata.get("xposedscope", "")):
                raise ValueError("Unexpected legacy API minimum or scope declaration")
            entries = archive.read("assets/xposed_init").decode().strip()
            values = {"minApiVersion": "82", "targetApiVersion": "82", "autoHotReload": "false"}
            scope = "com.android.systemui"
        else:
            if "assets/xposed_init" in names:
                raise ValueError("Modern APK must not contain a legacy entry point")
            if manifest is not None and (metadata.get("libxposed") != "true" or metadata.get("xposedmodule") is not None):
                raise ValueError("Modern APK must declare only the modern framework entry")
            prop = archive.read("META-INF/xposed/module.prop").decode()
            entries = archive.read("META-INF/xposed/java_init.list").decode().strip()
            scope = archive.read("META-INF/xposed/scope.list").decode().strip()
            values = dict(re.findall(r"^([^#=\s]+)\s*=\s*(.*?)\s*$", prop, re.M))
    for field in ("minApiVersion", "targetApiVersion"):
        if not values.get(field, "").isdigit() or int(values[field]) <= 0:
            raise ValueError(f"Missing or invalid Xposed {field}")
    if int(values["targetApiVersion"]) < int(values["minApiVersion"]):
        raise ValueError("Xposed target API is below the minimum API")
    if not entries or not all(re.fullmatch(r"[\w$]+(?:\.[\w$]+)+", x) for x in entries.splitlines()):
        raise ValueError("Missing or invalid Xposed Java entry point")
    if scope != "com.android.systemui":
        raise ValueError("Unexpected module scope")
    return {"api": flavor, "properties": values, "javaEntries": entries.splitlines(), "scope": scope}


def verify_apk(apk, variant, metadata, sdk, flavor="modern"):
    tools = sdk / "build-tools/37.0.0"
    badging = subprocess.check_output([str(tools / "aapt2"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: (.+)$", badging, re.M)
    if not package:
        raise ValueError("APK has no package metadata")
    attrs = dict(re.findall(r"(\w+)='([^']*)'", package[1]))
    expected = {"name": metadata["applicationId"], "versionName": metadata["versionName"],
                "versionCode": str(metadata["versionCode"])}
    if any(attrs.get(key) != value for key, value in expected.items()):
        raise ValueError("APK identity/version differs from Gradle output metadata")
    signature = subprocess.check_output(
        [str(tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True)
    certs = set(re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", signature))
    if len(certs) != 1:
        raise ValueError("Expected one verified APK signing certificate")
    cert = next(iter(certs)).lower()
    if variant == "release":
        if "application-debuggable" in badging:
            raise ValueError("Release APK must not be debuggable")
        if cert == DEVELOPMENT_CERT:
            raise ValueError("Release APK uses the publicly tracked development signing key")
    analyzer = sdk / "cmdline-tools/latest/bin/apkanalyzer"
    manifest = subprocess.check_output([str(analyzer), "manifest", "print", str(apk)], text=True)
    xposed = module_metadata(apk, flavor, manifest)
    if flavor == "legacy":
        resources = subprocess.check_output([str(tools / "aapt2"), "dump", "resources", str(apk)], text=True)
        scope = re.search(r"resource (0x[0-9a-fA-F]+) array/xposed_scope\n(.*?)(?=\n\s*resource |\Z)", resources, re.S)
        ns = "{http://schemas.android.com/apk/res/android}"
        declaration = next(item.get(ns + "resource") for item in ET.fromstring(manifest).findall("application/meta-data")
                           if item.get(ns + "name") == "xposedscope")
        if (not scope or not re.search(r"\(array\) size=1\b", scope[2])
                or not re.search(r'^\s*\["com.android.systemui"\]\s*$', scope[2], re.M)
                or declaration not in ("@array/xposed_scope", "@ref/" + scope[1])):
            raise ValueError("Unexpected legacy module scope")
    return cert, xposed


def verify_build_set(builds):
    """A dual release cannot mix commits, versions, package IDs or signing keys."""
    if not builds or len({build["flavor"] for build in builds}) != len(builds):
        raise ValueError("Missing or duplicate API variants")
    for key in ("applicationId", "versionName", "versionCode", "commit", "signerSha256", "variant"):
        if len({build[key] for build in builds}) != 1:
            raise ValueError("API variants disagree on " + key)


def apk_filename(version, flavor, suffix=""):
    # Old modern updaters accept any ColorDuo-*.apk, irrespective of asset order.
    # Keep legacy outside that pattern; GitHub does not preserve upload order.
    if flavor == "legacy":
        return f"ColorDuoLegacy-{version}{suffix}.apk"
    if flavor == "modern":
        return f"ColorDuo-{version}-modern{suffix}.apk"
    raise ValueError("Unknown Xposed API flavor")


def package_apk(args):
    out = args.output
    if out.exists() and any(out.iterdir()):
        raise ValueError("Artifact output directory must be empty")
    out.mkdir(parents=True, exist_ok=True)
    flavors = ("modern", "legacy") if args.flavor == "all" else (args.flavor,)
    commit = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    owner = os.environ.get("GITHUB_REPOSITORY", "chorusfruit-233/ColorDuo").split("/")[0]
    prefix = github_prefix(owner)
    builds = []
    for flavor in flavors:
        directory = ROOT / "app/build/outputs/apk" / flavor / args.variant
        output = json.loads((directory / "output-metadata.json").read_text())
        if len(output["elements"]) != 1:
            raise ValueError("Expected one universal APK per API flavor")
        item = output["elements"][0]
        version, code = item["versionName"], item["versionCode"]
        validate_version(version, code)
        if args.version is not None and (args.version != version or args.version_code != str(code)):
            raise ValueError("Built APK does not match the requested release version")
        apk = directory / item["outputFile"]
        metadata = {"applicationId": output["applicationId"], "versionName": version, "versionCode": code}
        cert, xposed = verify_apk(apk, args.variant, metadata, args.sdk, flavor)
        namespace_matches = metadata["applicationId"].lower().startswith(prefix)
        if args.variant == "release" and not namespace_matches:
            raise ValueError(f"Release application ID must start with {prefix}")
        suffix = f"-debug-{commit[:7]}" if args.variant == "debug" else ""
        target = out / apk_filename(version, flavor, suffix)
        shutil.copy2(apk, target)
        digest = hashlib.sha256(target.read_bytes()).hexdigest()
        builds.append({**metadata, "variant": args.variant, "flavor": flavor, "commit": commit,
                       "apk": target.name, "apkSha256": digest, "signerSha256": cert, "xposed": xposed,
                       "submission": {"expectedPrefix": prefix, "namespaceMatches": namespace_matches}})
    verify_build_set(builds)
    (out / "SHA256SUMS").write_text("".join(f"{build['apkSha256']}  {build['apk']}\n" for build in builds))
    info = {"builds": builds}
    (out / "build-info.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n")
    version = builds[0]["versionName"]
    if args.variant == "release":
        (out / "CHANGELOG.md").write_text(release_notes(version))
        shutil.copy2(ROOT / "module-README.md", out / "README.md")
        shutil.copy2(ROOT / "module-SUMMARY", out / "SUMMARY")
    artifact_name = f"ColorDuo-{version}-{args.flavor}-{args.variant}-{commit[:7]}"
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as stream:
            stream.write(f"artifact_name={artifact_name}\n")
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as stream:
            for build in builds:
                stream.write(f"APK: `{build['apk']}` — {build['versionName']} / {build['versionCode']}\n\n")
    print(json.dumps(info, ensure_ascii=False, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    plan = commands.add_parser("plan", help="Validate release inputs before using signing credentials")
    plan.add_argument("--version", required=True)
    plan.add_argument("--version-code", required=True)
    package = commands.add_parser("package", help="Verify and collect an existing Gradle APK")
    package.add_argument("--variant", choices=("debug", "release"), required=True)
    package.add_argument("--flavor", choices=("modern", "legacy", "all"), default="all")
    package.add_argument("--version")
    package.add_argument("--version-code")
    package.add_argument("--output", type=Path, default=ROOT / "dist")
    package.add_argument("--sdk", type=Path, default=os.environ.get("ANDROID_HOME"))
    args = parser.parse_args()
    if args.command == "plan":
        validate_version(args.version, args.version_code)
        release_notes(args.version)
        print(f"Release inputs valid: {args.version} / {args.version_code}")
    else:
        if args.sdk is None:
            parser.error("Set ANDROID_HOME or pass --sdk")
        if args.variant == "release" and (args.version is None or args.version_code is None):
            parser.error("Release packaging requires --version and --version-code")
        package_apk(args)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        sys.exit(str(error))
    except subprocess.CalledProcessError as error:
        sys.exit(f"{error.cmd[0]} failed with exit code {error.returncode}")
