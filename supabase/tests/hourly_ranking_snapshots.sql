-- ISOLATED LOCAL DATABASE ONLY. Requires migrations through 009 and rolls every fixture row back.
begin;

do $$
declare
  v_general_id text;
  v_arena_id text;
  v_general_count integer;
  v_arena_count integer;
begin
  if exists (select 1 from auth.users)
     or exists (select 1 from public.ranking_entries)
     or exists (select 1 from arena_ranking_private.season_standings) then
    raise exception 'hourly ranking fixture requires an EMPTY ISOLATED LOCAL database';
  end if;

  select latest_snapshot_id into strict v_general_id
    from ranking_private.daily_leaderboard_state where singleton;
  select latest_snapshot_id into strict v_arena_id
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  select count(*) into v_general_count
    from ranking_private.daily_leaderboard_snapshots;
  select count(*) into v_arena_count
    from arena_ranking_private.daily_arena_leaderboard_snapshots;

  -- 009 must retain the prior honest publication and must not label the partially tracked current
  -- bucket as if it had observed its entire input window.
  if ranking_private.publish_daily_leaderboard() <> v_general_id
     or arena_ranking_private.publish_daily_arena_leaderboard() <> v_arena_id
     or (select count(*) from ranking_private.daily_leaderboard_snapshots) <> v_general_count
     or (select count(*) from arena_ranking_private.daily_arena_leaderboard_snapshots) <> v_arena_count then
    raise exception '009 transition fabricated a partially tracked hourly snapshot';
  end if;

  if has_table_privilege('authenticated', 'ranking_private.daily_leaderboard_rows', 'SELECT')
     or has_table_privilege('authenticated', 'arena_ranking_private.season_standings', 'SELECT')
     or has_function_privilege(
       'authenticated', 'ranking_private.publish_daily_leaderboard(timestamptz)', 'EXECUTE'
     )
     or has_function_privilege(
       'authenticated',
       'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)',
       'EXECUTE'
     )
     or not has_function_privilege(
       'service_role', 'ranking_private.publish_daily_leaderboard(timestamptz)', 'EXECUTE'
     )
     or not has_function_privilege(
       'service_role',
       'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)',
       'EXECUTE'
     ) then
    raise exception '009 changed the private writer/reader privilege boundary';
  end if;
end;
$$;

do $$
begin
  if ranking_private.utc_hour_bucket('2026-09-08 00:00:00+00')
       <> '2026-09-08 00:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-08 00:59:59.999+00')
       <> '2026-09-08 00:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-08 07:59:59.999+00')
       <> '2026-09-08 07:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-08 08:00:00+00')
       <> '2026-09-08 08:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-08 15:59:59.999+00')
       <> '2026-09-08 15:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-08 16:00:00+00')
       <> '2026-09-08 16:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-08 23:59:59.999+00')
       <> '2026-09-08 23:00:00+00'::timestamptz
     or ranking_private.utc_hour_bucket('2026-09-09 01:30:00+09')
       <> '2026-09-08 16:00:00+00'::timestamptz
     or arena_ranking_private.utc_hour_bucket('2026-09-08 15:59:59.999+00')
       <> '2026-09-08 15:00:00+00'::timestamptz then
    raise exception 'UTC hourly bucket floor failed';
  end if;
end;
$$;

-- Every UTC hour creates a distinct cutoff ID. A healthy hourly scheduler therefore has no
-- repeated-bucket invocations within one UTC day.
do $$
declare
  v_distinct_buckets integer;
  v_repeated_hours integer;
begin
  with hourly as (
    select hour,
           ranking_private.utc_hour_bucket(
             '2026-09-08 00:00:00+00'::timestamptz + hour * interval '1 hour'
           ) as bucket
      from generate_series(0, 23) as hour
  ), marked as (
    select hour, bucket, lag(bucket) over (order by hour) as previous_bucket
      from hourly
  )
  select count(distinct bucket)::integer,
         count(*) filter (where previous_bucket = bucket)::integer
    into v_distinct_buckets, v_repeated_hours
    from marked;

  if v_distinct_buckets <> 24 or v_repeated_hours <> 0 then
    raise exception 'hourly schedule must produce twenty-four hourly publications';
  end if;
