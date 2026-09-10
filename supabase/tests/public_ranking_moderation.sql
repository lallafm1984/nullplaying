-- ISOLATED LOCAL DATABASE ONLY. Requires migrations through 010 and rolls all fixtures back.
begin;

insert into auth.users(id) values
  ('91000000-0000-4000-8000-000000000001'),
  ('91000000-0000-4000-8000-000000000002'),
  ('91000000-0000-4000-8000-000000000003'),
  ('91000000-0000-4000-8000-000000000004'),
  ('91000000-0000-4000-8000-000000000005'),
  ('91000000-0000-4000-8000-000000000006'),
  ('91000000-0000-4000-8000-000000000008');

update ranking_private.daily_leaderboard_state
   set latest_snapshot_id = null,
       last_settled_at = null,
       tracking_started_at = pg_catalog.clock_timestamp() - interval '24 hours'
 where singleton;
delete from ranking_private.daily_leaderboard_snapshots;
insert into ranking_private.daily_leaderboard_snapshots (
  snapshot_id, settled_at, generated_at, is_bootstrap, participant_count, top_entries
) values (
  'fixture:moderation:general', pg_catalog.clock_timestamp() - interval '8 hours',
  pg_catalog.clock_timestamp() - interval '7 hours 59 minutes', false, 8, '[]'::jsonb
);
update ranking_private.daily_leaderboard_state
   set latest_snapshot_id = 'fixture:moderation:general',
       last_settled_at = pg_catalog.clock_timestamp() - interval '8 hours'
 where singleton;

with fixture(
  user_id, character_id, slot_id, rank_number, list_index, display_name,
  hero_class, level, combat_power
) as (values
  ('91000000-0000-4000-8000-000000000001'::uuid,'92000000-0000-4000-8000-000000000001'::uuid,1::smallint,1::bigint,0::bigint,'Owner One','WARRIOR',20::bigint,800::bigint),
  ('91000000-0000-4000-8000-000000000002'::uuid,'92000000-0000-4000-8000-000000000002'::uuid,1::smallint,2::bigint,1::bigint,'Unsafe Two','MAGE',20::bigint,700::bigint),
  ('91000000-0000-4000-8000-000000000003'::uuid,'92000000-0000-4000-8000-000000000003'::uuid,1::smallint,2::bigint,2::bigint,'Tie Three','RANGER',20::bigint,700::bigint),
  ('91000000-0000-4000-8000-000000000004'::uuid,'92000000-0000-4000-8000-000000000004'::uuid,1::smallint,4::bigint,3::bigint,'Player Four','CLERIC',20::bigint,600::bigint),
  ('91000000-0000-4000-8000-000000000005'::uuid,'92000000-0000-4000-8000-000000000051'::uuid,1::smallint,5::bigint,4::bigint,'Cheat Five A','ROGUE',20::bigint,500::bigint),
  ('91000000-0000-4000-8000-000000000006'::uuid,'92000000-0000-4000-8000-000000000006'::uuid,1::smallint,6::bigint,5::bigint,'Player Six','PALADIN',20::bigint,400::bigint),
  ('91000000-0000-4000-8000-000000000005'::uuid,'92000000-0000-4000-8000-000000000052'::uuid,2::smallint,7::bigint,6::bigint,'Cheat Five B','ROGUE',20::bigint,300::bigint),
  -- Deliberately duplicate user 1's character UUID under another account. Legacy reads must bind
  -- by exact private row identity rather than assuming character_id is globally unique.
  ('91000000-0000-4000-8000-000000000008'::uuid,'92000000-0000-4000-8000-000000000001'::uuid,1::smallint,8::bigint,7::bigint,'Player Eight','WARRIOR',20::bigint,200::bigint)
)
insert into ranking_private.daily_leaderboard_rows (
  snapshot_id, user_id, character_id, slot_id, rank_number, list_index,
  display_name, system_entry_code, hero_class, level, combat_power,
  achieved_at, updated_at, compact_entry
)
select 'fixture:moderation:general', fixture.user_id, fixture.character_id,
       fixture.slot_id, fixture.rank_number, fixture.list_index, fixture.display_name,
       null, fixture.hero_class, fixture.level, fixture.combat_power,
       pg_catalog.clock_timestamp() - interval '1 day',
       pg_catalog.clock_timestamp() - interval '1 day',
       pg_catalog.jsonb_build_object(
         'r', fixture.rank_number, 'i', fixture.list_index, 'c', fixture.character_id,
         'n', fixture.display_name, 'h', fixture.hero_class, 'l', fixture.level,
         'p', fixture.combat_power,
         'a', (extract(epoch from pg_catalog.clock_timestamp() - interval '1 day') * 1000)::bigint
       )
  from fixture;

