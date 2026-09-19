# Optional desktop support / tipping

Updated: 2026-09-16. The maintainer reports the page is set up at
[ko-fi.com/najdev](https://ko-fi.com/najdev). Account/payment settings have not been
independently verified. The budget remains zero.

## Recommendation

Start with **Ko-fi Free and one-time tips**. There is no monthly payment and a 0% platform
fee on those tips. Processor fees still apply. New creators start in Standard mode,
which takes 5% on tips: switch it off under **Settings → Payment → Get all of Ko-fi**.
Memberships, monthly tips, shop sales, and commissions have different fees and are
unnecessary for this initial use case.

Source: [Ko-fi fees](https://help.ko-fi.com/hc/en-us/articles/360002506494-Does-Ko-fi-take-a-fee).

| Option | Upfront/monthly platform cost | Relevant tradeoff | Recommendation |
|---|---|---|---|
| Ko-fi Free, one-time tips | None | 0% platform fee; processor fees remain; check default mode | First choice |
| Buy Me a Coffee | No monthly fee | 5% platform fee plus processing/payout fees | Less attractive for this budget |
| GitHub Sponsors | No paid subscription needed to apply | Open-source contributor focus; bank/tax setup and regional eligibility | Explore later for developer supporters |

Buy Me a Coffee also documents payout processing fees and bank-transfer delays; it is
not a guaranteed way to receive spendable money immediately.
Sources: [Buy Me a Coffee fees](https://help.buymeacoffee.com/en/articles/8105744-how-to-calculate-charges-on-your-payment),
[Payout timing](https://help.buymeacoffee.com/en/articles/9770774-understanding-your-payouts-on-buy-me-a-coffee-through-stripe-express),
[GitHub Sponsors setup](https://docs.github.com/en/sponsors/receiving-sponsorships-through-github-sponsors/setting-up-github-sponsors-for-your-personal-account).

This assumes the maintainer can use PayPal or Stripe in their country. Ko-fi sends
payments directly to that provider; onboarding, verification, holds, and bank settlement
can still affect access to funds. Do not budget signing costs against tips not yet received.

Source: [Getting paid on Ko-fi](https://help.ko-fi.com/hc/en-us/articles/115003980093-How-do-I-get-paid).

## Maintainer setup

1. Create a free page at [ko-fi.com](https://ko-fi.com/) with a recognizable name
   associated with SnapVault. Do not buy Gold or create memberships for this launch.
2. Describe SnapVault as free, with optional tips supporting development and distribution.
   Avoid promising that tipping guarantees support or a signed release date.
3. Connect an already usable PayPal or Stripe account under Settings → Payment.
   If a new account needs verification, keep the app release independent.
4. Switch off Standard / Get all of Ko-fi and confirm one-time tips have 0% platform fees.
5. Preview the page signed out and check recipient information and currency. Personal
   PayPal accounts typically expose legal name/email. Inspect actual checkout/receipt
   information before sharing. Business accounts may offer more control, subject to
   provider eligibility.
6. Supply the final HTTPS URL for the app and README. Verify the recipient. A mock
   checkout or screenshot can verify the link without spending money on a test tip.

Sources: [Ko-fi getting started](https://help.ko-fi.com/hc/en-us/articles/360014098514-Getting-started-on-Ko-fi),
[Information shown to supporters](https://help.ko-fi.com/hc/en-us/articles/115004001294-What-personal-information-is-shared-on-Ko-fi-as-a-creator).

## Implemented desktop UI

The desktop sidebar has a small **Support me on Ko-fi** button directly below the
status indicator, with an optional-tip/browser hint. Settings also has a **Support
SnapVault** section above its version/license footer, reachable in narrow desktop
windows where the sidebar is absent. Both share the same opening/error flow.
Mobile builds omit the page by default. The README includes the same destination.

Suggested copy:

> **Support SnapVault**
>
> SnapVault is free to use. Optional tips help support development and distribution.
>
> **Support me on Ko-fi ↗**
>
> Opens Ko-fi in your browser.

Use a provider-neutral section name and provider-specific action. Keep “optional”; do
not imply payment is needed to import, preserve memories, or unlock a feature. Do not
tie the appeal to an unverified deletion deadline.

Behavior once the final URL exists:

- Open the fixed HTTPS page only on explicit activation in the default browser. No
  embedded checkout, SDK, account login, automatic browser launch, or media/path data
  in the URL. Payment and receipts are handled on the external site.
- Hide the section when the URL is absent. Do not ship placeholder links or dead buttons.
- Keep import/cancel/progress behavior unchanged when opening the link, including
  during a run. No nag dialog on first launch, completion, or exit.
- Support keyboard focus/activation and an accessible name with the destination.
  Use theme colors and string resources per `CLAUDE.md`.
- If opening fails, report it and offer to copy the URL. Do not show payment success
  merely because a browser opened.

Support uses Compose's platform URI handler, invoked away from the desktop UI thread,
rather than the existing silent `openUrl` helper. Synchronous opening failures show the
address and a copy action; copy failures leave the address selectable. The system may
accept a launch request and fail later without notifying the app, so no claim is made
that a page loaded or a payment completed. Other existing links are unchanged.

Scope the initial feature to direct desktop distribution. Check channel rules before
enabling it in a future Microsoft/Mac Store package.

## Required implementation tests

Follow `AGENTS.md`: write tests first, observe intended failures, then implement.
The original seven feature tests failed before implementation. The additional four edge
tests and default browser-handoff test were verified by deliberately breaking the relevant
production behavior, observing five intended failures, and restoring it. Test results
refer to this feature's isolated branch, not another agent's uncommitted changes.

- Missing/blank URL renders no action; a valid configured URL renders the expected label
  and optional/external-site explanation.
- Activation invokes an injected opener exactly once with the expected URL. Rendering
  or navigating alone does not invoke it. Include keyboard activation coverage.
- Opener failure shows a useful message and permits copying the correct URL.
- Opening support during an import leaves run/cancellation state intact.
- Both sidebar and narrow desktop layouts can reach the Settings section.

Use Compose v2 UI tests and existing Settings/root-screen patterns. Verify the flow on
each desktop OS after tests pass. Shared code changes require the platform compile
checks in `AGENTS.md`, even when the button is desktop only.

## Release follow-up

Validation on `feat/desktop-kofi-support`:

- All 329 desktop tests passed, including 12 support-link tests. Desktop/Android/iOS
  simulator compile checks passed.
- Static analysis found four existing issues in files unchanged by this feature:
  `DashboardViewModel.kt` (swallowed exception), `DownloadEngine.kt` (generic exception),
  `FileOutputDirectoryLocker.kt` (swallowed exception), and `DashboardScreen.kt` (unused
  parameter). No new support-code findings were reported. These remain integration checks
  for the concurrent remediation work.
- Browser handoff was tested through a fake platform URI handler; checkout/payment and
  real browser behavior on all shipping OSes were not tested.

Integrated into `fix/d01-d03-destructive-extraction-scope`, preserving the newer
remediation test and planning commits. The merged tree passed all 330 desktop tests
with no failures, errors, or skips, plus desktop/Android/iOS simulator compile checks.
The maintainer reviewed the locally launched desktop layout and approved integration.
Include the button in the next desktop build. No payment transaction was made during testing.
Confirm browser handoff on each shipping desktop OS as part of installer acceptance.
