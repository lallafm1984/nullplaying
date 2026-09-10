-- ISOLATED LOCAL DATABASE ONLY. Entire fixture is rolled back. The fixture
-- refuses a database containing any auth users or live ranking rows.
-- Run: PGLITE_MODULE_PATH=/tmp/.../node_modules/@electric-sql/pglite \
--        node tools/test-daily-ranking.mjs
-- Or run this SQL against a pristine local Postgres after ranking migrations.
begin;

do $$
declare v_initial ranking_private.daily_leaderboard_snapshots%rowtype;
begin
  if exists (select 1 from auth.users) or exists (select 1 from public.ranking_entries) then
    raise exception 'daily ranking fixture requires an EMPTY ISOLATED LOCAL database';
  end if;
  select * into strict v_initial from ranking_private.daily_leaderboard_snapshots;
  if not v_initial.is_bootstrap or v_initial.snapshot_id not like 'bootstrap:%'
     or v_initial.settled_at < (select tracking_started_at from ranking_private.daily_leaderboard_state)
     or v_initial.participant_count <> 0 then
    raise exception 'initial snapshot must have an honest bootstrap timestamp';
  end if;
  if ranking_private.publish_daily_leaderboard() <> v_initial.snapshot_id then
    raise exception 'bootstrap day must not fabricate a past midnight';
  end if;
  if has_table_privilege('authenticated', 'ranking_private.daily_leaderboard_rows', 'SELECT')
     or has_table_privilege('anon', 'ranking_private.daily_leaderboard_snapshots', 'SELECT')
     or has_table_privilege('authenticated', 'ranking_private.daily_ranking_before_images', 'SELECT')
     or has_function_privilege('authenticated', 'ranking_private.publish_daily_leaderboard(timestamptz)', 'EXECUTE')
     or has_function_privilege('anon', 'public.get_daily_leaderboard(text)', 'EXECUTE')
     or not has_function_privilege('service_role', 'ranking_private.publish_daily_leaderboard(timestamptz)', 'EXECUTE') then
    raise exception 'daily ranking privilege boundary failed';
  end if;
end;
$$;

