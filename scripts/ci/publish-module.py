#!/usr/bin/env python3
"""Submit ColorDuo and mirror a verified, already public source release."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

from artifacts import ROOT, apk_filename, github_prefix, validate_version, verify_apk

OWNER = "chorusfruit-233"
SOURCE = OWNER + "/ColorDuo"
PACKAGE = "io.github.chorusfruit_233.colorduo"
MODULE = "Xposed-Modules-Repo/" + PACKAGE
SUBMISSION = "Xposed-Modules-Repo/submission"
TITLE = "[submission] " + PACKAGE
SUPPORT = "https://github.com/" + SOURCE + "/issues"


class ApiError(RuntimeError):
    def __init__(self, status, message):
        self.status = status
        super().__init__(f"GitHub HTTP {status}: {message}")


def api(path, method="GET", data=None):
    body = None if data is None else json.dumps(data).encode()
    request = urllib.request.Request("https://api.github.com/" + path, data=body, method=method,
        headers={"Authorization": "Bearer " + os.environ["GH_TOKEN"],
                 "Accept": "application/vnd.github+json", "Content-Type": "application/json",
                 "User-Agent": "ColorDuo-publication", "X-GitHub-Api-Version": "2022-11-28"})
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            content = response.read()
            return json.loads(content) if content else None
    except urllib.error.HTTPError as error:
        try:
            message = json.loads(error.read()).get("message", error.reason)
        except (ValueError, OSError):
            message = error.reason
        raise ApiError(error.code, message) from None


def optional(path):
    try:
        return api(path)
    except ApiError as error:
        if error.status == 404:
            return None
        raise


def matching_invitation(invitations):
    return next((item for item in invitations
                 if item.get("repository", {}).get("full_name", "").lower() == MODULE.lower()), None)


def ensure_repository(release_url):
    repo = optional("repos/" + MODULE)
    issue_url = None
    if repo is None:
        query = urllib.parse.urlencode({"q": f'repo:{SUBMISSION} is:issue author:{OWNER} "{PACKAGE}"'})
        issues = api("search/issues?" + query)["items"]
        issue = next((item for item in issues if item["title"].lower() == TITLE.lower()), None)
        if issue is None:
            issue = api("repos/" + SUBMISSION + "/issues", "POST", {
                "title": TITLE,
                "body": f"Module: ColorDuo\nApplication ID: `{PACKAGE}`\n\n"
                        f"Source: https://github.com/{SOURCE}\nSigned release: {release_url}\n\n"
                        "ColorOS 17 module with modern API 102 and legacy API 82 builds. "
                        "GPL-3.0-only fork of HyperDuo, maintained entirely by Codex. "
                        "Module README, SUMMARY and a verified signed APK are ready."})
        issue_url = issue["html_url"]
        print("Submission: " + issue_url, flush=True)
    deadline = time.monotonic() + 600
    while True:
        repo = optional("repos/" + MODULE)
        if repo and repo.get("permissions", {}).get("push"):
            return repo, issue_url
        invitation = matching_invitation(api("user/repository_invitations?per_page=100"))
        if invitation:
            api(f"user/repository_invitations/{invitation['id']}", "PATCH")
            continue
        if time.monotonic() >= deadline:
            raise RuntimeError("Module repository/invitation is not ready; see " +
                               (issue_url or "https://github.com/" + MODULE + "/invitations"))
        time.sleep(10)


def update_file(name, content):
    path = "repos/" + MODULE + "/contents/" + name
    previous = optional(path)
    if previous and base64.b64decode(previous["content"]) == content:
        return
    payload = {"message": "docs: update ColorDuo " + name,
               "content": base64.b64encode(content).decode()}
    if previous:
        payload["sha"] = previous["sha"]
    api(path, "PUT", payload)


def update_settings(repo):
    if repo.get("description") == "ColorDuo" and repo.get("homepage") == SUPPORT:
        return True
    try:
        api("repos/" + MODULE, "PATCH", {"description": "ColorDuo", "homepage": SUPPORT})
        return True
    except ApiError as error:
        if error.status not in (403, 404):
            raise
    # Maintain-role permissions can differ between REST and the browser's GraphQL API.
    query = """mutation($id: ID!, $description: String!, $homepage: URI!) {
      updateRepository(input: {repositoryId: $id, description: $description, homepageUrl: $homepage}) {
        repository { description homepageUrl }
      }
    }"""
    try:
        result = api("graphql", "POST", {"query": query, "variables": {
            "id": repo["node_id"], "description": "ColorDuo", "homepage": SUPPORT}})
        return not result.get("errors")
    except ApiError as error:
        if error.status not in (403, 404):
            raise
        return False


def gh(*args):
    subprocess.run(["gh", *args], check=True)


def channel_flags(source):
    prerelease = bool(source["prerelease"])
    latest = bool(source.get("is_latest")) and not prerelease
    return [f"--prerelease={str(prerelease).lower()}", f"--latest={str(latest).lower()}"]


def publish_release(args, source, digest, repo):
    apks = getattr(args, "apks", None) or [args.apk]
    digests = digest if isinstance(digest, dict) else {args.apk.name: digest}
    tag = f"{args.version_code}-{args.version}"
    existing = optional("repos/" + MODULE + "/releases/tags/" + tag)
    if existing and not existing["draft"]:
        with tempfile.TemporaryDirectory() as directory:
            for apk in apks:
                gh("release", "download", tag, "--repo", MODULE, "--pattern", apk.name, "--dir", directory)
                if hashlib.sha256((Path(directory) / apk.name).read_bytes()).hexdigest() != digests[apk.name]:
                    raise ValueError("Published module APK differs; create a new version instead of replacing it")
        gh("release", "edit", tag, "--repo", MODULE, *channel_flags(source))
        return existing["html_url"]
    if existing is None:
        drafts = api("repos/" + MODULE + "/releases?per_page=100")
        existing = next((item for item in drafts if item["tag_name"] == tag), None)
        if existing and not existing["draft"]:
            raise RuntimeError("Release changed during publication; retry")
    notes = apks[0].parent / "module-release-notes.md"
    notes.write_text(source["body"], encoding="utf-8")
    if existing is None:
        command = ["release", "create", tag, "--repo", MODULE, "--target", repo["default_branch"],
                   "--title", args.version, "--notes-file", str(notes), "--draft"]
        command.append(f"--prerelease={str(bool(source['prerelease'])).lower()}")
        gh(*command)
    # Upload every APK before making the release visible to module managers.
    gh("release", "upload", tag, str(apks[0]), str(apks[0].parent / "SHA256SUMS"),
       "--repo", MODULE, "--clobber")
    for apk in apks[1:]:
        gh("release", "upload", tag, str(apk), "--repo", MODULE, "--clobber")
    gh("release", "edit", tag, "--repo", MODULE, "--draft=false", *channel_flags(source))
    return "https://github.com/" + MODULE + "/releases/tag/" + tag


def validate_source_assets(apks, checksum_text, source):
    checksums = {}
    for line in checksum_text.splitlines():
        fields = line.split()
        if len(fields) != 2 or fields[1] in checksums:
            raise ValueError("Invalid or duplicate source checksum")
        checksums[fields[1]] = fields[0]
    assets = {asset["name"]: asset for asset in source.get("assets", [])}
    digests = {}
    for apk in apks:
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
        if checksums.get(apk.name) != digest:
            raise ValueError("Source APK checksum differs from SHA256SUMS: " + apk.name)
        asset = assets.get(apk.name)
        if asset is None or asset.get("digest") != "sha256:" + digest:
            raise ValueError("APK differs from the published source asset: " + apk.name)
        digests[apk.name] = digest
    return digests


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version", required=True)
    parser.add_argument("--version-code", required=True)
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--apk", type=Path, help="Historical single modern release")
    inputs.add_argument("--directory", type=Path, help="Published modern and legacy APKs plus SHA256SUMS")
    args = parser.parse_args()
    validate_version(args.version, args.version_code)
    if api("user")["login"].lower() != OWNER:
        raise ValueError("MODULE_REPO_TOKEN must belong to " + OWNER)
    if not PACKAGE.startswith(github_prefix(OWNER)):
        raise ValueError("Module package does not match the submission owner")
    source = api(f"repos/{SOURCE}/releases/tags/v{args.version}")
    if source["draft"] or not source.get("body", "").strip():
        raise ValueError("Publish the source release with changelog first")
    latest = optional(f"repos/{SOURCE}/releases/latest")
    source["is_latest"] = latest is not None and latest["id"] == source["id"]
    metadata = {"applicationId": PACKAGE, "versionName": args.version, "versionCode": int(args.version_code)}
    args.apks = ([args.directory / apk_filename(args.version, flavor) for flavor in ("modern", "legacy")]
                 if args.directory is not None else [args.apk])
    certs = set()
    for apk in args.apks:
        flavor = "legacy" if apk.name.startswith("ColorDuoLegacy-") else "modern"
        cert, _ = verify_apk(apk, "release", metadata, Path(os.environ["ANDROID_HOME"]), flavor)
        certs.add(cert)
    if len(certs) != 1:
        raise ValueError("Modern and legacy APKs must use the same signing key")
    digest = validate_source_assets(args.apks, (args.apks[0].parent / "SHA256SUMS").read_text(), source)
    repo, issue_url = ensure_repository(source["html_url"])
    settings_ok = update_settings(repo)
    update_file("README.md", (ROOT / "module-README.md").read_bytes())
    update_file("SUMMARY", (ROOT / "module-SUMMARY").read_bytes())
    update_file("SOURCE_URL", ("https://github.com/" + SOURCE + "\n").encode())
    release_url = publish_release(args, source, digest, repo)
    result = {"submission": issue_url, "repository": "https://github.com/" + MODULE,
              "release": release_url, "sha256": digest, "repositorySettingsComplete": settings_ok}
    print(json.dumps(result, indent=2), flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"Module release: {release_url}\n\nSHA-256: `{digest}`\n\n")
            stream.write(f"Repository description/homepage configured: **{settings_ok}**\n")
    if not settings_ok:
        raise RuntimeError("APK and metadata published. GitHub requires browser access to set "
                           "Description=ColorDuo and Homepage=" + SUPPORT + " at https://github.com/" + MODULE)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        sys.exit(str(error))
