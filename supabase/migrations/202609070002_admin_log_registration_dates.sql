-- Include each subscriber's signup timestamp in the protected usage snapshot so
-- the native admin app can annotate session and operation logs without exposing
-- the subscriber table directly to ordinary authenticated clients.

create or replace function public.admin_companion_usage_snapshot()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $function$
declare
  v_user_id uuid := auth.uid();
  v_result jsonb;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  select pg_catalog.jsonb_build_object(
           'generated_at', pg_catalog.now(),
           'users', pg_catalog.coalesce(
             pg_catalog.jsonb_agg(
               pg_catalog.jsonb_build_object(
                 'user_id', subscribers.user_id,
                 'registered_at', subscribers.registered_at,
                 'session_count', pg_catalog.coalesce(totals.total_session_count, 0),
                 'last_session_at', totals.last_session_at,
                 'country_code', profiles.country_code
               ) order by
                 pg_catalog.coalesce(totals.total_session_count, 0) desc,
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
$function$;

revoke all on function public.admin_companion_usage_snapshot()
  from public, anon;
grant execute on function public.admin_companion_usage_snapshot()
  to authenticated;
