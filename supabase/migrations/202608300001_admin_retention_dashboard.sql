-- Protected retention metrics for the native administrator dashboard.
--
-- Retention is based on completed app sessions. The rolling cohort window is
-- four weeks so every target activity day remains inside the 30-day raw
-- session-log retention period. In-progress target days are excluded.

create or replace function public.admin_companion_retention(
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

  -- This conversion also validates the IANA timezone name.
  v_today := (now() at time zone p_timezone)::date;

  with retention_offsets(day_number) as (
    values (1), (7), (30)
  ),
  raw_cohorts as (
    select coalesce(
             events.subject_user_id,
             nullif(events.payload ->> 'user_id', '')::uuid
           ) as user_id,
           events.occurred_at
      from public.admin_events as events
     where events.event_type = 'subscriber_created'
  ),
  cohorts as (
    select raw.user_id,
           min((raw.occurred_at at time zone p_timezone)::date) as signup_date
      from raw_cohorts as raw
     where raw.user_id is not null
     group by raw.user_id
  ),
  activity_days as (
    select logs.user_id,
           (logs.ended_at at time zone p_timezone)::date as activity_date
      from public.app_session_logs as logs
     where logs.ended_at >= (v_today - v_cohort_window_days)::timestamp at time zone p_timezone
       and logs.ended_at < (v_today + 1)::timestamp at time zone p_timezone
     group by logs.user_id,
              (logs.ended_at at time zone p_timezone)::date
  ),
  retention_rows as (
    select offsets.day_number,
           count(cohorts.user_id)::integer as cohort_size,
           count(cohorts.user_id) filter (
             where exists (
               select 1
                 from activity_days as activity
                where activity.user_id = cohorts.user_id
                  and activity.activity_date = cohorts.signup_date + offsets.day_number
             )
           )::integer as retained_users
      from retention_offsets as offsets
      left join cohorts
        on cohorts.signup_date between
             v_today - (offsets.day_number + v_cohort_window_days)
             and v_today - (offsets.day_number + 1)
     group by offsets.day_number
  ),
  retention_json as (
    select jsonb_agg(
             jsonb_build_object(
               'day', rows.day_number,
               'cohort_size', rows.cohort_size,
               'retained_users', rows.retained_users,
               'rate_percent', case
                 when rows.cohort_size = 0 then null
                 else round(rows.retained_users * 100.0 / rows.cohort_size, 1)
               end
             ) order by rows.day_number
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
          from activity_days as activity
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

revoke all on function public.admin_companion_retention(text)
  from public, anon;
grant execute on function public.admin_companion_retention(text)
  to authenticated;
