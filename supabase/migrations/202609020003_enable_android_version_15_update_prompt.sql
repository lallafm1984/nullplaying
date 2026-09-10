-- Announce the production Android versionCode 15 release.
-- Version 14 receives a dismissible prompt; versions below 14 remain blocked by the existing
-- minimum-supported-version policy. Clients already on versionCode 15 or newer ignore this row.

insert into public.app_updates (
  platform,
  latest_version_code,
  latest_version_name,
  minimum_supported_version_code,
  title,
  message,
  update_url,
  force_update,
  enabled,
  updated_at
)
values (
  'android',
  15,
  '0.4.2',
  14,
  '새 버전이 준비되었습니다',
  '오프라인 모험 시간 충전 안내와 앱 안정성 개선이 반영되었습니다. 최신 버전으로 업데이트해 주세요.',
  'https://play.google.com/store/apps/details?id=com.nullplaying',
  false,
  true,
  now()
)
on conflict (platform) do update
set latest_version_code = excluded.latest_version_code,
    latest_version_name = excluded.latest_version_name,
    minimum_supported_version_code = excluded.minimum_supported_version_code,
    title = excluded.title,
    message = excluded.message,
    update_url = excluded.update_url,
    force_update = excluded.force_update,
    enabled = excluded.enabled,
    updated_at = excluded.updated_at;
