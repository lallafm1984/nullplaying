-- ISOLATED LOCAL DATABASE ONLY. The entire fixture is rolled back and refuses any database with
-- auth, shared-player, ranking, or arena-standing application rows. Never run this against live.
begin;

do $$
declare
  v_bootstrap arena_ranking_private.daily_arena_leaderboard_snapshots%rowtype;
begin
  if exists (select 1 from auth.users)
     or exists (select 1 from public.ranking_entries)
     or exists (select 1 from shared_player_private.snapshots)
     or exists (select 1 from arena_ranking_private.season_standings) then
    raise exception 'daily arena fixture requires an EMPTY ISOLATED LOCAL database';
  end if;
  select * into strict v_bootstrap
    from arena_ranking_private.daily_arena_leaderboard_snapshots;
  if not v_bootstrap.is_bootstrap
     or v_bootstrap.snapshot_id not like 'arena:s1:r1:bootstrap:%'
     or v_bootstrap.participant_count <> 0
     or v_bootstrap.top_entries <> '[]'::jsonb
     or arena_ranking_private.publish_daily_arena_leaderboard() <> v_bootstrap.snapshot_id then
    raise exception 'arena bootstrap must be honest, empty and idempotent';
  end if;
  if has_table_privilege('authenticated', 'arena_ranking_private.season_standings', 'SELECT')
     or has_table_privilege('anon', 'arena_ranking_private.daily_arena_leaderboard_rows', 'SELECT')
     or has_function_privilege(
       'authenticated',
       'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)',
       'EXECUTE'
     )
     or not has_function_privilege(
       'service_role',
       'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)',
       'EXECUTE'
     )
     or not has_function_privilege(
       'authenticated',
       'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)',
       'EXECUTE'
     )
     or has_function_privilege(
       'anon',
       'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)',
       'EXECUTE'
     )
     or not has_function_privilege(
       'authenticated', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
     )
     or has_function_privilege(
       'anon', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
     ) then
    raise exception 'arena RPC/private-table privilege boundary failed';
  end if;
  if (select count(*) from cron.job
       where jobname = 'alarmquest-daily-leaderboard' and schedule = '0 * * * *') <> 1
     or (select count(*) from cron.job
       where jobname = 'alarmquest-daily-arena-leaderboard' and schedule = '17 * * * *') <> 1 then
    raise exception 'general and arena hourly retry jobs must coexist on distinct schedules';
  end if;
end;
$$;

insert into auth.users(id, created_at) values
  ('10000000-0000-4000-8000-000000000001', statement_timestamp() - interval '100 days'),
  ('10000000-0000-4000-8000-000000000002', statement_timestamp() - interval '100 days'),
  ('10000000-0000-4000-8000-000000000003', statement_timestamp()),
  ('10000000-0000-4000-8000-000000000004', statement_timestamp() - interval '100 days'),
  ('10000000-0000-4000-8000-000000000005', statement_timestamp() - interval '100 days'),
  ('10000000-0000-4000-8000-000000000006', statement_timestamp() - interval '100 days'),
  ('10000000-0000-4000-8000-000000000007', statement_timestamp() - interval '100 days');

insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values
  ('10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001',
   'Arena A','WARRIOR',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002',
   'Arena A2','MAGE',19,70,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000003',
   'Arena A3','CLERIC',20,100,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000004',
   'Arena B','ROGUE',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000003','20000000-0000-4000-8000-000000000005',
   'Young','RANGER',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000007','20000000-0000-4000-8000-000000000015',
   'Legacy Local','PALADIN',16,60,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days');

-- Lv10 is eligible even without a public.ranking_entries row. Initial, deduplicated, burst and
-- cooldown receipts must all keep season_id as a JSON string for the Android wire contract.
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
set local role authenticated;
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1010, 10, 6, 3, 1, 1
  );
  if not (v_response->>'accepted')::boolean
     or (v_response->>'deduplicated')::boolean
     or pg_catalog.jsonb_typeof(v_response->'season_id') <> 'string'
     or v_response->>'season_id' <> '1'
     or (v_response->>'rules_version')::integer <> 1
     or (v_response->>'server_now')::bigint <= 0 then
    raise exception 'initial arena sync receipt failed: %', v_response;
  end if;
end;
$$;
reset role;

