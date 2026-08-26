-- One private profile per authenticated user. Country is supplied from Android's device locale;
-- no IP address or precise location is collected.

create table if not exists public.user_profiles (
  user_id uuid primary key references auth.users(id) on delete cascade,
  country_code text check (country_code is null or country_code ~ '^[A-Z]{2}$'),
  country_source text not null check (country_source in ('device_locale', 'unknown')),
  language_code text not null check (char_length(language_code) between 2 and 16),
  timezone text not null check (char_length(timezone) between 1 and 64),
  platform text not null check (platform = 'android'),
  app_version text not null check (char_length(app_version) between 1 and 32),
  app_version_code integer not null check (app_version_code >= 1),
  os_version text not null check (char_length(os_version) <= 32),
  device_manufacturer text not null check (char_length(device_manufacturer) <= 64),
  device_model text not null check (char_length(device_model) <= 96),
  created_at timestamptz not null default now(),
  last_seen_at timestamptz not null default now()
);

alter table public.user_profiles enable row level security;
revoke all on public.user_profiles from anon;
grant select, insert, update on public.user_profiles to authenticated;

create policy "users read their own profile"
  on public.user_profiles for select to authenticated
  using ((select auth.uid()) = user_id);

create policy "users insert their own profile"
  on public.user_profiles for insert to authenticated
  with check ((select auth.uid()) = user_id);

create policy "users update their own profile"
  on public.user_profiles for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

create or replace function public.preserve_user_profile_creation_time()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.created_at := old.created_at;
  return new;
end;
$$;

drop trigger if exists user_profiles_preserve_created_at on public.user_profiles;
create trigger user_profiles_preserve_created_at
before update on public.user_profiles
for each row execute function public.preserve_user_profile_creation_time();
