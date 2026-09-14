# Current source verification — 2026-09-15

## Scope

Current root source, version metadata 0.5.2 (27), including the approved post-build equipment
odds (+4 0.1%, +5 0.05%, conditional on an equipment drop). This records source verification;
the old signed 0.5.2-27 AAB predates the odds and trusted-clock fixes. No Play publication,
device installation or live database mutation is performed by this verification.

The Git update includes the current application's trait rules v3, Mythic Hall, WebP replacements,
supporting tests, historical deployment migrations and current documentation. Separate VFX/AI
experiments and local evidence remain outside this commit.

## Findings and corrections

1. **High-tier full sets are possible.** Natural progression reproduced a cleric wearing five +4
   and one +5 item at level 117, with full sets through level 120 (191.7 active hours in total).
   A warrior also wore four +4 and two +5 items at level 142, continuing into level 143
   (31.5 active hours), and a paladin wore three of each at level 132, with full sets through
   level 136 (302.5 active hours). **The cleric's level-120 arrival had six +4 items**, so a full
   set does not require any +5 item. Therefore
   neither the loot odds nor the automatic replacement rules guarantee that all six slots cannot
   become +4/+5. This is a balance finding, not evidence that every character or every level is
   saturated. The approved odds are retained; eliminating full sets would require a separate
   gameplay decision, not a weaker assertion in the test.
2. **Trusted-time rebasing omitted new trait clocks.** The v2/v3 evidence timestamps,
   formation-check timestamp and retention anchors were not shifted with the rest of the local
   timeline. A forward correction could age these clocks prematurely; a backward correction
   could delay eligibility/protection expiry. Both forward/backward tests failed before the fix
   and passed afterward. Nullable legacy evidence dates remain nullable.
3. **Backup regression still required an archived output path.** The shared synthetic QA output
   helper now supports `output/current-root-qa` in the active root. It does not access real saves.
4. **Event rarity coverage used old boundaries.** Coverage now checks the new 499/500 and
   1499/1500 boundaries and all one million rolls under the +4 cap. Capped rewards have exactly
   1,500 legendary rolls and zero mythic rolls; the ordinary drop table has 1,000 legendary and
   500 mythic rolls. The shop's full 1,000-roll table has no +4/+5 results.

## Equipment study methodology

- Natural cohort: six classes × four deterministic seeds, each from level 1 until first reaching
  level 150. Real stat rolling, growth, combat, loot, sales, purchases and automatic replacement.
- Events and traits enabled. Half of the natural cohort receives a refreshed synthetic relationship
  roster each active day; half has no roster. No economic reward or high-tier item is injected.
- Every action endpoint is observed, including short-lived equipment changes. Per-level CSV rows
  contain entry/exit loadouts, maximum high-tier slot count and duration at each slot count 0–6.
- Synthetic high-level cohorts separately start with ordinary, level-appropriate gear and class-guided
  stats at levels 300 and 1000, then progress five levels. These are stress scenarios, not observations
  of players who naturally reached those levels. Results must not be pooled with the natural cohort.
- Elapsed days are uninterrupted **active adventure time**, not expected calendar retention. The test
  uses synthetic saves and does not model unpaid offline time or a user's charging schedule.
- Level 150/305/1005 terminal rows describe arrival only; they have no observation duration.
- Finite deterministic cohorts cannot prove that a random outcome is impossible at other seeds,
  unobserved levels or under other Guidance/relationship conditions.

## Reproduction

```sh
./gradlew :app:compileDebugUnitTestKotlin --console=plain
python3 tools/qa/run-equipment-audit.py
python3 tools/qa/summarize-equipment-audit.py
./gradlew -I tools/qa/current-root-regression.init.gradle :app:testDebugUnitTest --console=plain
./gradlew :app:assembleOfflineQa :app:lintRelease --console=plain
./gradlew :app:testOfflineQaUnitTest --tests com.nullplaying.remote.OfflineQaIsolationTest --tests com.nullplaying.engine.PvpLevelBandInputAuditTest --tests 'com.nullplaying.data.SimpleGameRepositoryTest.offline qa queued event survives repository persistence and restart' --console=plain
```

The equipment runner freezes compiled classes and source hashes before starting three independent
JVMs. It starts no Gradle process and uses no production service. Its snapshot directory must be
absent for a new run; archive an existing run before repeating. All raw results and logs remain under
`output/verification/2026-09-15/`. The root regression explicitly excludes archived adventure probes,
independent arena balance/probe laboratories and the separately run equipment study. It is not a
claim that every historical analysis in the repository was rerun.

