-- Give administrator-owned ranking fixtures a stable system identity while keeping
-- ordinary player display names untouched. Older clients receive the English fallback
-- name; current clients localize the system code at presentation time.

alter table public.ranking_entries
  add column if not exists system_entry_code text;

alter table public.ranking_entries
  add constraint ranking_entries_system_entry_code_check check (
    system_entry_code is null
    or system_entry_code ~ '^RANK_GATE_(0[1-9]|1[0-9]|20)$'
  );

create unique index if not exists ranking_entries_system_entry_code_idx
  on public.ranking_entries (system_entry_code)
  where system_entry_code is not null;

comment on column public.ranking_entries.system_entry_code is
  'Administrator-assigned localization key for clearly identified system ranking entries.';

with numbered_gatekeepers as (
  select r.user_id,
         r.character_id,
         row_number() over (
           order by r.level, r.combat_power, r.achieved_at, r.character_id
         )::integer as gatekeeper_number
    from public.ranking_entries as r
    join auth.users as u on u.id = r.user_id
   where u.raw_user_meta_data->>'codex_batch' = 'alarmquest_rank_dummy_lv20_40_min_v1'
),
localized_gatekeepers as (
  select user_id,
         character_id,
         gatekeeper_number,
         (array[
           'I', 'II', 'III', 'IV', 'V',
           'VI', 'VII', 'VIII', 'IX', 'X',
           'XI', 'XII', 'XIII', 'XIV', 'XV',
           'XVI', 'XVII', 'XVIII', 'XIX', 'XX'
         ])[gatekeeper_number] as roman_number
    from numbered_gatekeepers
   where gatekeeper_number between 1 and 20
)
update public.ranking_entries as r
   set system_entry_code = 'RANK_GATE_' ||
         pg_catalog.lpad(localized.gatekeeper_number::text, 2, '0'),
       display_name = 'Gatekeeper ' || localized.roman_number
  from localized_gatekeepers as localized
 where r.user_id = localized.user_id
   and r.character_id = localized.character_id;

create or replace function public.get_leaderboard_v2(
  p_character_id uuid,
  p_limit integer default 1000
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  with ranked as (
    select rank() over (
             order by r.combat_power desc
           ) as rank_number,
           row_number() over (
             order by r.combat_power desc, r.achieved_at asc, r.character_id
           ) - 1 as list_index,
           r.user_id,
           r.character_id,
           r.display_name,
           r.system_entry_code,
           r.hero_class,
           r.level,
           r.combat_power,
           r.achieved_at,
           count(*) over ()::integer as total_participants
      from public.ranking_entries as r
     where r.level between 20 and 10000
       and r.combat_power <= ranking_private.maximum_accepted_combat_power(r.level)
  ),
  visible as (
    select *
      from ranked
     where rank_number <= least(greatest(p_limit, 1), 1000)
  )
  select pg_catalog.jsonb_build_object(
    't', coalesce((select pg_catalog.max(total_participants) from ranked), 0),
    'e', coalesce(
      (
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'r', rank_number,
            'i', list_index,
            'c', character_id,
            'n', display_name,
            'h', hero_class,
            'l', level,
            'p', combat_power,
            'a', (extract(epoch from achieved_at) * 1000)::bigint
          ) || case
            when system_entry_code is null then '{}'::jsonb
            else pg_catalog.jsonb_build_object('s', system_entry_code)
          end
          order by list_index
        )
          from visible
      ),
      '[]'::jsonb
    ),
    'm', (
      select pg_catalog.jsonb_build_object(
        'r', rank_number,
        'i', list_index,
        'c', character_id,
        'n', display_name,
        'h', hero_class,
        'l', level,
        'p', combat_power,
        'a', (extract(epoch from achieved_at) * 1000)::bigint
      ) || case
        when system_entry_code is null then '{}'::jsonb
        else pg_catalog.jsonb_build_object('s', system_entry_code)
      end
        from ranked
       where user_id = auth.uid()
         and character_id = p_character_id
       limit 1
    )
  );
$$;

revoke all on function public.get_leaderboard_v2(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard_v2(uuid, integer) to authenticated;
