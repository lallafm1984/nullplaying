-- User confirmed production deployment of 0.5.3 (28).
-- Preserve the existing localized update copy and Play destination.
do $$
begin
  if exists (select 1 from public.app_updates where platform = 'android' and latest_version_code > 28) then
    raise exception 'Refusing to downgrade a newer Android update policy';
  end if;
  update public.app_updates
  set latest_version_code = 28,
      latest_version_name = '0.5.3',
      minimum_supported_version_code = 28,
      force_update = true,
      enabled = true,
      updated_at = now()
  where platform = 'android';
  if not found then raise exception 'Existing Android update policy is missing'; end if;
end $$;
