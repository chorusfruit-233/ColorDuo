#!/usr/bin/env python3
"""Exercise external publication decisions without credentials or network writes."""
import base64
import hashlib
from pathlib import Path
import runpy
import sys
import unittest
import tempfile
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts/ci"))
module = runpy.run_path(str(Path(__file__).resolve().parents[1] / "scripts/ci/publish-module.py"))
state = module["api"].__globals__
MODULE = module["MODULE"]


class ModulePublicationTest(unittest.TestCase):
    def test_accept_only_exact_repository_invitation(self):
        invitations = [{"id": 1, "repository": {"full_name": MODULE + ".other"}},
                       {"id": 2, "repository": {"full_name": MODULE}},
                       {"id": 3, "repository": {"full_name": "someone/else"}}]
        self.assertEqual(module["matching_invitation"](invitations)["id"], 2)
        self.assertIsNone(module["matching_invitation"](invitations[:1]))

    def test_404_is_optional_but_permission_failure_is_not(self):
        for code in (403, 500):
            with mock.patch.dict(state, {"api": mock.Mock(side_effect=module["ApiError"](code, "failed"))}):
                with self.assertRaises(module["ApiError"]):
                    module["optional"]("repos/" + MODULE)
        with mock.patch.dict(state, {"api": mock.Mock(side_effect=module["ApiError"](404, "absent"))}):
            self.assertIsNone(module["optional"]("repos/" + MODULE))

    def test_existing_repository_needs_no_new_submission(self):
        repo = {"permissions": {"push": True}}
        api = mock.Mock()
        with mock.patch.dict(state, {"optional": mock.Mock(return_value=repo), "api": api}):
            self.assertEqual(module["ensure_repository"]("source"), (repo, None))
        api.assert_not_called()

    def test_reuse_existing_submission(self):
        repo = {"permissions": {"push": True}}
        issue = {"title": module["TITLE"], "html_url": "https://github.com/submission/1"}
        api = mock.Mock(return_value={"items": [issue]})
        with mock.patch.dict(state, {"optional": mock.Mock(side_effect=[None, repo]), "api": api}):
            self.assertEqual(module["ensure_repository"]("source"), (repo, issue["html_url"]))
        self.assertEqual(api.call_count, 1)
        self.assertTrue(api.call_args.args[0].startswith("search/issues?"))

    def test_create_one_submission_and_accept_matching_invite(self):
        repo = {"permissions": {"push": True}}
        invitation = {"id": 42, "repository": {"full_name": MODULE}}
        calls = []

        def api(path, method="GET", data=None):
            calls.append((path, method, data))
            if path.startswith("search/issues?"):
                return {"items": []}
            if path.endswith("/issues"):
                return {"html_url": "https://github.com/submission/42"}
            if path.startswith("user/repository_invitations?"):
                return [invitation]
            if path == "user/repository_invitations/42" and method == "PATCH":
                return None
            self.fail("Unexpected endpoint: " + path)

        with mock.patch.dict(state, {"optional": mock.Mock(side_effect=[None, None, repo]), "api": api}):
            self.assertEqual(module["ensure_repository"]("https://github.com/source/release")[0], repo)
        submissions = [item for item in calls if item[1] == "POST"]
        self.assertEqual(len(submissions), 1)
        self.assertEqual(submissions[0][2]["title"], module["TITLE"])
        self.assertIn(("user/repository_invitations/42", "PATCH", None), calls)

    def test_readme_updates_are_idempotent_and_use_current_sha(self):
        content = b"# ColorDuo\n"
        previous = {"sha": "current-sha", "content": base64.b64encode(content).decode()}
        api = mock.Mock()
        with mock.patch.dict(state, {"optional": mock.Mock(return_value=previous), "api": api}):
            module["update_file"]("README.md", content)
            api.assert_not_called()
            module["update_file"]("README.md", b"# Updated\n")
        self.assertEqual(api.call_args.args[1], "PUT")
        self.assertEqual(api.call_args.args[2]["sha"], "current-sha")

    def test_repository_settings_can_require_browser(self):
        responses = [module["ApiError"](403, "maintain role"), {"errors": [{"message": "denied"}]}]
        with mock.patch.dict(state, {"api": mock.Mock(side_effect=responses)}):
            self.assertFalse(module["update_settings"]({"node_id": "repo-id"}))

    def test_unchanged_repository_settings_need_no_write(self):
        api = mock.Mock()
        with mock.patch.dict(state, {"api": api}):
            self.assertTrue(module["update_settings"]({"description": "ColorDuo", "homepage": module["SUPPORT"]}))
        api.assert_not_called()

    def test_published_apk_cannot_be_replaced(self):
        args = type("Args", (), {"version_code": "10007", "version": "0.1.0",
                                 "apk": Path("ColorDuo-0.1.0.apk")})()
        calls = []

        def gh(*command):
            calls.append(command)
            self.assertIn(command[:2], (("release", "download"), ("release", "edit")))
            if command[:2] == ("release", "download"):
                directory = Path(command[command.index("--dir") + 1])
                (directory / args.apk.name).write_bytes(b"already published APK")

        existing = {"draft": False, "html_url": "https://github.com/module/release"}
        source = {"prerelease": False, "is_latest": True}
        with mock.patch.dict(state, {"optional": mock.Mock(return_value=existing), "gh": gh}):
            with self.assertRaisesRegex(ValueError, "instead of replacing"):
                module["publish_release"](args, source, "different-sha", {})
            self.assertEqual(len(calls), 1)
            digest = hashlib.sha256(b"already published APK").hexdigest()
            self.assertEqual(module["publish_release"](args, source, digest, {}), existing["html_url"])
        self.assertEqual(len(calls), 3)
        self.assertEqual(calls[-1][-2:], ("--prerelease=false", "--latest=true"))

    def test_stable_release_flags_are_explicit(self):
        flags = module["channel_flags"]({"prerelease": False, "is_latest": True})
        self.assertEqual(flags, ["--prerelease=false", "--latest=true"])

    def test_both_source_apks_are_verified_before_publication(self):
        with tempfile.TemporaryDirectory() as directory:
            files = [Path(directory) / f"ColorDuo-0.4.0-{flavor}.apk" for flavor in ("modern", "legacy")]
            for apk in files:
                apk.write_bytes(apk.name.encode())
            hashes = {apk.name: hashlib.sha256(apk.read_bytes()).hexdigest() for apk in files}
            checksums = "".join(f"{digest}  {name}\n" for name, digest in hashes.items())
            source = {"assets": [{"name": name, "digest": "sha256:" + digest} for name, digest in hashes.items()]}
            self.assertEqual(module["validate_source_assets"](files, checksums, source), hashes)
            with self.assertRaisesRegex(ValueError, "duplicate"):
                module["validate_source_assets"](files, checksums + checksums, source)
            source["assets"][1]["digest"] = "sha256:wrong"
            with self.assertRaisesRegex(ValueError, "published source asset"):
                module["validate_source_assets"](files, checksums, source)

    def test_uploads_modern_before_legacy_for_older_updaters(self):
        with tempfile.TemporaryDirectory() as directory:
            apks = [Path(directory) / f"ColorDuo-0.4.0-{flavor}.apk" for flavor in ("modern", "legacy")]
            args = type("Args", (), {"version_code": "10052", "version": "0.4.0", "apks": apks})()
            gh = mock.Mock()
            source = {"body": "Dual API release", "prerelease": False, "is_latest": True}
            with mock.patch.dict(state, {"optional": mock.Mock(return_value=None), "api": mock.Mock(return_value=[]), "gh": gh}):
                module["publish_release"](args, source, {apk.name: "digest" for apk in apks}, {"default_branch": "main"})
            uploads = [call.args for call in gh.call_args_list if call.args[:2] == ("release", "upload")]
            self.assertEqual(len(uploads), 2)
            self.assertIn(str(apks[0]), uploads[0])
            self.assertNotIn(str(apks[1]), uploads[0])
            self.assertIn(str(apks[1]), uploads[1])
            self.assertEqual(gh.call_args.args[:2], ("release", "edit"))

    def test_historical_release_does_not_replace_latest(self):
        flags = module["channel_flags"]({"prerelease": False, "is_latest": False})
        self.assertEqual(flags, ["--prerelease=false", "--latest=false"])

    def test_prerelease_cannot_be_latest(self):
        flags = module["channel_flags"]({"prerelease": True, "is_latest": True})
        self.assertEqual(flags, ["--prerelease=true", "--latest=false"])


if __name__ == "__main__":
    unittest.main()
