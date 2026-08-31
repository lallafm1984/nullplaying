# Stat bonus balance study — 2026-08-31

## Decision and authority

Choose a provisional, source-backed HP/offline-capacity, MP/skill-proc,
DEX/search-duration, and CHA/sale-value bonus model for all six classes.
The user changed the planned base offline capacity from 12 hours to 8 hours.
Interpretation: 8 hours is capacity, not login frequency; clarification was sent.
No production implementation, deployment, app launch, device registration,
database reads/writes, accounts, analytics events, or remote configuration writes.
All simulated heroes exist only in process memory and aggregate local files.

Authoritative inputs: current local engine/model/catalog source, not old design
simulators and not live server values. Baseline means the current mechanics with
the user-proposed 8-hour capacity. Existing 12-hour operation is a separate
counterfactual, not mixed into the baseline. Charge-to-full remains 12 minutes
as an explicit unconfirmed design assumption; evaluate full and short sessions.

## Study metrics and design guardrails

- Primary: worst class mean max/min gap of XP/elapsed day (progress proxy), at
  matched level/equipment/maturity and login interval; separate 4h/8h/12h/24h.
- Secondary: sale gold/day class gap, measured separately from gross quest gold,
  and skill uses/hour (not treated as a second independent XP reward).
- Drivers: combat/kill duration, search duration, bag-return overhead, effective
  proc including 15-basic pity, offline covered fraction, sale multiplier.
- Compare both absolute class spread and change relative to each class baseline;
  do not hide pre-existing class differences behind normalized uplifts.
- Nontriviality: require visible high-level bonus effects; zero/near-zero bonuses
  are not acceptable simply because they minimize spread.
- Safety: bonuses are monotone, nonnegative, bounded; new mechanics must not
  silently lower the existing class-based proc rate. Retain pity. Search affects
  the 5-second search only, not the 2-second reveal or attack animation. CHA is
  sales only. HP raises capacity and charging rate, not granted balance, XP rate,
  or retroactive covered offline time.
- Candidate optimum is conditional on model, grid, metrics and weights; no
  universal mathematically optimal or live-validated claim.

## Known caveats to carry forward

- StableRng uses low bits: independent 3d6/growth probability tables are not
  actual engine distributions. Use current RNG and real engine snapshots and
  expose any approximation in the candidate model.
- Adventure Tale completion grants another class-guided growth without a level;
  include actual trajectory evidence and test bonus-growth sensitivity.
- No real player login/ad/reroll distribution is available or queried. Use an
  explicit scenario matrix, do not invent population shares.
- Do not convert sales uplift directly into XP uplift without modeling shops.
- HP capacity benefit is zero when the session gap is already covered. Do not
  tune only one login pattern or claim convenience is equivalent to damage.

## Artifact and report plan

Route: product-business-analysis with context, KPI design, reproducible notebook,
validation, visualization, and one build-report surface. Audience: product
stakeholders. Report roles: title; Executive Summary; numerical evidence with
definitions and class/level/login cuts; recommendation; open questions; caveats.
Native bar charts for class comparisons and line charts for level progression;
lookup tables for exact formula constants. Source/QA notes remain local here.
