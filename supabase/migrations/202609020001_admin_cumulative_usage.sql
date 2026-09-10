-- Durable per-user usage counts for the native administrator companion app.
-- Raw session logs are pruned after 30 days and daily aggregates after 90 days,
-- so cumulative counts must be maintained independently at insert time.

create table if not exists public.admin_user_session_totals (
  user_id uuid primary key references auth.users(id) on delete cascade,
  total_session_count bigint not null default 0 check (total_session_count >= 0),
  last_session_at timestamptz,
  updated_at timestamptz not null default now()
);

create index if not exists admin_user_session_totals_count_idx
  on public.admin_user_session_totals (total_session_count desc, user_id);

alter table public.admin_user_session_totals enable row level security;
revoke all on table public.admin_user_session_totals from anon, authenticated;
grant select on table public.admin_user_session_totals to service_role;

create or replace function ranking_private.increment_admin_user_session_total()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  insert into public.admin_user_session_totals (
    user_id,
    total_session_count,
    last_session_at,
    updated_at
  ) values (
    new.user_id,
    1,
    new.ended_at,
    now()
  )
  on conflict (user_id) do update
    set total_session_count = public.admin_user_session_totals.total_session_count + 1,
        last_session_at = greatest(
          public.admin_user_session_totals.last_session_at,
          excluded.last_session_at
        ),
        updated_at = excluded.updated_at;

  return new;
end;
$$;

revoke all on function ranking_private.increment_admin_user_session_total()
  from public, anon, authenticated;

-- Supabase executes each migration statement separately. Keeping the lock,
-- historical backfill, and trigger installation inside one DO statement makes
-- them one transaction and prevents a session insert from falling through the
-- gap. Waiting inserts resume after this block commits and hit the trigger.
do $migration$
begin
  lock table public.app_session_logs in share row exclusive mode;

  with raw_daily as (
    select logs.user_id,
           (logs.ended_at at time zone 'UTC')::date as activity_date,
           count(*)::bigint as session_count,
           max(logs.ended_at) as last_ended_at
      from public.app_session_logs as logs
     group by logs.user_id,
              (logs.ended_at at time zone 'UTC')::date
  ),
  merged_daily as (
    select coalesce(daily.user_id, raw.user_id) as user_id,
           coalesce(daily.activity_date, raw.activity_date) as activity_date,
           greatest(
             coalesce(daily.session_count::bigint, 0),
             coalesce(raw.session_count, 0)
           ) as session_count,
           greatest(daily.last_ended_at, raw.last_ended_at) as last_ended_at
      from public.app_session_daily as daily
      full join raw_daily as raw using (user_id, activity_date)
  ),
  per_user as (
    select merged.user_id,
           sum(merged.session_count)::bigint as total_session_count,
           max(merged.last_ended_at) as last_session_at
      from merged_daily as merged
     group by merged.user_id
  )
  insert into public.admin_user_session_totals (
    user_id,
    total_session_count,
    last_session_at,
    updated_at
  )
  select users.user_id,
         users.total_session_count,
         users.last_session_at,
         now()
    from per_user as users
  on conflict (user_id) do update
    set total_session_count = greatest(
          public.admin_user_session_totals.total_session_count,
          excluded.total_session_count
        ),
        last_session_at = greatest(
          public.admin_user_session_totals.last_session_at,
          excluded.last_session_at
        ),
        updated_at = now();

  drop trigger if exists app_session_logs_admin_total
    on public.app_session_logs;
  create trigger app_session_logs_admin_total
  after insert on public.app_session_logs
  for each row execute function ranking_private.increment_admin_user_session_total();
end;
$migration$;

create or replace function public.admin_companion_usage_snapshot()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_result jsonb;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  select jsonb_build_object(
           'generated_at', now(),
           'users', coalesce(
             jsonb_agg(
               jsonb_build_object(
                 'user_id', subscribers.user_id,
                 'session_count', coalesce(totals.total_session_count, 0),
                 'last_session_at', totals.last_session_at,
                 'country_code', profiles.country_code
               ) order by
                 coalesce(totals.total_session_count, 0) desc,
                 subscribers.registered_at desc,
                 subscribers.user_id
             ),
             '[]'::jsonb
           )
         )
    into v_result
    from public.admin_subscribers as subscribers
    left join public.admin_user_session_totals as totals
      on totals.user_id = subscribers.user_id
    left join public.user_profiles as profiles
      on profiles.user_id = subscribers.user_id;

  return v_result;
end;
$$;

revoke all on function public.admin_companion_usage_snapshot()
  from public, anon;
grant execute on function public.admin_companion_usage_snapshot()
  to authenticated;