end;
$$;

-- Transition retry-storm regression. Model an application at HH:27 whose retained daily snapshot
-- is older than the current bucket. The response must point at the first fully tracked boundary,
-- rather than returning an already elapsed snapshot+1h timestamp.
do $$
declare
  v_bucket timestamptz := ranking_private.utc_hour_bucket(clock_timestamp());
begin
  -- 007/008 bootstrap timestamps are honest migration instants, not bucket boundaries. Put the
  -- bootstrap 27 minutes into this bucket so bootstrap+1h would be unschedulable and observable.
  update ranking_private.daily_leaderboard_snapshots
     set settled_at = v_bucket + interval '27 minutes';
  update arena_ranking_private.daily_arena_leaderboard_snapshots
     set settled_at = v_bucket + interval '27 minutes';
  update ranking_private.daily_leaderboard_state
     set tracking_started_at = v_bucket + interval '27 minutes';
  update arena_ranking_private.daily_arena_leaderboard_state
     set tracking_started_at = v_bucket + interval '27 minutes';
end;
$$;

select set_config('request.jwt.claim.sub', '80000000-0000-4000-8000-000000000001', true);
do $$
declare
  v_general_expected bigint := (
    extract(epoch from (
      ranking_private.utc_hour_bucket(clock_timestamp()) + interval '1 hour 1 minute'
    )) * 1000
  )::bigint;
  v_arena_expected bigint := (
    extract(epoch from (
      arena_ranking_private.utc_hour_bucket(clock_timestamp()) + interval '1 hour 18 minutes'
    )) * 1000
  )::bigint;
  v_general jsonb := public.get_daily_leaderboard(null);
  v_arena jsonb := public.get_daily_arena_leaderboard(null);
begin
  if (v_general->>'next_settlement_at')::bigint <> v_general_expected
     or (v_arena->>'next_settlement_at')::bigint <> v_arena_expected
     or (v_general->>'next_settlement_at')::bigint <= (v_general->>'server_now')::bigint
     or (v_arena->>'next_settlement_at')::bigint <= (v_arena->>'server_now')::bigint then
    raise exception 'bootstrap transition advertised an unschedulable retry time';
  end if;
end;
$$;

-- Fixture-only clock setup: retain the migration bootstrap as an older snapshot, add one previous
-- completed bucket, and make the current bucket fully tracked. Production never rewrites history.
do $$
declare
  v_bucket timestamptz := ranking_private.utc_hour_bucket(clock_timestamp());
  v_general_bootstrap text;
  v_arena_bootstrap text;
begin
  select latest_snapshot_id into strict v_general_bootstrap
    from ranking_private.daily_leaderboard_state where singleton;
  select latest_snapshot_id into strict v_arena_bootstrap
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;

  update ranking_private.daily_leaderboard_snapshots
     set settled_at = v_bucket - interval '24 hours'
   where snapshot_id = v_general_bootstrap;
  insert into ranking_private.daily_leaderboard_snapshots (
    snapshot_id, settled_at, generated_at, is_bootstrap, participant_count, top_entries
  ) values (
    'fixture:general:previous', v_bucket - interval '1 hour', clock_timestamp(), false, 0, '[]'
  );
  update ranking_private.daily_leaderboard_state
     set tracking_started_at = v_bucket - interval '24 hours',
         latest_snapshot_id = 'fixture:general:previous',
         last_settled_at = v_bucket - interval '1 hour'
   where singleton;

  update arena_ranking_private.daily_arena_leaderboard_snapshots
     set settled_at = v_bucket - interval '24 hours'
   where snapshot_id = v_arena_bootstrap;
  insert into arena_ranking_private.daily_arena_leaderboard_snapshots (
    snapshot_id, season_id, rules_version, settled_at, generated_at,
    is_bootstrap, participant_count, top_entries
  ) values (
    'fixture:arena:previous', 1, 1, v_bucket - interval '1 hour',
    clock_timestamp(), false, 0, '[]'
  );
  update arena_ranking_private.daily_arena_leaderboard_state
     set tracking_started_at = v_bucket - interval '24 hours',
         latest_snapshot_id = 'fixture:arena:previous',
         last_settled_at = v_bucket - interval '1 hour'
   where singleton;
