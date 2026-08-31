-- Rebalance the 20 administrator-owned ranking gatekeepers to a simple,
-- player-readable benchmark: displayed combat power equals level * 10.
-- Ordinary player ranking rows are intentionally outside this update.

do $$
declare
  gatekeeper_count integer;
begin
  select pg_catalog.count(*)::integer
    into gatekeeper_count
    from public.ranking_entries
   where system_entry_code ~ '^RANK_GATE_(0[1-9]|1[0-9]|20)$';

  if gatekeeper_count <> 20 then
    raise exception
      'Expected 20 ranking gatekeepers before rebalance, found %',
      gatekeeper_count;
  end if;

  if exists (
    select 1
      from public.ranking_entries
     where system_entry_code ~ '^RANK_GATE_(0[1-9]|1[0-9]|20)$'
       and not (
         (system_entry_code between 'RANK_GATE_01' and 'RANK_GATE_10'
           and level = 19 + pg_catalog.right(system_entry_code, 2)::integer)
         or
         (system_entry_code between 'RANK_GATE_11' and 'RANK_GATE_20'
           and level = 20 + pg_catalog.right(system_entry_code, 2)::integer)
       )
  ) then
    raise exception 'Ranking gatekeeper code-to-level mapping is not the expected Lv.20-29,31-40 roster';
  end if;

  update public.ranking_entries
     set combat_power = level * 10
   where system_entry_code ~ '^RANK_GATE_(0[1-9]|1[0-9]|20)$'
     and combat_power is distinct from level * 10;

  if exists (
    select 1
      from public.ranking_entries
     where system_entry_code ~ '^RANK_GATE_(0[1-9]|1[0-9]|20)$'
       and combat_power <> level * 10
  ) then
    raise exception 'Ranking gatekeeper rebalance verification failed';
  end if;

  if exists (
    select 1
      from public.ranking_entries
     where system_entry_code ~ '^RANK_GATE_(0[1-9]|1[0-9]|20)$'
       and combat_power > ranking_private.maximum_accepted_combat_power(level)
  ) then
    raise exception 'Rebalanced ranking gatekeeper exceeds the level-specific server ceiling';
  end if;
end;
$$;