do $$
begin
  if not exists (
    select 1 from arena_ranking_private.season_standings
     where user_id = '10000000-0000-4000-8000-000000000001'
       and character_id = '20000000-0000-4000-8000-000000000001'
       and level = 10 and completed_battles = 10 and score = 1010
  ) or exists (select 1 from public.ranking_entries) then
    raise exception 'Lv10 arena standing incorrectly depended on the Lv20 general ranking table';
  end if;
  update arena_ranking_private.account_call_limits
     set last_called_at = statement_timestamp() - interval '10 seconds'
   where user_id = '10000000-0000-4000-8000-000000000001';
end;
$$;

set local role authenticated;
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1010, 10, 6, 3, 1, 1
  );
  if not (v_response->>'accepted')::boolean
     or not (v_response->>'deduplicated')::boolean
     or pg_catalog.jsonb_typeof(v_response->'season_id') <> 'string' then
    raise exception 'arena deduplication receipt failed: %', v_response;
  end if;
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1010, 10, 6, 3, 1, 1
  );
  if (v_response->>'accepted')::boolean
     or not (v_response->>'rate_limited')::boolean
     or pg_catalog.jsonb_typeof(v_response->'season_id') <> 'string'
     or current_setting('response.status', true) <> '429' then
    raise exception 'arena burst receipt failed: %', v_response;
  end if;
  perform set_config('response.status', '', true);
  perform set_config('response.headers', '', true);
end;
$$;
reset role;

-- Removing a shared profile expires but preserves its standing and anti-cheat floor. It is no
-- longer leaderboard-eligible. Republishing the profile reactivates the same standing, and the
-- already accepted aggregate can then be acknowledged inside the original 15-minute window.
delete from shared_player_private.snapshots
 where user_id = '10000000-0000-4000-8000-000000000001'
   and character_id = '20000000-0000-4000-8000-000000000001';
do $$ begin
  if not exists (
    select 1 from arena_ranking_private.season_standings
     where user_id = '10000000-0000-4000-8000-000000000001'
       and character_id = '20000000-0000-4000-8000-000000000001'
       and score = 1010 and completed_battles = 10
       and wins = 6 and losses = 3 and draws = 1
       and profile_expires_at <= statement_timestamp()
  ) or not exists (
    select 1 from arena_ranking_private.entry_sync_limits
     where user_id = '10000000-0000-4000-8000-000000000001'
       and character_id = '20000000-0000-4000-8000-000000000001'
  ) or exists (
    select 1 from arena_ranking_private.season_standings
     where user_id = '10000000-0000-4000-8000-000000000001'
       and character_id = '20000000-0000-4000-8000-000000000001'
       and profile_expires_at > statement_timestamp()
  ) then
    raise exception 'profile deletion did not preserve and expire the arena anti-cheat standing';
  end if;
end $$;
insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values (
  '10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000001',
  'Arena A','WARRIOR',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'
);
update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '10 seconds'
 where user_id = '10000000-0000-4000-8000-000000000001';
set local role authenticated;
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1010, 10, 6, 3, 1, 1
  );
  if not (v_response->>'accepted')::boolean
     or not (v_response->>'deduplicated')::boolean
     or coalesce((v_response->>'rate_limited')::boolean, false) then
    raise exception 'same aggregate did not reactivate the preserved standing: %', v_response;
  end if;
end;
$$;
reset role;
do $$ begin
  if not exists (
    select 1 from arena_ranking_private.season_standings
     where user_id = '10000000-0000-4000-8000-000000000001'
       and character_id = '20000000-0000-4000-8000-000000000001'
       and score = 1010 and completed_battles = 10
       and profile_expires_at > statement_timestamp()
  ) then
    raise exception 'republished profile did not reactivate its preserved arena standing';
  end if;
end $$;

update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
update arena_ranking_private.entry_sync_limits
   set last_called_at = statement_timestamp() - interval '16 minutes',
       last_synced_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';

set local role authenticated;
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1034, 11, 7, 3, 1, 1
  );
  if not (v_response->>'accepted')::boolean
     or (v_response->>'deduplicated')::boolean
     or pg_catalog.jsonb_typeof(v_response->'season_id') <> 'string' then
    raise exception 'valid arena delta receipt failed: %', v_response;
  end if;
end;
$$;
reset role;

update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '10 seconds'
 where user_id = '10000000-0000-4000-8000-000000000001';
set local role authenticated;
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1058, 12, 8, 3, 1, 1
  );
  if (v_response->>'accepted')::boolean
     or not (v_response->>'rate_limited')::boolean
     or pg_catalog.jsonb_typeof(v_response->'season_id') <> 'string'
     or current_setting('response.status', true) <> '429' then
    raise exception 'changed arena cooldown receipt failed: %', v_response;
  end if;
  perform set_config('response.status', '', true);
  perform set_config('response.headers', '', true);
