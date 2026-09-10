# Shared player snapshot deployment runbook

The shared resolver keeps controls off for non-release builds. The reviewed release build now
defaults `ADVENTURE_SYSTEM_ENABLED`, `SHARED_PLAYER_SYNC_ENABLED`, and
`ARENA_SERVER_MATCHING_ENABLED` to on because migrations 004 through 008 are already deployed; an
explicit `false` value remains the rollout and rollback override.
`ADVENTURE_SYSTEM_ENABLED` controls normal events, current adventure traits, the relationship tab
and relationship encounters. `SHARED_PLAYER_SYNC_ENABLED`
controls public snapshot transport, and its gate additionally requires the adventure system flag.
This prevents a shared=true/adventure=false build from uploading data for an invisible feature.
An adventure=true/shared=false build may run ordinary events and traits, but has no remote player
roster and therefore no remote relationship encounter. `ARENA_SERVER_MATCHING_ENABLED` separately
controls use of those projections in the arena and requires both shared sync and remote services.
Arena reads only the installed daily roster cache; starting each match does not make another network
request. A changed complete local profile is published once before that cache is reused, while an
unchanged profile and roster need no Auth or network call for 24 hours. Debug, migration-test, EEA QA
and battle QA override the remote controls to false.
Offline QA alone enables the adventure system and sequential QA preview while keeping shared
transport, URLs, keys and INTERNET disabled. No key is added to the APK; production continues to use
the existing publishable key and anonymous Supabase Auth session.

## Local arena execution boundary

`ARENA_SERVER_MATCHING_ENABLED` means that the local opponent pool may contain a projection from the
cached daily roster. It does not enable server-run combat. Opponent selection, battle seed derivation,
turn simulation, narration, ticket consumption, score change and recent history all execute locally.
The server returns no match-start, action, outcome, reward or settlement response. A current client
uses Auth plus `sync_player_network_profile` and `get_daily_public_player_roster`; the older
`sync_ranking_entries` and `sync_public_player_snapshots` RPCs remain compatible fallbacks for
already-released clients. After a current client has completed the unified contract once, its legacy
ranking writes may only mirror the complete level-20+ subset of the strongly validated shared rows.

The legacy `202609040001_battle_v01_backend.sql` migration is outside this rollout and must not be
applied or merged with this patch. No Edge Function or `battle_*` RPC is required. A valid cached
roster plus unchanged unified-success receipt is returned before Auth or publication work, so repeated
foreground checks and all matches on the same server day add no shared-player network call. A local
level, stat, power, trait, name, class or character-set change invalidates only the publication receipt:
the complete profile is synchronized atomically, then the existing same-day opponent roster is reused.
Character deletion therefore removes both the shared and ranking rows without downloading a second
roster. A requester level change also invalidates its roster and may request a new one.

Arena tickets and match history remain local. The locally calculated score and win/loss/draw totals
are submitted through the separate arena-ranking RPC and published in an immutable server snapshot;
they are not part of the merged profile RPC. The server validates identity, bounds, monotonic battle
counts, rate limits, and trusted receipt time, but it cannot independently prove the locally
calculated winner. The UI therefore shows the server-published rank without describing the battle
result as server-verified. A nonce, result hash, client log, or authenticated request cannot prove a
local winner. A result-authoritative arena would require the server to replay the exact shipped
engine or use a trusted attestation service; the current local result does not authorize economy
rewards.

## Unified publication contract

`sync_player_network_profile` accepts one strict complete array of at most three level-10+ characters.
Each row adds only `slot_id` to the existing public-snapshot shape. Inside one database transaction,
the RPC canonicalizes slot order, runs the existing public-snapshot validation and publication first,
then projects level-20+ rows into the existing combat-power ranking function. A shared-player soft
rejection leaves ranking unchanged; a ranking exception rolls back the preceding shared writes.

This removes the second copy of name, class, level and combat power and one HTTP/Auth exchange on a
daily cache miss. It does not loosen either existing validator and creates no arena table, result
endpoint or retention ledger. Existing unchanged-row and payload-hash checks are deduplication
controls only; they are not signatures and are never used as game authority.

## Public contract

The service accepts level 10+ hero identity, display name, class, level, displayed combat power,
six base attributes, maximum HP/MP and active adventure trait IDs. It does not accept or return
learned-technique state, item metadata, economy, bag contents, current HP/MP, relationship score,
private progress or reward results. The client derives level-appropriate combat techniques and a
bounded power correction locally. Remote battle outcomes never choose economic rewards.
Opponent rows also omit the exact publication timestamp so a stable projection and display name
cannot be combined with millisecond activity tracking. The app timestamps an arena input with the
locally received daily-roster time; snapshot age and expiry use only the roster envelope.

