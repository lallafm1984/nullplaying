-- Supabase SQL Editor (postgres): developer arena data only.
-- Abort if another account has arena data; no Auth/general ranking/profile deletion.
begin isolation level read committed;
set local lock_timeout = '3s';
set local statement_timeout = '20s';
select pg_advisory_xact_lock(20260908, 8);

do $$
declare
  v_user constant uuid := '00000000-0000-0000-0000-000000000000';
  v_tables constant text[] := array[
    'season_standings', 'daily_arena_before_images',
    'season_character_bindings', 'entry_sync_limits',
    'account_call_limits', 'daily_arena_leaderboard_rows'
  ];
  v_table text;
  v_has_other boolean;
  v_snapshot text;
begin
  if v_user = '00000000-0000-0000-0000-000000000000'::uuid then
    raise exception 'Set v_user to the verified developer Auth user UUID first.';
  end if;
  -- Check every table BEFORE deleting anything.
  foreach v_table in array v_tables loop
    execute format(
      'select exists(select 1 from arena_ranking_private.%I where user_id <> $1)',
      v_table
    ) into v_has_other using v_user;
    if v_has_other then
      raise exception 'Other arena users exist in %. Cleanup cancelled.', v_table;
    end if;
  end loop;

  if exists (
    select 1
    from arena_ranking_private.daily_arena_leaderboard_snapshots s,
         lateral jsonb_array_elements(s.top_entries) e
    where e->>'u' is distinct from
          (md5('arena-ranking-public-v1:' || v_user::text)::uuid)::text
  ) then
    raise exception 'Other users exist in cached rankings. Cleanup cancelled.';
  end if;

  -- Delete standings before before-images: the delete trigger may create an image.
  foreach v_table in array v_tables loop
    execute format('delete from arena_ranking_private.%I where user_id = $1', v_table)
      using v_user;
  end loop;

  -- Remove the old public cache, then publish a fresh empty snapshot/version.
  update arena_ranking_private.daily_arena_leaderboard_state
  set latest_snapshot_id = null, last_settled_at = null,
      tracking_started_at = clock_timestamp()
  where singleton;
  delete from arena_ranking_private.daily_arena_leaderboard_snapshots;
  v_snapshot := arena_ranking_private.publish_daily_arena_leaderboard();

  update arena_ranking_private.daily_arena_leaderboard_state st
  set tracking_started_at = s.settled_at
  from arena_ranking_private.daily_arena_leaderboard_snapshots s
  where st.singleton and s.snapshot_id = v_snapshot;
end $$;

select s.snapshot_id, s.participant_count,
       (select count(*) from arena_ranking_private.season_standings) as remaining_standings
from arena_ranking_private.daily_arena_leaderboard_state st
join arena_ranking_private.daily_arena_leaderboard_snapshots s
  on s.snapshot_id = st.latest_snapshot_id
where st.singleton;
commit;
