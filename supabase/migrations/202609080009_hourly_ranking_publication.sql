-- Change the immutable general and arena ranking publications installed by 007/008 from one
-- UTC midnight snapshot to immutable hourly UTC buckets.
--
-- This migration intentionally does not rewrite 007 or 008. Existing completed snapshots remain
-- readable until normal latest-two retention removes them. The transition takes both existing
-- writer barriers, clears unconsumed daily-era before-images and starts a new honest tracking
-- window. Therefore no hourly cutoff before this migration's server timestamp is fabricated.
-- The first fully tracked boundary after deployment is the first eligible hourly publication.

create function ranking_private.utc_hour_bucket(p_at timestamptz)
returns timestamptz
language sql
immutable
returns null on null input
set search_path = ''
as $$
  select pg_catalog.date_trunc('hour', p_at at time zone 'UTC') at time zone 'UTC'
$$;

create function arena_ranking_private.utc_hour_bucket(p_at timestamptz)
returns timestamptz
language sql
immutable
returns null on null input
set search_path = ''
as $$
  select pg_catalog.date_trunc('hour', p_at at time zone 'UTC') at time zone 'UTC'
$$;

revoke all on function ranking_private.utc_hour_bucket(timestamptz),
  arena_ranking_private.utc_hour_bucket(timestamptz)
  from public, anon, authenticated, service_role;

create or replace function ranking_private.capture_daily_ranking_before_image()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admitted_at timestamptz;
  v_cutoff_at timestamptz;
begin
  select greatest(pg_catalog.statement_timestamp(), state.last_settled_at)
    into v_admitted_at
    from ranking_private.daily_leaderboard_state as state
   where state.singleton;
  v_cutoff_at := ranking_private.utc_hour_bucket(v_admitted_at);

  if tg_op <> 'INSERT' and old.daily_admitted_at < v_cutoff_at then
    insert into ranking_private.daily_ranking_before_images as previous (
      user_id, character_id, cutoff_at, row_data
    ) values (
      old.user_id, old.character_id, v_cutoff_at, pg_catalog.to_jsonb(old)
    )
    on conflict (user_id, character_id) do update
      set cutoff_at = excluded.cutoff_at,
          row_data = excluded.row_data
    where previous.cutoff_at < excluded.cutoff_at;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  new.daily_admitted_at := v_admitted_at;
  return new;
end;
$$;

revoke all on function ranking_private.capture_daily_ranking_before_image()
  from public, anon, authenticated, service_role;

create or replace function arena_ranking_private.capture_daily_arena_before_image()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admitted_at timestamptz;
  v_cutoff_at timestamptz;
begin
  select greatest(pg_catalog.statement_timestamp(), state.last_settled_at)
    into v_admitted_at
    from arena_ranking_private.daily_arena_leaderboard_state as state
   where state.singleton;
  v_cutoff_at := arena_ranking_private.utc_hour_bucket(v_admitted_at);

  if tg_op <> 'INSERT' and old.daily_admitted_at < v_cutoff_at then
    insert into arena_ranking_private.daily_arena_before_images as previous (
      season_id, user_id, character_id, cutoff_at, row_data
    ) values (
      old.season_id, old.user_id, old.character_id, v_cutoff_at, pg_catalog.to_jsonb(old)
    )
    on conflict (season_id, user_id, character_id) do update
      set cutoff_at = excluded.cutoff_at,
          row_data = excluded.row_data
    where previous.cutoff_at < excluded.cutoff_at;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  new.daily_admitted_at := v_admitted_at;
  return new;
end;
$$;

revoke all on function arena_ranking_private.capture_daily_arena_before_image()
  from public, anon, authenticated, service_role;