The database creates stable projection IDs, excludes every character owned by the requester,
and freezes one level +/-1 roster for the same requester character, requester level and UTC date.
After the first accepted publication for a character, level cannot decrease and may advance by at
most one level per elapsed 15-minute publication window. A changed level creates a new band, but
the account receives at most six newly generated rosters per UTC day. Six permits each of three
character slots one first roster and one ordinary level-up roster. With 20 newly generated rows per
roster, this bounds one account to 120 newly disclosed opponent rows per day. Candidate selection rotates the
three levels and performs an indexed UUID cursor seek with a wrap query, so it avoids a per-request
full candidate hash sort.

## Deployment order

1. Confirm the live migration history and read back the 004-through-008 RPC and privilege contracts
   before producing the Android release. A normal release enables all three reviewed systems; set an
   explicit flag to `false` if the corresponding live contract is not healthy.
2. Run the repository SQL structural tests and a Supabase migration dry run against an isolated
   staging project. Review every planned object and privilege change.
3. On a fresh staging baseline, apply `202609070004_shared_player_snapshots.sql` after the ordered
   ranking migration that defines `ranking_private.maximum_accepted_combat_power(bigint)`, followed by
   `202609070005_fix_shared_player_runtime_functions.sql`,
   `202609070006_atomic_player_network_profile_sync.sql`, then
   `202609070007_daily_ranking_cache_and_read_boundary.sql`, and finally
   `202609070008_daily_arena_ranking_boundary.sql`. The current live baseline already contains this
   complete ordered 004-through-008 set; do not reapply it. Migration 007 replaces the never-applied
   `202609050001_daily_ranking_snapshots.sql` draft; never put both in one migration history. Migration
   006 remains independent of the unapplied `202609040001_battle_v01_backend.sql` and its endpoints.
4. In staging, verify anonymous Auth, RLS denial for direct table access, self-exclusion, stable
   projection IDs, same-day/same-level roster reuse, monotonic bounded level changes, the six-roster
   UTC-day budget, rate limiting, malformed-row rejection and partial client filtering. Confirm a
   seventh newly generated roster returns `rate_limited`, and an implausible level change returns
   `invalid_level`. Do not seed production data.
   Also prove a unified valid batch updates both existing stores atomically, a shared soft rejection
   leaves ranking unchanged, an empty complete batch removes both stores, legacy RPCs still work,
   and direct authenticated `ranking_entries` reads are denied while both bounded leaderboard RPCs
   keep their response contracts. Stop deliberately after 006 once and prove that a 1,005-way rank tie
   still returns at most 1,000 rows; repeat after 007 and prove the requested own row occupies one of
   those bounded slots.
5. Inspect `EXPLAIN (ANALYZE, BUFFERS)` with synthetic staging cohorts at each supported level.
   The roster plan should use `shared_player_snapshots_seek_idx` for the six bounded seek/wrap
   branches and must not sort the whole level band.
6. If the real PostgreSQL plan, scale check, or live read-back regresses, explicitly disable the
   affected release flag. Static SQL inspection does not replace this deployment gate.
7. Verify 007's immutable UTC snapshot, before-image cutoff, hourly retry, two-snapshot retention,
   stable pseudonymous legacy IDs and unchanged-response cache on representative staging volumes.
8. The reviewed production release enables adventure, shared sync, and arena server matching by
   default. Monitor RPC latency, error rate, snapshot/roster row counts and storage growth; each flag
   can still be disabled independently without rebuilding the database.

## Retention and operations

Each successful publication opportunistically deletes at most 500 expired snapshot rows and 500
expired roster rows using expiry indexes. The migration also defines
`shared_player_private.cleanup_expired_shared_player_data(integer)`: a bounded, SECURITY INVOKER
maintenance hook whose execute permission is revoked from `public`, `anon`, `authenticated`, and
`service_role`. If scheduled cleanup is enabled, invoke it only from a database-owner job, first in
staging, with `select shared_player_private.cleanup_expired_shared_player_data(5000);`. Verify the
owner receives bounded deleted-row counts and that authenticated and service-role test sessions
receive permission denied. Do not grant the function, private schema, or tables to an app or
service role. `sync_limits`, `roster_call_limits`, and
`roster_daily_issuance_limits` each remain bounded to one row per authenticated user and cascade on
user deletion; the daily row overwrites its date and count at the next UTC day. The owner cleanup
also removes each guard after 90 days only when no associated snapshot/roster remains; the shared
sync/ranking guard remains while that user still has a ranking row. Each delete is capped by the
requested cleanup limit and skips rows held by a concurrent client transaction. Ordered timestamp
and activity-expression indexes support the three stale-guard scans; staging must still confirm their
plans with representative account counts before scheduling the owner job.

