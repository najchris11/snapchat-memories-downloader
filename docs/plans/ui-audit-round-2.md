# UI Audit — Round 2: the design system

Ten findings from the UI audit, on a branch off `develop` after round 1 merges.

Round 1 fixed specific defects. This round fixes the layer underneath them: there is no
typography scale, two colours compete for the same role, controls are hand-rolled `Box`es
with no button semantics, and nothing enforces any of it. Every item here touches every
screen, which is why they belong together and why the linter goes first.

Testing policy is in `AGENTS.md` / `CLAUDE.md`: a behavioural change lands with a test that
fails without it, verified by deliberately reintroducing the bug.

**Decided:** `#8B5CF6` becomes the theme primary (item 2).

**Status:** all ten items landed on `fix/ui-audit-round-2`. 137 tests, detekt clean, desktop
+ Android + iOS compiling. Two decisions in here were reversed while doing the work and are
recorded where they were made: the detekt baseline (item 1) and resetting the experimental
matching flag (round 1).

---

## Current state, measured

| | Count |
|---|---|
| Hardcoded `fontSize =` literals | **87** |
| `MaterialTheme.typography` references | **3** |
| Type sizes in use | 8sp ×1, 9sp ×6, 10sp ×21, 11sp ×22, 12sp ×18, 13sp ×16, 15/18/22sp ×1 each |
| `.copy(alpha = …)` sites | **105** (56 of them `onSurfaceVariant`) |
| `SnapVaultColors.electricPurple` call sites | **62** |
| Raw `Color.Black` / `Color.White` | **19** |
| `.clickable` on non-button composables | **20** |
| Lint tooling | none |

---

## 1. D6 — Add detekt, and run it in CI

**Severity:** Cleanup · **Files:** `build.gradle.kts`, `composeApp/build.gradle.kts`, CI workflow

Nothing enforces any of the conventions below. Round 1 removed eleven unused imports, two
never-called public composables, a duplicated theme constant and an unused function
parameter — all of which a default rule set flags in one run.

This goes **first** in the round because the sweeps that follow (T1 especially) touch every
UI file, and a linter running on each of them is worth far more than one running at the end.

**Fix:** add `io.gitlab.arturbosch.detekt` building on its default rule set, with a
`./gradlew detekt` step in CI ahead of the tests.

**Outcome — 134 findings on the first run, and two decisions changed as a result.**

