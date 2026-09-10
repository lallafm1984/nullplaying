-- Durable per-user activity days and completed weekly return retention for the
-- native administrator companion app.
--
-- Raw session logs are pruned after 30 days, so a compact user/day table is
-- maintained independently. Weekly retention counts a user once when they
-- complete at least one session during days 1-7, 8-14, 15-21, or 22-28 after
-- signup. Only cohorts whose full target week has completed are eligible.

create table if not exists public.admin_user_activity_days (
  user_id uuid not null references auth.users(id) on delete cascade,
  activity_date date not null,
  first_ended_at timestamptz not null,
  last_ended_at timestamptz not null,
  session_count bigint not null default 1 check (session_count > 0),
  updated_at timestamptz not null default now(),
  primary key (user_id, activity_date)
);

create index if not exists admin_user_activity_days_date_idx
  on public.admin_user_activity_days (activity_date, user_id);

alter table public.admin_user_activity_days enable row level security;
revoke all on table public.admin_user_activity_days from anon, authenticated;
grant select on table public.admin_user_activity_days to service_role;

create or replace function ranking_private.upsert_admin_user_activity_day()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_activity_date date := (new.ended_at at time zone 'Asia/Seoul')::date;
begin
  insert into public.admin_user_activity_days (
    user_id,
    activity_date,
    first_ended_at,
    last_ended_at,
    session_count,
    updated_at
  ) values (
    new.user_id,
    v_activity_date,
    new.ended_at,
    new.ended_at,
    1,
    now()
  )
  on conflict (user_id, activity_date) do update
    set first_ended_at = least(
          public.admin_user_activity_days.first_ended_at,
          excluded.first_ended_at
        ),
        last_ended_at = greatest(
          public.admin_user_activity_days.last_ended_at,
          excluded.last_ended_at
        ),
        session_count = public.admin_user_activity_days.session_count + 1,
        updated_at = excluded.updated_at;

  return new;
end;
$$;

revoke all on function ranking_private.upsert_admin_user_activity_day()
  from public, anon, authenticated;

-- Supabase executes migration statements separately. Keeping the lock,
-- historical backfill, and trigger installation inside one DO statement makes
-- the transition atomic. Waiting inserts resume after commit and hit the new
-- trigger, while retries cannot inflate backfilled counts.
do $migration$
begin
  lock table public.app_session_logs in share row exclusive mode;

  insert into public.admin_user_activity_days (
    user_id,
    activity_date,
    first_ended_at,
    last_ended_at,
    session_count,
    updated_at
  )
  select logs.user_id,
         (logs.ended_at at time zone 'Asia/Seoul')::date,
         min(logs.ended_at),
         max(logs.ended_at),
         count(*)::bigint,
         now()
    from public.app_session_logs as logs
   group by logs.user_id,
            (logs.ended_at at time zone 'Asia/Seoul')::date
  on conflict (user_id, activity_date) do update
    set first_ended_at = least(
          public.admin_user_activity_days.first_ended_at,
          excluded.first_ended_at
        ),
        last_ended_at = greatest(
          public.admin_user_activity_days.last_ended_at,
          excluded.last_ended_at
        ),
        session_count = greatest(
          public.admin_user_activity_days.session_count,
          excluded.session_count
        ),
        updated_at = excluded.updated_at;

  drop trigger if exists app_session_logs_admin_activity_day
    on public.app_session_logs;
  create trigger app_session_logs_admin_activity_day
  after insert on public.app_session_logs
  for each row execute function ranking_private.upsert_admin_user_activity_day();
end;
$migration$;

create or replace function public.admin_companion_weekly_retention(
  p_timezone text default 'Asia/Seoul'
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_today date;
  v_cohort_window_days constant integer := 28;
  v_result jsonb;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  if p_timezone <> 'Asia/Seoul' then
    raise exception 'weekly retention supports Asia/Seoul only' using errcode = '22023';
  end if;

  v_today := (now() at time zone p_timezone)::date;

  with retention_weeks(week_number, start_day, end_day) as (
    values
      (1, 1, 7),
      (2, 8, 14),
      (3, 15, 21),
      (4, 22, 28)
  ),
  cohorts as (
    select subscribers.user_id,
           (subscribers.registered_at at time zone p_timezone)::date as signup_date
      from public.admin_subscribers as subscribers
  ),
  retention_rows as (
    select weeks.week_number,
           weeks.start_day,
           weeks.end_day,
           count(cohorts.user_id)::integer as cohort_size,
           count(cohorts.user_id) filter (
             where exists (
               select 1
                 from public.admin_user_activity_days as activity
                where activity.user_id = cohorts.user_id
                  and activity.activity_date between
                        cohorts.signup_date + weeks.start_day
                        and cohorts.signup_date + weeks.end_day
             )
           )::integer as returned_users
      from retention_weeks as weeks
      left join cohorts
        on cohorts.signup_date between
             v_today - (weeks.end_day + v_cohort_window_days)
             and v_today - (weeks.end_day + 1)
     group by weeks.week_number,
              weeks.start_day,
              weeks.end_day
  ),
  retention_json as (
    select jsonb_agg(
             jsonb_build_object(
               'week', rows.week_number,
               'start_day', rows.start_day,
               'end_day', rows.end_day,
               'cohort_size', rows.cohort_size,
               'returned_users', rows.returned_users,
               'rate_percent', case
                 when rows.cohort_size = 0 then null
                 else round(rows.returned_users * 100.0 / rows.cohort_size, 1)
               end
             ) order by rows.week_number
           ) as value
      from retention_rows as rows
  ),
  trend_days as (
    select day::date as day
      from generate_series(v_today - 6, v_today, interval '1 day') as day
  ),
  active_trend_json as (
    select jsonb_agg(
             jsonb_build_object(
               'date', days.day,
               'active_users', coalesce(activity.active_users, 0)
             ) order by days.day
           ) as value
      from trend_days as days
      left join (
        select activity.activity_date as day,
               count(distinct activity.user_id)::integer as active_users
          from public.admin_user_activity_days as activity
         where activity.activity_date between v_today - 6 and v_today
         group by activity.activity_date
      ) as activity using (day)
  )
  select jsonb_build_object(
           'generated_at', now(),
           'timezone', p_timezone,
           'cohort_window_days', v_cohort_window_days,
           'completed_through', v_today - 1,
           'metrics', coalesce(retention_json.value, '[]'::jsonb),
           'active_trend', coalesce(active_trend_json.value, '[]'::jsonb)
         )
    into v_result
    from retention_json,
         active_trend_json;

  return v_result;
end;
$$;

revoke all on function public.admin_companion_weekly_retention(text)
  from public, anon;
grant execute on function public.admin_companion_weekly_retention(text)
  to authenticated;
