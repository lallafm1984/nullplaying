#!/usr/bin/env node
// Executes the production migration chain through unapplied 009 in an in-memory PostgreSQL.
// It does not read a Supabase URL, token, password or network client.
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
    [stagedRoot, '202609070007_daily_ranking_cache_and_read_boundary.sql'],
    [stagedRoot, '202609070008_daily_arena_ranking_boundary.sql'],
    [stagedRoot, '202609080009_hourly_ranking_publication.sql'],
  ];
  for (const [root, name] of migrations) {
    await db.exec(await readMigration(root, name));
    console.log(`PASS execute ${name}`);
  }

  const postMigration = await db.query(`
    select
      (select count(*)::integer from ranking_private.daily_leaderboard_snapshots)
        as general_snapshots,
      (select count(*)::integer from arena_ranking_private.daily_arena_leaderboard_snapshots)
        as arena_snapshots,
      (select count(*)::integer from cron.job
        where jobname = 'alarmquest-daily-leaderboard' and schedule = '0 * * * *')
        as general_cron,
      (select count(*)::integer from cron.job
        where jobname = 'alarmquest-daily-arena-leaderboard' and schedule = '17 * * * *')
        as arena_cron,
      ranking_private.utc_hour_bucket('2026-09-08 23:59:59+00')
        = '2026-09-08 23:00:00+00'::timestamptz as general_bucket,
      arena_ranking_private.utc_hour_bucket('2026-09-08 08:00:00+00')
        = '2026-09-08 08:00:00+00'::timestamptz as arena_bucket
  `);
  const contract = postMigration.rows[0];
  if (contract.general_snapshots !== 1 || contract.arena_snapshots !== 1 ||
      contract.general_cron !== 1 || contract.arena_cron !== 1 ||
      !contract.general_bucket || !contract.arena_bucket) {
    throw new Error(`009 deployment contract failed: ${JSON.stringify(contract)}`);
  }
  console.log('PASS 009 transition honesty, UTC bucket helpers and separated hourly cron jobs');

  await db.exec(await readFile(
    path.join(stagedRoot, 'supabase/tests/hourly_ranking_snapshots.sql'), 'utf8',
  ));
  console.log('PASS hourly general/arena cutoff, latest outage bucket, no-op, retention and RPC fixture');
  console.log(JSON.stringify({ migrationsApplied: migrations.length, liveDatabaseWrites: 0 }));
} finally {
  await db.close();
}
