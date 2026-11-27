#!/usr/bin/env python3
"""Report a verified staged desktop check for protected-main publication."""

import argparse
import json
import os
import re
import subprocess
import sys


class ReportError(Exception):
    """The dispatched check cannot safely be reported as successful."""


def require(condition, message):
    if not condition:
        raise ReportError(message)


def gh_api(path, *, payload=None):
    command = ["gh", "api"]
    if payload is not None:
        command += ["--method", "POST", path, "--input", "-"]
    else:
        command.append(path)
    try:
        result = subprocess.run(
            command, input=json.dumps(payload) if payload is not None else None,
            capture_output=True, text=True, check=True,
        )
        response = json.loads(result.stdout)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as error:
        raise ReportError(f"GitHub API request failed for {path}: {error}") from error
    require(isinstance(response, dict), f"GitHub API returned a non-object for {path}")
    return response


def numeric(value):
    return type(value) is int and value > 0


def report(run_id, sha, branch, repository):
    require(numeric(run_id), "run ID must be a positive integer")
    require(re.fullmatch(r"[0-9a-fA-F]{40}", sha) is not None, "SHA must be 40 hexadecimal characters")
    require(re.fullmatch(r"release-staging-[0-9]+-[0-9]+", branch) is not None,
            "branch must be a release staging branch")
    require(re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository or "") is not None,
            "GITHUB_REPOSITORY must identify an owner and repository")

    base = f"/repos/{repository}"
    run = gh_api(f"{base}/actions/runs/{run_id}")
    for field, expected in (
        ("id", run_id), ("head_sha", sha), ("head_branch", branch),
        ("event", "workflow_dispatch"), ("path", ".github/workflows/check.yml"),
        ("status", "completed"), ("conclusion", "success"),
    ):
        require(run.get(field) == expected, f"run {run_id} has unexpected {field}: {run.get(field)!r}")
    require(numeric(run["id"]), "run ID must be an integer")

    page = gh_api(f"{base}/actions/runs/{run_id}/jobs?per_page=100")
    jobs = page.get("jobs")
    count = page.get("total_count")
    require(type(count) is int and 0 <= count <= 100 and isinstance(jobs, list)
            and len(jobs) == count, "job response is incomplete or invalid")
    require(all(isinstance(job, dict) for job in jobs), "job response contains an invalid job")
    desktop = [job for job in jobs if job.get("name") == "desktop"]
    require(len(desktop) == 1, f"expected exactly one desktop job, found {len(desktop)}")
    job = desktop[0]
    require(numeric(job.get("id")), "desktop job ID is invalid")
    require(numeric(job.get("run_id")) and job["run_id"] == run_id,
            "desktop job belongs to another run")
    require(job.get("status") == "completed" and job.get("conclusion") == "success",
            "desktop job did not complete successfully")
    url = f"https://github.com/{repository}/actions/runs/{run_id}/job/{job['id']}"
    require(job.get("html_url") == url, "desktop job URL does not match its verified identity")

    gh_api(f"{base}/statuses/{sha}", payload={
        "state": "success",
        "context": "desktop",
        "target_url": url,
        "description": "Verified desktop job passed in staged PR Check run",
    })


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-id", required=True, type=int)
    parser.add_argument("--sha", required=True)
    parser.add_argument("--branch", required=True)
    args = parser.parse_args(argv)
    try:
        report(args.run_id, args.sha, args.branch, os.environ.get("GITHUB_REPOSITORY"))
    except ReportError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
