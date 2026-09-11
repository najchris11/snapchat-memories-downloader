# SnapVault — working notes for agents

Kotlin Multiplatform / Compose Multiplatform app that turns a Snapchat "Download My Data"
export into a dated, GPS-tagged media library. Targets desktop (JVM), Android, and iOS.

## Testing policy

**Every behavioural change lands with a test that fails without it.** This is not optional,
and it applies to UI code as much as pipeline code.

The order is: write the failing test, watch it fail for the right reason, make it pass. If a
test passes the first time you run it, you have not yet proven it tests anything — break the
production code deliberately, confirm the test fails, then restore. A test that cannot fail
is worse than no test, because it reads as coverage.

### What must have a test

| Change | Required test |
|---|---|
| Bug fix | A regression test that reproduces the bug, named for the behaviour, with a comment saying what broke and how |
| New behaviour | Tests for the expected path and the failure/edge path |
| A default that has consequences | An assertion on the default itself (see below) |
| Refactor with no behaviour change | No new test, but the existing suite must pass untouched — if it does not, it was not a refactor |

### Making things testable is part of the work

If a behaviour cannot be tested where it lives, move it until it can. Two patterns this
codebase already uses:

- **Name your defaults.** Option defaults live as `internal const val` in
  `DashboardScreen.kt` (`DEFAULT_DRY_RUN`, `DEFAULT_PIPELINE_EXPANDED`, …) rather than as
  literals inside `remember { mutableStateOf(true) }`, precisely so a test can assert them.
  A destructive default silently flipping was a real blocker; the constants exist so it
  cannot happen unnoticed.
- **Publish what the UI needs to show.** `DashboardViewModel.failureCount` is public state
  because a private accumulator could not be asserted, and the UI could not report it.

### Test locations

| Path | Use for |
|---|---|
| `composeApp/src/commonTest/` | Pure logic with no platform dependency — parsers, dedupe, the download engine |
| `composeApp/src/desktopTest/` | Anything needing the JVM, the filesystem, real processes, or Compose UI |

Compose UI tests use `androidx.compose.ui.test.v2.runComposeUiTest` (the **v2** package — v1
is deprecated) with `@OptIn(ExperimentalTestApi::class)`. `DashboardScreenTest` is the
reference example. `onNode`/`onAllNodes` are receiver methods, not imports.

### Commands

```
./gradlew :composeApp:desktopTest                 # full suite
./gradlew :composeApp:desktopTest --tests "…"     # one class
./gradlew :composeApp:compileKotlinDesktop        # desktop
./gradlew :composeApp:compileDebugKotlinAndroid   # Android
./gradlew :composeApp:compileKotlinIosSimulatorArm64  # iOS (cross-compiles on Linux)
```

A commonMain change compiles for three targets. Check all three before claiming it builds —
desktop passing does not mean Android and iOS do.

There is no detekt/ktlint. Until there is, unused imports and dead code are on you to catch:
eleven unused imports and two never-called public composables reached `main` this way.

## Theming rules

- Colours come from `MaterialTheme.colorScheme`, `SnapVaultColors`, or `LogColors`. The raw
  palette constants in `Theme.kt` are `private` deliberately — they are single-theme values,
  and reaching for one produces a colour that does not adapt.
- `SnapVaultColors` holds only what Material 3 lacks: `success`, `warning`, `info`,
  `electricPurple`. There is deliberately **no** `error` — `colorScheme.error` is the one
  error colour.
- `LogColors` is fixed in both themes because the log panel is a terminal. Anything drawn on
  that surface must come from `LogColors`, including tag colours.
- A hardcoded `Color.Black`/`Color.White` is only acceptable over *media* (scrims,
  letterboxing), never over a theme surface. Prefer a named token so the intent is legible.

## Strings

User-facing text goes in `composeApp/src/commonMain/composeResources/values/strings.xml`,
including `contentDescription`s. Roughly half the UI still has hardcoded literals; when you
touch a line with one, extract it. Do not add new ones.

Avoid `"item${if (n == 1) "" else "s"}"` — use a plural resource.

## Known footguns

- **`AppBuildConfig.IS_DEBUG` flips on the Gradle task name.** `:composeApp:run` sets it
  `true`; `compileKotlinDesktop` and `desktopTest` set it `false`. It is a `const val`, so
  it is inlined at every use site and each alternation invalidates everything referencing
  it. Alternating between running the app and running tests causes large recompiles, and
  can leave `build/classes` inconsistent — the symptom is a `NoClassDefFoundError` for a
  synthetic lambda class such as `DraggableAreaKt$DraggableArea$2$1$1`. `./gradlew clean`
  clears it.
- The debug build caps imports at 2,500 items. Never diagnose "missing memories" without
  checking `IS_DEBUG` first.
- `iosMain`'s `VideoPlayer` builds its `AVPlayer` outside `remember`, so it is recreated on
  every recomposition. Known, unfixed.

## Commits

No `Claude-Session:` trailer. Explain *why* in the body, not just what — the mechanism
behind a bug is the part that stops it recurring.
