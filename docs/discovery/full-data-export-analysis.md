# Full Data Export — Discovery Analysis

Snapchat's "Download My Data" offers two export scopes:

- **Only Memories** — what SnapVault currently supports. A `memories/` folder plus `memories_history.html`/`.json`.
- **All Time / all supported data** — everything below, split across many `mydata~<id>[-N].zip` parts (in this case 28 parts, ~62 GB compressed total).

This doc catalogs what's actually inside the full export, based on a real one, so we know what else SnapVault could plausibly read and render. It intentionally omits personal content (names, messages, coordinates) — schema and counts only.

## Verification: memories still extract correctly from the full export

Ran the real ZIP-import pipeline (`DashboardViewModel` → `ZipImportParser` → `DesktopZipPipelineRunner`) against all 28 zip parts unmodified, default options (metadata + experimental ZIP matching + combine, no dedupe), output to a scratch folder outside the repo.

- `ZipImportParser` correctly scoped itself to `memories/` + `json/memories_history.json` in each part and ignored `chat_media/`, `html/`, and every other top-level folder — no code changes needed for the pipeline to behave correctly on a full export.
- Indexed **10,151 memories** across the 28 parts (parts 2–7 and the base zip contribute 0, as expected — memories only start at part 8 in this export).
- Extracted **14,988 files** cleanly (paired `-main`/`-overlay` files count as 2).
- Experimental ZIP metadata matching: **10,118 / 10,151 (99.7%)** matched `memories_history.json` by exact capture timestamp (date + time-of-day + GPS where available); the rest fell back to date-only ZIP metadata, which is the documented, correct fallback behavior. 198 files hit a timestamp collision with a conflicting location and correctly had GPS omitted rather than risk tagging the wrong file — matches the "never guesses" behavior documented in the README.
- Metadata written: **11,360 files tagged (10,452 with GPS)**, 3,628 correctly skipped (WebP-format overlays misnamed `.png` — harmless, exiftool rejects them but the combine phase still consumes them fine).
- Overlay combine: **4,841 overlay pairs combined**, all video encodes ran on the hardware path (**2,644 hardware / 0 software**, VAAPI).
- Final: **10,151 memories, 14,988 files on disk, 4,841 overlays combined. Sync complete, zero warnings.**

Conclusion: **the full "all data" export is a drop-in superset of the "Only Memories" export as far as the memories pipeline is concerned.** No pipeline changes are needed to point SnapVault at a full export instead of a memories-only one.

(First attempt at this run failed with "No space left on device" — that was a scratch-disk sizing mistake on my end, output was pointed at a 16 GB tmpfs against a ~44 GB memories payload, not a pipeline bug. Rerun against real disk succeeded end-to-end.)

## What else is in a full export

| Category | Top-level path(s) | Size | Count |
|---|---|---|---|
| Memories (already supported) | `memories/`, `json/memories_history.json` | ~44.4 GB | 15,009 files / 10,151 memories |
| Chat media (photos/video sent in chats) | `chat_media/` | ~15.5 GB | 18,633 files |
| Chat history (saved messages) | `json/chat_history.json`, `html/chat_history/` | ~18 MB (json) | 368 conversations |
| Snap history (ephemeral snaps, metadata only) | `json/snap_history.json`, `html/snap_history/` | ~1.3 MB (json) | 92 conversations |
| Everything else (friends, location, account, etc.) | `json/*.json`, `html/*.html` | ~113 MB combined | 25 categories |

`chat_media/` extensions: 9,118 jpg, 7,101 mp4, 1,366 webp, 924 png, 52 gif, 49 "unknown", 18 heif, 5 mov.

### Chat history — the obvious next feature

`json/chat_history.json` is an object keyed by conversation UUID/username, each value an array of message objects:

```
Content, Conversation Title, Created, Created(microseconds), From, IsSaved, IsSender, Media IDs, Media Type
```

`Media IDs` cross-references files in `chat_media/` by filename stem — same "sidecar index + real files on disk" shape as `memories_history.json` → `memories/`. This is the single biggest thing SnapVault doesn't touch yet: comparable scale to Memories (18.6k files / 15.5 GB), same file-manifest structure, and there's a parallel HTML rendering already (`html/chat_history/subpage_*.html`, one per conversation) we could reference for formatting cues.

`json/snap_history.json` looks superficially similar (also keyed by conversation, array of per-snap objects) but its objects only carry `Conversation Title, Created, Created(microseconds), From, IsSender, Media Type` — no `Content`, no `Media IDs`. Confirmed: ephemeral (unsaved) snaps have no recoverable content in the export, only a metadata log of who/when/what-type. Useful for a stats view, not a media viewer.

### Other categories, structurally

Everything else is a single JSON object per category (no sidecar media folder — these are metadata-only):

- **`friends.json`** — `Friends` (Display Name, Username, Creation/Last-Modified Timestamp, Source), plus Blocked/Deleted/Pending/Ignored/Hidden-Suggestion/Shortcut lists. A social-graph view is very renderable from this alone.
- **`location_history.json`** — Home/School/Work, Frequent Locations (City/Country/Region), Latest Location, Daily/Six-Day Top Locations, Areas/Businesses visited. Schema supports a map view; this particular export has most arrays empty (Location History off), but `snap_map_places_history.json` (95 entries: Place, Place Location, Date, Share Type) is populated and map-renderable on its own.
- **`story_history.json`** — `Your Story Views` (Story Date, Story Replies, Story Views) + `Friend and Public Story Views`. A viewing-history timeline, not new media.
- **`search_history.json`** — Search Term + Date + Location, small.
- **`ranking.json`** — Snapscore, friend/following counts, Spotlight array — a simple stats card.
- **`account.json` / `account_history.json`** — Basic Information (Name, Username, Country, Creation Date, Last Active, Registration IP), Device History/Information, Login History, Family Center, plus a change-log (display name/email/phone/password changes, 2FA, deactivation events). A security/account-timeline view.
- **`bitmoji.json`, `cameos_metadata.json`** — Selfies/analytics/support-case metadata (both empty of media in this export — Bitmoji/Cameos unused).
- **`user_profile.json`** — Snapchat's own ad-profiling of the user: Ad Interactions, Interest/Content Categories, Demographics, Discover Channels Viewed, Engagement, Geographic Information. Interesting as a "what Snapchat inferred about you" privacy-audit view, not a media feature.
- **Everything else** (`connected_apps`, `custom_sticker`, `snapchat_ai`, `snap_pro`, `snapchat_plus`, `snap_ads`, `terms_history`, `email_campaign_history`, `feature_emails`, `in_app_reports`, `shared_story`) — small, low-value metadata; skip unless a specific ask surfaces.

## What's actually worth building

1. **Chat archive viewer** (chat_history.json + chat_media/) — same shape as Memories, comparable scale, real content. The natural "second pillar" next to the Library.
2. **Friends / social graph list** — cheap, friends.json is small and fully structured.
3. **Account/security timeline** — cheap, useful, no media involved.
4. Everything else is metadata-only and lower value — worth a look only if a specific feature request comes up.

Snap history, location history, and the ad-profiling data are schema-ready but this particular export has too little populated data to validate a real UI against — would need to build against the *shape*, not sample values.
