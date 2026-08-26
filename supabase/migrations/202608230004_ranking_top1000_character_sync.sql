-- Character-owned Top 1,000 leaderboard. Development data may be cleared before release.

alter table public.ranking_entries
  add column if not exists character_id uuid;

update public.ranking_entries
   set character_id = gen_random_uuid()
 where character_id is null;

alter table public.ranking_entries
  alter column character_id set not null;

alter table public.ranking_entries
  drop constraint if exists ranking_entries_pkey;

alter table public.ranking_entries
  add primary key (user_id, character_id);

create unique index if not exists ranking_entries_user_slot_idx
  on public.ranking_entries (user_id, slot_id);

drop index if exists public.ranking_entries_score_idx;
create index ranking_entries_score_idx
  on public.ranking_entries (combat_power desc, achieved_at asc, character_id);

create or replace function public.sync_ranking_entries(p_entries jsonb)
returns void
language plpgsql
security invoker
set search_path = public
as $$
declare
  current_user_id uuid := (select auth.uid());
begin
  if current_user_id is null then
    raise exception 'authentication required';
  end if;
  if jsonb_typeof(p_entries) <> 'array' or jsonb_array_length(p_entries) > 3 then
    raise exception 'invalid ranking batch';
  end if;

  delete from public.ranking_entries r
   where r.user_id = current_user_id
     and not exists (
       select 1
         from jsonb_to_recordset(p_entries) as e(character_id uuid)
        where e.character_id = r.character_id
     );

  insert into public.ranking_entries (
    user_id, character_id, slot_id, display_name, hero_class, level, combat_power
  )
  select current_user_id,
         e.character_id,
         e.slot_id,
         left(btrim(e.display_name), 24),
         e.hero_class,
         e.level,
         e.combat_power
    from jsonb_to_recordset(p_entries) as e(
      character_id uuid,
      slot_id smallint,
      display_name text,
      hero_class text,
      level bigint,
      combat_power bigint
    )
   where e.level >= 20
     and e.slot_id between 1 and 3
     and char_length(btrim(e.display_name)) between 1 and 24
     and e.hero_class in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')
     and e.combat_power >= 0
  on conflict (user_id, character_id) do update
    set slot_id = excluded.slot_id,
        display_name = excluded.display_name,
        hero_class = excluded.hero_class,
        level = excluded.level,
        combat_power = excluded.combat_power;
end;
$$;

revoke all on function public.sync_ranking_entries(jsonb) from public, anon;
grant execute on function public.sync_ranking_entries(jsonb) to authenticated;

drop function if exists public.get_leaderboard(smallint, integer);

create or replace function public.get_leaderboard(
  p_character_id uuid,
  p_limit integer default 1000
)
returns table (
  rank_number bigint,
  list_index bigint,
  user_id uuid,
  character_id uuid,
  slot_id smallint,
  display_name text,
  hero_class text,
  level bigint,
  combat_power bigint,
  achieved_at timestamptz,
  updated_at timestamptz,
  total_participants integer
)
language sql
stable
security invoker
set search_path = public
as $$
  with ranked as (
    select rank() over (
             order by r.combat_power desc
           ) as rank_number,
           row_number() over (
             order by r.combat_power desc, r.achieved_at asc, r.character_id
           ) - 1 as list_index,
           r.*,
           count(*) over ()::integer as total_participants
      from public.ranking_entries r
     where r.level >= 20
  )
  select rank_number, list_index, user_id, character_id, slot_id, display_name,
         hero_class, level, combat_power, achieved_at, updated_at, total_participants
    from ranked
   where rank_number <= least(greatest(p_limit, 1), 1000)
      or user_id = (select auth.uid())
   order by list_index;
$$;

revoke all on function public.get_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard(uuid, integer) to authenticated;
