"""Regression tests for reporting a real dispatched desktop result."""

import json
import os
import subprocess
import unittest
from unittest.mock import patch

import report_release_status


REPO = "najchris11/snapchat-memories-downloader"
RUN_ID = 37505290335
SHA = "b8240ae9c0879657366a8da65acc1fa8178da7d3"
BRANCH = "release-staging-37504128896-1"
JOB_ID = 112412373681
JOB_URL = f"https://github.com/{REPO}/actions/runs/{RUN_ID}/job/{JOB_ID}"


def valid_run():
    return dict(id=RUN_ID, head_sha=SHA, head_branch=BRANCH,
                event="workflow_dispatch", path=".github/workflows/check.yml",
                status="completed", conclusion="success")


def valid_jobs():
    return dict(total_count=4, jobs=[
        dict(id=JOB_ID, run_id=RUN_ID, name="desktop", status="completed",
             conclusion="success", html_url=JOB_URL),
        *[dict(id=n, run_id=RUN_ID, name=name, status="completed",
               conclusion="success") for n, name in enumerate(("changes", "android", "ios"), 1)],
    ])


class ReportReleaseStatusTest(unittest.TestCase):
    def run_reporter(self, run=None, jobs=None, sha=SHA, branch=BRANCH, run_id=RUN_ID,
                     fail_call=None, malformed_call=None):
        calls = []

        def fake_gh(command, **kwargs):
            calls.append((command, kwargs))
            number = len(calls)
            if number == fail_call:
                raise subprocess.CalledProcessError(1, command, stderr="API unavailable")
            if number == malformed_call:
                return subprocess.CompletedProcess(command, 0, stdout="{bad json")
            if "--method" in command:
                return subprocess.CompletedProcess(command, 0, stdout="{}")
            payload = (run if run is not None else valid_run()) if number == 1 else (
                jobs if jobs is not None else valid_jobs())
            return subprocess.CompletedProcess(command, 0, stdout=json.dumps(payload))

        with patch.dict(os.environ, {"GITHUB_REPOSITORY": REPO}), patch.object(
            report_release_status.subprocess, "run", side_effect=fake_gh
        ):
            result = report_release_status.main([
                "--run-id", str(run_id), "--sha", sha, "--branch", branch,
            ])
        return result, calls

    # GH006 failed because workflow_dispatch's successful desktop job produced no
    # required commit status. The status must refer to that exact checked job and SHA.
    def test_success_posts_verified_desktop_job_to_exact_sha(self):
        result, calls = self.run_reporter()
        self.assertEqual(result, 0)
        self.assertEqual(len(calls), 3)
        command, kwargs = calls[-1]
        self.assertEqual(command, ["gh", "api", "--method", "POST",
                                   f"/repos/{REPO}/statuses/{SHA}", "--input", "-"])
        self.assertEqual(json.loads(kwargs["input"]), dict(
            state="success", context="desktop", target_url=JOB_URL,
            description="Verified desktop job passed in staged PR Check run",
        ))

    def test_mismatched_run_metadata_never_posts(self):
        changes = [
            ("run id", {"id": RUN_ID + 1}),
            ("SHA", {"head_sha": "a" * 40}),
            ("branch", {"head_branch": "release-staging-2-1"}),
            ("event", {"event": "push"}),
            ("workflow", {"path": ".github/workflows/other.yml"}),
            ("incomplete", {"status": "in_progress"}),
            ("failed", {"conclusion": "failure"}),
            ("cancelled", {"conclusion": "cancelled"}),
        ]
        for label, change in changes:
            with self.subTest(label=label):
                result, calls = self.run_reporter(run={**valid_run(), **change})
                self.assertNotEqual(result, 0)
                self.assertFalse(any("POST" in command for command, _ in calls))

    def test_invalid_inputs_never_call_api(self):
        for sha, branch in [("short", BRANCH), (SHA, "main"), (SHA, "release-staging-a-1")]:
            with self.subTest(sha=sha, branch=branch):
                result, calls = self.run_reporter(sha=sha, branch=branch)
                self.assertNotEqual(result, 0)
                self.assertEqual(calls, [])

    def test_missing_duplicate_or_unverified_desktop_never_posts(self):
        base = valid_jobs()
        cases = [
            ("missing", [j for j in base["jobs"] if j["name"] != "desktop"]),
            ("duplicate", base["jobs"] + [base["jobs"][0].copy()]),
            ("incomplete", [{**base["jobs"][0], "status": "queued"}, *base["jobs"][1:]]),
            ("failed", [{**base["jobs"][0], "conclusion": "failure"}, *base["jobs"][1:]]),
            ("wrong run", [{**base["jobs"][0], "run_id": RUN_ID + 1}, *base["jobs"][1:]]),
            ("wrong URL", [{**base["jobs"][0], "html_url": JOB_URL + "-other"}, *base["jobs"][1:]]),
        ]
        for label, entries in cases:
            with self.subTest(label=label):
                result, calls = self.run_reporter(jobs={"total_count": len(entries), "jobs": entries})
                self.assertNotEqual(result, 0)
                self.assertFalse(any("POST" in command for command, _ in calls))

    def test_incomplete_job_page_never_posts(self):
        jobs = valid_jobs()
        jobs["total_count"] += 1
        result, calls = self.run_reporter(jobs=jobs)
        self.assertNotEqual(result, 0)
        self.assertFalse(any("POST" in command for command, _ in calls))

    def test_api_and_parse_errors_never_post(self):
        for call in (1, 2):
            for failure in ("api", "json"):
                with self.subTest(call=call, failure=failure):
                    options = {"fail_call" if failure == "api" else "malformed_call": call}
                    result, calls = self.run_reporter(**options)
                    self.assertNotEqual(result, 0)
                    self.assertFalse(any("POST" in command for command, _ in calls))


if __name__ == "__main__":
    unittest.main()