Local Mythic Hall SQL contracts also cover retries, hourly admission, immutable ordering, newest-100
cap, moderation, ownership rejection, authentication and table/RPC privileges using PGlite.

## Software verification

- Current-root regression: **1,410 passed, 0 failed/errors, 10 conditional skips**, 181 suites.
  See `regression-summary.json` for the exact selection and exclusions.
- Five of those conditional skips were then run in the offline QA variant: **5/5 passed**.
  They cover offline isolation, level-band inputs and queued-event persistence. The remaining
  conditions concern server/battle QA variants or optional device/export inputs.
- Clock-fix reproduction: **2/2 failed before the fix**; focused clock/backup/reward checks:
  **10/10 passed after the fix**. Full regression also includes these tests.
- Offline QA APK: built successfully as `com.nullplaying.adventurepreview`, 0.5.2-offline-qa (27).
  Independent APK manifest readback confirms no Internet permission; generated configuration has
  remote services disabled and empty Supabase credentials. No installation was performed.
- Release lint: **0 errors/fatal findings**, 93 warnings and 7 hints. Release Kotlin compilation
  also completed. This is not a newly signed production AAB verification.
- The 56 added/changed application input files that remain present matched their recorded hashes
  after build; the study also retains a complete Kotlin source hash manifest with its class snapshot.
- Local SQL contracts passed. Source whitespace checks passed.

## Final results

### Natural progression

All **24 characters** reached level 150: **35,445,570 kills** and **336,191,487 observed actions**.
Three characters encountered a full +4/+5 loadout. This is an observed count in four matched seed
domains across six classes, not an estimate from 24 independent player samples.

| Level interval | Maximum +4/+5 slots observed | Levels with full sets |
|---|---:|---|
| 1–19 | 2 / 6 | None observed |
| 20–49 | 4 / 6 | None observed |
| 50–116 | 5 / 6 | None observed |
| 117–150 | 6 / 6 | 117–120, 132–136, 142–143 |

| Level arrival | Mean +4 slots | Mean +5 slots |
|---|---:|---:|
| 10 | 0.083 | 0.042 |
| 20 | 0.125 | 0.042 |
| 50 | 0.292 | 0.292 |
| 100 | 0.917 | 1.000 |
| 150 | 1.292 | 1.875 |

The growth-linked power premium allows rare equipment to survive several level-ups, while the
approved increased drop frequency creates more opportunities to fill the remaining slots. At level
150 the mean combined high-tier loadout is 3.167 of 6 slots; this is materially different from a
promise that high-tier gear remains isolated to one slot or that full sets never occur.

### Synthetic high-level stress and detailed data

All **12 stress scenarios** completed: **3,794,884 kills** and **67,840,942 observed actions**.
Two of six level-1000 starts (warrior and paladin) reached a full high-tier set at levels 1003–1004
and still had it on arrival at 1005. None of the six level-300 starts reached a full set in their
five-level window. These accelerated-start outcomes do not estimate long-term player prevalence.

Combined study: **36 completed scenarios, 404,032,429 observed actions**, with all six isolated
JUnit runs passing. Independent reconciliation confirmed that the per-level action counts and
observed durations sum back to every character trajectory. No production gameplay input was
changed by the study.

- [Every tested level: mean loadouts, maxima and full-set duration](equipment-by-level.csv)
- [All completed synthetic character trajectories](characters.csv)
- [First full-set example per character and level, with exact item acquisition levels](full-sets.csv)
- [Aggregate equipment summary](equipment-summary.json)
- [Current-root test summary and exclusions](regression-summary.json)
- [Additional offline QA tests](offline-qa-summary.json)
- [APK identity, isolation and lint verification](build-verification.json)

The full-set examples record one permutation per character/level; they are not an enumeration of
every later permutation in that level. The measured combined +4/+5 occupancy covers every settled
action endpoint. In particular, a zero count of all-+5 **examples** must not be reported as proof
that all-+5 equipment is impossible.

**Conclusion:** software regression and build validation passed, but a requirement that characters
never equip +4/+5 in all six slots is not satisfied by the current balance. The approved drop-rate
change is preserved, and the observed balance finding is explicitly included in this source update.