create or replace function ranking_private.publish_daily_leaderboard(
  p_cutoff_at timestamptz default null
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_cutoff_at timestamptz;
  v_state ranking_private.daily_leaderboard_state%rowtype;
  v_snapshot_id text;
  v_bootstrap boolean;
begin
  if pg_catalog.current_setting('transaction_isolation') <> 'read committed' then
    raise exception 'hourly ranking publication requires READ COMMITTED';
  end if;

  -- An already published hour returns after one state lookup and does not contend with writers.
  -- An untracked transition bucket also keeps the previous honest snapshot.
  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from ranking_private.daily_leaderboard_state where singleton;
  v_cutoff_at := coalesce(
    p_cutoff_at,
    ranking_private.utc_hour_bucket(v_now)
  );
  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future ranking cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  if v_state.latest_snapshot_id is not null and v_cutoff_at < v_state.tracking_started_at then
    if p_cutoff_at is not null then
      raise exception 'only the latest fully tracked UTC hourly bucket can be settled';
    end if;
    return v_state.latest_snapshot_id;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(20260905, 1);
  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from ranking_private.daily_leaderboard_state where singleton;
  v_bootstrap := v_state.latest_snapshot_id is null;
  v_cutoff_at := coalesce(
    p_cutoff_at,
    case when v_bootstrap then v_now
      else ranking_private.utc_hour_bucket(v_now)
    end
  );

  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future ranking cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  if v_bootstrap then
    if p_cutoff_at is not null then
      raise exception 'bootstrap cutoff must use actual server time';
    end if;
  elsif v_cutoff_at < v_state.tracking_started_at then
    if p_cutoff_at is not null then
      raise exception 'only the latest fully tracked UTC hourly bucket can be settled';
    end if;
    return v_state.latest_snapshot_id;
  elsif v_cutoff_at <> ranking_private.utc_hour_bucket(v_now) then
    -- One before-image per row retains the latest recoverable boundary. After a long outage, skip
    -- imaginary missed buckets and publish only the current UTC hour boundary.
    raise exception 'only the latest fully tracked UTC hourly bucket can be settled';
  end if;

  v_snapshot_id := case when v_bootstrap then 'bootstrap:' else 'utc:' end ||
    ((extract(epoch from v_cutoff_at) * 1000)::bigint)::text;
  insert into ranking_private.daily_leaderboard_snapshots (
    snapshot_id, settled_at, generated_at, is_bootstrap, participant_count, top_entries
  ) values (v_snapshot_id, v_cutoff_at, v_now, v_bootstrap, 0, '[]'::jsonb);

  with cutoff_rows as (
    select row.user_id, row.character_id, row.slot_id, row.display_name,
           row.system_entry_code, row.hero_class, row.level, row.combat_power,
           row.achieved_at, row.updated_at
      from public.ranking_entries as row
     where v_bootstrap or row.daily_admitted_at < v_cutoff_at
    union all
    select row.user_id, row.character_id, row.slot_id, row.display_name,
           row.system_entry_code, row.hero_class, row.level, row.combat_power,
           row.achieved_at, row.updated_at
      from ranking_private.daily_ranking_before_images as saved
      cross join lateral pg_catalog.jsonb_populate_record(
        null::public.ranking_entries, saved.row_data
      ) as row
     where not v_bootstrap and saved.cutoff_at = v_cutoff_at
  ), ranked as (
    -- Legacy gatekeeper rows remain stored for compatibility but no longer participate in any
    -- adventurer rank, participant count or published Top 1000 payload.
    select row.*,
           rank() over (order by row.combat_power desc) as rank_number,
           row_number() over (
             order by row.combat_power desc, row.achieved_at asc,
                      row.character_id, row.user_id
           ) - 1 as list_index
      from cutoff_rows as row
     where row.level between 20 and 10000
       and row.combat_power >= 0
       and row.combat_power <= ranking_private.maximum_accepted_combat_power(row.level)
       and row.system_entry_code is null
  )
  insert into ranking_private.daily_leaderboard_rows (
    snapshot_id, user_id, character_id, slot_id, rank_number, list_index,
    display_name, system_entry_code, hero_class, level, combat_power,
    achieved_at, updated_at, compact_entry
  )
  select v_snapshot_id, row.user_id, row.character_id, row.slot_id,
         row.rank_number, row.list_index, row.display_name, row.system_entry_code,
         row.hero_class, row.level, row.combat_power, row.achieved_at, row.updated_at,
         pg_catalog.jsonb_build_object(
           'r', row.rank_number, 'i', row.list_index, 'c', row.character_id,
           'n', row.display_name, 'h', row.hero_class, 'l', row.level,
           'p', row.combat_power,
           'a', (extract(epoch from row.achieved_at) * 1000)::bigint
         ) || case when row.system_entry_code is null then '{}'::jsonb
           else pg_catalog.jsonb_build_object('s', row.system_entry_code) end
    from ranked as row;

  update ranking_private.daily_leaderboard_snapshots as snapshot
     set participant_count = (
           select pg_catalog.count(*)::integer
             from ranking_private.daily_leaderboard_rows as row
            where row.snapshot_id = v_snapshot_id
         ),
         top_entries = coalesce((
           select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
             from ranking_private.daily_leaderboard_rows as row
            where row.snapshot_id = v_snapshot_id and row.list_index < 1000
         ), '[]'::jsonb),
         generated_at = pg_catalog.clock_timestamp()
   where snapshot.snapshot_id = v_snapshot_id;

  update ranking_private.daily_leaderboard_state
     set latest_snapshot_id = v_snapshot_id,
         last_settled_at = v_cutoff_at
   where singleton;

  delete from ranking_private.daily_leaderboard_snapshots as snapshot
   where snapshot.snapshot_id not in (
     select keep.snapshot_id
       from ranking_private.daily_leaderboard_snapshots as keep
      order by keep.settled_at desc
      limit 2
   );
  delete from ranking_private.daily_ranking_before_images
   where cutoff_at <= v_cutoff_at;
  return v_snapshot_id;
end;
$$;

revoke all on function ranking_private.publish_daily_leaderboard(timestamptz)
  from public, anon, authenticated;
grant execute on function ranking_private.publish_daily_leaderboard(timestamptz)
  to service_role;

create or replace function arena_ranking_private.publish_daily_arena_leaderboard(
  p_cutoff_at timestamptz default null
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_cutoff_at timestamptz;
  v_state arena_ranking_private.daily_arena_leaderboard_state%rowtype;
  v_snapshot_id text;
  v_bootstrap boolean;
begin
  if pg_catalog.current_setting('transaction_isolation') <> 'read committed' then
    raise exception 'hourly arena publication requires READ COMMITTED';
  end if;

  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  v_cutoff_at := coalesce(
    p_cutoff_at,
    arena_ranking_private.utc_hour_bucket(v_now)
  );
  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future arena cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  if v_state.latest_snapshot_id is not null and v_cutoff_at < v_state.tracking_started_at then
    if p_cutoff_at is not null then
      raise exception 'only the latest fully tracked UTC hourly bucket can be settled for arena';
    end if;
    return v_state.latest_snapshot_id;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(20260908, 8);
  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  v_bootstrap := v_state.latest_snapshot_id is null;
  v_cutoff_at := coalesce(
    p_cutoff_at,
    case when v_bootstrap then v_now
      else arena_ranking_private.utc_hour_bucket(v_now)
    end
  );

  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future arena cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  if v_bootstrap then
    if p_cutoff_at is not null then
      raise exception 'arena bootstrap cutoff must use actual server time';
    end if;
  elsif v_cutoff_at < v_state.tracking_started_at then
    if p_cutoff_at is not null then
      raise exception 'only the latest fully tracked UTC hourly bucket can be settled for arena';
    end if;
    return v_state.latest_snapshot_id;
  elsif v_cutoff_at <> arena_ranking_private.utc_hour_bucket(v_now) then
    raise exception 'only the latest fully tracked UTC hourly bucket can be settled for arena';
  end if;

  v_snapshot_id := 'arena:s' || v_state.season_id::text || ':r' ||
    v_state.rules_version::text || ':' || case when v_bootstrap then 'bootstrap:' else 'utc:' end ||
    ((extract(epoch from v_cutoff_at) * 1000)::bigint)::text;

  insert into arena_ranking_private.daily_arena_leaderboard_snapshots (
    snapshot_id, season_id, rules_version, settled_at, generated_at,
    is_bootstrap, participant_count, top_entries
  ) values (
    v_snapshot_id, v_state.season_id, v_state.rules_version, v_cutoff_at, v_now,
    v_bootstrap, 0, '[]'::jsonb
  );

  with cutoff_rows as (
    select standing.season_id, standing.rules_version, standing.user_id,
           standing.character_id, standing.display_name, standing.hero_class,
           standing.level, standing.score, standing.completed_battles,
           standing.wins, standing.losses, standing.draws,
           standing.score_achieved_at, standing.profile_expires_at, standing.updated_at
      from arena_ranking_private.season_standings as standing
     where standing.season_id = v_state.season_id
       and standing.rules_version = v_state.rules_version
       and (v_bootstrap or standing.daily_admitted_at < v_cutoff_at)
    union all
    select standing.season_id, standing.rules_version, standing.user_id,
           standing.character_id, standing.display_name, standing.hero_class,
           standing.level, standing.score, standing.completed_battles,
           standing.wins, standing.losses, standing.draws,
           standing.score_achieved_at, standing.profile_expires_at, standing.updated_at
      from arena_ranking_private.daily_arena_before_images as saved
      cross join lateral pg_catalog.jsonb_populate_record(
        null::arena_ranking_private.season_standings,
        saved.row_data
      ) as standing
     where not v_bootstrap
       and saved.season_id = v_state.season_id
       and saved.cutoff_at = v_cutoff_at
  ), ranked as (
    select standing.*,
           rank() over (
             order by standing.score desc, standing.wins desc,
                      standing.losses asc, standing.draws desc
           ) as rank_number,
           row_number() over (
             order by standing.score desc, standing.wins desc,
                      standing.losses asc, standing.draws desc,
                      standing.score_achieved_at asc,
                      standing.character_id,
                      pg_catalog.md5('arena-ranking-public-v1:' || standing.user_id::text)::uuid
           ) - 1 as list_index
      from cutoff_rows as standing
     where standing.level between 10 and 10000
       and standing.profile_expires_at > v_cutoff_at
       and standing.completed_battles between 10 and 1000000
       and standing.wins::bigint + standing.losses::bigint + standing.draws::bigint =
           standing.completed_battles::bigint
       and standing.score::bigint between greatest(
         0::bigint,
         1000::bigint - 24::bigint * standing.completed_battles::bigint
       ) and 1000::bigint + 24::bigint * standing.completed_battles::bigint
  )
  insert into arena_ranking_private.daily_arena_leaderboard_rows (
    snapshot_id, user_id, character_id, rank_number, list_index,
    display_name, hero_class, level, score, completed_battles,
    wins, losses, draws, score_achieved_at, compact_entry
  )
  select v_snapshot_id, standing.user_id, standing.character_id,
         standing.rank_number, standing.list_index,
         standing.display_name, standing.hero_class, standing.level,
         standing.score, standing.completed_battles,
         standing.wins, standing.losses, standing.draws, standing.score_achieved_at,
         pg_catalog.jsonb_build_object(
           'r', standing.rank_number,
           'i', standing.list_index,
           'u', pg_catalog.md5('arena-ranking-public-v1:' || standing.user_id::text)::uuid,
           'c', standing.character_id,
           'n', standing.display_name,
           'h', standing.hero_class,
           'l', standing.level,
           'p', standing.score,
           'b', standing.completed_battles,
           'w', standing.wins,
           'x', standing.losses,
           'd', standing.draws,
           'a', (extract(epoch from standing.score_achieved_at) * 1000)::bigint
         )
    from ranked as standing;

  update arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
     set participant_count = (
       select pg_catalog.count(*)::integer
         from arena_ranking_private.daily_arena_leaderboard_rows as row
        where row.snapshot_id = v_snapshot_id
     ),
         top_entries = coalesce((
           select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
             from arena_ranking_private.daily_arena_leaderboard_rows as row
            where row.snapshot_id = v_snapshot_id
              and row.list_index < 997
         ), '[]'::jsonb),
         generated_at = pg_catalog.clock_timestamp()
   where snapshot.snapshot_id = v_snapshot_id;

  update arena_ranking_private.daily_arena_leaderboard_state
     set latest_snapshot_id = v_snapshot_id,
         last_settled_at = v_cutoff_at
   where singleton;

  delete from arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
   where snapshot.snapshot_id not in (
     select keep.snapshot_id
       from arena_ranking_private.daily_arena_leaderboard_snapshots as keep
      order by keep.settled_at desc
      limit 2
   );
  delete from arena_ranking_private.daily_arena_before_images
   where cutoff_at <= v_cutoff_at;

  -- Run bounded stale-guard cleanup only after a real bucket publication. A repeated call in the
  -- same hour returns before this work.
  with doomed as (
    select guard.user_id, guard.character_id
      from arena_ranking_private.entry_sync_limits as guard
     where guard.last_called_at <= v_now - interval '90 days'
       and not exists (
         select 1 from arena_ranking_private.season_standings as standing
          where standing.user_id = guard.user_id
            and standing.character_id = guard.character_id
       )
     order by guard.last_called_at, guard.user_id, guard.character_id
     limit 500
     for update of guard skip locked
  )
  delete from arena_ranking_private.entry_sync_limits as guard
   using doomed
   where guard.user_id = doomed.user_id
     and guard.character_id = doomed.character_id;

  with doomed as (
    select guard.user_id
      from arena_ranking_private.account_call_limits as guard
     where guard.last_called_at <= v_now - interval '90 days'
       and not exists (
         select 1 from arena_ranking_private.entry_sync_limits as entry
          where entry.user_id = guard.user_id
       )
       and not exists (
         select 1 from arena_ranking_private.season_standings as standing
          where standing.user_id = guard.user_id
       )
     order by guard.last_called_at, guard.user_id
     limit 500
     for update of guard skip locked
  )
  delete from arena_ranking_private.account_call_limits as guard
   using doomed
   where guard.user_id = doomed.user_id;

  return v_snapshot_id;
end;
$$;

revoke all on function arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)
  from public, anon, authenticated, service_role;
grant execute on function arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)
  to service_role;

