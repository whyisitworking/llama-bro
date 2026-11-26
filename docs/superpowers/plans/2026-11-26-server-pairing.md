# Typable Key, Throttling and Pairing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** a laptop gets the phone server's key without typing 43 characters: a short typable key, throttled guessing,
and pairing from the laptop's browser with approval on the phone.

**Spec:** [docs/design/server.md](../../design/server.md) §6 (added 2026-11-26).

## Global Constraints
- Module defaults stay upstream-like: `keyMatching = Exact`, `pairing = null`; throttling is on when a key is set.
- Next free IDs: D173, F192. Decisions: D173 key matching and throttling; D174 pairing.
- Checks scoped (owner's rule): SDK `sdk/scripts/check.sh --only api,quality,lint,jvm,scripts`; demo
  `demo/scripts/check.sh` host steps. No phone; journeys only on an already-running emulator.
- Demo UI from `:demo:designsystem` only (D113).

### Task P1: Typable key, key matching, throttling, QR removed
**Files:** sdk/llamabro-server Auth.kt, ServerConfig.kt (+ new Throttle.kt) and tests; demo server/ServerKey.kt,
ui/server (Address section, previews, view model, snippets), designsystem QrCode (remove if unused) and its tests;
docs/decisions.md D173; release notes.
- [ ] Tests first: `typableMatchingIgnoresCaseSpacesAndDashes`, `exactMatchingIsTheDefault`,
  `fiveWrongKeysThrottleThatAddress` (429 + Retry-After), `waitDoublesUpToFiveMinutes` (virtual clock),
  `rightKeyResetsTheThrottle`, `otherAddressesAreNotThrottled`, `throttleCanBeTurnedOff`; demo `keyIsSixteenCrockford`,
  `keyShowsInFourGroups`, `noQrOnTheServerPage`.
- [ ] Implement; apiDump; D173; checks; commit "server: a key you can type, and wrong keys are throttled; the QR code
  goes".

### Task P2: Pairing from the laptop's browser
**Files:** sdk/llamabro-server Pairing.kt, PairingRoutes.kt, a resource HTML page, tests; demo server/ServerMode
(approve), ui/server (approval dialog on Server and docked views), ServerService (notification actions); D174; docs.
- [ ] Tests first: `rootServesThePageOnlyWithPairing`, `pairGivesFourDigitCode`, `onePendingPerAddressThreeInAll`,
  `approvedDeliversKeyOnce`, `deniedAndExpired`, `pairingSharesTheThrottle`, `pageHasNoExternalResources`; demo
  `approvalDialogShowsCodeAndClient`, `noAnswerIsADeny`, `backgroundShowsNotificationActions`.
- [ ] Implement; apiDump; D174; docs (server.md status, architecture, release notes, demo-app.md); checks; commit
  "server: pair a laptop from its browser, approved on the phone".
