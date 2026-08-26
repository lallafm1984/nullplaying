-- Keep ranking snapshots in sync after character deletion while preventing clients from
-- writing ranking_entries directly. The exposed function is an invoker wrapper; the
-- privileged implementation lives outside the Data API's exposed schemas.

alter table public.ranking_entries enable row level security;

revoke insert, update, delete on table public.ranking_entries from anon, authenticated;

drop policy if exists "users insert their own ranking" on public.ranking_entries;
drop policy if exists "users update their own ranking" on public.ranking_entries;
drop policy if exists "users delete their own ranking" on public.ranking_entries;

create schema if not exists ranking_private;
revoke all on schema ranking_private from public, anon, authenticated;
grant usage on schema ranking_private to authenticated;

-- The engine's stat contribution approaches at most 135% of its level benchmark.
-- Add the strongest mythic equipment the engine can generate, then allow twice that
-- theoretical total so ordinary balance changes do not reject legitimate players.
create or replace function ranking_private.maximum_accepted_combat_power(p_level bigint)
returns bigint
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  equipment_benchmark numeric;
  maximum_shop_power numeric;
  mythic_source_power numeric;
  guaranteed_mythic_power numeric;
  maximum_equipment_power numeric;
  maximum_stat_power numeric;
begin
  if p_level < 1 or p_level > 10000 then
    return 0;
  end if;

  equipment_benchmark := 1 + (p_level::numeric - 1) * 5;
  maximum_shop_power := equipment_benchmark + 13;
  mythic_source_power := greatest(equipment_benchmark - 10, 0) + 30;
  guaranteed_mythic_power := pg_catalog.ceil(maximum_shop_power * 105 / 100);
  maximum_equipment_power := greatest(mythic_source_power, guaranteed_mythic_power) + 11;
  maximum_stat_power := pg_catalog.ceil(equipment_benchmark * 135 / 100);

  return greatest(
    500::numeric,
    (maximum_stat_power + maximum_equipment_power) * 2
  )::bigint;
end;
$$;

revoke all on function ranking_private.maximum_accepted_combat_power(bigint)
  from public, anon, authenticated;
grant execute on function ranking_private.maximum_accepted_combat_power(bigint)
  to authenticated;

create or replace function ranking_private.sync_ranking_entries(p_entries jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
begin
  if current_user_id is null then
    raise exception 'authentication required';
  end if;

  if p_entries is null
     or pg_catalog.jsonb_typeof(p_entries) is distinct from 'array'
     or pg_catalog.jsonb_array_length(p_entries) > 3 then
    raise exception 'invalid ranking batch';
  end if;

  -- Reject missing, unknown, or malformed fields. Invalid rows must never be silently
  -- discarded because this RPC treats the payload as the caller's complete roster.
  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_entries) as payload(entry)
     where pg_catalog.jsonb_typeof(payload.entry) is distinct from 'object'
        or not (payload.entry ?& array[
          'character_id', 'slot_id', 'display_name', 'hero_class', 'level', 'combat_power'
        ]::text[])
        or payload.entry - array[
          'character_id', 'slot_id', 'display_name', 'hero_class', 'level', 'combat_power'
        ]::text[] <> '{}'::jsonb
  ) then
    raise exception 'invalid ranking batch';
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_to_recordset(p_entries) as e(
        character_id uuid,
        slot_id smallint,
        display_name text,
        hero_class text,
        level bigint,
        combat_power bigint
      )
     where e.character_id is null
        or e.slot_id is null
        or e.slot_id not between 1 and 3
        or e.display_name is null
        or pg_catalog.char_length(pg_catalog.btrim(e.display_name)) not between 1 and 24
        or e.display_name ~ '[[:cntrl:]]'
        or e.hero_class is null
        or e.hero_class not in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')
        or e.level is null
        or e.level < 20
        or e.level > 10000
        or e.combat_power is null
        or e.combat_power < 0
        or e.combat_power > ranking_private.maximum_accepted_combat_power(e.level)
  ) then
    raise exception 'invalid ranking batch';
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_to_recordset(p_entries) as e(character_id uuid, slot_id smallint)
     group by e.character_id
    having pg_catalog.count(*) > 1
  ) or exists (
    select 1
      from pg_catalog.jsonb_to_recordset(p_entries) as e(character_id uuid, slot_id smallint)
     group by e.slot_id
    having pg_catalog.count(*) > 1
  ) then
    raise exception 'duplicate ranking identity';
  end if;

  -- Serialize complete-roster replacements for this user. The whole RPC is one transaction,
  -- so a failed insert rolls the deletion back and readers never observe a partial roster.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  delete from public.ranking_entries as r
   where r.user_id = current_user_id
     and not exists (
       select 1
         from pg_catalog.jsonb_to_recordset(p_entries) as e(character_id uuid)
        where e.character_id = r.character_id
     );

  insert into public.ranking_entries (
    user_id, character_id, slot_id, display_name, hero_class, level, combat_power
  )
  select current_user_id,
         e.character_id,
         e.slot_id,
         pg_catalog.btrim(e.display_name),
         e.hero_class,
         e.level,
         e.combat_power
    from pg_catalog.jsonb_to_recordset(p_entries) as e(
      character_id uuid,
      slot_id smallint,
      display_name text,
      hero_class text,
      level bigint,
      combat_power bigint
    )
  on conflict (user_id, character_id) do update
    set slot_id = excluded.slot_id,
        display_name = excluded.display_name,
        hero_class = excluded.hero_class,
        level = excluded.level,
        combat_power = excluded.combat_power
  where (
    public.ranking_entries.slot_id,
    public.ranking_entries.display_name,
    public.ranking_entries.hero_class,
    public.ranking_entries.level,
    public.ranking_entries.combat_power
  ) is distinct from (
    excluded.slot_id,
    excluded.display_name,
    excluded.hero_class,
    excluded.level,
    excluded.combat_power
  );
end;
$$;

revoke all on function ranking_private.sync_ranking_entries(jsonb) from public, anon;
grant execute on function ranking_private.sync_ranking_entries(jsonb) to authenticated;

create or replace function public.sync_ranking_entries(p_entries jsonb)
returns void
language plpgsql
security invoker
set search_path = ''
as $$
begin
  perform ranking_private.sync_ranking_entries(p_entries);
end;
$$;

revoke all on function public.sync_ranking_entries(jsonb) from public, anon;
grant execute on function public.sync_ranking_entries(jsonb) to authenticated;

-- Hide any implausible legacy rows immediately, without deleting data during this migration.
-- New client writes can only enter through the validated sync RPC above.
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
set search_path = ''
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
      from public.ranking_entries as r
     where r.level between 20 and 10000
       and r.combat_power <= ranking_private.maximum_accepted_combat_power(r.level)
  )
  select rank_number, list_index, user_id, character_id, slot_id, display_name,
         hero_class, level, combat_power, achieved_at, updated_at, total_participants
    from ranked
   where rank_number <= least(greatest(p_limit, 1), 1000)
      or (
        user_id = (select auth.uid())
        and character_id = p_character_id
      )
   order by list_index;
$$;

revoke all on function public.get_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard(uuid, integer) to authenticated;
