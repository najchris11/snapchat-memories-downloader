# SnapVault — agent instructions

Kotlin Multiplatform / Compose Multiplatform app that turns a Snapchat "Download My Data"
export into a dated, GPS-tagged media library. Targets desktop (JVM), Android, and iOS.

`CLAUDE.md` holds the full working notes — theming rules, string-resource policy, known
footguns. **Read it before changing code.** The testing policy is repeated here in full
because it is not optional and must not be missed.

## Testing policy

**Every behavioural change lands with a test that fails without it.** This applies to UI
code as much as pipeline code.

The order is: write the failing test, watch it fail for the right reason, make it pass.

If a test passes the first time you run it, you have not yet proven it tests anything. Break
the production code deliberately, confirm the test fails, then restore. A test that cannot
fail is worse than no test, because it reads as coverage. Do this verification and say in
your summary that you did.

### What must have a test

| Change | Required test |
|---|---|
| Bug fix | A regression test reproducing the bug, named for the behaviour, commented with what broke and how |
| New behaviour | The expected path and the failure/edge path |
| A default with consequences | An assertion on the default itself |
| Refactor with no behaviour change | No new test, but the existing suite must pass untouched — if it does not, it was not a refactor |

### Making things testable is part of the work

If a behaviour cannot be tested where it lives, move it until it can. Two patterns already
in this codebase:

- **Name your defaults.** Option defaults are `internal const val` in `DashboardScreen.kt`
  (`DEFAULT_DRY_RUN`, `DEFAULT_PIPELINE_EXPANDED`, …), not literals inside
  `remember { mutableStateOf(true) }`, so tests can assert them. A destructive default
  flipping silently was a real blocker; the constants exist so it cannot recur unnoticed.
- **Publish what the UI must show.** `DashboardViewModel.failureCount` is public state
  because a private accumulator could neither be asserted nor reported to the user.

### Where tests go

| Path | Use for |
|---|---|
| `composeApp/src/commonTest/` | Pure logic, no platform dependency — parsers, dedupe, download engine |
| `composeApp/src/desktopTest/` | JVM, filesystem, real processes, or Compose UI |

Compose UI tests use `androidx.compose.ui.test.v2.runComposeUiTest` — the **v2** package; v1
is deprecated — with `@OptIn(ExperimentalTestApi::class)`. See `DashboardScreenTest` as the
reference. `onNode`/`onAllNodes` are receiver methods, not imports.

### Verify before claiming done

```
./gradlew :composeApp:desktopTest                     # full suite
./gradlew :composeApp:compileKotlinDesktop            # desktop
./gradlew :composeApp:compileDebugKotlinAndroid       # Android
./gradlew :composeApp:compileKotlinIosSimulatorArm64  # iOS (cross-compiles on Linux)
```

A `commonMain` change compiles for three targets. Desktop passing does not mean Android and
iOS do — check all three.

There is no detekt/ktlint yet, so unused imports and dead code are on you to catch. Eleven
unused imports and two never-called public composables reached `main` this way.

## Commits

No `Claude-Session:` trailer. Explain *why* in the body — the mechanism behind a bug is the
part that stops it recurring.