-- Establish yesterday's server state without a real midnight wait. These
-- privileged fixture edits are isolated and never part of the production path.
update ranking_private.daily_leaderboard_snapshots
   set settled_at = (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC') - interval '12 hours';
update ranking_private.daily_leaderboard_state
   set tracking_started_at = (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC') - interval '1 day',
       last_settled_at = (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC') - interval '12 hours';
insert into ranking_private.daily_leaderboard_snapshots
  (snapshot_id, settled_at, generated_at, participant_count, top_entries)
select 'old:' || n,
       (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC') - n * interval '1 day',
       clock_timestamp(), 0, '[]'::jsonb from generate_series(2,3) as n;

insert into auth.users (id)
select ('10000000-0000-0000-0000-' || lpad(n::text,12,'0'))::uuid
  from generate_series(1,1002) as n;
insert into auth.users (id) values
  ('20000000-0000-0000-0000-000000000001'), -- owner of all three slots
  ('20000000-0000-0000-0000-000000000002'), -- changed after cutoff
  ('20000000-0000-0000-0000-000000000003'), -- auth deletion after cutoff
  ('20000000-0000-0000-0000-000000000004'), -- deleted/recreated character
  ('20000000-0000-0000-0000-000000000005'), -- invalid legacy score
  ('20000000-0000-0000-0000-000000000006'); -- first joined after cutoff

insert into public.ranking_entries (
  user_id, character_id, slot_id, display_name, hero_class, level, combat_power, system_entry_code
)
select id, id, 1, 'Tied ' || right(id::text,4), 'WARRIOR', 20, 250,
       case when id = '10000000-0000-0000-0000-000000000001' then 'RANK_GATE_01' else null end
  from auth.users where id::text like '10000000%';
insert into public.ranking_entries (
  user_id, character_id, slot_id, display_name, hero_class, level, combat_power
) values
  ('20000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000001',1,'Own A','WARRIOR',20,100),
  ('20000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000002',2,'Own B','MAGE',20,90),
  ('20000000-0000-0000-0000-000000000001','30000000-0000-0000-0000-000000000003',3,'Own C','CLERIC',20,80),
  ('20000000-0000-0000-0000-000000000002','30000000-0000-0000-0000-000000000004',1,'Before rename','WARRIOR',20,120),
  ('20000000-0000-0000-0000-000000000003','30000000-0000-0000-0000-000000000005',1,'Deleted','WARRIOR',20,110),
  ('20000000-0000-0000-0000-000000000004','30000000-0000-0000-0000-000000000006',1,'Recreated','WARRIOR',20,105),
  ('20000000-0000-0000-0000-000000000005','30000000-0000-0000-0000-000000000007',1,'Invalid legacy','WARRIOR',20,999);

alter table public.ranking_entries disable trigger ranking_entries_daily_capture;
alter table public.ranking_entries disable trigger ranking_entries_timestamps;
update public.ranking_entries
   set daily_admitted_at = (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC') - interval '1 second',
       achieved_at = (date_trunc('day', clock_timestamp() at time zone 'UTC') at time zone 'UTC') - interval '1 hour';
alter table public.ranking_entries enable trigger ranking_entries_timestamps;
alter table public.ranking_entries enable trigger ranking_entries_daily_capture;

-- Simulate mutations received after midnight BEFORE a delayed publisher runs.
update public.ranking_entries set combat_power = 220, display_name = 'After rename'
 where character_id = '30000000-0000-0000-0000-000000000004';
update public.ranking_entries set combat_power = 230
 where character_id = '30000000-0000-0000-0000-000000000004';
delete from auth.users where id = '20000000-0000-0000-0000-000000000003';
delete from public.ranking_entries where character_id = '30000000-0000-0000-0000-000000000006';
insert into public.ranking_entries (user_id, character_id, slot_id, display_name, hero_class, level, combat_power)
values ('20000000-0000-0000-0000-000000000004','30000000-0000-0000-0000-000000000006',1,'New incarnation','MAGE',20,240),
       ('20000000-0000-0000-0000-000000000006','30000000-0000-0000-0000-000000000008',1,'Joined late','MAGE',20,255);

-- Exercise the existing validated full-roster RPC, not just administrator DML.
select set_config('request.jwt.claim.sub', '20000000-0000-0000-0000-000000000001', true);
set local role authenticated;
select public.sync_ranking_entries('[
  {"character_id":"30000000-0000-0000-0000-000000000001","slot_id":1,"display_name":"Own A","hero_class":"WARRIOR","level":20,"combat_power":150},
  {"character_id":"30000000-0000-0000-0000-000000000002","slot_id":2,"display_name":"Own B","hero_class":"MAGE","level":20,"combat_power":90},
  {"character_id":"30000000-0000-0000-0000-000000000003","slot_id":3,"display_name":"Own C","hero_class":"CLERIC","level":20,"combat_power":80}
]'::jsonb);
reset role;

do $$
begin
  if (select count(*) from ranking_private.daily_ranking_before_images) <> 4
     or (select row_data->>'combat_power' from ranking_private.daily_ranking_before_images
          where character_id = '30000000-0000-0000-0000-000000000004') <> '120' then
    raise exception 'first before-image must be saved once, unaffected by later updates';
  end if;
end;
$$;

-- Force a failure during ranked-row insertion, then verify the old publication
-- remains intact and retry the same cutoff successfully.
create function pg_temp.reject_daily_fixture_insert() returns trigger language plpgsql as $$
begin raise exception 'intentional local publication failure'; end; $$;
create trigger daily_fixture_failure before insert on ranking_private.daily_leaderboard_rows
for each row execute function pg_temp.reject_daily_fixture_insert();
do $$
declare v_old text; v_count integer;
begin
  select latest_snapshot_id into v_old from ranking_private.daily_leaderboard_state;
  select count(*) into v_count from ranking_private.daily_leaderboard_snapshots;
  begin
    perform ranking_private.publish_daily_leaderboard();
    raise exception 'fixture publication should have failed';
  exception when others then
    if sqlerrm <> 'intentional local publication failure' then raise; end if;
  end;
  if (select latest_snapshot_id from ranking_private.daily_leaderboard_state) <> v_old
     or (select count(*) from ranking_private.daily_leaderboard_snapshots) <> v_count
     or (select count(*) from ranking_private.daily_ranking_before_images) <> 4 then
    raise exception 'failed publication leaked a partial snapshot or lost cutoff data';
  end if;
end;
$$;
drop trigger daily_fixture_failure on ranking_private.daily_leaderboard_rows;
select ranking_private.publish_daily_leaderboard();

do $$
declare v_id text; v_generated timestamptz; v_response jsonb;
begin
  select s.snapshot_id, s.generated_at into v_id, v_generated
    from ranking_private.daily_leaderboard_snapshots as s
    join ranking_private.daily_leaderboard_state as state on state.latest_snapshot_id = s.snapshot_id;
  if ranking_private.publish_daily_leaderboard() <> v_id
     or (select generated_at from ranking_private.daily_leaderboard_snapshots where snapshot_id = v_id) <> v_generated
     or (select count(*) from ranking_private.daily_leaderboard_snapshots) <> 2 then
    raise exception 'publication idempotence or two-settlement retention failed';
  end if;
  if exists (select 1 from ranking_private.daily_ranking_before_images) then
    raise exception 'consumed before-images were not pruned';
  end if;
  if (select combat_power from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_id and character_id = '30000000-0000-0000-0000-000000000004') <> 120
     or (select display_name from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_id and character_id = '30000000-0000-0000-0000-000000000004') <> 'Before rename'
     or (select combat_power from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_id and character_id = '30000000-0000-0000-0000-000000000005') <> 110
     or (select combat_power from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_id and character_id = '30000000-0000-0000-0000-000000000006') <> 105
     or exists (select 1 from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_id and character_id in ('30000000-0000-0000-0000-000000000007','30000000-0000-0000-0000-000000000008')) then
    raise exception 'delayed settlement did not reconstruct the cutoff roster';
  end if;
  begin
    perform ranking_private.publish_daily_leaderboard(clock_timestamp() + interval '1 day');
    raise exception 'future cutoff unexpectedly accepted';
  exception when others then
    if sqlerrm <> 'cannot publish a future ranking cutoff' then raise; end if;
  end;
end;
$$;

set local role authenticated;
do $$
declare v_response jsonb; v_unchanged jsonb; v_v2 jsonb; v_other jsonb; v_total integer;
begin
  v_response := public.get_daily_leaderboard();
  if (v_response->>'t')::integer <> 1008 or jsonb_array_length(v_response->'e') <> 1000
     or jsonb_array_length(v_response->'o') <> 3
     or (v_response->'o'->0->>'r')::integer <> 1006
     or (v_response->'o'->0->>'p')::integer <> 100
     or (v_response->'o'->2->>'r')::integer <> 1008
     or (v_response->'e'->999->>'i')::integer <> 999
     or (v_response->'e'->999->>'r')::integer <> 1
     or v_response->'e'->0->>'s' <> 'RANK_GATE_01'
     or (v_response->>'is_bootstrap')::boolean
     or (v_response->>'next_settlement_at')::bigint - (v_response->>'settled_at')::bigint <> 86400000 then
    raise exception 'daily body, exact own ranks, deterministic ties, system code or UTC timing failed';
  end if;
  v_unchanged := public.get_daily_leaderboard(v_response->>'snapshot_id');
  if not (v_unchanged->>'unchanged')::boolean or v_unchanged ? 'e' or v_unchanged ? 'o' then
    raise exception 'unchanged response must omit leaderboard bodies';
  end if;
  v_v2 := public.get_leaderboard_v2('30000000-0000-0000-0000-000000000002',10);
  if jsonb_array_length(v_v2->'e') <> 10 or (v_v2->'m'->>'r')::integer <> 1007 then
    raise exception 'legacy compact RPC must use exact daily rank and row limit';
  end if;
  v_other := public.get_leaderboard_v2('30000000-0000-0000-0000-000000000004',10);
  if v_other->'m' <> 'null'::jsonb then
    raise exception 'caller must not fetch another user private own-row position';
  end if;
  select count(*) into v_total from public.get_leaderboard('30000000-0000-0000-0000-000000000002',10);
  if v_total <> 10 or not exists (
    select 1
      from public.get_leaderboard('30000000-0000-0000-0000-000000000002',10) as visible
     where visible.character_id = '30000000-0000-0000-0000-000000000002'
  ) then
    raise exception 'legacy table RPC must reserve one of ten bounded rows for the exact own row';
  end if;
  perform set_config('request.jwt.claim.sub', '', true);
  begin
    perform public.get_daily_leaderboard();
    raise exception 'missing identity unexpectedly accepted';
  exception when others then
    if sqlerrm <> 'authentication required' then raise; end if;
  end;
end;
$$;
reset role;

rollback;
