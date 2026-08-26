-- Remote Android update policy. No row is inserted by this migration, so the feature stays idle
-- until an operator explicitly creates an enabled policy row.

create table if not exists public.app_updates (
  platform text primary key check (platform in ('android')),
  latest_version_code integer not null check (latest_version_code >= 1),
  latest_version_name text not null check (char_length(latest_version_name) between 1 and 32),
  minimum_supported_version_code integer not null check (minimum_supported_version_code >= 1),
  title text not null default '새 버전이 준비되었습니다',
  message text not null default '더 안정적인 모험을 위해 앱을 업데이트해 주세요.',
  update_url text not null check (update_url ~ '^https://'),
  force_update boolean not null default false,
  enabled boolean not null default false,
  updated_at timestamptz not null default now(),
  check (latest_version_code >= minimum_supported_version_code)
);

alter table public.app_updates enable row level security;
revoke all on public.app_updates from anon, authenticated;
grant select on public.app_updates to anon, authenticated;

create policy "clients read enabled Android update policy"
  on public.app_updates for select to anon, authenticated
  using (enabled and platform = 'android');
