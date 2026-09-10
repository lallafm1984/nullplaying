-- Show current weekly return data as soon as a cohort has completed the first
-- day of that week. A current rate can include in-progress cohorts, so the RPC
-- identifies provisional metrics separately from cohorts that finished the
-- full seven-day observation window.

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
             where cohorts.signup_date <= v_today - (weeks.end_day + 1)
           )::integer as completed_cohort_size,
           count(cohorts.user_id) filter (
             where exists (
               select 1
                 from public.admin_user_activity_days as activity
                where activity.user_id = cohorts.user_id
                  and activity.activity_date between
                        cohorts.signup_date + weeks.start_day
                        and least(cohorts.signup_date + weeks.end_day, v_today - 1)
             )
           )::integer as returned_users
      from retention_weeks as weeks
      left join cohorts
        -- Keep the most recent 28 signup-date cohorts that have completed at
        -- least the first observation day of this week.
        on cohorts.signup_date between
             v_today - (weeks.start_day + v_cohort_window_days)
             and v_today - (weeks.start_day + 1)
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
               'completed_cohort_size', rows.completed_cohort_size,
               'returned_users', rows.returned_users,
               'is_provisional', rows.completed_cohort_size < rows.cohort_size,
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
