-- Catalog/security contract for migration 010. Run after migrations through 010.
create extension if not exists pgtap with schema extensions;
select extensions.plan(17);

select extensions.ok(
  pg_catalog.to_regclass('ranking_private.public_ranking_moderation') is not null,
  'private shared ranking moderation table exists'
);
select extensions.ok(
  pg_catalog.to_regclass('ranking_private.public_ranking_moderation_state') is not null,
  'private moderation revision state exists'
);
select extensions.ok(
  (select relrowsecurity from pg_catalog.pg_class
    where oid = 'ranking_private.public_ranking_moderation'::regclass)
  and (select relrowsecurity from pg_catalog.pg_class
    where oid = 'ranking_private.public_ranking_moderation_state'::regclass),
  'moderation and revision tables have RLS enabled'
);
select extensions.ok(
  not pg_catalog.has_table_privilege(
    'authenticated', 'ranking_private.public_ranking_moderation', 'SELECT'
  ) and not pg_catalog.has_table_privilege(
    'service_role', 'ranking_private.public_ranking_moderation', 'SELECT'
  ),
  'clients and service role have no direct moderation table read'
);
select extensions.ok(
  pg_catalog.to_regprocedure(
    'public.admin_set_ranking_moderation(uuid,uuid,boolean,boolean,text,text,text,text)'
  ) is not null,
  'bounded audited admin moderation RPC exists'
);
select extensions.ok(
  pg_catalog.has_function_privilege(
    'service_role',
    'public.admin_set_ranking_moderation(uuid,uuid,boolean,boolean,text,text,text,text)',
    'EXECUTE'
  ) and not pg_catalog.has_function_privilege(
    'authenticated',
    'public.admin_set_ranking_moderation(uuid,uuid,boolean,boolean,text,text,text,text)',
    'EXECUTE'
  ),
  'only service role can mutate ranking moderation'
);
select extensions.ok(
  pg_catalog.has_function_privilege(
    'service_role', 'public.admin_list_ranking_moderation(integer,bigint)', 'EXECUTE'
  ) and not pg_catalog.has_function_privilege(
    'authenticated', 'public.admin_list_ranking_moderation(integer,bigint)', 'EXECUTE'
  ),
  'only service role can list ranking moderation audit rows'
);
select extensions.ok(
  pg_catalog.to_regclass(
    'ranking_private.public_ranking_moderation_account_scope_uidx'
  ) is not null and pg_catalog.to_regclass(
    'ranking_private.public_ranking_moderation_character_scope_uidx'
  ) is not null,
  'account and character moderation scopes are unique'
);
select extensions.ok(
  (select count(*) = 4 from pg_catalog.pg_trigger
    where tgrelid = 'ranking_private.public_ranking_moderation'::regclass
      and not tgisinternal),
  'insert update delete and truncate all invalidate the moderation revision'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.pg_get_functiondef('public.get_daily_leaderboard(text)'::regprocedure),
    'public_ranking_cache_token'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.pg_get_functiondef('public.get_daily_arena_leaderboard(text)'::regprocedure),
    'public_ranking_cache_token'
  ) > 0,
  'both ranking cache tokens include moderation revision'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_leaderboard(text)'::regprocedure
    )), 'rank() over'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_leaderboard(text)'::regprocedure
    )), 'bounded.combat_power'
  ) > 0,
  'general public rows are window-ranked after hiding'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'rank() over'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'bounded.score'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'bounded.wins'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'bounded.losses'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'bounded.draws'
  ) > 0,
  'arena public rows reuse the published tie rules after hiding'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_leaderboard(text)'::regprocedure
    )), 'limit 1000'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'limit 997'
  ) > 0,
  'public lists keep the established general and arena caps after filtering'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_leaderboard(text)'::regprocedure
    )), 'adjusted.display_name'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'adjusted.display_name'
  ) > 0,
  'owner arrays preserve original snapshot nicknames'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_leaderboard(text)'::regprocedure
    )), 'if not v_has_active_moderation then'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_leaderboard(text)'::regprocedure
    )), '''e'', v_snapshot.top_entries'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_daily_arena_leaderboard(text)'::regprocedure
    )), 'if not v_has_active_moderation then'
  ) > 0,
  'zero-active-policy reads retain the precomputed snapshot fast path'
);
select extensions.ok(
  pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_leaderboard(uuid,integer)'::regprocedure
    )), 'annotated.is_hidden'
  ) > 0 and pg_catalog.strpos(
    pg_catalog.lower(pg_catalog.pg_get_functiondef(
      'public.get_leaderboard(uuid,integer)'::regprocedure
    )), 'row.user_id = v_user_id'
  ) > 0,
  'legacy table reader independently moderates and proves requested-character ownership'
);
select extensions.ok(
  pg_catalog.has_function_privilege(
    'authenticated', 'public.get_daily_leaderboard(text)', 'EXECUTE'
  ) and pg_catalog.has_function_privilege(
    'authenticated', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
  ) and not pg_catalog.has_function_privilege(
    'anon', 'public.get_daily_leaderboard(text)', 'EXECUTE'
  ),
  'authenticated read boundary remains intact for both boards'
);

select * from extensions.finish();