update ranking_private.daily_leaderboard_snapshots as snapshot
   set top_entries = (
     select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
       from ranking_private.daily_leaderboard_rows as row
      where row.snapshot_id = snapshot.snapshot_id
   )
 where snapshot.snapshot_id = 'fixture:moderation:general';

update arena_ranking_private.daily_arena_leaderboard_state
   set latest_snapshot_id = null,
       last_settled_at = null,
       tracking_started_at = pg_catalog.clock_timestamp() - interval '24 hours'
 where singleton;
delete from arena_ranking_private.daily_arena_leaderboard_snapshots;
insert into arena_ranking_private.daily_arena_leaderboard_snapshots (
  snapshot_id, season_id, rules_version, settled_at, generated_at,
  is_bootstrap, participant_count, top_entries
) values (
  'fixture:moderation:arena', 1, 1,
  pg_catalog.clock_timestamp() - interval '8 hours',
  pg_catalog.clock_timestamp() - interval '7 hours 59 minutes', false, 8, '[]'::jsonb
);
update arena_ranking_private.daily_arena_leaderboard_state
   set latest_snapshot_id = 'fixture:moderation:arena',
       last_settled_at = pg_catalog.clock_timestamp() - interval '8 hours'
 where singleton;

with fixture(
  user_id, character_id, rank_number, list_index, display_name,
  hero_class, level, score
) as (values
  ('91000000-0000-4000-8000-000000000001'::uuid,'93000000-0000-4000-8000-000000000001'::uuid,1::bigint,0::bigint,'Arena Owner One','WARRIOR',20::bigint,1240),
  ('91000000-0000-4000-8000-000000000002'::uuid,'93000000-0000-4000-8000-000000000002'::uuid,2::bigint,1::bigint,'Arena Unsafe Two','MAGE',20::bigint,1200),
  ('91000000-0000-4000-8000-000000000003'::uuid,'93000000-0000-4000-8000-000000000003'::uuid,2::bigint,2::bigint,'Arena Tie Three','RANGER',20::bigint,1200),
  ('91000000-0000-4000-8000-000000000004'::uuid,'93000000-0000-4000-8000-000000000004'::uuid,4::bigint,3::bigint,'Arena Player Four','CLERIC',20::bigint,1160),
  ('91000000-0000-4000-8000-000000000005'::uuid,'93000000-0000-4000-8000-000000000051'::uuid,5::bigint,4::bigint,'Arena Cheat Five A','ROGUE',20::bigint,1120),
  ('91000000-0000-4000-8000-000000000006'::uuid,'93000000-0000-4000-8000-000000000006'::uuid,6::bigint,5::bigint,'Arena Player Six','PALADIN',20::bigint,1080),
  ('91000000-0000-4000-8000-000000000005'::uuid,'93000000-0000-4000-8000-000000000052'::uuid,7::bigint,6::bigint,'Arena Cheat Five B','ROGUE',20::bigint,1040),
  ('91000000-0000-4000-8000-000000000008'::uuid,'93000000-0000-4000-8000-000000000008'::uuid,8::bigint,7::bigint,'Arena Player Eight','WARRIOR',20::bigint,1000)
)
insert into arena_ranking_private.daily_arena_leaderboard_rows (
  snapshot_id, user_id, character_id, rank_number, list_index,
  display_name, hero_class, level, score, completed_battles,
  wins, losses, draws, score_achieved_at, compact_entry
)
select 'fixture:moderation:arena', fixture.user_id, fixture.character_id,
       fixture.rank_number, fixture.list_index, fixture.display_name,
       fixture.hero_class, fixture.level, fixture.score, 10, 5, 4, 1,
       pg_catalog.clock_timestamp() - interval '1 day',
       pg_catalog.jsonb_build_object(
         'r', fixture.rank_number, 'i', fixture.list_index,
         'u', pg_catalog.md5('arena-ranking-public-v1:' || fixture.user_id::text)::uuid,
         'c', fixture.character_id, 'n', fixture.display_name, 'h', fixture.hero_class,
         'l', fixture.level, 'p', fixture.score, 'b', 10, 'w', 5, 'x', 4, 'd', 1,
         'a', (extract(epoch from pg_catalog.clock_timestamp() - interval '1 day') * 1000)::bigint
       )
  from fixture;

update arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
   set top_entries = (
     select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
       from arena_ranking_private.daily_arena_leaderboard_rows as row
      where row.snapshot_id = snapshot.snapshot_id
   )
 where snapshot.snapshot_id = 'fixture:moderation:arena';

-- With no active policy, both readers return the precomputed snapshot body unchanged. This is the
-- dominant production path and avoids all moderation joins/windows.
select pg_catalog.set_config(
  'request.jwt.claim.sub', '91000000-0000-4000-8000-000000000001', true
);
do $$
declare
  v_general jsonb := public.get_daily_leaderboard(null);
  v_arena jsonb := public.get_daily_arena_leaderboard(null);
begin
  if (v_general->>'t')::integer <> 8
     or (v_general->>'next_settlement_at')::bigint -
       (v_general->>'settled_at')::bigint <> 3660000
     or pg_catalog.jsonb_array_length(v_general->'e') <> 8
     or v_general->'e' <> (
       select snapshot.top_entries
         from ranking_private.daily_leaderboard_snapshots as snapshot
        where snapshot.snapshot_id = 'fixture:moderation:general'
     )
     or (v_arena->>'t')::integer <> 8
     or (v_arena->>'next_settlement_at')::bigint -
       (v_arena->>'settled_at')::bigint <> 4680000
     or pg_catalog.jsonb_array_length(v_arena->'e') <> 8
     or v_arena->'e' <> (
       select snapshot.top_entries
         from arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
        where snapshot.snapshot_id = 'fixture:moderation:arena'
     ) then
    raise exception 'zero-active-moderation precomputed fast path failed';
  end if;
end;
$$;

-- Authenticated clients cannot read or mutate the private policy and cannot invoke admin RPCs.
do $$
begin
  if pg_catalog.has_table_privilege(
       'authenticated', 'ranking_private.public_ranking_moderation', 'SELECT'
     )
     or pg_catalog.has_table_privilege(
       'service_role', 'ranking_private.public_ranking_moderation', 'SELECT'
     )
     or pg_catalog.has_function_privilege(
       'authenticated',
       'public.admin_set_ranking_moderation(uuid,uuid,boolean,boolean,text,text,text,text)',
       'EXECUTE'
     )
     or not pg_catalog.has_function_privilege(
       'service_role',
       'public.admin_set_ranking_moderation(uuid,uuid,boolean,boolean,text,text,text,text)',
       'EXECUTE'
     ) then
    raise exception 'ranking moderation ACL boundary failed';
  end if;
end;
$$;

set local role service_role;
select public.admin_set_ranking_moderation(
  '91000000-0000-4000-8000-000000000005', null, true, true, null,
  'CHEAT', 'confirmed fixture account hide', 'qa-admin'
);
-- Account nickname fallback plus a character-specific replacement proves override precedence.
select public.admin_set_ranking_moderation(
  '91000000-0000-4000-8000-000000000002', null, true, false, 'Safe Account',
  'OBSCENE_NAME', 'fixture account replacement', 'qa-admin'
);
select public.admin_set_ranking_moderation(
  '91000000-0000-4000-8000-000000000002',
  '92000000-0000-4000-8000-000000000002', true, false, 'Safe General',
  'HATEFUL_NAME', 'fixture character replacement', 'qa-admin'
);
select public.admin_set_ranking_moderation(
  '91000000-0000-4000-8000-000000000002',
  '93000000-0000-4000-8000-000000000002', true, false, 'Safe Arena',
  'HATEFUL_NAME', 'fixture character replacement', 'qa-admin'
);
reset role;

-- An exact admin no-op does not advance the revision; a real name change does.
do $$
declare
  v_before bigint;
  v_after bigint;
begin
  select revision into strict v_before
    from ranking_private.public_ranking_moderation_state where singleton;
  perform public.admin_set_ranking_moderation(
    '91000000-0000-4000-8000-000000000002',
    '92000000-0000-4000-8000-000000000002', true, false, 'Safe General',
    'HATEFUL_NAME', 'fixture character replacement', 'qa-admin'
  );
  select revision into strict v_after
    from ranking_private.public_ranking_moderation_state where singleton;
  if v_after <> v_before then
    raise exception 'exact moderation no-op invalidated public ranking cache';
  end if;
end;
$$;

select pg_catalog.set_config(
  'request.jwt.claim.sub', '91000000-0000-4000-8000-000000000001', true
);
do $$
declare
  v_general jsonb := public.get_daily_leaderboard(null);
  v_arena jsonb := public.get_daily_arena_leaderboard(null);
  v_general_known jsonb;
  v_arena_known jsonb;