end;
$$;

insert into auth.users(id) values
  ('81000000-0000-4000-8000-000000000001'),
  ('81000000-0000-4000-8000-000000000002'),
  ('81000000-0000-4000-8000-000000000003'),
  ('81000000-0000-4000-8000-000000000004');

alter table public.ranking_entries disable trigger ranking_entries_daily_capture;
insert into public.ranking_entries (
  user_id, character_id, slot_id, display_name, hero_class, level, combat_power,
  achieved_at, updated_at, daily_admitted_at, system_entry_code
) values
  ('81000000-0000-4000-8000-000000000001','82000000-0000-4000-8000-000000000001',
   1,'General before A','WARRIOR',20,100,clock_timestamp()-interval '1 day',
   clock_timestamp()-interval '1 day',ranking_private.utc_hour_bucket(clock_timestamp())-interval '1 second',null),
  ('81000000-0000-4000-8000-000000000002','82000000-0000-4000-8000-000000000002',
   1,'General before B','MAGE',20,80,clock_timestamp()-interval '1 day',
   clock_timestamp()-interval '1 day',ranking_private.utc_hour_bucket(clock_timestamp())-interval '1 second',null),
  ('81000000-0000-4000-8000-000000000004','82000000-0000-4000-8000-000000000004',
   1,'Legacy gatekeeper','WARRIOR',20,150,clock_timestamp()-interval '1 day',
   clock_timestamp()-interval '1 day',ranking_private.utc_hour_bucket(clock_timestamp())-interval '1 second',
   'RANK_GATE_01');
alter table public.ranking_entries enable trigger ranking_entries_daily_capture;

-- Seed an older missed-bucket image. The first mutation after the current boundary must replace it
-- with the current cutoff and preserve that mutation's before-image exactly once.
insert into ranking_private.daily_ranking_before_images (
  user_id, character_id, cutoff_at, row_data
)
select row.user_id, row.character_id,
       ranking_private.utc_hour_bucket(clock_timestamp()) - interval '1 hour',
       to_jsonb(row)
  from public.ranking_entries as row
 where row.system_entry_code is null;

update public.ranking_entries
   set combat_power = 200, display_name = 'General after A'
 where character_id = '82000000-0000-4000-8000-000000000001';
update public.ranking_entries
   set combat_power = 210, display_name = 'General after A2'
 where character_id = '82000000-0000-4000-8000-000000000001';
delete from public.ranking_entries
 where character_id = '82000000-0000-4000-8000-000000000002';
insert into public.ranking_entries (
  user_id, character_id, slot_id, display_name, hero_class, level, combat_power
) values (
  '81000000-0000-4000-8000-000000000003','82000000-0000-4000-8000-000000000003',
  1,'General joined late','RANGER',20,250
);

alter table arena_ranking_private.season_standings
  disable trigger arena_season_standings_daily_capture;
