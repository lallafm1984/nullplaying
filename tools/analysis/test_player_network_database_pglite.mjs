#!/usr/bin/env node
// Executes the merged ranking/shared-player database contract in an in-memory PostgreSQL engine.
// No Supabase URL, token, database password, or network client is loaded.
import { access, readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const stagedRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const baselineName = '202608230001_rankings_and_session_logs.sql';
const stagedHasBaseline = await access(path.join(stagedRoot, 'supabase/migrations', baselineName))
  .then(() => true, () => false);
const baselineRoot = stagedHasBaseline ? stagedRoot : path.resolve(stagedRoot, '../../../..');
const require = createRequire(import.meta.url);
let PGlite;
try {
  ({ PGlite } = require(process.env.PGLITE_MODULE_PATH || '@electric-sql/pglite'));
} catch {
  throw new Error(
    'Install @electric-sql/pglite in a temporary directory and set PGLITE_MODULE_PATH to that package directory.',
  );
}

const db = new PGlite();
const migration = (root, name) => path.join(root, 'supabase/migrations', name);
const readMigration = async (root, name) => (await readFile(migration(root, name), 'utf8'))
  .replace(/^create extension if not exists pg_cron with schema pg_catalog;\s*$/gm, '');

async function expectRejected(action, pattern, message) {
  try {
    await action();
  } catch (error) {
    if (pattern.test(String(error))) return;
    throw error;
  }
  throw new Error(message);
}

try {
  await db.exec(`
    create role anon nologin;
    create role authenticated nologin;
    create role service_role nologin;
    create schema auth;
    create table auth.users (
      id uuid primary key,
      created_at timestamptz not null default statement_timestamp(),
      raw_user_meta_data jsonb not null default '{}'::jsonb
    );
    create function auth.uid() returns uuid language sql stable set search_path = '' as $$
      select nullif(pg_catalog.current_setting('request.jwt.claim.sub', true), '')::uuid
    $$;
    create schema cron;
    create table cron.job (
      jobid bigserial primary key,
      jobname text not null,
      schedule text not null,
      command text not null
    );
    create function cron.unschedule(bigint) returns boolean language sql as $$
      delete from cron.job where jobid = $1 returning true
    $$;
    create function cron.schedule(text, text, text) returns bigint language sql as $$
      insert into cron.job(jobname, schedule, command) values ($1, $2, $3) returning jobid
    $$;
  `);

  const migrations = [
    [baselineRoot, '202608230001_rankings_and_session_logs.sql'],
    [baselineRoot, '202608230004_ranking_top1000_character_sync.sql'],
    [baselineRoot, '202608230005_secure_atomic_ranking_sync.sql'],
    [baselineRoot, '202608240001_compact_ranking_and_session_retention.sql'],
    [baselineRoot, '202608240002_strict_level_ranking_ceiling.sql'],
    [baselineRoot, '202608270001_localized_ranking_gatekeepers.sql'],
    [stagedRoot, '202609070004_shared_player_snapshots.sql'],
    [stagedRoot, '202609070005_fix_shared_player_runtime_functions.sql'],
    [stagedRoot, '202609070006_atomic_player_network_profile_sync.sql'],
  ];
  for (const [root, name] of migrations) {
    await db.exec(await readMigration(root, name));
    console.log(`PASS execute ${name}`);
  }

  // A migration deploys one file per transaction. Prove 006 is independently safe if 007 fails:
  // a 1,005-way tie must not make its owner-privileged compatibility RPC return the full table.
  const interimUser = '99999999-9999-4999-8999-999999999999';
  const interimCharacter = '88888888-8888-4888-8888-888888888888';
  await db.exec(`
    insert into auth.users(id)
    select case when n = 1005 then '${interimUser}'::uuid
                else pg_catalog.md5('interim-user:' || n::text)::uuid end
      from pg_catalog.generate_series(1, 1005) as n;
    insert into public.ranking_entries(
      user_id, character_id, slot_id, display_name, hero_class, level, combat_power, achieved_at
    )
    select case when n = 1005 then '${interimUser}'::uuid
                else pg_catalog.md5('interim-user:' || n::text)::uuid end,
           case when n = 1005 then '${interimCharacter}'::uuid
                else pg_catalog.md5('interim-character:' || n::text)::uuid end,
           1, 'Tie' || n::text, 'WARRIOR', 20, 100,
           '2026-09-08T00:00:00Z'::timestamptz + n * interval '1 millisecond'
      from pg_catalog.generate_series(1, 1005) as n;
    set role authenticated;
    set "request.jwt.claim.sub" = '${interimUser}';
  `);
  const interimRows = await db.query(
    'select * from public.get_leaderboard($1::uuid, 1000)', [interimCharacter],
  );
  const interimCompact = await db.query(
    'select public.get_leaderboard_v2($1::uuid, 1000) as response', [interimCharacter],
  );
  if (interimRows.rows.length !== 1000 || interimCompact.rows[0].response.e.length !== 1000 ||
      !interimRows.rows.some((row) => row.character_id === interimCharacter)) {
    throw new Error(`006 compatibility readers were not strictly bounded: ${JSON.stringify({
      rows: interimRows.rows.length,
      compact: interimCompact.rows[0].response.e.length,
    })}`);
  }
  await db.exec(`
    reset role;
    delete from public.ranking_entries;
    delete from auth.users;
  `);
  console.log('PASS 006 standalone 1,000-row cap with 1,005-way tie and own-row retention');

  await db.exec(await readMigration(
    stagedRoot, '202609070007_daily_ranking_cache_and_read_boundary.sql',
  ));
  console.log('PASS execute 202609070007_daily_ranking_cache_and_read_boundary.sql');

  // Reuse the exhaustive daily-cutoff fixture. Its transaction rolls back all fixture rows.
  const dailyFixtureRoot = await access(
    path.join(stagedRoot, 'supabase/tests/daily_ranking_snapshots.sql'),
  ).then(() => stagedRoot, () => baselineRoot);
  await db.exec(await readFile(
    path.join(dailyFixtureRoot, 'supabase/tests/daily_ranking_snapshots.sql'), 'utf8',
  ));
  console.log('PASS daily UTC cutoff/concurrency model, tie, cache, ACL, retention and rollback fixture');

  const ownerChecks = await db.query(`
    select
      pg_catalog.has_function_privilege(
        'authenticated', 'public.sync_player_network_profile(jsonb)', 'EXECUTE'
      ) as auth_profile,
      pg_catalog.has_function_privilege(
        'anon', 'public.sync_player_network_profile(jsonb)', 'EXECUTE'
      ) as anon_profile,
      pg_catalog.has_table_privilege(
        'authenticated', 'public.ranking_entries', 'SELECT'
      ) as auth_direct_ranking,
      (
        select convalidated from pg_catalog.pg_constraint
         where conname = 'daily_rosters_snapshots_wire_size_v2_check'
      ) as roster_cap_validated,
      (
        select pg_catalog.count(*)::integer from cron.job
         where jobname = 'alarmquest-daily-leaderboard'
           and schedule = '0 * * * *'
      ) as cron_jobs,
      pg_catalog.strpos(
        pg_catalog.pg_get_functiondef(
          'ranking_private.sync_ranking_entries(jsonb)'::pg_catalog.regprocedure
        ),
        'response.status'
      ) > 0 and pg_catalog.strpos(
        pg_catalog.pg_get_functiondef(
          'ranking_private.sync_ranking_entries_outcome(jsonb,boolean)'::pg_catalog.regprocedure
        ),
        'ranking_last_called_at = v_now'
      ) > 0 and not pg_catalog.has_function_privilege(
        'authenticated',
        'ranking_private.sync_ranking_entries_outcome(jsonb,boolean)',
        'EXECUTE'
      ) as ranking_guard_preserved,
      pg_catalog.strpos(
        pg_catalog.pg_get_functiondef(
          'public.get_leaderboard(uuid,integer)'::pg_catalog.regprocedure
        ),
        'ranking-public-v1:'
      ) > 0 as legacy_user_id_pseudonymized
  `);
  const contract = ownerChecks.rows[0];
  if (!contract.auth_profile || contract.anon_profile || contract.auth_direct_ranking ||
      !contract.roster_cap_validated || contract.cron_jobs !== 1 || !contract.ranking_guard_preserved ||
      !contract.legacy_user_id_pseudonymized) {
    throw new Error(`catalog contract failed: ${JSON.stringify(contract)}`);
  }

  const userId = '11111111-1111-4111-8111-111111111111';
  const characterId = '22222222-2222-4222-8222-222222222222';
  const validProfile = (id, displayName = 'ProfileTester') => JSON.stringify([{
    character_id: id,
    slot_id: 1,
    display_name: displayName,
    hero_class: 'WARRIOR',
    level: 10000,
    combat_power: 49996,
    rules_version: 1,
    snapshot_version: 1,
    stats: {
      strength: 3350,
      constitution: 3350,
      dexterity: 3350,
      intelligence: 3350,
      wisdom: 3350,
      charisma: 3350,
      max_health: 200000,
      max_mana: 100000,
    },
    adventure_trait_ids: [],
  }]);

  await db.exec(`insert into auth.users(id) values ('${userId}')`);
  await db.exec(`set role authenticated; set "request.jwt.claim.sub" = '${userId}'`);
  const accepted = await db.query(
    'select public.sync_player_network_profile($1::jsonb) as response',
    [validProfile(characterId)],
  );
  if (accepted.rows[0].response.accepted !== true ||
      accepted.rows[0].response.ranking_synced_count !== 1) {
    throw new Error(`unexpected unified receipt: ${JSON.stringify(accepted.rows[0])}`);
  }
  await db.exec('begin');
  await db.query('select public.sync_ranking_entries($1::jsonb)', [JSON.stringify([{
      character_id: characterId,
      slot_id: 1,
      display_name: 'ProfileTester',
      hero_class: 'WARRIOR',
      level: 10000,
      combat_power: 49996,
    }])]);
  const burstResponse = await db.query(
    "select current_setting('response.status', true) as status",
  );
  if (burstResponse.rows[0].status !== '429') {
    throw new Error(`legacy ranking burst did not set HTTP 429: ${JSON.stringify(burstResponse.rows[0])}`);
  }
  await db.exec('rollback');

  // A changed legacy payload inside five minutes must also return HTTP 429, but unlike RAISE it
  // must commit the new call timestamp so repeated valid payloads stay under the account bound.
  await db.exec(`
    reset role;
    update shared_player_private.sync_limits
       set ranking_last_called_at = statement_timestamp() - interval '10 seconds',
           ranking_last_synced_at = statement_timestamp() - interval '2 minutes'
     where user_id = '${userId}';
    set role authenticated;
    set "request.jwt.claim.sub" = '${userId}';
    begin;
  `);
  await db.query('select public.sync_ranking_entries($1::jsonb)', [JSON.stringify([{
    character_id: characterId,
    slot_id: 1,
    display_name: 'ChangedDuringCooldown',
    hero_class: 'WARRIOR',
    level: 10000,
    combat_power: 49996,
  }])]);
  const cooldownResponse = await db.query(
    "select current_setting('response.status', true) as status",
  );
  if (cooldownResponse.rows[0].status !== '429') {
    throw new Error(`changed cooldown did not set HTTP 429: ${JSON.stringify(cooldownResponse.rows[0])}`);
  }
  await db.exec('commit; reset role');
  const committedCooldown = await db.query(`
    select statement_timestamp() - guard.ranking_last_called_at < interval '2 seconds' as guarded,
           ranking.display_name
      from shared_player_private.sync_limits as guard
      join public.ranking_entries as ranking on ranking.user_id = guard.user_id
     where guard.user_id = '${userId}'
  `);
  if (!committedCooldown.rows[0].guarded ||
      committedCooldown.rows[0].display_name !== 'ProfileTester') {
    throw new Error(`changed cooldown guard did not commit safely: ${JSON.stringify(committedCooldown.rows[0])}`);
  }
  await db.exec(`set role authenticated; set "request.jwt.claim.sub" = '${userId}'`);
  await expectRejected(
    () => db.query('select count(*) from public.ranking_entries'),
    /permission denied/i,
    'authenticated direct ranking read was not denied',
  );
  const legacy = await db.query(
    'select * from public.get_leaderboard($1::uuid, 1000)', [characterId],
  );
  const compact = await db.query(
    'select public.get_leaderboard_v2($1::uuid, 1000) as response', [characterId],
  );
  const daily = await db.query('select public.get_daily_leaderboard(null) as response');
  if (!Array.isArray(legacy.rows) || compact.rows[0].response.e == null ||
      !daily.rows[0].response.snapshot_id) {
    throw new Error('bounded ranking compatibility RPC failed');
  }
  await db.exec('reset role');

  const stored = await db.query(`
    select
      (select count(*) from shared_player_private.snapshots where user_id = '${userId}')::integer
        as snapshots,
      (select count(*) from public.ranking_entries where user_id = '${userId}')::integer
        as rankings
  `);
  if (stored.rows[0].snapshots !== 1 || stored.rows[0].rankings !== 1) {
    throw new Error(`unified stores diverged: ${JSON.stringify(stored.rows[0])}`);
  }

  // Regression: A successful profile A followed by a changed profile B during the ranking
  // cooldown must not commit B to shared snapshots while the legacy-compatible ranking layer
  // normal-returns HTTP 429. Age only the shared guard so the old broken ordering would accept B
  // on the shared side, then verify snapshot, ranking, marker and accepted hashes stay on A.
  const cooldownAtomicUser = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
  const cooldownAtomicCharacter = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
  await db.exec(`insert into auth.users(id) values ('${cooldownAtomicUser}')`);
  await db.exec(`set role authenticated; set "request.jwt.claim.sub" = '${cooldownAtomicUser}'`);
  const profileA = await db.query(
    'select public.sync_player_network_profile($1::jsonb) as response',
    [validProfile(cooldownAtomicCharacter, 'AtomicProfileA')],
  );
  if (profileA.rows[0].response.accepted !== true) {
    throw new Error(`atomic cooldown setup A failed: ${JSON.stringify(profileA.rows[0])}`);
  }
  await db.exec('reset role');
  await db.exec(`
    update shared_player_private.sync_limits
       set last_called_at = statement_timestamp() - interval '20 minutes',
           last_synced_at = statement_timestamp() - interval '20 minutes',
           ranking_last_called_at = statement_timestamp() - interval '10 seconds',
           ranking_last_synced_at = statement_timestamp() - interval '2 minutes'
     where user_id = '${cooldownAtomicUser}';
  `);
  const beforeCooldown = await db.query(`
    select payload_hash, ranking_payload_hash, last_called_at, last_synced_at,
           ranking_last_synced_at, ranking_last_called_at, profile_contract_version
      from shared_player_private.sync_limits
     where user_id = '${cooldownAtomicUser}'
  `);
  await db.exec(`
    set role authenticated;
    set "request.jwt.claim.sub" = '${cooldownAtomicUser}';
    begin;
  `);
  const profileB = await db.query(
    'select public.sync_player_network_profile($1::jsonb) as response',
    [validProfile(cooldownAtomicCharacter, 'AtomicProfileB')],
  );
  const unifiedCooldownStatus = await db.query(
    "select current_setting('response.status', true) as status",
  );
  if (profileB.rows[0].response.accepted !== false ||
      profileB.rows[0].response.rate_limited !== true ||
      profileB.rows[0].response.synced_count !== -1 ||
      unifiedCooldownStatus.rows[0].status !== '429') {
    throw new Error(`changed unified cooldown did not reject atomically: ${JSON.stringify({
      response: profileB.rows[0].response,
      status: unifiedCooldownStatus.rows[0].status,
    })}`);
  }
  await db.exec('commit; reset role');
  const afterCooldown = await db.query(`
    select snapshot.display_name as snapshot_name,
           ranking.display_name as ranking_name,
           guard.payload_hash,
           guard.ranking_payload_hash,
           guard.last_called_at,
           guard.last_synced_at,
           guard.ranking_last_synced_at,
           guard.ranking_last_called_at,
           guard.profile_contract_version
      from shared_player_private.sync_limits as guard
      join shared_player_private.snapshots as snapshot
        on snapshot.user_id = guard.user_id
      join public.ranking_entries as ranking
        on ranking.user_id = guard.user_id
       and ranking.character_id = snapshot.character_id
     where guard.user_id = '${cooldownAtomicUser}'
  `);
  const beforeAtomic = beforeCooldown.rows[0];
  const afterAtomic = afterCooldown.rows[0];
  if (afterAtomic.snapshot_name !== 'AtomicProfileA' ||
      afterAtomic.ranking_name !== 'AtomicProfileA' ||
      afterAtomic.payload_hash !== beforeAtomic.payload_hash ||
      afterAtomic.ranking_payload_hash !== beforeAtomic.ranking_payload_hash ||
      String(afterAtomic.last_called_at) !== String(beforeAtomic.last_called_at) ||
      String(afterAtomic.last_synced_at) !== String(beforeAtomic.last_synced_at) ||
      String(afterAtomic.ranking_last_synced_at) !== String(beforeAtomic.ranking_last_synced_at) ||
      afterAtomic.profile_contract_version !== beforeAtomic.profile_contract_version ||
      afterAtomic.profile_contract_version !== 1 ||
      !(new Date(afterAtomic.ranking_last_called_at) > new Date(beforeAtomic.ranking_last_called_at))) {
    throw new Error(`unified cooldown split stores or receipt: ${JSON.stringify({
      before: beforeAtomic,
      after: afterAtomic,
    })}`);
  }
  console.log('PASS unified ranking cooldown preserves snapshot/ranking/marker/receipt atomically');

  // Force the second half of the bridge to fail and prove that the preceding shared write rolls back.
  const rollbackUser = '33333333-3333-4333-8333-333333333333';
  const rollbackCharacter = '44444444-4444-4444-8444-444444444444';
  await db.exec(`
    insert into auth.users(id) values ('${rollbackUser}');
    create function public.reject_atomic_qa_row() returns trigger language plpgsql as $$
    begin
      if new.display_name = 'ForceRollback' then raise exception 'forced ranking failure'; end if;
      return new;
    end $$;
    create trigger reject_atomic_qa_row before insert or update on public.ranking_entries
      for each row execute function public.reject_atomic_qa_row();
    set role authenticated;
    set "request.jwt.claim.sub" = '${rollbackUser}';
  `);
  await expectRejected(
    () => db.query('select public.sync_player_network_profile($1::jsonb)', [
      validProfile(rollbackCharacter, 'ForceRollback'),
    ]),
    /forced ranking failure/i,
    'forced ranking error did not escape the atomic bridge',
  );
  await db.exec('reset role');
  const rolledBack = await db.query(`
    select
      (select count(*) from shared_player_private.snapshots where user_id = '${rollbackUser}')::integer
        as snapshots,
      (select count(*) from public.ranking_entries where user_id = '${rollbackUser}')::integer
        as rankings
  `);
  if (rolledBack.rows[0].snapshots !== 0 || rolledBack.rows[0].rankings !== 0) {
    throw new Error(`atomic rollback failed: ${JSON.stringify(rolledBack.rows[0])}`);
  }
  await db.exec('drop trigger reject_atomic_qa_row on public.ranking_entries;');
  await db.exec('drop function public.reject_atomic_qa_row();');

  // Once upgraded, the legacy ranking RPC cannot diverge from the strongly validated projection.
  const upgradedUser = '55555555-5555-4555-8555-555555555555';
  const upgradedCharacter = '66666666-6666-4666-8666-666666666666';
  await db.exec(`insert into auth.users(id) values ('${upgradedUser}')`);
  await db.exec(`set role authenticated; set "request.jwt.claim.sub" = '${upgradedUser}'`);
  await db.query('select public.sync_player_network_profile($1::jsonb)', [
    validProfile(upgradedCharacter, 'StrongProfile'),
  ]);
  await db.exec('reset role');
  await db.exec(`
    update shared_player_private.sync_limits
       set last_called_at = statement_timestamp() - interval '20 minutes',
           last_synced_at = statement_timestamp() - interval '20 minutes',
           ranking_last_called_at = statement_timestamp() - interval '20 minutes',
           ranking_last_synced_at = statement_timestamp() - interval '20 minutes'
     where user_id = '${upgradedUser}';
    set role authenticated;
    set "request.jwt.claim.sub" = '${upgradedUser}';
  `);
  await expectRejected(
    () => db.query('select public.sync_ranking_entries($1::jsonb)', [JSON.stringify([{
      character_id: upgradedCharacter,
      slot_id: 1,
      display_name: 'WeakerBypass',
      hero_class: 'WARRIOR',
      level: 10000,
      combat_power: 49996,
    }])]),
    /does not match validated public snapshots/i,
    'upgraded account bypassed the strong shared-snapshot contract',
  );
  await db.exec('reset role');
  const unchangedStrong = await db.query(`
    select display_name, profile_contract_version
      from public.ranking_entries as ranking
      join shared_player_private.sync_limits as guard on guard.user_id = ranking.user_id
     where ranking.user_id = '${upgradedUser}'
  `);
  if (unchangedStrong.rows[0].display_name !== 'StrongProfile' ||
      unchangedStrong.rows[0].profile_contract_version !== 1) {
    throw new Error(`strong profile marker or row changed: ${JSON.stringify(unchangedStrong.rows[0])}`);
  }
  await db.exec(`set role authenticated; set "request.jwt.claim.sub" = '${upgradedUser}'`);
  await db.query('select public.sync_player_network_profile($1::jsonb)', ['[]']);
  await db.exec('reset role');
  const emptied = await db.query(`
    select
      (select count(*) from shared_player_private.snapshots where user_id = '${upgradedUser}')::integer
        as snapshots,
      (select count(*) from public.ranking_entries where user_id = '${upgradedUser}')::integer
        as rankings,
      (select profile_contract_version from shared_player_private.sync_limits
        where user_id = '${upgradedUser}') as profile_contract_version
  `);
  if (emptied.rows[0].snapshots !== 0 || emptied.rows[0].rankings !== 0 ||
      emptied.rows[0].profile_contract_version !== 1) {
    throw new Error(`atomic empty profile failed: ${JSON.stringify(emptied.rows[0])}`);
  }

  await db.exec(`
    insert into auth.users(id)
      select md5('opponent-user-' || g::text)::uuid from generate_series(1, 25) as g;
    insert into shared_player_private.snapshots (
      user_id, character_id, display_name, hero_class, level, combat_power,
      rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
    )
    select md5('opponent-user-' || g::text)::uuid,
           md5('opponent-character-' || g::text)::uuid,
           'Opponent' || g::text, 'WARRIOR', 10000, 49996, 1, 1,
           '{"strength":3350,"constitution":3350,"dexterity":3350,"intelligence":3350,"wisdom":3350,"charisma":3350,"max_health":200000,"max_mana":100000}'::jsonb,
           '[]'::jsonb, statement_timestamp(), statement_timestamp() + interval '72 hours'
      from generate_series(1, 25) as g;
    set role authenticated;
    set "request.jwt.claim.sub" = '${userId}';
  `);
  const roster = await db.query(
    'select public.get_daily_public_player_roster($1::uuid, 1) as response', [characterId],
  );
  if (roster.rows[0].response.snapshots.length !== 20) {
    throw new Error(`new roster was not capped at 20: ${roster.rows[0].response.snapshots.length}`);
  }
  await db.exec('reset role');

  await expectRejected(
    () => db.query(`
      insert into shared_player_private.daily_rosters (
        user_id, requester_character_id, rules_version, roster_date_utc,
        requester_level, snapshots, generated_at, valid_until
      ) values (
        $1::uuid, $2::uuid, 1, (statement_timestamp() at time zone 'UTC')::date,
        10000, jsonb_build_array(jsonb_build_object('padding', repeat('x', 25000))),
        statement_timestamp(), statement_timestamp() + interval '1 day'
      )
    `, [rollbackUser, rollbackCharacter]),
    /wire-size limit|wire_size_v2_check/i,
    'oversized roster write was not denied',
  );

  await db.exec('set role anon; reset "request.jwt.claim.sub"');
  await expectRejected(
    () => db.query('select public.sync_player_network_profile($1::jsonb)', ['[]']),
    /permission denied|authentication required/i,
    'anonymous profile publication was not denied',
  );
  await db.exec('reset role');

  console.log(JSON.stringify({
    migrationsApplied: 10,
    atomicProfileAccepted: true,
    forcedAtomicRollback: true,
    unifiedCooldownAtomic: true,
    strongLegacyBypassDenied: true,
    atomicEmptyProfileAccepted: true,
    legacyBurstDenied: true,
    legacyCooldownGuardCommitted: true,
    anonymousRpcDenied: true,
    directRankingReadDenied: true,
    boundedRankingRpcsReadable: true,
    rosterSize: roster.rows[0].response.snapshots.length,
    oversizedRosterDenied: true,
    dailyRankingGuardPreserved: true,
    cronJobs: contract.cron_jobs,
  }));
} finally {
  await db.close();
}
