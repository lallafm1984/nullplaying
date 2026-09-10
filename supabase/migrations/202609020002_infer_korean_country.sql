-- Resolve otherwise unknown countries only when both independent device hints
-- agree: Korean language and the Asia/Seoul timezone. The explicit source
-- keeps this fallback distinguishable from a locale-supplied country code.

alter table public.user_profiles
  drop constraint if exists user_profiles_country_source_check;

alter table public.user_profiles
  add constraint user_profiles_country_source_check check (
    country_source in ('device_locale', 'language_timezone_inferred', 'unknown')
  );

create or replace function ranking_private.infer_korean_profile_country()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.country_code is null
     and lower(new.language_code) ~ '^ko([_-].*)?$'
     and new.timezone = 'Asia/Seoul' then
    new.country_code := 'KR';
    new.country_source := 'language_timezone_inferred';
  end if;

  return new;
end;
$$;

revoke all on function ranking_private.infer_korean_profile_country()
  from public, anon, authenticated;

drop trigger if exists user_profiles_infer_korean_country
  on public.user_profiles;
create trigger user_profiles_infer_korean_country
before insert or update of country_code, country_source, language_code, timezone
on public.user_profiles
for each row execute function ranking_private.infer_korean_profile_country();

update public.user_profiles
   set country_code = 'KR',
       country_source = 'language_timezone_inferred'
 where country_code is null
   and lower(language_code) ~ '^ko([_-].*)?$'
   and timezone = 'Asia/Seoul';