The sync RPC rejects calls within five seconds per account, separately limits changed publication
to once per 15 minutes, deduplicates an unchanged payload, and rejects level rollback or advance
faster than the elapsed 15-minute windows. The roster RPC rejects calls within five seconds per
authenticated account and consumes the six-per-UTC-day budget only when it must generate a new
roster; reuse of an existing same-day/same-level roster does not consume another issuance. Configure
an additional project/API-gateway quota before enabling production because database guards alone
cannot stop an attacker creating many accounts.

The legacy ranking write RPC now applies a server-statement-time five-second burst guard and a
five-minute changed-payload cooldown. A canonical unchanged retry after the burst window updates only
the bounded guard and leaves ranking rows untouched. A changed request inside the cooldown commits its
new call timestamp, sets PostgREST `response.status=429` plus `Retry-After`, and returns without touching
ranking rows. The HTTP error makes an installed client retain its pending upload while avoiding the
transaction rollback that would otherwise erase the five-second guard. A successful unified call
permanently marks the account as contract
version 1; subsequent legacy ranking writes must exactly match its validated shared level-20+ rows, so
the weaker compatibility payload cannot fork the two stores. The current app republishes the unified
complete profile when its payload changes, including deletion, before it flushes any legacy queue.
Direct authenticated
table SELECT and its allow-all policy are removed; the bounded `get_leaderboard` and
`get_leaderboard_v2` signatures remain available under pinned SECURITY DEFINER contexts. The legacy
table-shaped RPC returns the caller's real `user_id` only for that caller's own row and a stable
deterministic pseudonym for other rows, preserving its non-null UUID response shape without exposing
the raw Auth IDs.
Both 006's live-table bridge and 007's daily-cache replacement reserve the caller's own row inside an
absolute maximum of 1,000 returned rows, including when every participant has the same rank.

Server snapshots expire after 72 hours. Server daily roster JSON expires at the next UTC day and is
then deleted by maintenance. At expiry, account replacement, or a build with shared transport off,
the app removes the full local public roster and any frozen pending-battle stats/traits from both
Room and its fallback save. It may retain only compact public character identity and the
relationship narrative formed from completed encounters in the player's own local adventure save;
the server never stores the resulting relationship score or narrative history.

Newly generated rosters contain at most 20 remote candidates; a client fills only the missing count
from its deterministic local English reserve catalog. A legacy same-day row may still contain up to
24 candidates until its normal UTC expiry, so the compatibility parser accepts it while gameplay
uses at most 20. New and changed daily roster rows also have a 24 KiB ceiling. Migration 006 adds the constraint as
`NOT VALID` so an exceptional active legacy row cannot block deployment, while a before-write trigger
and the constraint reject every oversized new roster. The migration validates the constraint
immediately when no legacy violation exists. If it remains unvalidated, wait through the next UTC
roster expiry, run the bounded owner cleanup, confirm there are no rows above 24 KiB, then validate it
in a separately reviewed maintenance statement. Do not delete an active legacy row just to complete
deployment.

Fresh anonymous accounts can still submit client-declared, bounded public projections after reaching
the feature gate. Database validation cannot prove offline progression or stop a farm of distinct
accounts. Keep these projections non-authoritative, exclude them from rewards and official arena
ranking, and enforce per-project Auth/API quotas before rollout. The current MD5 payload hashes only
deduplicate one account's own upload; collision resistance is not treated as a security boundary.

## Rollback

1. Set `SHARED_PLAYER_SYNC_ENABLED=false` first to halt publication. Set
   `ADVENTURE_SYSTEM_ENABLED=false` as well if ordinary adventure events/traits must be rolled back.
   Set `ARENA_SERVER_MATCHING_ENABLED=false` to stop arena use of cached server projections.
2. Revoke authenticated execute on the unified and two legacy shared-player RPCs if an immediate
   server stop is
   required.
3. Preserve the ranking compatibility RPCs and existing data. Reverse 006 privilege/column/constraint
   changes only through a separately reviewed rollback migration after active clients are disabled.

Migration 006 changes no existing ranking row during deployment and creates no arena result data.
Migration 007 creates an honestly timestamped bootstrap snapshot and hourly idempotent publication job;
it does not accept or settle any arena result.
