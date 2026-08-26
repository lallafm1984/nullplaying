-- Compact leaderboard responses and bounded lifecycle analytics retention.
-- Keep get_leaderboard intact for already-installed development clients.

create or replace function public.get_leaderboard_v2(
  p_character_id uuid,
  p_limit integer default 1000
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  with ranked as (
    select rank() over (
             order by r.combat_power desc
           ) as rank_number,
           row_number() over (
             order by r.combat_power desc, r.achieved_at asc, r.character_id
           ) - 1 as list_index,
           r.user_id,
           r.character_id,
           r.display_name,
           r.hero_class,
           r.level,
           r.combat_power,
           r.achieved_at,
           count(*) over ()::integer as total_participants
      from public.ranking_entries as r
     where r.level between 20 and 10000
       and r.combat_power <= ranking_private.maximum_accepted_combat_power(r.level)
  ),
  visible as (
    select *
      from ranked
     where rank_number <= least(greatest(p_limit, 1), 1000)
  )
  select pg_catalog.jsonb_build_object(
    't', coalesce((select pg_catalog.max(total_participants) from ranked), 0),
    'e', coalesce(
      (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'r', rank_number,
            'i', list_index,
            'c', character_id,
            'n', display_name,
            'h', hero_class,
            'l', level,
            'p', combat_power,
            'a', (extract(epoch from achieved_at) * 1000)::bigint
          ) order by list_index
        )
          from visible
      ),
      '[]'::jsonb
    ),
    'm', (
      select pg_catalog.jsonb_build_object(
        'r', rank_number,
        'i', list_index,
        'c', character_id,
        'n', display_name,
        'h', hero_class,
        'l', level,
        'p', combat_power,
        'a', (extract(epoch from achieved_at) * 1000)::bigint
      )
        from ranked
       where user_id = auth.uid()
         and character_id = p_character_id
       limit 1
    )
  );
$$;

revoke all on function public.get_leaderboard_v2(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard_v2(uuid, integer) to authenticated;

create index if not exists app_session_logs_ended_at_idx
  on public.app_session_logs (ended_at);

create table if not exists public.app_session_daily (
  user_id uuid not null references auth.users(id) on delete cascade,
  activity_date date not null,
  session_count integer not null check (session_count >= 0),
  last_ended_at timestamptz not null,
  last_slot_id smallint,
  peak_hero_level bigint,
  peak_combat_power bigint,
  last_total_acts bigint,
  last_total_kills bigint,
  updated_at timestamptz not null default now(),
  primary key (user_id, activity_date)
);

alter table public.app_session_daily enable row level security;
revoke all on table public.app_session_daily from anon, authenticated;

create or replace function ranking_private.aggregate_and_prune_session_logs()
returns void
language plpgsql
security definer
set search_path = ''
as $$
begin
  with summarized as (
    select logs.user_id,
           (logs.ended_at at time zone 'UTC')::date as activity_date,
           pg_catalog.count(*)::integer as session_count,
           pg_catalog.max(logs.ended_at) as last_ended_at,
           pg_catalog.max(logs.hero_level) as peak_hero_level,
           pg_catalog.max(logs.combat_power) as peak_combat_power
      from public.app_session_logs as logs
     group by logs.user_id, (logs.ended_at at time zone 'UTC')::date
  ),
  latest as (
    select distinct on (logs.user_id, (logs.ended_at at time zone 'UTC')::date)
           logs.user_id,
           (logs.ended_at at time zone 'UTC')::date as activity_date,
           logs.slot_id,
           logs.total_acts,
           logs.total_kills
      from public.app_session_logs as logs
     order by logs.user_id,
              (logs.ended_at at time zone 'UTC')::date,
              logs.ended_at desc,
              logs.created_at desc,
              logs.event_id
  )
  insert into public.app_session_daily (
    user_id,
    activity_date,
    session_count,
    last_ended_at,
    last_slot_id,
    peak_hero_level,
    peak_combat_power,
    last_total_acts,
    last_total_kills,
    updated_at
  )
  select summarized.user_id,
         summarized.activity_date,
         summarized.session_count,
         summarized.last_ended_at,
         latest.slot_id,
         summarized.peak_hero_level,
         summarized.peak_combat_power,
         latest.total_acts,
         latest.total_kills,
         pg_catalog.now()
    from summarized
    join latest using (user_id, activity_date)
  on conflict (user_id, activity_date) do update
    set session_count = excluded.session_count,
        last_ended_at = excluded.last_ended_at,
        last_slot_id = excluded.last_slot_id,
        peak_hero_level = excluded.peak_hero_level,
        peak_combat_power = excluded.peak_combat_power,
        last_total_acts = excluded.last_total_acts,
        last_total_kills = excluded.last_total_kills,
        updated_at = excluded.updated_at;

  delete from public.app_session_logs
   where ended_at < pg_catalog.now() - interval '30 days';

  delete from public.app_session_daily
   where activity_date < (current_date - 90);
end;
$$;

revoke all on function ranking_private.aggregate_and_prune_session_logs()
  from public, anon, authenticated;

create extension if not exists pg_cron with schema pg_catalog;

select cron.unschedule(jobid)
  from cron.job
 where jobname = 'alarmquest-session-retention';

select cron.schedule(
  'alarmquest-session-retention',
  '17 3 * * *',
  'select ranking_private.aggregate_and_prune_session_logs()'
);
