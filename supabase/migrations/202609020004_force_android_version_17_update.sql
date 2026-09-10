-- Require the production Android versionCode 17 release.
-- Clients already on versionCode 17 or newer ignore this policy in the app.

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
  17,
  '0.4.3',
  17,
  '필수 업데이트가 준비되었습니다',
  '최근 사건 기능과 오프라인 모험 충전 안내 개선이 반영된 최신 버전으로 업데이트해 주세요.',
  'https://play.google.com/store/apps/details?id=com.nullplaying',
  true,
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
