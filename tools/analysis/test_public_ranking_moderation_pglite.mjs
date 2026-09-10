#!/usr/bin/env node
// Executes the production migration chain through unapplied 010 in an in-memory PostgreSQL.
// It never reads a Supabase endpoint, credential, environment file or network client.
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
    [stagedRoot, '202609080010_public_ranking_moderation.sql'],
  ];
  for (const [root, name] of migrations) {
    await db.exec(await readMigration(root, name));
    console.log(`PASS execute ${name}`);
  }

  await db.exec(await readFile(
    path.join(stagedRoot, 'supabase/tests/public_ranking_moderation.sql'), 'utf8',
  ));
  console.log('PASS moderation behavior, ownership, cache revision, legacy and immutable snapshot fixture');

  // PGlite lacks pgTAP. Strict shims execute all catalog assertions and fail on false.
  await db.exec(`
    create schema if not exists extensions;
    create function extensions.plan(test_count integer) returns text
      language plpgsql as $$
    begin
      if test_count <> 17 then raise exception 'unexpected pgTAP plan: %', test_count; end if;
      return '1..' || test_count::text;
    end
    $$;
    create function extensions.ok(assertion boolean, description text) returns text
      language plpgsql as $$
    begin
      if not coalesce(assertion, false) then
        raise exception 'pgTAP assertion failed: %', description;
      end if;
      return 'ok - ' || description;
    end
    $$;
    create function extensions.finish() returns setof text
      language sql as $$ select null::text where false $$;
  `);
  const pgTapSql = (await readFile(
    path.join(stagedRoot, 'supabase/tests/public_ranking_moderation_pgtap.sql'), 'utf8',
  )).replace(/^create extension if not exists pgtap with schema extensions;\s*$/gm, '');
  const assertionCount = (pgTapSql.match(/select\s+extensions\.ok\s*\(/gi) || []).length;
  if (assertionCount !== 17) {
    throw new Error(`pgTAP plan/assertion mismatch: expected 17, found ${assertionCount}`);
  }
  await db.exec(pgTapSql);
  console.log('PASS pgTAP catalog/security assertions through strict PGlite shims (17/17)');
  console.log(JSON.stringify({ migrationsApplied: migrations.length, liveDatabaseWrites: 0 }));
} finally {
  await db.close();
}
