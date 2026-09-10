-- Separate impossible future-dated client logs in the native administrator app.
-- The raw client ended_at remains unchanged for audit; created_at is the trusted
-- server receipt time used to detect a device clock that is too far ahead.

create or replace function public.admin_companion_suspicious_session_logs(
  p_tolerance_seconds integer default 300,
  p_limit integer default 150
)
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

  if p_tolerance_seconds < 0 or p_tolerance_seconds > 3600 then
    raise exception 'tolerance must be between 0 and 3600 seconds' using errcode = '22023';
  end if;

  if p_limit < 1 or p_limit > 500 then
    raise exception 'limit must be between 1 and 500' using errcode = '22023';
  end if;

  select coalesce(
           pg_catalog.jsonb_agg(
             pg_catalog.to_jsonb(session_rows)
             order by session_rows.created_at desc, session_rows.event_id
           ),
           '[]'::jsonb
         )
    into v_result
    from (
      select logs.event_id,
             logs.user_id,
             logs.ended_at,
             logs.created_at,
             pg_catalog.floor(
               extract(epoch from (logs.ended_at - logs.created_at))
             )::bigint as clock_skew_seconds,
             true as is_suspicious_time,
             logs.reason,
             logs.slot_id,
             logs.hero_level,
             logs.combat_power,
             logs.total_acts,
             logs.total_kills,
             logs.app_version,
             logs.device_model
        from public.app_session_logs as logs
       where logs.ended_at > logs.created_at
             + pg_catalog.make_interval(secs => p_tolerance_seconds)
       order by logs.created_at desc, logs.event_id
       limit p_limit
    ) as session_rows;

  return v_result;
end;
$$;

revoke all on function public.admin_companion_suspicious_session_logs(integer, integer)
  from public, anon;
grant execute on function public.admin_companion_suspicious_session_logs(integer, integer)
  to authenticated;
