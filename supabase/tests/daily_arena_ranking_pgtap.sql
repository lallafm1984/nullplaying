-- Run with `supabase test db` after migrations 004..008 in an isolated local Supabase stack.
begin;
create extension if not exists pgtap with schema extensions;
select extensions.plan(21);

select extensions.ok(
  pg_catalog.to_regnamespace('arena_ranking_private') is not null,
  'arena ranking data has a private schema'
);

select extensions.ok(
  pg_catalog.to_regclass('arena_ranking_private.arena_season_standings_profile_idx') is not null
  and (
    select indexdef like '%(user_id, character_id)%'
      from pg_catalog.pg_indexes
     where schemaname = 'arena_ranking_private'
       and indexname = 'arena_season_standings_profile_idx'
  ),
  'profile mirror and Auth cascade use a user-character standing index'
);

select extensions.ok(
  pg_catalog.to_regclass(
    'arena_ranking_private.arena_season_character_bindings_user_idx'
  ) is not null
  and (
    select indexdef like '%(user_id, season_id, binding_slot)%'
      from pg_catalog.pg_indexes
     where schemaname = 'arena_ranking_private'
       and indexname = 'arena_season_character_bindings_user_idx'
  ),
  'binding account probes and Auth cascade use a user-leading index'
);

select extensions.ok(
  (
    select pg_catalog.count(*) = 8
      from pg_catalog.pg_class as relation
      join pg_catalog.pg_namespace as namespace on namespace.oid = relation.relnamespace
     where namespace.nspname = 'arena_ranking_private'
       and relation.relkind in ('r','p')
       and relation.relname in (
         'daily_arena_leaderboard_snapshots','daily_arena_leaderboard_state',
         'season_standings','season_character_bindings','account_call_limits','entry_sync_limits',
         'daily_arena_leaderboard_rows','daily_arena_before_images'
       )
       and relation.relrowsecurity
  ),
  'all eight private arena tables have RLS enabled'
);

select extensions.ok(
  pg_catalog.to_regprocedure(
    'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'
  ) is not null,
  'fixed-size arena standing RPC exists'
);

select extensions.ok(
  pg_catalog.to_regprocedure('public.get_daily_arena_leaderboard(text)') is not null,
  'conditional daily arena read RPC exists'
);

select extensions.ok(
  pg_catalog.has_function_privilege(
    'authenticated',
    'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)',
    'EXECUTE'
  ) and not pg_catalog.has_function_privilege(
    'anon',
    'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)',
    'EXECUTE'
  ),
  'only authenticated clients can submit compact arena standings'
);

select extensions.ok(
  pg_catalog.has_function_privilege(
    'authenticated', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
  ) and not pg_catalog.has_function_privilege(
    'anon', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
  ),
  'only authenticated clients can read the daily arena board'
);

select extensions.ok(
  not pg_catalog.has_table_privilege(
    'authenticated', 'arena_ranking_private.season_standings', 'SELECT'
  ) and not pg_catalog.has_table_privilege(
    'service_role', 'arena_ranking_private.season_standings', 'SELECT'
  ) and not pg_catalog.has_table_privilege(
    'authenticated', 'arena_ranking_private.season_character_bindings', 'SELECT'
  ),
  'arena standings and reusable server bindings have no direct read grant'
);

select extensions.ok(
  pg_catalog.has_function_privilege(
    'service_role',
    'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)',
    'EXECUTE'
  ) and not pg_catalog.has_function_privilege(
    'authenticated',
    'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)',
    'EXECUTE'
  ),
  'only the server role can invoke arena publication'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    'shared_player_private.snapshots'
  ) > 0,
  'arena identity and ownership derive from shared snapshots'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    'snapshot.level between 10 and 10000'
  ) > 0,
  'arena ranking begins at level ten'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    'season_character_bindings'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    'candidate.binding_slot'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    '''error_code'', ''season_character_limit'''
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    'previous.profile_expires_at <= v_now'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    'p_completed_battles <> 10'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::regprocedure
    ),
    '''error_code'', ''replacement_requires_placement'''
  ) > 0,
  'one account has three reusable server slots and replacement requires exact placement ten'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)'::regprocedure
    ),
    'row.list_index < 997'
  ) > 0,
  'daily publication precomputes at most 997 global rows'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.pg_get_functiondef('public.get_daily_arena_leaderboard(text)'::regprocedure),
    'limit 3'
  ) > 0,
  'daily reads reserve at most three own rows inside the absolute 1000-row cap'
);

select extensions.ok(
  exists (
    select 1 from pg_catalog.pg_trigger
     where tgrelid = 'shared_player_private.snapshots'::regclass
       and tgname = 'shared_snapshot_mirror_arena_identity_upsert'
       and not tgisinternal
  ) and exists (
    select 1 from pg_catalog.pg_trigger
     where tgrelid = 'shared_player_private.snapshots'::regclass
       and tgname = 'shared_snapshot_expire_arena_identity_delete'
       and not tgisinternal
  ),
  'shared profile insert/update reactivates and delete expires arena standings'
);

select extensions.ok(
  exists (
    select 1 from pg_catalog.pg_constraint
     where conrelid = 'arena_ranking_private.season_standings'::regclass
       and contype = 'f'
       and confrelid = 'auth.users'::regclass
       and confdeltype = 'c'
  ) and not exists (
    select 1 from pg_catalog.pg_constraint
     where conrelid = 'arena_ranking_private.season_standings'::regclass
       and contype = 'f'
       and confrelid = 'shared_player_private.snapshots'::regclass
  ),
  'arena anti-cheat standing outlives profile rows but cascades with its Auth account'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    )),
      'tg_op = ''delete'''
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    )),
    'profile_expires_at = pg_catalog.statement_timestamp()'
  ) > 0,
  'profile deletion expires rather than erases arena anti-cheat state'
);

select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    )),
    'pg_advisory_xact_lock_shared(20260908, 8)'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    ),
    'arena-ranking-account:'
  ) > pg_catalog.strpos(
    pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    ),
    'pg_advisory_xact_lock_shared(20260908, 8)'
  ) and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    )),
    'update arena_ranking_private.season_standings'
  ) > pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'arena_ranking_private.mirror_shared_snapshot_identity()'::regprocedure
    )),
    'arena-ranking-account:'
  ),
  'profile mirror uses daily barrier then account advisory before standing rows'
);

select extensions.ok(
  (select pg_catalog.count(*) = 1 from cron.job
    where jobname = 'alarmquest-daily-leaderboard' and schedule = '0 * * * *')
  and
  (select pg_catalog.count(*) = 1 from cron.job
    where jobname = 'alarmquest-daily-arena-leaderboard' and schedule = '17 * * * *'),
  'general and arena publication retries coexist without a cron collision'
);

select extensions.ok(
  (select pg_catalog.count(*) = 1
     from arena_ranking_private.daily_arena_leaderboard_snapshots
    where is_bootstrap and participant_count = 0 and top_entries = '[]'::jsonb),
  'migration creates one honestly empty bootstrap snapshot'
);

select * from extensions.finish();
rollback;
