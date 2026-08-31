-- Enable a dismissible update prompt for the production Android versionCode 13 release.
-- Clients already on versionCode 13 or newer ignore this policy in the app.

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
  13,
  '0.4.1',
  1,
  '새 버전이 준비되었습니다',
  '보상형 광고 연결과 최신 Android 호환성 개선이 반영되었습니다. 안정적인 이용을 위해 최신 버전으로 업데이트해 주세요.',
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