insert into arena_ranking_private.season_standings (
  season_id, rules_version, user_id, character_id, display_name, hero_class, level,
  score, completed_battles, wins, losses, draws, score_achieved_at,
  profile_expires_at, updated_at, daily_record_date_utc, daily_record_base,
  daily_admitted_at
) values
  (1,1,'81000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000001',
   'Arena before A','WARRIOR',20,1100,10,5,4,1,clock_timestamp()-interval '1 day',
   clock_timestamp()+interval '7 days',clock_timestamp()-interval '1 day',
   (clock_timestamp() at time zone 'UTC')::date,10,
   arena_ranking_private.utc_hour_bucket(clock_timestamp())-interval '1 second'),
  (1,1,'81000000-0000-4000-8000-000000000002','83000000-0000-4000-8000-000000000002',
   'Arena before B','MAGE',20,1050,10,4,4,2,clock_timestamp()-interval '1 day',
   clock_timestamp()+interval '7 days',clock_timestamp()-interval '1 day',
   (clock_timestamp() at time zone 'UTC')::date,10,
   arena_ranking_private.utc_hour_bucket(clock_timestamp())-interval '1 second');
alter table arena_ranking_private.season_standings
  enable trigger arena_season_standings_daily_capture;

insert into arena_ranking_private.daily_arena_before_images (
  season_id, user_id, character_id, cutoff_at, row_data
)
select row.season_id, row.user_id, row.character_id,
       arena_ranking_private.utc_hour_bucket(clock_timestamp()) - interval '1 hour',
       to_jsonb(row)
  from arena_ranking_private.season_standings as row;

update arena_ranking_private.season_standings
   set score = 900, display_name = 'Arena after A', updated_at = clock_timestamp()
 where character_id = '83000000-0000-4000-8000-000000000001';
update arena_ranking_private.season_standings
   set score = 880, display_name = 'Arena after A2', updated_at = clock_timestamp()
 where character_id = '83000000-0000-4000-8000-000000000001';
delete from arena_ranking_private.season_standings
 where character_id = '83000000-0000-4000-8000-000000000002';
insert into arena_ranking_private.season_standings (
  season_id, rules_version, user_id, character_id, display_name, hero_class, level,
  score, completed_battles, wins, losses, draws, score_achieved_at,
  profile_expires_at, updated_at, daily_record_date_utc, daily_record_base,
  daily_admitted_at
) values (
  1,1,'81000000-0000-4000-8000-000000000003','83000000-0000-4000-8000-000000000003',
  'Arena joined late','RANGER',20,1200,10,6,3,1,clock_timestamp(),
  clock_timestamp()+interval '7 days',clock_timestamp(),
  (clock_timestamp() at time zone 'UTC')::date,10,clock_timestamp()
);

do $$
declare
  v_bucket timestamptz := ranking_private.utc_hour_bucket(clock_timestamp());
begin
  if (select count(*) from ranking_private.daily_ranking_before_images) <> 2
     or exists (
       select 1 from ranking_private.daily_ranking_before_images where cutoff_at <> v_bucket
     )
     or (select row_data->>'combat_power'
           from ranking_private.daily_ranking_before_images
          where character_id = '82000000-0000-4000-8000-000000000001') <> '100'
     or (select count(*) from arena_ranking_private.daily_arena_before_images) <> 2
     or exists (
       select 1 from arena_ranking_private.daily_arena_before_images where cutoff_at <> v_bucket
     )
     or (select row_data->>'score'
           from arena_ranking_private.daily_arena_before_images
          where character_id = '83000000-0000-4000-8000-000000000001') <> '1100' then
    raise exception 'latest recoverable hourly before-image cutoff failed';
  end if;
end;
$$;

create function pg_temp.reject_hourly_general_insert()
returns trigger language plpgsql as $$
begin raise exception 'intentional hourly general publication failure'; end;
$$;
create trigger reject_hourly_general_insert
before insert on ranking_private.daily_leaderboard_rows
for each row execute function pg_temp.reject_hourly_general_insert();

create function pg_temp.reject_hourly_arena_insert()
returns trigger language plpgsql as $$
begin raise exception 'intentional hourly arena publication failure'; end;
$$;
create trigger reject_hourly_arena_insert
before insert on arena_ranking_private.daily_arena_leaderboard_rows
for each row execute function pg_temp.reject_hourly_arena_insert();

