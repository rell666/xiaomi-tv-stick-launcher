# Code review rubric

Every scored review of this repository uses this file, so that scores from different reviewers
and different days can be compared. A score that was not produced under this rubric is not
comparable with one that was; say which rubric version a score came from.

**Rubric version: 1 (2026-10-30).** Bump the version when anchors or metrics change.

## Who reviews what

A full review is three independent, read-only reviewers. Each reviewer's copy is a **full git
clone** checked out at one exact commit (`git clone <authority> <dir> && git -C <dir> checkout -q
--detach <sha>`), not `git archive`: the offline tests need the history (`git show 065aa2b`, the
rollback SHAs) and fail without it. (Procedure fix, 2026-10-31; no anchor or metric changed, so
this is still rubric v1.) Each one gets that clone, the prompt at the bottom of this file, and its
area:

| Area | Files |
|---|---|
| **A. Android** | `android/**` (Java, manifests, Gradle), reading `web/device.js` only for the bridge contract |
| **B. Web layer** | `web/device.js`, `tools/android-patch.py`, `tools/check.py`, `tools/upstream-manifest.json`, `web/index.html` script order. Upstream files in `web/` are not reviewed for quality |
| **C. Release tooling** | `build.sh`, `install.sh`, `tools/*.sh` (incl. `stick-lib.sh`), `tools/*.mjs`, `tools/icon_metrics.py`, `tests/**`, `docs/REVIEW.md`, `.gitignore`, `docs/RULES.md`, `README.md`, `LICENSE`, `THIRD_PARTY_NOTICES.md`, `web/LICENSES/` |

Reviewers are not shown earlier scores or earlier findings. They do not modify the clone,
do not SSH anywhere and do not touch the device.

## Metrics

Each area scores the metrics marked for it, out of 10, in steps of 0.5.

| Metric | A | B | C | What it measures |
|---|---|---|---|---|
| Correctness | ✓ | ✓ | ✓ | Does the code do what it claims, on every path the reviewer traced? |
| Robustness | ✓ | ✓ | ✓ | Behaviour on failure: bad input, missing device, crash, low memory, partial runs |
| Security | ✓ | ✓ | ✓ | Attack surface, origin lock, input validation, secrets |
| Maintainability | ✓ | ✓ | ✓ | Can someone new change it safely: structure, comments, gates that catch mistakes |
| Testability | ✓ | ✓ | ✓ | Automated tests for this area (see evidence rules) |
| Performance | ✓ | ✓ | | Memory, CPU and UI-thread cost on the 2 GB, 32-bit stick |
| Documentation | | | ✓ | Docs match the scripts and code; a newcomer can build, test, release and roll back |
| Reproducibility | | | ✓ | Same commit gives the same artifact; provenance can be read back |

**Code quality** is not a separate metric: it was the mean of the others in all but name.

## Score anchors

Use the anchor that fits the **worst confirmed** problem in the metric, then adjust by at most
±1 for breadth.

| Score | Meaning |
|---|---|
| 9–10 | No confirmed problem above *low*. Nothing a reviewer would change before shipping |
| 7–8 | Confirmed *low*/*medium* problems only; none can leave the stick without a working Home or ship wrong code |
| 5–6 | At least one confirmed *medium* problem with a realistic scenario, or several *low* ones in the same path |
| 3–4 | A confirmed *high*: realistic loss of Home, wrong code shipped, a gate that passes when it should fail |
| 0–2 | A confirmed *critical*: data or credential exposure, or a defect that is hit in normal use |

Severity: **critical** = hit in normal use, or secret exposure. **high** = realistic scenario
leaves the stick without a working Home, ships unverified code, or a gate fails open.
**medium** = realistic scenario with a visible wrong result that a gate or fallback limits.
**low** = an unlikely scenario, or a harmless wrong result. **nit** = style.

### Testability anchors (stricter, because this is the metric that drifted)

| Score | Evidence required |
|---|---|
| 9–10 | Every behaviour that matters in the area has a committed automated test that runs offline from `tools/check.py` (so on every build), plus committed on-device tests for what only the device can show |
| 7–8 | Main paths and main failure paths have committed tests run by `check.py`; device-only paths have committed device tests; small gaps named |
| 5–6 | Committed tests exist for the main path but a whole class of failure paths is untested, or the tests exist but no gate runs them |
| 3–4 | Only device tests, or tests that print without asserting |
| 0–2 | No committed tests |

## Evidence rules

1. **Only what is committed counts.** Wiki notes, chat logs and "verified by hand" claims do not
   raise *Testability*. They may support *Correctness* only if the reviewer can see the code path.
2. **A test counts only if it asserts and fails loudly.** Read it: a check that passes without
   the behaviour (e.g. "Home closes the panel" with no panel opened) counts as missing.
3. **A test run by a gate counts more than one run by hand.** Say which gate runs it.
4. **Verify every finding against the code** and give `file:line`. Mark anything not verified
   as *unverified*; unverified findings do not move a score by more than 0.5.
5. **Accepted risks are listed, not scored.** Current list:
   - The signing-key backup archive is unencrypted (2026-10-30; it goes to a private, backup-only store).
   - WebView DevTools is enabled in the release build too (2026-10-31). It is reachable only
     over adb, which the stick grants to the build machine's key alone (a new key needs OK pressed on the TV),
     and anyone with adb can already install apps, send keys and open a shell. `tools/stick-test.sh`
     and `--reboot` drive the live Home through it.
6. **Device facts are given, not guessed:** Xiaomi TV Stick 4K, Android TV 11 *user* build, app
   not debuggable, 2 GB RAM, 32-bit, the app is the **only** launcher, adb only from the build machine.

## Aggregating

- **Metric score** = mean of the areas that score it, rounded to 0.5.
- **Overall** = mean of the eight metric scores, rounded to 0.5.
- Report the per-area table, the per-metric table, the overall, the rubric version and the
  commit, and keep each reviewer's model and that its scores are "rubric v1".

## Reviewer prompt

```
Independent code review, READ-ONLY, under the rubric in docs/REVIEW.md (rubric v<N>).
Full git clone, checked out at commit <sha>: <path>. Read docs/REVIEW.md first and follow it exactly.
Your area: <A | B | C> — files as listed in docs/REVIEW.md.
Do not modify the clone, do not SSH anywhere, do not touch any device; you may run
read-only tools (node, python -I, bash -n, shellcheck, the offline tests under tests/) and
write only in your own new folder under the scratch directory, deleted when you finish.

Report:
1. Findings, most severe first: file:line, severity (rubric definitions), concrete scenario,
   suggested fix, verified/unverified.
2. Testability evidence: each committed test that covers your area, what it asserts, which
   gate runs it; then the behaviours with no test.
3. Accepted risks seen (not scored).
4. Scores for your area's metrics, each with the anchor used and one line of justification.
Under 800 words.
```
