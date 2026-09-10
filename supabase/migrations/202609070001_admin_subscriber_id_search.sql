-- Search the complete subscriber set by UUID prefix without exposing the
-- administrator-only tables to the Android client.

create or replace function public.admin_companion_search_subscribers(
  p_query text,
  p_limit integer default 100
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_query text := pg_catalog.replace(
    pg_catalog.lower(pg_catalog.btrim(pg_catalog.coalesce(p_query, ''))),
    '-',
    ''
  );
  v_result jsonb;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  if pg_catalog.length(v_query) < 4
     or pg_catalog.length(v_query) > 32
     or v_query !~ '^[0-9a-f]+$' then
    raise exception 'subscriber id query must be a hexadecimal UUID prefix of 4 to 32 characters'
      using errcode = '22023';
  end if;

  if p_limit < 1 or p_limit > 100 then
    raise exception 'limit must be between 1 and 100' using errcode = '22023';
  end if;

  with matched_subscribers as (
    select subscribers.user_id,
           subscribers.registered_at,
           subscribers.is_anonymous,
           subscribers.provider,
           profiles.country_code,
           profiles.country_source,
           profiles.language_code,
           profiles.timezone,
           profiles.app_version,
           profiles.app_version_code,
           profiles.os_version,
           profiles.device_manufacturer,
           profiles.device_model,
           profiles.last_seen_at,
           pg_catalog.coalesce(totals.total_session_count, 0)::bigint as session_count
      from public.admin_subscribers as subscribers
      left join public.user_profiles as profiles
        on profiles.user_id = subscribers.user_id
      left join public.admin_user_session_totals as totals
        on totals.user_id = subscribers.user_id
     where pg_catalog.replace(subscribers.user_id::text, '-', '') like v_query || '%'
     order by subscribers.registered_at desc, subscribers.user_id
     limit p_limit
  )
  select pg_catalog.jsonb_build_object(
           'generated_at', pg_catalog.now(),
           'query', v_query,
           'subscribers', pg_catalog.coalesce(
             pg_catalog.jsonb_agg(
               pg_catalog.to_jsonb(matched_subscribers)
               order by matched_subscribers.registered_at desc, matched_subscribers.user_id
             ),
             '[]'::jsonb
           )
         )
    into v_result
    from matched_subscribers;

  return v_result;
end;
$$;

revoke all on function public.admin_companion_search_subscribers(text, integer)
  from public, anon;
grant execute on function public.admin_companion_search_subscribers(text, integer)
  to authenticated;