*Style rules turned off.* 98 of the 134 were `MaxLineLength`, almost all log messages and
ffmpeg argument lists in pipeline code. Reformatting them would produce a large diff across
files unrelated to whatever change is in flight, which is the churn that gets a linter
ignored. Off, along with the rules that fight Compose idioms rather than catching defects:
`MagicNumber` (dp and sp literals are a UI file's whole vocabulary), `LongMethod` /
`NestedBlockDepth` / `LongParameterList` (composable screens are legitimately both),
`MatchingDeclarationName` (one-declaration-per-file is deliberately not the convention here),
and `LargeClass` (`DashboardViewModel` is a ~1000-line orchestrator; splitting it is a real
refactor, not a lint side effect). Each is commented in `config/detekt/detekt.yml` with the
reason.

*~~No baseline file~~ — reversed.* That call assumed round 1 had already cleared what a
baseline would hold. It had, for the UI. What detekt actually surfaced was **23 pre-existing
issues in pipeline and platform code**: 16 swallowed exceptions, 6 printed stack traces, and
one generic throw — every one a place where a failure vanishes without the user learning
about it. That is a real finding, and fixing it properly means threading logging through the
pipeline, which is its own piece of work rather than a side quest inside a typography round.

So the backlog is baselined at `config/detekt/baseline.xml` (13 signatures covering the 23
sites), with `SwallowedException` and `PrintStackTrace` left **active** so anything new
fails. Verified by injecting a swallowed exception, an unused import and an unused private
function into a UI file: all three failed the build, confirming the baseline is scoped to
existing sites rather than acting as a blanket amnesty.

Fixed outright rather than baselined: three unused imports, one unused private property
(`IosZipPipelineRunner` stored a `MediaProcessor` it never read — iOS does no overlay
combining, so the constructor parameter is gone and its one caller updated), and a test
helper that only returned a constant.

**Tests:** none (tooling). Verified by `./gradlew detekt` passing on a clean tree and failing
on each of three deliberately injected violations.

**Follow-up for a later round:** burn down the baseline. 23 vanishing failures is worth its
own pass, and the count is now visible instead of unknown.

---

## 2. C1 — One accent colour

**Severity:** Fix · **Files:** `Theme.kt`, all UI files

`colorScheme.primary` is `#D0BCFF` and is used by exactly one component — `SectionLabel`.
Everything else accents with `SnapVaultColors.electricPurple`, `#8B5CF6`, across **62** call
sites. Two violets 60 points apart in lightness, inside the same card. They converge in the
light theme, so this only shows in the default dark one.

**Fix (decided):** set the dark scheme's `primary = #8B5CF6` and delete
`SnapVaultColors.electricPurple`; the 62 call sites become `MaterialTheme.colorScheme.primary`.

`onPrimary` must change with it — `#3C0091` was chosen to sit on pale lavender and will not
read on a mid violet. Use `#FFFFFF`, and check the contrast ratio rather than assuming it.

The payoff beyond consistency: every Material default — `Button`, `Switch`, `Slider`, text
cursor, ripple, `FilterChip` selection — currently has to be overridden per call site to
reach the brand colour. Once `primary` *is* the brand colour, those overrides can go, which
shrinks items 7 and 8 below.

`SnapVaultColors` keeps `success`, `warning`, `info` — the roles Material genuinely lacks.
After this it holds three colours and the object has a clear reason to exist.

**Tests:** `ThemeTest` asserting `SnapVaultColorScheme.primary == SnapVaultLightColorScheme`'s
brand violet is not the point — assert instead that no UI file references a removed symbol
(detekt covers that) and that `onPrimary` meets 4.5:1 against `primary` in both schemes, as a
plain contrast-ratio unit test. That one is worth writing: it is the assumption most likely
to be wrong.

---

## 3. C4 — Name the media colours

**Severity:** Cleanup · **Files:** `Theme.kt`, `LibraryScreen.kt`, `VideoPlayer.kt`

**19** raw `Color.Black` / `Color.White` remain. Most are correct: scrims and letterboxing sit
over *photographs*, not over a theme surface, so they genuinely should not follow the theme.
The problem is that they are written identically to the two light-theme bugs round 1 fixed,
so nobody reading or grepping the file can tell "correct, media-relative" from "forgot the
theme exists".

**Fix:** add `mediaScrim`, `mediaScrimStrong` and `onMedia` to the theme — fixed in both
themes, like `LogColors` — and route the eleven `LibraryScreen` sites and four `VideoPlayer`
sites through them. Any `Color.Black`/`Color.White` left after this is a bug by definition,
which is the actual goal.

**Tests:** none directly (colour constants). Detekt gains a custom `ForbiddenMethodCall` /
naming rule if practical; otherwise the invariant is documented in `CLAUDE.md`, which already
states it.

---

## 4. T1 — A real typography scale

**Severity:** Fix · **Files:** `Theme.kt`, all UI files

`MaterialTheme(colorScheme = …, content = content)` passes no `typography`, so the app runs on
Material defaults it never uses. **87** hardcoded `fontSize` literals against **3**
`MaterialTheme.typography` references. Nothing can be scaled from one place, sizes drift
between screens that mean to match, and there is no mechanism for a text-size preference.

This is the largest item in the round and the reason T2 and T3 are cheap once it lands.

**Fix:** define a `Typography` in `Theme.kt` with the roles this UI actually uses, then
replace the literals screen by screen. Proposed mapping from the measured distribution:

| Current | Role | Used for |
|---|---|---|
| 22sp | `headlineSmall` | progress percentage |
| 18sp | `titleLarge` | brand wordmark |
| 15sp | `titleMedium` | primary button |
| 13sp | `bodyMedium` | option rows, settings rows |
| 12sp | `bodySmall` | secondary text, filter tabs |
| 11sp | `labelMedium` | field labels, metric chips |
| 10sp | `labelSmall` | section labels, inspector rows |
| 9sp, 8sp | *removed* | promoted to `labelSmall` — see item 5 |

Do Dashboard first; it is the busiest and will shake out the roles. Keep `FontFamily.Monospace`
overrides where they are — those are semantic, not sizing.

**Tests:** UI tests here would assert pixel sizes, which is brittle and low-value. Instead
assert the invariant that matters: a `TypographyTest` checking every role in the scale is at
least the minimum body size from item 5, so the floor cannot be lowered by editing the theme.
Detekt catches stray `fontSize =` reintroductions if a custom rule is practical; otherwise the
count is recorded here and checked at review.

---

## 5. T2 — Raise the type floor

**Severity:** Fix · **Files:** `Theme.kt` (via item 4)

The scale bottoms out at 8sp — the type badge on every media card — with 9sp at six more
sites. 10sp and 11sp carry the bulk of the interface: every card's section label, every
inspector row, every metric chip, every helper line, the whole log panel, and all four
stepper labels.

Below roughly 11sp text stops being reliably legible for a lot of people, and this is desktop
software where the space to be larger exists. On the phone layout it is read at arm's length.

**Fix:** floor of **12sp** for anything a user needs to read and **11sp** for genuine
micro-labels; 8sp and 9sp go entirely. Folded into item 4's sweep, so it is one edit rather
than 87.

**Tests:** covered by item 4's `TypographyTest`.

---

## 6. T3 — Stop stacking alpha

**Severity:** Fix · **Files:** all UI files

**105** `.copy(alpha = …)` sites, **56** of them on `onSurfaceVariant`. The compounding is the
problem: cards are `surface.copy(alpha = 0.45f)` over `background`, so 10sp text at 0.7 alpha
is a faded foreground on a translucent panel over another colour — under WCAG AA, and
unverifiable by inspection because the effective value depends on three layers.

It also duplicates what the palette already provides: `onSurfaceVariant` **is** the
de-emphasised role. Multiplying it by 0.7 de-emphasises the de-emphasis.

**Fix:** drop the `.copy(alpha = 0.7f)` habit and let `onSurfaceVariant` carry secondary text
at full opacity. If a third tier is genuinely needed, add one opaque token to the scheme and
measure it once. Give the cards a solid `surfaceContainer` rather than a translucent surface,
so contrast becomes computable at all.

Keep alpha where it means something: disabled states, scrims, hover overlays.

**Tests:** a contrast-ratio unit test over the token pairs the UI actually uses
(`onSurface`/`surface`, `onSurfaceVariant`/`surfaceContainer`, `onPrimary`/`primary`,
`LogColors.onSurface`/`LogColors.surface`), asserting 4.5:1 for body and 3:1 for large text.
This is the highest-value test in the round: it is a real user-facing property, it is cheap to
compute, and it will catch a future palette edit that looks fine in dark mode and fails in
light.

---

## 7. T4 — Make the controls real controls

**Severity:** Fix · **Files:** `DashboardScreen.kt`, `LibraryScreen.kt`, `SettingsScreen.kt`

**20** `.clickable` modifiers sit on `Box`, `Row` and `Text`. They get no `Role.Button`, so
assistive tech announces them as text, and their hit area is whatever the padding gives —
well under the 48dp minimum, which is a live problem on the phone layout where these are
finger targets.

| Control | Effective target |
|---|---|
| `DashboardScreen.kt` "Clear" | an 11sp `Text`, no padding at all |
| `DashboardScreen.kt` copy-logs | 13dp icon + 6/3dp padding ≈ 25 × 19dp |
| `DashboardScreen.kt` logs disclosure | 4dp vertical padding |
| `LibraryScreen.kt` refresh | 16dp icon + 8/5dp ≈ 32 × 26dp |
| `LibraryScreen.kt` filter tabs | ≈ 12dp vertical padding |
| `SettingsScreen.kt` theme + layout segmented controls | same |
| `SettingsScreen.kt` "Refresh Check" | a bare coloured `Text` |

**Fix:** these are all either icon buttons or toggle groups. Use `IconButton` (48dp by
default) and `SegmentedButton` / `FilterChip`, which bring the role, the target and a focus
state with them. Item 2 makes this cheaper: once `primary` is the brand colour, the Material
components need no colour overrides. Where a custom look must stay, add
`Modifier.semantics { role = Role.Button }` and `sizeIn(minWidth = 48.dp, minHeight = 48.dp)`.

**Tests:** Compose UI tests asserting each control exposes a click action and meets the
minimum touch target. `assertTouchWidthIsEqualTo` / `assertTouchHeightIsEqualTo` make this
directly assertable, and it is exactly the kind of regression that returns silently.

---

## 8. T5 — The preview dialog announces itself as a button

**Severity:** Fix · **Files:** `LibraryScreen.kt`

`MediaPreviewDialog` stops scrim clicks from closing the dialog by putting `.clickable { }` —
an empty handler — on the content `Surface`. The comment is honest about it (*"absorb clicks
so the scrim handler doesn't fire"*), but the entire card becomes focusable, ripples on click,
and is announced as an interactive control that does nothing.

**Fix:** consume the gesture instead of handling it —
`Modifier.pointerInput(Unit) { detectTapGestures { } }`, or
`clickable(interactionSource, indication = null)`. Same effect, no phantom control.

**Tests:** the existing `bannerWithoutAnActionRendersNoButton` pattern applies directly —
assert the dialog content exposes no click action while the scrim still dismisses.

---

## 9. T6 — Semantics, starting with the stepper

**Severity:** Cleanup · **Files:** `DashboardScreen.kt`, plus `contentDescription`s

Not one `Modifier.semantics` or `Role` in the codebase. The clearest case is the four-step
progress indicator: step number, active state and completion are conveyed entirely by fill,
border and icon swap, with no state description — so the app's central status display reads
as four unlabelled shapes.

**Fix:** add `stateDescription` to `StepItem` ("Step 2 of 4, in progress") and
`progressSemantics` to the progress ring. `CompactStepper` already renders that sentence as
visible text, so the two can share one string resource — which is the tell that the expanded
stepper was missing it.

`contentDescription`s are part of the round 3 string sweep, since they need resources anyway.

**Tests:** assert the stepper's state description at each `currentStep`, and that the ring
exposes a progress value.

**Landed.** One `stepStateDescription` shared by both steppers, so they cannot drift;
`DASHBOARD_STEP_COUNT` replaces the literal 4 and the four hand-written step numbers. The
ring moved into `PipelineProgressRing` — Material publishes the range info itself, but not
what the bar is measuring, and extracting it is what made the range info assertable at all.

---

## 10. T7 — Keyboard support

**Severity:** Cleanup · **Files:** `LibraryScreen.kt`, `DashboardScreen.kt`

No `focusRequester`, no `onKeyEvent`, no `onPreviewKeyEvent`, no shortcuts anywhere. For a
desktop app that means the media preview has no Escape handling of its own, no arrow-key
movement through the library grid, no <kbd>/</kbd> to focus search, and no defined tab order
through the custom clickable boxes in item 7 — which, having no button role, are unlikely to
be reachable in a sensible sequence.

**Fix:** Escape-to-close on the dialog and arrow-key grid movement are the two a desktop user
notices immediately. Adopting real Material controls in item 7 fixes tab order as a side
effect, so do this after.

**Tests:** UI tests dispatching key events — Escape dismisses the dialog, arrow keys move grid
selection.

**Landed.** Escape, arrow keys, Enter/Space, Home/End, and `/` to focus search. Two things
worth knowing next time: Compose Desktop's `Dialog` does **not** handle Escape for you, and
the dialog must hold focus or the handler never fires, because key events follow the focus
path. Both were established by removing the code and watching the test fail. The grid moved
into `LibraryGrid` and the movement arithmetic into the pure `libraryGridTarget`, since the
inline grid needed a real folder on disk before it would render anything to test.

---

## Sequencing

1 first (the linter guards everything after it). Then 2 → 3, which are theme-level and make
4 and 7 smaller. Then 4 → 5 → 6 as one typography-and-contrast sweep. Then 7 → 8 → 9 → 10 as
the reach cluster, in that order, because 10 depends on 7.

Run `./gradlew :composeApp:desktopTest` plus all three compile targets after each item.

## Deferred to round 3

Strings and localisation (S1, S2, S4, S5, S6, T8), information architecture (N6, N7, N9, N10),
the three feature builds (N11 sort, N12 reveal, D4 favourites), and the remaining dead code
(D2, D3, D5). See `ui-audit-round-3.md`.