do $$
declare
  v_general_id text;
  v_arena_id text;
  v_general_snapshots integer;
  v_arena_snapshots integer;
  v_general_images integer;
  v_arena_images integer;
begin
  select latest_snapshot_id into strict v_general_id
    from ranking_private.daily_leaderboard_state where singleton;
  select latest_snapshot_id into strict v_arena_id
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  select count(*) into v_general_snapshots from ranking_private.daily_leaderboard_snapshots;
  select count(*) into v_arena_snapshots
    from arena_ranking_private.daily_arena_leaderboard_snapshots;
  select count(*) into v_general_images from ranking_private.daily_ranking_before_images;
  select count(*) into v_arena_images from arena_ranking_private.daily_arena_before_images;

  begin
    perform ranking_private.publish_daily_leaderboard();
    raise exception 'general failure fixture unexpectedly published';
  exception when others then
    if sqlerrm <> 'intentional hourly general publication failure' then raise; end if;
  end;
  begin
    perform arena_ranking_private.publish_daily_arena_leaderboard();
    raise exception 'arena failure fixture unexpectedly published';
  exception when others then
    if sqlerrm <> 'intentional hourly arena publication failure' then raise; end if;
  end;

  if (select latest_snapshot_id from ranking_private.daily_leaderboard_state) <> v_general_id
     or (select latest_snapshot_id
           from arena_ranking_private.daily_arena_leaderboard_state) <> v_arena_id
     or (select count(*) from ranking_private.daily_leaderboard_snapshots) <> v_general_snapshots
     or (select count(*)
           from arena_ranking_private.daily_arena_leaderboard_snapshots) <> v_arena_snapshots
     or (select count(*) from ranking_private.daily_ranking_before_images) <> v_general_images
     or (select count(*)
           from arena_ranking_private.daily_arena_before_images) <> v_arena_images then
    raise exception 'failed hourly publication leaked pointer, row or before-image state';
  end if;
end;
$$;

drop trigger reject_hourly_general_insert on ranking_private.daily_leaderboard_rows;
drop trigger reject_hourly_arena_insert
  on arena_ranking_private.daily_arena_leaderboard_rows;

select ranking_private.publish_daily_leaderboard();
select arena_ranking_private.publish_daily_arena_leaderboard();

do $$
declare
  v_bucket timestamptz := ranking_private.utc_hour_bucket(clock_timestamp());
  v_general_id text;
  v_arena_id text;
  v_general_generated timestamptz;
  v_arena_generated timestamptz;