begin
  if (v_general->>'t')::integer <> 6
     or pg_catalog.jsonb_array_length(v_general->'e') <> 6
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
        where value->>'c' in (
          '92000000-0000-4000-8000-000000000051',
          '92000000-0000-4000-8000-000000000052'
        )
     )
     or (select value->>'n' from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000002') <> 'Safe General'
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
        where value->>'n' = 'Unsafe Two'
     )
     or (select (value->>'r')::integer from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000006') <> 5
     or (select (value->>'i')::integer from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000006') <> 4
     or (select (value->>'r')::integer from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000003') <> 2 then
    raise exception 'general public hide, replacement, tie rank or continuous rerank failed: %', v_general;
  end if;

  if (v_arena->>'t')::integer <> 6
     or pg_catalog.jsonb_array_length(v_arena->'e') <> 6
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
        where value->>'c' in (
          '93000000-0000-4000-8000-000000000051',
          '93000000-0000-4000-8000-000000000052'
        )
     )
     or (select value->>'n' from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
          where value->>'c' = '93000000-0000-4000-8000-000000000002') <> 'Safe Arena'
     or (select (value->>'r')::integer from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
          where value->>'c' = '93000000-0000-4000-8000-000000000006') <> 5
     or (select (value->>'i')::integer from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
          where value->>'c' = '93000000-0000-4000-8000-000000000006') <> 4
     or (select (value->>'r')::integer from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
          where value->>'c' = '93000000-0000-4000-8000-000000000003') <> 2 then
    raise exception 'arena public hide, replacement, tie rank or continuous rerank failed: %', v_arena;
  end if;

  if pg_catalog.octet_length(v_general->>'snapshot_id') > 128
     or pg_catalog.octet_length(v_arena->>'snapshot_id') > 128
     or v_general->>'snapshot_id' not like 'fixture:moderation:general:m%'
     or v_arena->>'snapshot_id' not like 'fixture:moderation:arena:m%' then
    raise exception 'moderation cache token contract failed';
  end if;
  v_general_known := public.get_daily_leaderboard(v_general->>'snapshot_id');
  v_arena_known := public.get_daily_arena_leaderboard(v_arena->>'snapshot_id');
  if not (v_general_known->>'unchanged')::boolean
     or v_general_known ? 'e' or v_general_known ? 'o'
     or not (v_arena_known->>'unchanged')::boolean
     or v_arena_known ? 'e' or v_arena_known ? 'o' then
    raise exception 'same moderation revision did not return metadata only';
  end if;
end;
$$;

-- A visible owner below hidden rows receives the public rank but keeps the raw name in `o`.
select pg_catalog.set_config(
  'request.jwt.claim.sub', '91000000-0000-4000-8000-000000000006', true
);
do $$
declare
  v_general jsonb := public.get_daily_leaderboard(null);
  v_arena jsonb := public.get_daily_arena_leaderboard(null);
begin
  if (v_general->>'t')::integer <> 6
     or (v_general#>>'{o,0,r}')::integer <> 5
     or (v_general#>>'{o,0,i}')::integer <> 4
     or v_general#>>'{o,0,n}' <> 'Player Six'
     or (v_arena#>>'{o,0,r}')::integer <> 5
     or (v_arena#>>'{o,0,i}')::integer <> 4
     or v_arena#>>'{o,0,n}' <> 'Arena Player Six' then
    raise exception 'visible owner did not receive its public rank and original nickname';
  end if;
end;
$$;

-- The hidden account does not enter `e`, but sees both original characters/ranks/names and the
-- original total in `o`.  This is the only requester that sees its pre-moderation personal ranks.
select pg_catalog.set_config(
  'request.jwt.claim.sub', '91000000-0000-4000-8000-000000000005', true
);
do $$
declare
  v_general jsonb := public.get_daily_leaderboard(null);
  v_arena jsonb := public.get_daily_arena_leaderboard(null);
begin
  if (v_general->>'t')::integer <> 8
     or pg_catalog.jsonb_array_length(v_general->'o') <> 2
     or v_general#>>'{o,0,n}' <> 'Cheat Five A'
     or (v_general#>>'{o,0,r}')::integer <> 5
     or (v_general#>>'{o,0,i}')::integer <> 4
     or v_general#>>'{o,1,n}' <> 'Cheat Five B'
     or (v_general#>>'{o,1,r}')::integer <> 7
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
        where value->>'c' in (
          '92000000-0000-4000-8000-000000000051',
          '92000000-0000-4000-8000-000000000052'
        )
     )
     or (v_arena->>'t')::integer <> 8
     or pg_catalog.jsonb_array_length(v_arena->'o') <> 2
     or v_arena#>>'{o,0,n}' <> 'Arena Cheat Five A'
     or (v_arena#>>'{o,0,r}')::integer <> 5
     or v_arena#>>'{o,1,n}' <> 'Arena Cheat Five B'
     or (v_arena#>>'{o,1,r}')::integer <> 7
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
        where value->>'c' in (
          '93000000-0000-4000-8000-000000000051',
          '93000000-0000-4000-8000-000000000052'
        )
     ) then
    raise exception 'hidden owner original own-row contract failed';
  end if;
end;
$$;

-- The legacy table RPC cannot bypass moderation.  It reserves the requested owner's original row,
-- pseudonymizes other account IDs and returns the same sanitized total/public ranks.
select pg_catalog.set_config(
  'request.jwt.claim.sub', '91000000-0000-4000-8000-000000000006', true
);
do $$
declare
  v_rows integer;
  v_rank bigint;
  v_name text;
  v_total integer;
  v_foreign_user uuid;
begin
  select pg_catalog.count(*),
         max(row.rank_number) filter (
           where row.character_id = '92000000-0000-4000-8000-000000000006'
         ),
         max(row.display_name) filter (
           where row.character_id = '92000000-0000-4000-8000-000000000002'
         ),
         max(row.total_participants)
    into v_rows, v_rank, v_name, v_total
    from public.get_leaderboard(
      '92000000-0000-4000-8000-000000000006', 1000
    ) as row;
  select row.user_id into strict v_foreign_user
    from public.get_leaderboard(
      '92000000-0000-4000-8000-000000000006', 1000
    ) as row
   where row.display_name = 'Owner One';
  if v_rows <> 6 or v_rank <> 5 or v_name <> 'Safe General' or v_total <> 6
     or v_foreign_user = '91000000-0000-4000-8000-000000000001'::uuid then
    raise exception 'legacy ranking moderation compatibility failed';
  end if;
end;
$$;

-- A foreign character UUID never reserves or removes that row. The duplicated UUID under two
-- accounts also proves the legacy reader carries exact user identity instead of joining by
-- character_id alone.
do $$
declare
  v_rows integer;
  v_duplicate_rows integer;
begin
  select pg_catalog.count(*),
         pg_catalog.count(*) filter (
           where row.character_id = '92000000-0000-4000-8000-000000000001'
         )
    into v_rows, v_duplicate_rows
    from public.get_leaderboard(
      '92000000-0000-4000-8000-000000000001', 1000
    ) as row;
  if v_rows <> 6 or v_duplicate_rows <> 2 then
    raise exception 'foreign or duplicate character UUID confused legacy ownership';
  end if;
end;
$$;

-- A real moderation edit changes only the public cache token/projection; stored snapshots remain
-- raw and the old token no longer receives an unchanged response.
select pg_catalog.set_config(
  'request.jwt.claim.sub', '91000000-0000-4000-8000-000000000001', true
);
do $$
declare
  v_before jsonb := public.get_daily_leaderboard(null);
  v_revision_before bigint;
  v_revision_after bigint;
  v_after jsonb;
begin
  select revision into strict v_revision_before
    from ranking_private.public_ranking_moderation_state where singleton;
  perform public.admin_set_ranking_moderation(
    '91000000-0000-4000-8000-000000000002',
    '92000000-0000-4000-8000-000000000002', true, false, 'Safer General',
    'HATEFUL_NAME', 'fixture character replacement changed', 'qa-admin'
  );
  select revision into strict v_revision_after
    from ranking_private.public_ranking_moderation_state where singleton;
  v_after := public.get_daily_leaderboard(v_before->>'snapshot_id');
  if v_revision_after <> v_revision_before + 1
     or (v_after->>'unchanged')::boolean
     or v_after->>'snapshot_id' = v_before->>'snapshot_id'
     or (select value->>'n' from pg_catalog.jsonb_array_elements(v_after->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000002') <> 'Safer General'
     or (select display_name from ranking_private.daily_leaderboard_rows
          where snapshot_id = 'fixture:moderation:general'
            and character_id = '92000000-0000-4000-8000-000000000002') <> 'Unsafe Two'
     or (select display_name from arena_ranking_private.daily_arena_leaderboard_rows
          where snapshot_id = 'fixture:moderation:arena'
            and character_id = '93000000-0000-4000-8000-000000000002') <> 'Arena Unsafe Two' then
    raise exception 'moderation revision or immutable raw-snapshot contract failed';
  end if;
end;
$$;

-- Operational rollback uses the same audited RPC with is_active=false. Each actual deactivation
-- advances the cache revision once; after all four policies are inactive, count, ranks and raw
-- names return without deleting audit rows.
do $$
declare
  v_revision bigint;
  v_next bigint;
  v_general jsonb;
  v_arena jsonb;
begin
  select revision into strict v_revision
    from ranking_private.public_ranking_moderation_state where singleton;

  perform public.admin_set_ranking_moderation(
    '91000000-0000-4000-8000-000000000005', null, false, true, null,
    'CHEAT', 'confirmed fixture account hide', 'qa-admin'
  );
  select revision into strict v_next
    from ranking_private.public_ranking_moderation_state where singleton;
  if v_next <> v_revision + 1 then
    raise exception 'cheat moderation deactivation did not advance revision exactly once';
  end if;
  v_revision := v_next;

  perform public.admin_set_ranking_moderation(
    '91000000-0000-4000-8000-000000000002',
    '92000000-0000-4000-8000-000000000002', false, false, 'Safer General',
    'HATEFUL_NAME', 'fixture character replacement changed', 'qa-admin'
  );
  select revision into strict v_next
    from ranking_private.public_ranking_moderation_state where singleton;
  if v_next <> v_revision + 1 then
    raise exception 'general replacement deactivation did not advance revision exactly once';
  end if;
  v_revision := v_next;

  perform public.admin_set_ranking_moderation(
    '91000000-0000-4000-8000-000000000002',
    '93000000-0000-4000-8000-000000000002', false, false, 'Safe Arena',
    'HATEFUL_NAME', 'fixture character replacement', 'qa-admin'
  );
  select revision into strict v_next
    from ranking_private.public_ranking_moderation_state where singleton;
  if v_next <> v_revision + 1 then
    raise exception 'arena replacement deactivation did not advance revision exactly once';
  end if;
  v_revision := v_next;

  perform public.admin_set_ranking_moderation(
    '91000000-0000-4000-8000-000000000002', null, false, false, 'Safe Account',
    'OBSCENE_NAME', 'fixture account replacement', 'qa-admin'
  );
  select revision into strict v_next
    from ranking_private.public_ranking_moderation_state where singleton;
  if v_next <> v_revision + 1 then
    raise exception 'account replacement deactivation did not advance revision exactly once';
  end if;

  v_general := public.get_daily_leaderboard(null);
  v_arena := public.get_daily_arena_leaderboard(null);
  if exists (
       select 1 from ranking_private.public_ranking_moderation where is_active
     )
     or (v_general->>'t')::integer <> 8
     or pg_catalog.jsonb_array_length(v_general->'e') <> 8
     or (select value->>'n' from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000002') <> 'Unsafe Two'
     or (select (value->>'r')::integer from pg_catalog.jsonb_array_elements(v_general->'e') as entry(value)
          where value->>'c' = '92000000-0000-4000-8000-000000000006') <> 6
     or (v_arena->>'t')::integer <> 8
     or pg_catalog.jsonb_array_length(v_arena->'e') <> 8
     or (select value->>'n' from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
          where value->>'c' = '93000000-0000-4000-8000-000000000002') <> 'Arena Unsafe Two'
     or (select (value->>'r')::integer from pg_catalog.jsonb_array_elements(v_arena->'e') as entry(value)
          where value->>'c' = '93000000-0000-4000-8000-000000000006') <> 6 then
    raise exception 'moderation deactivation did not restore raw public presentation';
  end if;
end;
$$;

-- Invalid active policies fail closed.
do $$
begin
  begin
    perform public.admin_set_ranking_moderation(
      '91000000-0000-4000-8000-000000000003', null, true, false, null,
      'CHEAT', null, 'qa-admin'
    );
    raise exception 'invalid cheat moderation was accepted';
  exception when sqlstate '22023' then null;
  end;
  begin
    perform public.admin_set_ranking_moderation(
      '91000000-0000-4000-8000-000000000003', null, true, false,
      ' bad name ', 'OBSCENE_NAME', null, 'qa-admin'
    );
    raise exception 'untrimmed replacement display name was accepted';
  exception when sqlstate '22023' then null;
  end;
end;
$$;

rollback;
