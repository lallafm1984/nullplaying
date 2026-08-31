-- Native administrator companion support.
--
-- Security contract:
--   * Game clients never receive administrator data or FCM device tokens.
--   * Administrator reads are exposed only through security-definer RPCs that
--     verify auth.uid() against admin_companion_users.
--   * The service_role is used only by the admin-push Edge Function.

create table if not exists public.admin_companion_users (
  user_id uuid primary key references auth.users(id) on delete cascade,
  enabled boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.admin_companion_devices (
  id uuid primary key default gen_random_uuid(),
  admin_user_id uuid not null references public.admin_companion_users(user_id) on delete cascade,
  fcm_token text not null unique check (char_length(fcm_token) between 32 and 4096),
  device_name text not null check (char_length(device_name) between 1 and 96),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create table if not exists public.admin_subscribers (
  user_id uuid primary key references auth.users(id) on delete cascade,
  registered_at timestamptz not null,
  is_anonymous boolean not null,
  provider text,
  created_at timestamptz not null default now()
);

create index if not exists admin_subscribers_registered_at_idx
  on public.admin_subscribers (registered_at desc, user_id);

create table if not exists public.admin_events (
  id uuid primary key default gen_random_uuid(),
  event_type text not null check (
    event_type in ('subscriber_created', 'subscriber_deleted', 'push_test')
  ),
  subject_user_id uuid references auth.users(id) on delete set null,
  occurred_at timestamptz not null default now(),
  payload jsonb not null default '{}'::jsonb,
  delivery_status text not null default 'pending' check (
    delivery_status in ('pending', 'delivered', 'partial', 'failed', 'skipped')
  ),
  delivered_device_count integer not null default 0 check (delivered_device_count >= 0),
  failed_device_count integer not null default 0 check (failed_device_count >= 0),
  delivery_error text,
  processed_at timestamptz
);

create index if not exists admin_events_occurred_at_idx
  on public.admin_events (occurred_at desc, id);

alter table public.admin_companion_users enable row level security;
alter table public.admin_companion_devices enable row level security;
alter table public.admin_subscribers enable row level security;
alter table public.admin_events enable row level security;

revoke all on table public.admin_companion_users from anon, authenticated;
revoke all on table public.admin_companion_devices from anon, authenticated;
revoke all on table public.admin_subscribers from anon, authenticated;
revoke all on table public.admin_events from anon, authenticated;

grant select on table public.admin_companion_users to service_role;
grant select, insert, update, delete on table public.admin_companion_devices to service_role;
grant select on table public.admin_subscribers to service_role;
grant select, insert, update on table public.admin_events to service_role;

create or replace function public.admin_companion_capture_signup()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- NULL PLAYING game installations use anonymous Auth users. Email/password
  -- accounts are reserved for administrators and must not inflate subscriber metrics.
  if not coalesce(new.is_anonymous, false) then
    return new;
  end if;

  insert into public.admin_subscribers (
    user_id,
    registered_at,
    is_anonymous,
    provider
  ) values (
    new.id,
    new.created_at,
    coalesce(new.is_anonymous, false),
    nullif(new.raw_app_meta_data ->> 'provider', '')
  )
  on conflict (user_id) do nothing;

  insert into public.admin_events (
    event_type,
    subject_user_id,
    occurred_at,
    payload
  ) values (
    'subscriber_created',
    new.id,
    new.created_at,
    jsonb_build_object(
      'user_id', new.id,
      'is_anonymous', coalesce(new.is_anonymous, false),
      'provider', nullif(new.raw_app_meta_data ->> 'provider', '')
    )
  );

  return new;
end;
$$;

create or replace function public.admin_companion_capture_deletion()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if not exists (
    select 1 from public.admin_subscribers where user_id = old.id
  ) then
    return old;
  end if;

  insert into public.admin_events (
    event_type,
    subject_user_id,
    occurred_at,
    payload,
    delivery_status
  ) values (
    'subscriber_deleted',
    old.id,
    now(),
    jsonb_build_object('user_id', old.id),
    'skipped'
  );

  return old;
end;
$$;

drop trigger if exists admin_companion_auth_signup on auth.users;
create trigger admin_companion_auth_signup
after insert on auth.users
for each row execute function public.admin_companion_capture_signup();

drop trigger if exists admin_companion_auth_deletion on auth.users;
create trigger admin_companion_auth_deletion
before delete on auth.users
for each row execute function public.admin_companion_capture_deletion();

-- Capture existing users without generating historical push events.
insert into public.admin_subscribers (
  user_id,
  registered_at,
  is_anonymous,
  provider
)
select users.id,
       users.created_at,
       coalesce(users.is_anonymous, false),
       nullif(users.raw_app_meta_data ->> 'provider', '')
  from auth.users as users
 where coalesce(users.is_anonymous, false)
on conflict (user_id) do nothing;

create or replace function public.admin_companion_is_enabled(p_user_id uuid)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1
      from public.admin_companion_users as admins
     where admins.user_id = p_user_id
       and admins.enabled
  );
$$;

revoke all on function public.admin_companion_is_enabled(uuid) from public, anon, authenticated;
grant execute on function public.admin_companion_is_enabled(uuid) to service_role;

create or replace function public.admin_companion_register_device(
  p_fcm_token text,
  p_device_name text
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_device_id uuid;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  if char_length(trim(p_fcm_token)) not between 32 and 4096 then
    raise exception 'invalid FCM token' using errcode = '22023';
  end if;

  if char_length(trim(p_device_name)) not between 1 and 96 then
    raise exception 'invalid device name' using errcode = '22023';
  end if;

  insert into public.admin_companion_devices (
    admin_user_id,
    fcm_token,
    device_name,
    updated_at
  ) values (
    v_user_id,
    trim(p_fcm_token),
    trim(p_device_name),
    now()
  )
  on conflict (fcm_token) do update
    set admin_user_id = excluded.admin_user_id,
        device_name = excluded.device_name,
        updated_at = excluded.updated_at
  returning id into v_device_id;

  return v_device_id;
end;
$$;

revoke all on function public.admin_companion_register_device(text, text)
  from public, anon;
grant execute on function public.admin_companion_register_device(text, text)
  to authenticated;

create or replace function public.admin_companion_unregister_device(p_fcm_token text)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_deleted integer;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  delete from public.admin_companion_devices
   where admin_user_id = v_user_id
     and fcm_token = trim(p_fcm_token);

  get diagnostics v_deleted = row_count;
  return v_deleted > 0;
end;
$$;

revoke all on function public.admin_companion_unregister_device(text)
  from public, anon;
grant execute on function public.admin_companion_unregister_device(text)
  to authenticated;

create or replace function public.admin_companion_snapshot(
  p_start_date date,
  p_end_date date,
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
  v_result jsonb;
begin
  if v_user_id is null or not public.admin_companion_is_enabled(v_user_id) then
    raise exception 'administrator access required' using errcode = '42501';
  end if;

  if p_start_date is null
     or p_end_date is null
     or p_end_date < p_start_date
     or p_end_date - p_start_date > 62 then
    raise exception 'date range must be between 1 and 63 days' using errcode = '22023';
  end if;

  -- This also validates the IANA timezone name.
  v_today := (now() at time zone p_timezone)::date;

  with range_days as (
    select day::date as day
      from generate_series(p_start_date, p_end_date, interval '1 day') as day
  ),
  subscriber_profiles as (
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
           profiles.last_seen_at
      from public.admin_subscribers as subscribers
      left join public.user_profiles as profiles
        on profiles.user_id = subscribers.user_id
  ),
  daily_counts as (
    select (registered_at at time zone p_timezone)::date as day,
           count(*)::integer as subscriber_count
      from subscriber_profiles
     where registered_at >= p_start_date::timestamp at time zone p_timezone
       and registered_at < (p_end_date + 1)::timestamp at time zone p_timezone
     group by (registered_at at time zone p_timezone)::date
  ),
  country_counts as (
    select (registered_at at time zone p_timezone)::date as day,
           coalesce(country_code, 'ZZ') as country_code,
           count(*)::integer as subscriber_count
      from subscriber_profiles
     where registered_at >= p_start_date::timestamp at time zone p_timezone
       and registered_at < (p_end_date + 1)::timestamp at time zone p_timezone
     group by (registered_at at time zone p_timezone)::date,
              coalesce(country_code, 'ZZ')
  ),
  daily_countries as (
    select day,
           jsonb_object_agg(country_code, subscriber_count order by country_code) as countries
      from country_counts
     group by day
  ),
  calendar_json as (
    select coalesce(
      jsonb_agg(
        jsonb_build_object(
          'date', days.day,
          'count', coalesce(counts.subscriber_count, 0),
          'countries', coalesce(countries.countries, '{}'::jsonb)
        ) order by days.day
      ),
      '[]'::jsonb
    ) as value
      from range_days as days
      left join daily_counts as counts using (day)
      left join daily_countries as countries using (day)
  ),
  recent_subscribers_json as (
    select coalesce(jsonb_agg(to_jsonb(recent_rows) order by recent_rows.registered_at desc), '[]'::jsonb) as value
      from (
        select profiles.user_id,
               profiles.registered_at,
               profiles.is_anonymous,
               profiles.provider,
               profiles.country_code,
               profiles.country_source,
               profiles.language_code,
               profiles.timezone,
               profiles.app_version,
               profiles.app_version_code,
               profiles.os_version,
               profiles.device_manufacturer,
               profiles.device_model,
               profiles.last_seen_at
          from subscriber_profiles as profiles
         order by profiles.registered_at desc
         limit 250
      ) as recent_rows
  ),
  range_subscribers_json as (
    select coalesce(jsonb_agg(to_jsonb(range_rows) order by range_rows.registered_at desc), '[]'::jsonb) as value
      from (
        select profiles.user_id,
               profiles.registered_at,
               profiles.is_anonymous,
               profiles.provider,
               profiles.country_code,
               profiles.country_source,
               profiles.language_code,
               profiles.timezone,
               profiles.app_version,
               profiles.app_version_code,
               profiles.os_version,
               profiles.device_manufacturer,
               profiles.device_model,
               profiles.last_seen_at
          from subscriber_profiles as profiles
         where profiles.registered_at >= p_start_date::timestamp at time zone p_timezone
           and profiles.registered_at < (p_end_date + 1)::timestamp at time zone p_timezone
         order by profiles.registered_at desc
         limit 2000
      ) as range_rows
  ),
  admin_events_json as (
    select coalesce(jsonb_agg(to_jsonb(event_rows) order by event_rows.occurred_at desc), '[]'::jsonb) as value
      from (
        select events.id,
               events.event_type,
               events.subject_user_id,
               events.occurred_at,
               events.delivery_status,
               events.delivered_device_count,
               events.failed_device_count,
               events.delivery_error
          from public.admin_events as events
         order by events.occurred_at desc
         limit 150
      ) as event_rows
  ),
  session_logs_json as (
    select coalesce(jsonb_agg(to_jsonb(session_rows) order by session_rows.ended_at desc), '[]'::jsonb) as value
      from (
        select logs.event_id,
               logs.user_id,
               logs.ended_at,
               logs.reason,
               logs.slot_id,
               logs.hero_level,
               logs.combat_power,
               logs.total_acts,
               logs.total_kills,
               logs.app_version,
               logs.device_model
          from public.app_session_logs as logs
         order by logs.ended_at desc
         limit 150
      ) as session_rows
  )
  select jsonb_build_object(
    'generated_at', now(),
    'timezone', p_timezone,
    'start_date', p_start_date,
    'end_date', p_end_date,
    'summary', jsonb_build_object(
      'total', (select count(*) from public.admin_subscribers),
      'today', (
        select count(*)
          from public.admin_subscribers
         where (registered_at at time zone p_timezone)::date = v_today
      ),
      'last_7_days', (
        select count(*)
          from public.admin_subscribers
         where registered_at >= (v_today - 6)::timestamp at time zone p_timezone
           and registered_at < (v_today + 1)::timestamp at time zone p_timezone
      ),
      'active_24h', (
        select count(*)
          from public.user_profiles
         where last_seen_at >= now() - interval '24 hours'
      )
    ),
    'calendar', calendar_json.value,
    'recent_subscribers', recent_subscribers_json.value,
    'range_subscribers', range_subscribers_json.value,
    'admin_events', admin_events_json.value,
    'session_logs', session_logs_json.value
  )
    into v_result
    from calendar_json,
         recent_subscribers_json,
         range_subscribers_json,
         admin_events_json,
         session_logs_json;

  return v_result;
end;
$$;

revoke all on function public.admin_companion_snapshot(date, date, text)
  from public, anon;
grant execute on function public.admin_companion_snapshot(date, date, text)
  to authenticated;