begin
  select snapshot.snapshot_id, snapshot.generated_at
    into strict v_general_id, v_general_generated
    from ranking_private.daily_leaderboard_state as state
    join ranking_private.daily_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;
  select snapshot.snapshot_id, snapshot.generated_at
    into strict v_arena_id, v_arena_generated
    from arena_ranking_private.daily_arena_leaderboard_state as state
    join arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;

  if (select settled_at from ranking_private.daily_leaderboard_snapshots
       where snapshot_id = v_general_id) <> v_bucket
     or (select settled_at from arena_ranking_private.daily_arena_leaderboard_snapshots
       where snapshot_id = v_arena_id) <> v_bucket
     or (select participant_count from ranking_private.daily_leaderboard_snapshots
       where snapshot_id = v_general_id) <> 2
     or (select participant_count from arena_ranking_private.daily_arena_leaderboard_snapshots
       where snapshot_id = v_arena_id) <> 2
     or exists (select 1 from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_general_id and system_entry_code is not null)
     or (select combat_power from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_general_id
         and character_id = '82000000-0000-4000-8000-000000000001') <> 100
     or (select score from arena_ranking_private.daily_arena_leaderboard_rows
       where snapshot_id = v_arena_id
         and character_id = '83000000-0000-4000-8000-000000000001') <> 1100
     or exists (select 1 from ranking_private.daily_leaderboard_rows
       where snapshot_id = v_general_id
         and character_id = '82000000-0000-4000-8000-000000000003')
     or exists (select 1 from arena_ranking_private.daily_arena_leaderboard_rows
       where snapshot_id = v_arena_id
         and character_id = '83000000-0000-4000-8000-000000000003')
     or exists (select 1 from ranking_private.daily_ranking_before_images)
     or exists (select 1 from arena_ranking_private.daily_arena_before_images)
     or (select count(*) from ranking_private.daily_leaderboard_snapshots) <> 2
     or (select count(*) from arena_ranking_private.daily_arena_leaderboard_snapshots) <> 2 then
    raise exception 'hourly cutoff reconstruction or latest-two retention failed';
  end if;

  -- Multiple same-hour cron retries do no work and do not touch generated_at.
  if ranking_private.publish_daily_leaderboard() <> v_general_id
     or ranking_private.publish_daily_leaderboard() <> v_general_id
     or arena_ranking_private.publish_daily_arena_leaderboard() <> v_arena_id
     or arena_ranking_private.publish_daily_arena_leaderboard() <> v_arena_id
     or (select generated_at from ranking_private.daily_leaderboard_snapshots
          where snapshot_id = v_general_id) <> v_general_generated
     or (select generated_at from arena_ranking_private.daily_arena_leaderboard_snapshots
          where snapshot_id = v_arena_id) <> v_arena_generated then
    raise exception 'same-bucket hourly retry was not an immutable metadata no-op';
  end if;
end;
$$;

select set_config('request.jwt.claim.sub', '81000000-0000-4000-8000-000000000001', true);
set local role authenticated;
do $$
declare
  v_general jsonb;
  v_general_unchanged jsonb;
  v_general_legacy jsonb;
  v_arena jsonb;
  v_arena_unchanged jsonb;
begin
  v_general := public.get_daily_leaderboard(null);
  v_general_unchanged := public.get_daily_leaderboard(v_general->>'snapshot_id');
  v_general_legacy := public.get_leaderboard_v2(
    '82000000-0000-4000-8000-000000000001', 1000
  );
  v_arena := public.get_daily_arena_leaderboard(null);
  v_arena_unchanged := public.get_daily_arena_leaderboard(v_arena->>'snapshot_id');

  if (v_general->>'next_settlement_at')::bigint -
       (v_general->>'settled_at')::bigint <> 3660000
     or (v_arena->>'next_settlement_at')::bigint -
       (v_arena->>'settled_at')::bigint <> 4680000
     or jsonb_array_length(v_general->'e') <> 2
     or jsonb_array_length(v_general->'o') <> 1
     or not (v_general_legacy ?& array['t','e','m'])
     or jsonb_array_length(v_arena->'e') <> 2
     or jsonb_array_length(v_arena->'o') <> 1
     or pg_catalog.jsonb_typeof(v_arena->'season_id') <> 'string'
     or not (v_general_unchanged->>'unchanged')::boolean
     or v_general_unchanged ? 'e' or v_general_unchanged ? 'o'
     or not (v_arena_unchanged->>'unchanged')::boolean
     or v_arena_unchanged ? 'e' or v_arena_unchanged ? 'o' then
    raise exception 'general/arena hourly RPC envelope compatibility failed';
  end if;
end;
$$;
reset role;

do $$
begin
  begin
    perform ranking_private.publish_daily_leaderboard(clock_timestamp() + interval '1 hour');
    raise exception 'future general cutoff unexpectedly accepted';
  exception when others then
    if sqlerrm <> 'cannot publish a future ranking cutoff' then raise; end if;
  end;
  begin
    perform arena_ranking_private.publish_daily_arena_leaderboard(
      clock_timestamp() + interval '1 hour'
    );
    raise exception 'future arena cutoff unexpectedly accepted';
  exception when others then
    if sqlerrm <> 'cannot publish a future arena cutoff' then raise; end if;
  end;
end;
$$;

rollback;