end;
$$;
reset role;

do $$
begin
  if (select completed_battles from arena_ranking_private.season_standings
       where user_id = '10000000-0000-4000-8000-000000000001'
         and character_id = '20000000-0000-4000-8000-000000000001') <> 11 then
    raise exception 'cooldown changed the arena standing';
  end if;
end;
$$;

-- Semantic rejections are normal receipts so the preceding account guard remains committed.
update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
update arena_ranking_private.entry_sync_limits
   set last_called_at = statement_timestamp() - interval '16 minutes',
       last_synced_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
do $$
declare v_before smallint; v_response jsonb;
begin
  select call_count into v_before from arena_ranking_private.account_call_limits
   where user_id = '10000000-0000-4000-8000-000000000001';
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1010, 10, 6, 3, 1, 1
  );
  if v_response->>'error_code' <> 'record_decrease'
     or not (v_response->>'invalid')::boolean
     or (select call_count from arena_ranking_private.account_call_limits
          where user_id = '10000000-0000-4000-8000-000000000001') <> v_before + 1 then
    raise exception 'record-decrease rejection did not preserve the account guard: %', v_response;
  end if;
end;
$$;

update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
update arena_ranking_private.entry_sync_limits
   set last_called_at = statement_timestamp() - interval '16 minutes',
       last_synced_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1100, 12, 8, 3, 1, 1
  );
  if v_response->>'error_code' <> 'score_movement_limit' then
    raise exception 'score movement bound failed: %', v_response;
  end if;
end;
$$;

update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
update arena_ranking_private.entry_sync_limits
   set last_called_at = statement_timestamp() - interval '16 minutes',
       last_synced_at = statement_timestamp() - interval '16 minutes'
 where user_id = '10000000-0000-4000-8000-000000000001';
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1034, 31, 27, 3, 1, 1
  );
  if v_response->>'error_code' <> 'daily_match_limit'
     or (v_response->>'retry_after_seconds')::bigint <= 0 then
    raise exception 'UTC daily match bound failed: %', v_response;
  end if;
end;
$$;

-- A newly created anonymous-style account cannot import more than one trusted UTC day's record.
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000003', true);
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000005', 1000, 21, 10, 10, 1, 1
  );
  if v_response->>'error_code' <> 'account_age_limit'
     or (v_response->>'retry_after_seconds')::bigint <= 0 then
    raise exception 'account-age arena bound failed: %', v_response;
  end if;
end;
$$;

-- The first three empty server slots accept an existing unreleased local aggregate above ten, as
-- long as the ordinary account-age/global plausibility bounds pass. Only slot replacement is
-- forced back to the exact placement checkpoint.
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000007', true);
set local role authenticated;
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000015', 1024, 11, 6, 4, 1, 1
  );
  if not (v_response->>'accepted')::boolean
     or (v_response->>'deduplicated')::boolean then
    raise exception 'first empty slot rejected a valid legacy aggregate above placement: %', v_response;
  end if;
end;
$$;
reset role;
do $$ begin
  if not exists (
       select 1 from arena_ranking_private.season_character_bindings
        where user_id = '10000000-0000-4000-8000-000000000007'
          and binding_slot = 1
          and character_id = '20000000-0000-4000-8000-000000000015'
     )
     or not exists (
       select 1 from arena_ranking_private.season_standings
        where user_id = '10000000-0000-4000-8000-000000000007'
          and character_id = '20000000-0000-4000-8000-000000000015'
          and completed_battles = 11
     ) then
    raise exception 'legacy aggregate did not persist in the first empty server slot';
  end if;
end $$;
delete from auth.users where id = '10000000-0000-4000-8000-000000000007';