create or replace function public.get_daily_leaderboard(
  p_known_snapshot_id text default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot ranking_private.daily_leaderboard_snapshots%rowtype;
  v_tracking_started_at timestamptz;
  v_response jsonb;
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  select snapshot.snapshot_id, snapshot.settled_at, snapshot.generated_at,
         snapshot.is_bootstrap, snapshot.participant_count, snapshot.top_entries,
         state.tracking_started_at
    into strict v_snapshot.snapshot_id, v_snapshot.settled_at, v_snapshot.generated_at,
         v_snapshot.is_bootstrap, v_snapshot.participant_count, v_snapshot.top_entries,
         v_tracking_started_at
    from ranking_private.daily_leaderboard_state as state
    join ranking_private.daily_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;
  v_response := pg_catalog.jsonb_build_object(
    'snapshot_id', v_snapshot.snapshot_id,
    'settled_at', (extract(epoch from v_snapshot.settled_at) * 1000)::bigint,
    'next_settlement_at', (extract(epoch from case
      -- A 007 bootstrap was stamped at its real migration instant rather than at a UTC boundary.
      -- Waiting bootstrap+8h would advertise a time at which this bucket publisher can never run.
      when v_snapshot.is_bootstrap then
        ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 1 minute'
      else greatest(
        v_snapshot.settled_at + interval '1 hour 1 minute',
        ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 1 minute'
      )
    end) * 1000)::bigint,
    'generated_at', (extract(epoch from v_snapshot.generated_at) * 1000)::bigint,
    'server_now', (extract(epoch from pg_catalog.statement_timestamp()) * 1000)::bigint,
    'is_bootstrap', v_snapshot.is_bootstrap,
    'unchanged', coalesce(p_known_snapshot_id = v_snapshot.snapshot_id, false),
    't', v_snapshot.participant_count
  );
  if p_known_snapshot_id = v_snapshot.snapshot_id then return v_response; end if;
  return v_response || pg_catalog.jsonb_build_object(
    'e', v_snapshot.top_entries,
    'o', coalesce((
      select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
        from ranking_private.daily_leaderboard_rows as row
       where row.snapshot_id = v_snapshot.snapshot_id and row.user_id = v_user_id
    ), '[]'::jsonb)
  );
end;
$$;

revoke all on function public.get_daily_leaderboard(text) from public, anon;
grant execute on function public.get_daily_leaderboard(text) to authenticated;

create or replace function public.get_daily_arena_leaderboard(
  p_known_snapshot_id text default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot arena_ranking_private.daily_arena_leaderboard_snapshots%rowtype;
  v_tracking_started_at timestamptz;
  v_response jsonb;
  v_own_entries jsonb;
begin
  if v_user_id is null then
    raise exception 'authentication required';
  end if;
  if p_known_snapshot_id is not null
     and pg_catalog.octet_length(p_known_snapshot_id) > 128 then
    raise exception 'invalid arena snapshot id' using errcode = '22023';
  end if;

  select snapshot.snapshot_id, snapshot.season_id, snapshot.rules_version,
         snapshot.settled_at, snapshot.generated_at, snapshot.is_bootstrap,
         snapshot.participant_count, snapshot.top_entries, state.tracking_started_at
    into strict v_snapshot.snapshot_id, v_snapshot.season_id, v_snapshot.rules_version,
         v_snapshot.settled_at, v_snapshot.generated_at, v_snapshot.is_bootstrap,
         v_snapshot.participant_count, v_snapshot.top_entries, v_tracking_started_at
    from arena_ranking_private.daily_arena_leaderboard_state as state
    join arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;

  v_response := pg_catalog.jsonb_build_object(
    'snapshot_id', v_snapshot.snapshot_id,
    'season_id', v_snapshot.season_id::text,
    'rules_version', v_snapshot.rules_version,
    'settled_at', (extract(epoch from v_snapshot.settled_at) * 1000)::bigint,
    'next_settlement_at', (extract(epoch from case
      -- An 008 bootstrap also uses its honest migration instant, not a schedulable UTC bucket.
      when v_snapshot.is_bootstrap then
        arena_ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 18 minutes'
      else greatest(
        v_snapshot.settled_at + interval '1 hour 18 minutes',
        arena_ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 18 minutes'
      )
    end) * 1000)::bigint,
    'generated_at', (extract(epoch from v_snapshot.generated_at) * 1000)::bigint,
    'server_now', (extract(epoch from pg_catalog.statement_timestamp()) * 1000)::bigint,
    'is_bootstrap', v_snapshot.is_bootstrap,
    'unchanged', coalesce(p_known_snapshot_id = v_snapshot.snapshot_id, false),
    't', v_snapshot.participant_count
  );
  if p_known_snapshot_id = v_snapshot.snapshot_id then
    return v_response;
  end if;

  select coalesce(
           pg_catalog.jsonb_agg(owned.compact_entry order by owned.list_index),
           '[]'::jsonb
         )
    into v_own_entries
    from (
      select row.list_index, row.compact_entry
        from arena_ranking_private.daily_arena_leaderboard_rows as row
       where row.snapshot_id = v_snapshot.snapshot_id
         and row.user_id = v_user_id
       order by row.list_index
       limit 3
    ) as owned;

  return v_response || pg_catalog.jsonb_build_object(
    'e', v_snapshot.top_entries,
    'o', v_own_entries
  );
end;
$$;

revoke all on function public.get_daily_arena_leaderboard(text)
  from public, anon, authenticated, service_role;
grant execute on function public.get_daily_arena_leaderboard(text) to authenticated;

comment on function public.get_daily_leaderboard(text) is
  'Immutable UTC hourly ranking snapshots at every UTC hour. A known snapshot returns metadata only.';
comment on function public.get_daily_arena_leaderboard(text) is
  'Immutable UTC hourly compact arena standings at every UTC hour. Global top and own arrays together contain at most 1,000 rows; own rows can also occur in the global top and account identifiers are stable pseudonyms.';

-- Establish the new honest tracking epoch atomically with the function swap. Existing daily-era
-- before-images cannot prove an hourly boundary and are deliberately discarded under both writer
-- barriers. No application row or completed snapshot is fabricated here.
do $$
declare
  v_tracking_started_at timestamptz;
begin
  perform pg_catalog.pg_advisory_xact_lock(20260905, 1);
  perform pg_catalog.pg_advisory_xact_lock(20260908, 8);
  v_tracking_started_at := pg_catalog.clock_timestamp();

  update ranking_private.daily_leaderboard_state
     set tracking_started_at = v_tracking_started_at
   where singleton;
  update arena_ranking_private.daily_arena_leaderboard_state
     set tracking_started_at = v_tracking_started_at
   where singleton;
  delete from ranking_private.daily_ranking_before_images;
  delete from arena_ranking_private.daily_arena_before_images;
end;
$$;

-- Keep the two hourly writers separated: general publishes at :00 and arena at :17. Read RPCs
-- advertise :01 and :18 respectively, leaving one minute for each transaction to complete before
-- clients refresh their immutable cache. A healthy client therefore reads each board once an hour.
create extension if not exists pg_cron with schema pg_catalog;
select cron.unschedule(jobid)
  from cron.job
 where jobname = 'alarmquest-daily-leaderboard';
select cron.schedule(
  'alarmquest-daily-leaderboard',
  '0 * * * *',
  'select ranking_private.publish_daily_leaderboard()'
);
select cron.unschedule(jobid)
  from cron.job
 where jobname = 'alarmquest-daily-arena-leaderboard';
select cron.schedule(
  'alarmquest-daily-arena-leaderboard',
  '17 * * * *',
  'select arena_ranking_private.publish_daily_arena_leaderboard()'
);

-- Deployment-time catalog guardrails only. This block never publishes a new snapshot and creates
-- no fixture/application row.
do $$
declare
  v_general_capture text;
  v_general_publish text;
  v_arena_capture text;
  v_arena_publish text;
begin
  select pg_catalog.pg_get_functiondef(
    'ranking_private.capture_daily_ranking_before_image()'::pg_catalog.regprocedure
  ) into v_general_capture;
  select pg_catalog.pg_get_functiondef(
    'ranking_private.publish_daily_leaderboard(timestamptz)'::pg_catalog.regprocedure
  ) into v_general_publish;
  select pg_catalog.pg_get_functiondef(
    'arena_ranking_private.capture_daily_arena_before_image()'::pg_catalog.regprocedure
  ) into v_arena_capture;
  select pg_catalog.pg_get_functiondef(
    'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)'::pg_catalog.regprocedure
  ) into v_arena_publish;

  if pg_catalog.strpos(v_general_capture, 'ranking_private.utc_hour_bucket') = 0
     or pg_catalog.strpos(v_general_publish, 'pg_advisory_xact_lock(20260905, 1)') = 0
     or pg_catalog.strpos(v_general_publish, 'row.list_index < 1000') = 0
     or pg_catalog.strpos(v_general_publish, 'row.system_entry_code is null') = 0
     or pg_catalog.strpos(v_arena_capture, 'arena_ranking_private.utc_hour_bucket') = 0
     or pg_catalog.strpos(v_arena_publish, 'pg_advisory_xact_lock(20260908, 8)') = 0
     or pg_catalog.strpos(v_arena_publish, 'arena-ranking-public-v1:') = 0
     or pg_catalog.strpos(v_arena_publish, 'row.list_index < 997') = 0
     or ranking_private.utc_hour_bucket('2026-09-08 23:59:59.999+00')
       <> '2026-09-08 23:00:00+00'::timestamptz
     or arena_ranking_private.utc_hour_bucket('2026-09-08 15:59:59.999+00')
       <> '2026-09-08 15:00:00+00'::timestamptz then
    raise exception 'hourly ranking publication contract self-check failed';
  end if;

  if pg_catalog.has_table_privilege(
       'authenticated', 'ranking_private.daily_leaderboard_rows', 'SELECT'
     )
     or pg_catalog.has_table_privilege(
       'authenticated', 'arena_ranking_private.season_standings', 'SELECT'
     )
     or not pg_catalog.has_function_privilege(
       'authenticated', 'public.get_daily_leaderboard(text)', 'EXECUTE'
     )
     or not pg_catalog.has_function_privilege(
       'authenticated', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
     )
     or pg_catalog.has_function_privilege(
       'anon', 'public.get_daily_leaderboard(text)', 'EXECUTE'
     )
     or pg_catalog.has_function_privilege(
       'anon', 'public.get_daily_arena_leaderboard(text)', 'EXECUTE'
     ) then
    raise exception 'hourly ranking ACL self-check failed';
  end if;

  if (
    select pg_catalog.count(*)
      from cron.job
     where jobname = 'alarmquest-daily-leaderboard'
       and schedule = '0 * * * *'
  ) <> 1
     or (
       select pg_catalog.count(*)
         from cron.job
        where jobname = 'alarmquest-daily-arena-leaderboard'
          and schedule = '17 * * * *'
     ) <> 1 then
    raise exception 'hourly ranking cron separation self-check failed';
  end if;
end;
$$;
