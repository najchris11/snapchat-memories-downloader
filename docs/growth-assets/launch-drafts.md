# Launch and directory drafts (checklist 4.4)

Owner-review drafts. Nothing here has been posted, submitted or registered anywhere.
Facts describe the **published v1.0.15** release ([tagged README](https://github.com/najchris11/snapchat-memories-downloader/blob/v1.0.15/README.md) and [release assets](https://github.com/najchris11/snapchat-memories-downloader/releases/tag/v1.0.15)). Checked 2026-11-08.

Do not advertise as released until a later release ships them: the "keep originals"
toggle, the unified source picker, the manual update check, split-video stitching, GPS
timezone lookup, the GPS map, the disk-space planner, and anything on Android or iOS.
Also do not link the new website guides (`/guides/*`, `/vs/*`) until deployed; link only
the home page.

## 1. Directory listing (AlternativeTo, SaaSHub, OpenAlternative)

Each site has its own form and moderation. Read its current submission terms before
pasting, and fill in its category and "alternative to" fields yourself. Describe SnapVault
as an alternative to whatever the site lets you select; do not claim it is already listed.

| Field | Text |
|---|---|
| Name | SnapVault |
| Tagline (under 80 chars) | Free open-source desktop app to restore a Snapchat Memories export |
| Short description (about 160 chars) | Turns Snapchat's "Download My Data" Memories export into a dated library, with caption overlays combined into the photos and videos. macOS, Windows, Linux. |
| Platforms | macOS 12+ (Apple Silicon and Intel), Windows 10+, Ubuntu 20.04+ (.deb) |
| License | GPL-3.0-or-later |
| Price | Free. Optional tips on Ko-fi |
| Website | https://snapvault.naj-dev.com |
| Source | https://github.com/najchris11/snapchat-memories-downloader |
| Downloads | https://github.com/najchris11/snapchat-memories-downloader/releases/tag/v1.0.15 |
| Tags | snapchat, memories, export, backup, exif, open source |

### Long description

SnapVault is an independent, open-source desktop app for people who have requested their
own Memories through Snapchat's "Download My Data". Choose the ZIP archives from the
export and an output folder. SnapVault extracts the media and can write each file's
capture date to its metadata with ExifTool. It can also combine the separate caption and
sticker overlay files with their photos and videos into a single viewable file, and
remove byte-identical duplicates (with an optional preview, and favorited files are kept).

Modern ZIP imports are processed on your computer. The older link-based export
(`memories_history.html` or `.json`) is also supported; in that mode SnapVault downloads
the media from the links in your export, so it needs internet access.

When your export provides it, ZIP mode can also recover time of day and GPS location, but
only when a file can be paired safely, and it falls back to date-only tags otherwise. GPS
is written into the files themselves, so anyone you share them with can read it. The app
can be set to date-only tagging.

SnapVault is free to use, with no account and no paywall on processing. It is not
affiliated with Snap Inc.; it works only on the export Snapchat gives you and does not log
in to your account. The macOS and Windows installers are not code-signed yet, so Gatekeeper
or SmartScreen will warn on first launch; the release notes explain the steps and each
download has a checksum in `SHA256SUMS.txt`. On Linux, install FFmpeg and Perl first.

### Alternative-to wording

"A free, open-source desktop app that does a similar job to paid or hosted Snapchat
Memories export tools: it restores a Snapchat export on your own computer." Use only
the competitor names the directory form offers. Say nothing negative about them, and do
not claim feature parity without checking their current pages yourself.

## 2. Short reusable blurb

For a forum or community whose rules you have read and which allows you to use drafted
text. Edit it into your own voice first.

> I made SnapVault, a free open-source desktop app for restoring a Snapchat Memories
> export (macOS, Windows, Linux). Point it at the ZIPs from Snapchat's "Download My Data"
> and it extracts the media, writes capture dates (and GPS where the export has it), and
> combines caption overlays with their photos and videos. ZIP imports run locally; the
> older link-based export downloads from the links in your file. The installers are not
> signed yet, so expect a Gatekeeper or SmartScreen warning. GPL-3.0-or-later:
> https://github.com/najchris11/snapchat-memories-downloader

Optional timeliness line: Snapchat says exceeding the free allowance does not trigger
automatic deletion. From January 2027, older Memories outside the oldest 5 GB can be
archived; Memories from the past year remain accessible. Archived items stay stored,
but using them inside Snapchat requires a storage upgrade. Downloading provides access
outside Snapchat. This is an archiving change, not a deletion deadline.
Source (independently read 2026-11-08):
https://help.snapchat.com/hc/en-us/articles/41291271694228-How-do-I-manage-my-Memories-storage
Recheck the page before posting; the policy may change.

Existing clip captions are in `docs/growth-assets/README.md`.

## 3. Venues where drafted copy is not provided

- **Hacker News (Show HN): do not use generated text.** Guidelines say "Don't post generated
  text or AI-edited text. HN is for conversation between humans." Its submission guidance
  also requires human-written posts. Show HN asks for something
  people can try without sign-up, and says not to ask friends to vote or comment.
  Owner step: write the title and first comment yourself. Sources:
  https://news.ycombinator.com/newsguidelines.html and
  https://news.ycombinator.com/showhn.html (both read 2026-11-08).
- **Reddit (`r/DataHoarder`, `r/SnapchatHelp`, `r/selfhosted`, `r/immich`, `r/opensource`,
  `r/macapps`): no venue-specific copy.** Reddit returned HTTP 403 to automated requests for
  each community's rules, so they could not be read. Owner step: read each subreddit's
  current rules (sidebar, or `https://www.reddit.com/r/<name>/about/rules`) for
  self-promotion and AI-content limits. Post only where allowed, once per community, in
  your own words, using section 2 as a fact sheet. Do not post near-identical threads.

## 4. Verified directory submission steps

Checked 2026-11-08 without submitting any form:

- AlternativeTo requires an account with a verified email before a new app can enter its review queue. Its form needs the platform/license/description/tag fields prepared above. [Official FAQ](https://alternativeto.net/faq/#add-a-new-application).
- OpenAlternative's submission route redirects to its sign-in page, offering email or OAuth login. No authenticated directory session is available here. [Submission entry](https://openalternative.co/submit).
- SaaSHub's actual product form is `/services/submit`, rather than the separate `/submit` promotion tool. It requests relevant categories and competitor entries, offers higher priority for domain-email verification, and asks agents to leave submission to a human operator. Use the canonical custom domain, since its guidance rejects free-host subdomains. [Official product submission page](https://www.saashub.com/services/submit).

## 5. Remaining blockers

1. Owner reads and passes each directory's submission terms and Reddit rules, then
   submits manually (no registrations were made).
2. The v1.0.16 features above are unreleased and the new guides are undeployed; refresh this
   copy after release and deployment.
3. Directory sites may require screenshots or a logo; use the real current-UI screenshots, not
   mockups. The demo clips are 1080x1920 and suit social posts more than directory forms.
4. Unsigned macOS and Windows installers will cause first-launch warnings; keep that
   disclosure in anything you post.