-- The server assigns three slots. A removed profile preserves its standing/floor until a different
-- character with an exact ten-match placement aggregate atomically reuses that inactive slot.
insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values
  ('10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000010',
   'Bound One','WARRIOR',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000011',
   'Bound Two','MAGE',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'),
  ('10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000012',
   'Bound Three','CLERIC',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days');
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000006', true);
do $$
declare v_response jsonb; v_character uuid;
begin
  foreach v_character in array array[
    '20000000-0000-4000-8000-000000000010'::uuid,
    '20000000-0000-4000-8000-000000000011'::uuid,
    '20000000-0000-4000-8000-000000000012'::uuid
  ] loop
    update arena_ranking_private.account_call_limits
       set last_called_at = statement_timestamp() - interval '10 seconds'
     where user_id = '10000000-0000-4000-8000-000000000006';
    v_response := public.sync_arena_ranking_entry(v_character, 1000, 10, 5, 4, 1, 1);
    if not (v_response->>'accepted')::boolean then
      raise exception 'one of the first three season characters was rejected: %', v_response;
    end if;
  end loop;
end;
$$;
do $$ begin
  if (select count(*) from arena_ranking_private.season_character_bindings
       where user_id = '10000000-0000-4000-8000-000000000006') <> 3
     or (select count(distinct binding_slot) from arena_ranking_private.season_character_bindings
          where user_id = '10000000-0000-4000-8000-000000000006') <> 3
     or exists (
       select 1 from arena_ranking_private.season_character_bindings
        where user_id = '10000000-0000-4000-8000-000000000006'
          and binding_slot not between 1 and 3
     ) then
    raise exception 'first three arena characters did not receive distinct server slots';
  end if;
end $$;
delete from shared_player_private.snapshots
 where user_id = '10000000-0000-4000-8000-000000000006'
   and character_id = '20000000-0000-4000-8000-000000000010';

-- Force the preserved row before today's cutoff without firing the capture trigger. The later
-- replacement DELETE must retain this exact old standing as the daily before-image.
alter table arena_ranking_private.season_standings
  disable trigger arena_season_standings_daily_capture;
update arena_ranking_private.season_standings
   set daily_admitted_at = (
     pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC'
   ) - interval '1 second'
 where user_id = '10000000-0000-4000-8000-000000000006'
   and character_id = '20000000-0000-4000-8000-000000000010';
alter table arena_ranking_private.season_standings
  enable trigger arena_season_standings_daily_capture;

insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values (
  '10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000013',
  'Rotated Four','ROGUE',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'
);
update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '10 seconds'
 where user_id = '10000000-0000-4000-8000-000000000006';
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000013', 1024, 11, 6, 4, 1, 1
  );
  if v_response->>'error_code' <> 'replacement_requires_placement'
     or not (v_response->>'invalid')::boolean
     or (select count(*) from arena_ranking_private.season_character_bindings
          where user_id = '10000000-0000-4000-8000-000000000006') <> 3
     or (select count(*) from arena_ranking_private.season_standings
          where user_id = '10000000-0000-4000-8000-000000000006') <> 3
     or not exists (
       select 1 from arena_ranking_private.season_standings
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000010'
          and profile_expires_at <= statement_timestamp()
     )
     or exists (
       select 1 from arena_ranking_private.entry_sync_limits
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000013'
     ) then
    raise exception 'non-placement replacement changed its inactive server slot: %', v_response;
  end if;
end;
$$;

update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '10 seconds'
 where user_id = '10000000-0000-4000-8000-000000000006';
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000013', 1000, 10, 5, 4, 1, 1
  );
  if not (v_response->>'accepted')::boolean
     or (v_response->>'deduplicated')::boolean
     or (select count(*) from arena_ranking_private.season_character_bindings
          where user_id = '10000000-0000-4000-8000-000000000006') <> 3
     or not exists (
       select 1 from arena_ranking_private.season_character_bindings
        where user_id = '10000000-0000-4000-8000-000000000006'
          and binding_slot = 1
          and character_id = '20000000-0000-4000-8000-000000000013'
     )
     or exists (
       select 1 from arena_ranking_private.season_standings
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000010'
     )
     or exists (
       select 1 from arena_ranking_private.entry_sync_limits
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000010'
     )
     or not exists (
       select 1 from arena_ranking_private.season_standings
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000013'
          and completed_battles = 10
     )
     or not exists (
       select 1 from arena_ranking_private.daily_arena_before_images
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000010'
          and row_data->>'character_id' = '20000000-0000-4000-8000-000000000010'
     ) then
    raise exception 'placement replacement was not an atomic bounded slot reuse: %', v_response;
  end if;
end;
$$;
delete from arena_ranking_private.daily_arena_before_images
 where user_id = '10000000-0000-4000-8000-000000000006'
   and character_id = '20000000-0000-4000-8000-000000000010';

-- A fourth active identity cannot evict any of the three active server slots. This is retryable
-- client state because a later complete shared-profile sync can retire one of those projections.
insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values (
  '10000000-0000-4000-8000-000000000006','20000000-0000-4000-8000-000000000014',
  'Active Fourth','PALADIN',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'
);
update arena_ranking_private.account_call_limits
   set last_called_at = statement_timestamp() - interval '10 seconds'
 where user_id = '10000000-0000-4000-8000-000000000006';
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000014', 1000, 10, 5, 4, 1, 1
  );
  if v_response->>'error_code' <> 'season_character_limit'
     or not (v_response->>'invalid')::boolean
     or (select count(*) from arena_ranking_private.season_character_bindings
          where user_id = '10000000-0000-4000-8000-000000000006') <> 3
     or exists (
       select 1 from arena_ranking_private.entry_sync_limits
        where user_id = '10000000-0000-4000-8000-000000000006'
          and character_id = '20000000-0000-4000-8000-000000000014'
     ) then
    raise exception 'fourth active character unexpectedly evicted an arena slot: %', v_response;
  end if;
