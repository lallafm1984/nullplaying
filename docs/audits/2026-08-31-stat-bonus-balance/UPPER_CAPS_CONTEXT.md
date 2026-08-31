# Upper-cap comparison context

User proposal: HP capacity maximum 12h, MP total raw proc maximum 30%,
DEX search minimum 4s, CHA sale multiplier maximum +20%.
Base offline capacity remains 8h. Existing diminishing-return denominators
350 / 750 / 35 / 20 are held fixed to isolate the cap change.

Decision criterion changed from near-minimum class spread to preserving useful
class identities while all classes still benefit from every stat. Distinguish
absolute class gap, overall acceleration, and specialized vs off-stat benefits.
No production implementation, DB access, device registration, or app launch.
Read-only game source hash comparison passed before rerun.

Reference: prior 10h / 28% / 4.25s / +15% recommendation, same four paired
seeds 2..5 across six classes. Replay the proposed cap set to level 100 with
every-level checkpoints. Comparison seeds are reused paired scenarios, not a
new independent holdout after the earlier recommendation.

Report audience: product stakeholders. Roles: answer-first summary, level100
effects and cross-stat overlap, level20/50/100 rate/gold spread, recommendation,
question of cap vs curve shape, assumptions and limits. Source-backed exact
tables retained. MCP viewer previously rejected Python provenance; retain the
full prior report and write a separate comparison supplement, without claiming
successful native chart rendering. Jupyter kernel remains unavailable.

Validation: 24 proposed engine trajectories / 16,838,353 kills, paired against
the prior 24 trajectories with unchanged game-source hashes. Every-level rows
complete (2400 per variant), no duplicates/nulls, tale-growth counts aligned.
Log-grid monotonic/cap checks use 1e-12 floating-point tolerance at extreme
1e18 stats. All three notebook code cells executed sequentially in Python.

Final-context QA: the Markdown supplement was read back; all six classes and
requested four stats are present, exact table values match generated CSVs,
raw proc vs pity and price vs income are separated, and unchanged 8h base/12m
charge assumptions are visible. Native chart QA remains unavailable: the
existing MCP source validator rejects Python provenance, and the HTML builder
was source-verified to invoke that same validator (build_portable_artifact.mjs
line1505). Do not imply successful MCP/HTML rendering; deliver the complete
Markdown comparison with exact tables. No fake SQL or DB connection used to
work around the report tooling limit.
