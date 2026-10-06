"""Source contracts for a release commit validated before protected-main publication."""

from pathlib import Path
import re
import unittest


WORKFLOWS = Path(__file__).resolve().parents[1] / ".github/workflows"


def job(source, name):
    match = re.search(rf"^  {re.escape(name)}:\n(.*?)(?=^  [\w-]+:\n|\Z)", source, re.M | re.S)
    if match is None:
        raise AssertionError(f"missing {name} job")
    return match.group(1)


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.release = (WORKFLOWS / "release.yml").read_text()
        self.check = (WORKFLOWS / "check.yml").read_text()

    def test_required_desktop_check_can_be_dispatched_on_staging_branch(self):
        self.assertRegex(self.check, r"(?m)^  workflow_dispatch:\s*$")
        self.assertIn("  desktop:\n", self.check)

    # GH006 rejected the old publish job because its version commit had no required check.
    def test_version_commit_is_staged_after_all_builds_with_scoped_permissions(self):
        staged = job(self.release, "stage-version")
        self.assertIn("needs: [prepare, build]", staged)
        self.assertIn("if: ${{ !inputs.dry_run }}", staged)
        self.assertRegex(staged, r"permissions:\n\s+contents: write\n\s+actions: write")
        self.assertIn("ref: ${{ needs.prepare.outputs.sha }}", staged)
        self.assertIn('python3 scripts/release_version.py apply "$VERSION"', staged)
        self.assertIn("git push origin", staged)
        self.assertIn("$GITHUB_RUN_ID", staged)
        self.assertNotIn("[skip ci]", staged)

    def test_dispatched_check_is_correlated_to_exact_commit_and_bounded(self):
        staged = job(self.release, "stage-version")
        self.assertIn("gh workflow run check.yml --ref", staged)
        self.assertIn("gh run list", staged)
        self.assertIn("--workflow check.yml", staged)
        self.assertIn("--event workflow_dispatch", staged)
        self.assertIn("--branch", staged)
        self.assertIn("headSha", staged)
        self.assertRegex(staged, r"\.headSha\s*==")
        self.assertRegex(staged, r"for attempt in \{1\.\.[0-9]+\}")
        self.assertIn("if [[ -z \"$run_id\" ]]", staged)
        self.assertIn("exit 1", staged)
        self.assertIn("gh run watch \"$run_id\" --exit-status", staged)
        self.assertIn("commit: ${{ steps.stage.outputs.commit }}", staged)
        self.assertIn("branch: ${{ steps.stage.outputs.branch }}", staged)

    def test_failed_validation_prevents_publish_and_main_movement_is_refused(self):
        published = job(self.release, "publish")
        self.assertIn("needs: [prepare, build, stage-version]", published)
        self.assertIn("if: ${{ !inputs.dry_run }}", published)
        self.assertIn('"$(git rev-parse HEAD)" != "${{ needs.prepare.outputs.sha }}"', published)
        self.assertIn("exit 1", published)

    def test_publish_pushes_only_the_checked_commit_then_tags_it(self):
        published = job(self.release, "publish")
        self.assertIn("needs.stage-version.outputs.commit", published)
        self.assertIn("needs.stage-version.outputs.branch", published)
        self.assertIn("git fetch origin", published)
        self.assertIn('git rev-parse "refs/remotes/origin/$BRANCH")" != "$COMMIT"', published)
        self.assertIn('git rev-parse "$COMMIT^', published)
        self.assertIn("git merge --ff-only", published)
        self.assertIn("git push origin HEAD:main", published)
        self.assertIn('git tag "$TAG"', published)
        self.assertIn('git push origin "$TAG"', published)
        self.assertIn("git push origin --delete", published)
        self.assertLess(published.index("git push origin HEAD:main"), published.index('git tag "$TAG"'))
        self.assertNotIn("release_version.py apply", published)
        self.assertNotIn("git commit", published)

    def test_dry_run_has_no_staging_or_dispatch_dependency(self):
        summary = job(self.release, "dry-run-summary")
        staged = job(self.release, "stage-version")
        self.assertRegex(self.release, r"dry_run:\n(?:.*\n){0,3}\s+default: true")
        self.assertIn("needs: [prepare, build]", summary)
        self.assertIn("if: ${{ inputs.dry_run }}", summary)
        self.assertNotIn("stage-version", summary)
        self.assertIn("if: ${{ !inputs.dry_run }}", staged)
        self.assertIn("if: ${{ !inputs.dry_run }}", job(self.release, "publish"))


if __name__ == "__main__":
    unittest.main()