end;
$$;
delete from auth.users where id = '10000000-0000-4000-8000-000000000006';
do $$ begin
  if exists (select 1 from arena_ranking_private.season_character_bindings
              where user_id = '10000000-0000-4000-8000-000000000006')
     or exists (select 1 from arena_ranking_private.season_standings
                 where user_id = '10000000-0000-4000-8000-000000000006')
     or exists (select 1 from arena_ranking_private.entry_sync_limits
                 where user_id = '10000000-0000-4000-8000-000000000006')
     or exists (select 1 from shared_player_private.snapshots
                 where user_id = '10000000-0000-4000-8000-000000000006') then
    raise exception 'Auth account deletion did not cascade arena/profile state';
  end if;
end $$;

-- Wrong-owner and malformed calls consume the same account budget; a 25th non-burst call is denied.
select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000002', true);
do $$
declare v_response jsonb;
begin
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1000, 10, 5, 4, 1, 1
  );
  if v_response->>'error_code' <> 'eligible_owned_profile_required' then
    raise exception 'cross-account arena character was accepted: %', v_response;
  end if;
  v_response := public.sync_arena_ranking_entry(
    '20000000-0000-4000-8000-000000000001', 1000, 10, 5, 4, 1, 1
  );
  if not (v_response->>'rate_limited')::boolean then
    raise exception 'wrong-owner burst did not hit the account guard: %', v_response;
  end if;
  perform set_config('response.status', '', true);
  perform set_config('response.headers', '', true);
end;
$$;

select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000004', true);
do $$
declare v_response jsonb; v_iteration integer;
begin
  for v_iteration in 1..24 loop
    update arena_ranking_private.account_call_limits
       set last_called_at = statement_timestamp() - interval '10 seconds'
     where user_id = '10000000-0000-4000-8000-000000000004';
    v_response := public.sync_arena_ranking_entry(null, 1000, 10, 5, 4, 1, 1);
    if v_response->>'error_code' <> 'invalid_standing' then
      raise exception 'malformed call did not consume normal budget at %: %', v_iteration, v_response;
    end if;
  end loop;
  update arena_ranking_private.account_call_limits
     set last_called_at = statement_timestamp() - interval '10 seconds'
   where user_id = '10000000-0000-4000-8000-000000000004';
  v_response := public.sync_arena_ranking_entry(null, 1000, 10, 5, 4, 1, 1);
  if not (v_response->>'rate_limited')::boolean
     or not (v_response->>'daily_limit')::boolean
     or (select call_count from arena_ranking_private.account_call_limits
          where user_id = '10000000-0000-4000-8000-000000000004') <> 24 then
    raise exception 'daily account call budget failed: %', v_response;
  end if;
  perform set_config('response.status', '', true);
  perform set_config('response.headers', '', true);
end;
$$;

-- Profile identity changes mirror into existing arena standings without another local arena match.
update shared_player_private.snapshots
   set display_name = 'Mirrored A', level = 11
 where user_id = '10000000-0000-4000-8000-000000000001'
   and character_id = '20000000-0000-4000-8000-000000000001';
do $$ begin
  if not exists (
    select 1 from arena_ranking_private.season_standings
     where user_id = '10000000-0000-4000-8000-000000000001'
       and character_id = '20000000-0000-4000-8000-000000000001'
       and display_name = 'Mirrored A' and level = 11
  ) then raise exception 'shared profile identity did not mirror into arena standing'; end if;
end $$;
update shared_player_private.snapshots
   set display_name = 'Arena A', level = 10
 where user_id = '10000000-0000-4000-8000-000000000001'
   and character_id = '20000000-0000-4000-8000-000000000001';

-- Build a >1,000 participant cutoff entirely inside this rollback-only fixture.
insert into auth.users(id, created_at)
select pg_catalog.md5('arena-user:' || n::text)::uuid, statement_timestamp() - interval '100 days'
  from pg_catalog.generate_series(1,1002) as n;
insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
)
select pg_catalog.md5('arena-user:' || n::text)::uuid,
       pg_catalog.md5('arena-character:' || n::text)::uuid,
       'Tied ' || n::text, 'WARRIOR', 10, 40, 1, 1, '{}', '[]',
       statement_timestamp(), statement_timestamp() + interval '7 days'
  from pg_catalog.generate_series(1,1002) as n;
insert into arena_ranking_private.season_standings (
  season_id, rules_version, user_id, character_id, display_name, hero_class, level,
  score, completed_battles, wins, losses, draws, score_achieved_at, profile_expires_at, updated_at,
  daily_record_date_utc, daily_record_base, daily_admitted_at
)
select 1, 1, pg_catalog.md5('arena-user:' || n::text)::uuid,
       pg_catalog.md5('arena-character:' || n::text)::uuid,
       'Tied ' || n::text, 'WARRIOR', 10, 1200, 10, 6, 3, 1,
       statement_timestamp() - interval '1 hour' + n * interval '1 millisecond',
       statement_timestamp() + interval '7 days', statement_timestamp(),
       (statement_timestamp() at time zone 'UTC')::date - 1,
       10, statement_timestamp()
  from pg_catalog.generate_series(1,1002) as n;

insert into arena_ranking_private.season_standings (
  season_id, rules_version, user_id, character_id, display_name, hero_class, level,
  score, completed_battles, wins, losses, draws, score_achieved_at, profile_expires_at, updated_at,
  daily_record_date_utc, daily_record_base, daily_admitted_at
) values
  (1,1,'10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000002',
   'Arena A2','MAGE',19,900,10,5,4,1,statement_timestamp()-interval '2 hours',
   statement_timestamp()+interval '7 days',statement_timestamp(),
   (statement_timestamp() at time zone 'UTC')::date-1,10,statement_timestamp()),
  (1,1,'10000000-0000-4000-8000-000000000001','20000000-0000-4000-8000-000000000003',
   'Arena A3','CLERIC',20,800,10,5,4,1,statement_timestamp()-interval '2 hours',
   statement_timestamp()+interval '7 days',statement_timestamp(),
   (statement_timestamp() at time zone 'UTC')::date-1,10,statement_timestamp()),
  (1,1,'10000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000004',
   'Arena B','ROGUE',10,1100,10,5,4,1,statement_timestamp()-interval '2 hours',
   statement_timestamp()+interval '7 days',statement_timestamp(),
   (statement_timestamp() at time zone 'UTC')::date-1,10,statement_timestamp());
update arena_ranking_private.season_standings
   set score = 1240, score_achieved_at = statement_timestamp() - interval '3 hours'
 where user_id = '10000000-0000-4000-8000-000000000001'
   and character_id = '20000000-0000-4000-8000-000000000001';

