-- Reject ranking combat power above the exact display ceiling for the submitted level.
-- Equality is valid: a legitimately maxed character must still be able to rank.

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

  -- The normalized stat contribution approaches 135% from below and is displayed with
  -- roundToLong(), so positive values use the same half-up result as round(numeric).
  maximum_stat_power := pg_catalog.round(equipment_benchmark * 135 / 100);

  return (maximum_stat_power + maximum_equipment_power)::bigint;
end;
$$;

revoke all on function ranking_private.maximum_accepted_combat_power(bigint)
  from public, anon, authenticated;
grant execute on function ranking_private.maximum_accepted_combat_power(bigint)
  to authenticated;

do $$
begin
  if ranking_private.maximum_accepted_combat_power(20) <> 257
     or ranking_private.maximum_accepted_combat_power(50) <> 615
     or ranking_private.maximum_accepted_combat_power(100) <> 1216 then
    raise exception 'ranking combat-power ceiling self-check failed';
  end if;
end;
$$;