-- Leave one expired shared projection physically present to prove publication filters it even if
-- the bounded 006 cleanup has not deleted it yet.
update shared_player_private.snapshots
   set published_at =
         (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
           - interval '2 days',
       expires_at =
         (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
           - interval '1 second'
 where user_id = pg_catalog.md5('arena-user:1002')::uuid
   and character_id = pg_catalog.md5('arena-character:1002')::uuid;

-- Privileged fixture-only clock setup: make all current standings pre-midnight, then simulate
-- mutations admitted after midnight before a delayed hourly retry publishes that cutoff.
alter table arena_ranking_private.season_standings disable trigger arena_season_standings_daily_capture;
update arena_ranking_private.season_standings
   set daily_admitted_at =
     (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
       - interval '1 second';
alter table arena_ranking_private.season_standings enable trigger arena_season_standings_daily_capture;

update arena_ranking_private.daily_arena_leaderboard_snapshots
   set settled_at =
     (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
       - interval '12 hours';
update arena_ranking_private.daily_arena_leaderboard_state
   set tracking_started_at =
         (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
           - interval '1 day',
       last_settled_at =
         (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
           - interval '12 hours';
insert into arena_ranking_private.daily_arena_leaderboard_snapshots (
  snapshot_id, season_id, rules_version, settled_at, generated_at,
  is_bootstrap, participant_count, top_entries
)
select 'arena:old:' || n::text, 1, 1,
       (pg_catalog.date_trunc('day', statement_timestamp() at time zone 'UTC') at time zone 'UTC')
         - n * interval '1 day',
       statement_timestamp(), false, 0, '[]'::jsonb
  from pg_catalog.generate_series(2,3) as n;

update shared_player_private.snapshots
   set display_name = 'After cutoff A'
 where user_id = '10000000-0000-4000-8000-000000000001'
   and character_id = '20000000-0000-4000-8000-000000000001';
update shared_player_private.snapshots
   set display_name = 'After cutoff A2'
 where user_id = '10000000-0000-4000-8000-000000000001'
   and character_id = '20000000-0000-4000-8000-000000000001';

delete from shared_player_private.snapshots
 where user_id = '10000000-0000-4000-8000-000000000002'
   and character_id = '20000000-0000-4000-8000-000000000004';
insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values (
  '10000000-0000-4000-8000-000000000002','20000000-0000-4000-8000-000000000004',
  'New B','ROGUE',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'
);
update arena_ranking_private.season_standings
   set score = 900,
       score_achieved_at = statement_timestamp(),
       updated_at = statement_timestamp()
 where user_id = '10000000-0000-4000-8000-000000000002'
   and character_id = '20000000-0000-4000-8000-000000000004';

insert into shared_player_private.snapshots (
  user_id, character_id, display_name, hero_class, level, combat_power,
  rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
) values (
  '10000000-0000-4000-8000-000000000005','20000000-0000-4000-8000-000000000006',
  'Joined late','PALADIN',10,40,1,1,'{}','[]',statement_timestamp(),statement_timestamp()+interval '7 days'
);
insert into arena_ranking_private.season_standings (
  season_id, rules_version, user_id, character_id, display_name, hero_class, level,
  score, completed_battles, wins, losses, draws, score_achieved_at, profile_expires_at, updated_at,
  daily_record_date_utc, daily_record_base, daily_admitted_at
) values (
  1,1,'10000000-0000-4000-8000-000000000005','20000000-0000-4000-8000-000000000006',
  'Joined late','PALADIN',10,1240,10,6,3,1,statement_timestamp(),
  statement_timestamp()+interval '7 days',statement_timestamp(),
  (statement_timestamp() at time zone 'UTC')::date,10,statement_timestamp()
);

do $$
begin
  if (select count(*) from arena_ranking_private.daily_arena_before_images) <> 2
     or (select row_data->>'display_name'
           from arena_ranking_private.daily_arena_before_images
          where user_id = '10000000-0000-4000-8000-000000000001') <> 'Arena A'
     or (select row_data->>'score'
           from arena_ranking_private.daily_arena_before_images
          where user_id = '10000000-0000-4000-8000-000000000002') <> '1100' then
    raise exception 'arena first-before-image cutoff capture failed';
  end if;
end;
$$;

create function pg_temp.reject_daily_arena_fixture_insert()
returns trigger language plpgsql as $$
begin raise exception 'intentional local arena publication failure'; end;
$$;
create trigger daily_arena_fixture_failure
before insert on arena_ranking_private.daily_arena_leaderboard_rows
for each row execute function pg_temp.reject_daily_arena_fixture_insert();
do $$
declare v_old text; v_snapshot_count integer; v_before_count integer;
begin
  select latest_snapshot_id into v_old
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  select count(*) into v_snapshot_count
    from arena_ranking_private.daily_arena_leaderboard_snapshots;
  select count(*) into v_before_count
    from arena_ranking_private.daily_arena_before_images;
  begin
    perform arena_ranking_private.publish_daily_arena_leaderboard();
    raise exception 'arena publication fixture should have failed';
  exception when others then
    if sqlerrm <> 'intentional local arena publication failure' then raise; end if;
  end;
  if (select latest_snapshot_id from arena_ranking_private.daily_arena_leaderboard_state) <> v_old
     or (select count(*) from arena_ranking_private.daily_arena_leaderboard_snapshots) <> v_snapshot_count
     or (select count(*) from arena_ranking_private.daily_arena_before_images) <> v_before_count then
    raise exception 'failed arena publication leaked a partial pointer, snapshot or cutoff image';
  end if;
end;
$$;
drop trigger daily_arena_fixture_failure on arena_ranking_private.daily_arena_leaderboard_rows;
select arena_ranking_private.publish_daily_arena_leaderboard();

do $$
declare v_id text; v_generated timestamptz;
begin
  select snapshot.snapshot_id, snapshot.generated_at into strict v_id, v_generated
    from arena_ranking_private.daily_arena_leaderboard_state as state
    join arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;
  if arena_ranking_private.publish_daily_arena_leaderboard() <> v_id
     or (select generated_at from arena_ranking_private.daily_arena_leaderboard_snapshots
          where snapshot_id = v_id) <> v_generated
     or (select count(*) from arena_ranking_private.daily_arena_leaderboard_snapshots) <> 2
     or exists (select 1 from arena_ranking_private.daily_arena_before_images) then
    raise exception 'arena publication idempotence, latest-two retention or image cleanup failed';
  end if;
  if (select participant_count from arena_ranking_private.daily_arena_leaderboard_snapshots
       where snapshot_id = v_id) <> 1005
     or (select jsonb_array_length(top_entries)
           from arena_ranking_private.daily_arena_leaderboard_snapshots
          where snapshot_id = v_id) <> 997
     or (select pg_column_size(top_entries)
           from arena_ranking_private.daily_arena_leaderboard_snapshots
          where snapshot_id = v_id) > 491520
     or (select count(*) from arena_ranking_private.daily_arena_leaderboard_rows
          where snapshot_id = v_id) <> 1005 then
    raise exception 'arena participant count, precomputed top-997 or payload bound failed';
  end if;
  if (select display_name from arena_ranking_private.daily_arena_leaderboard_rows
       where snapshot_id = v_id
         and character_id = '20000000-0000-4000-8000-000000000001') <> 'Arena A'
     or (select score from arena_ranking_private.daily_arena_leaderboard_rows
       where snapshot_id = v_id
         and character_id = '20000000-0000-4000-8000-000000000004') <> 1100
     or exists (
       select 1 from arena_ranking_private.daily_arena_leaderboard_rows
        where snapshot_id = v_id
          and character_id = '20000000-0000-4000-8000-000000000006'
     )
     or exists (
       select 1 from arena_ranking_private.daily_arena_leaderboard_rows
        where snapshot_id = v_id
          and character_id = pg_catalog.md5('arena-character:1002')::uuid
     ) then
    raise exception 'delayed arena publication did not reconstruct the UTC cutoff';
  end if;
end;
$$;

select set_config('request.jwt.claim.sub', '10000000-0000-4000-8000-000000000001', true);
set local role authenticated;
do $$
declare v_response jsonb; v_unchanged jsonb; v_id text;
begin
  v_response := public.get_daily_arena_leaderboard(null);
  if pg_catalog.jsonb_typeof(v_response->'season_id') <> 'string'
     or v_response->>'season_id' <> '1'
     or (v_response->>'rules_version')::integer <> 1
     or (v_response->>'t')::integer <> 1005
     or jsonb_array_length(v_response->'e') <> 997
     or jsonb_array_length(v_response->'o') <> 3
     or jsonb_array_length(v_response->'e') + jsonb_array_length(v_response->'o') > 1000
     or v_response->'e'->0->>'c' <> '20000000-0000-4000-8000-000000000001'
     or v_response->'e'->0->>'u' = '10000000-0000-4000-8000-000000000001'
     or v_response->'o'->0->>'u' <> v_response->'o'->1->>'u'
     or (v_response->>'next_settlement_at')::bigint -
          (v_response->>'settled_at')::bigint <> 86400000 then
    raise exception 'compact arena daily response contract failed: %', v_response;
  end if;
  v_id := v_response->>'snapshot_id';
  v_unchanged := public.get_daily_arena_leaderboard(v_id);
  if not (v_unchanged->>'unchanged')::boolean
     or v_unchanged ? 'e' or v_unchanged ? 'o' then
    raise exception 'unchanged arena response must omit the ranking bodies: %', v_unchanged;
  end if;
  begin
    perform public.get_daily_arena_leaderboard(pg_catalog.repeat('x',129));
    raise exception 'oversized arena snapshot id unexpectedly accepted';
  exception when sqlstate '22023' then null;
  end;
  begin
    perform count(*) from arena_ranking_private.season_standings;
    raise exception 'authenticated direct arena standing read unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;
end;
$$;
reset role;

-- Anti-cheat state survives profile lifecycle, but deleting the Auth account removes it.
delete from auth.users where id = '10000000-0000-4000-8000-000000000002';
do $$ begin
  if exists (
    select 1 from arena_ranking_private.season_standings
     where user_id = '10000000-0000-4000-8000-000000000002'
  ) or exists (
    select 1 from arena_ranking_private.account_call_limits
     where user_id = '10000000-0000-4000-8000-000000000002'
  ) or exists (
    select 1 from shared_player_private.snapshots
     where user_id = '10000000-0000-4000-8000-000000000002'
  ) then
    raise exception 'Auth account deletion did not cascade arena/profile state';
  end if;
end $$;

select set_config('request.jwt.claim.sub', '', true);
set local role anon;
do $$
begin
  begin
    perform public.get_daily_arena_leaderboard(null);
    raise exception 'anonymous arena daily read unexpectedly succeeded';
  exception when insufficient_privilege then null;
  end;
end;
$$;
reset role;

rollback;
