-- Authenticated public hero projections for relationships and non-economic arena reuse.
-- Staged for review only. Apply after the ordered ranking migrations; no existing ranking object
-- is changed. All values are client-declared and can never authorize XP, gold, loot or ranking.

create schema if not exists shared_player_private;
revoke all on schema shared_player_private from public, anon, authenticated, service_role;

create table shared_player_private.snapshots (
  projection_id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  display_name text not null check (
    pg_catalog.char_length(display_name) between 1 and 24
    and pg_catalog.octet_length(display_name) <= 96
    and display_name = pg_catalog.btrim(display_name)
    and display_name !~ '[[:cntrl:]]'
  ),
  hero_class text not null check (
    hero_class in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')
  ),
  level bigint not null check (level between 10 and 10000),
  combat_power bigint not null check (combat_power >= 1),
  rules_version integer not null check (rules_version = 1),
  snapshot_version integer not null check (snapshot_version = 1),
  stats jsonb not null check (pg_catalog.jsonb_typeof(stats) = 'object'),
  adventure_trait_ids jsonb not null check (pg_catalog.jsonb_typeof(adventure_trait_ids) = 'array'),
  published_at timestamptz not null default pg_catalog.statement_timestamp(),
  expires_at timestamptz not null,
  unique (user_id, character_id),
  check (expires_at > published_at)
);

-- Seek-and-wrap roster selection uses this ordered index instead of sorting an entire level band.
create index shared_player_snapshots_seek_idx
  on shared_player_private.snapshots (rules_version, level, projection_id);
create index shared_player_snapshots_expiry_idx
  on shared_player_private.snapshots (expires_at, projection_id);

create table shared_player_private.daily_rosters (
  roster_id uuid not null default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  requester_character_id uuid not null,
  rules_version integer not null check (rules_version = 1),
  roster_date_utc date not null,
  requester_level bigint not null check (requester_level between 10 and 10000),
  snapshots jsonb not null check (
    pg_catalog.jsonb_typeof(snapshots) = 'array'
    and pg_catalog.jsonb_array_length(snapshots) <= 24
    and pg_catalog.pg_column_size(snapshots) <= 131072
  ),
  generated_at timestamptz not null,
  valid_until timestamptz not null,
  primary key (user_id, requester_character_id, rules_version),
  unique (roster_id),
  check (valid_until > generated_at)
);
create index shared_player_daily_rosters_expiry_idx
  on shared_player_private.daily_rosters (valid_until, roster_id);

create table shared_player_private.sync_limits (
  user_id uuid primary key references auth.users(id) on delete cascade,
  last_called_at timestamptz not null,
  last_synced_at timestamptz not null,
  payload_hash text not null check (payload_hash ~ '^[0-9a-f]{32}$')
);

create table shared_player_private.roster_call_limits (
  user_id uuid primary key references auth.users(id) on delete cascade,
  last_called_at timestamptz not null
);

-- One row per account is overwritten at each UTC boundary. This caps all newly issued rosters
-- across the account's characters without creating an unbounded per-day ledger.
create table shared_player_private.roster_daily_issuance_limits (
  user_id uuid primary key references auth.users(id) on delete cascade,
  roster_date_utc date not null,
  issued_count smallint not null check (issued_count between 1 and 6),
  last_issued_at timestamptz not null
);

alter table shared_player_private.snapshots enable row level security;
alter table shared_player_private.daily_rosters enable row level security;
alter table shared_player_private.sync_limits enable row level security;
alter table shared_player_private.roster_call_limits enable row level security;
alter table shared_player_private.roster_daily_issuance_limits enable row level security;
revoke all on table shared_player_private.snapshots,
  shared_player_private.daily_rosters,
  shared_player_private.sync_limits,
  shared_player_private.roster_call_limits,
  shared_player_private.roster_daily_issuance_limits
  from public, anon, authenticated, service_role;

create function shared_player_private.is_valid_snapshot(p_snapshot jsonb)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_stats jsonb;
  v_traits jsonb;
  v_level bigint;
  v_power bigint;
  v_class text;
  v_max_base bigint;
  v_max_total bigint;
  v_base_total numeric;
  v_vitality_total numeric;
  v_primary numeric;
  v_secondary numeric;
  v_weighted double precision;
  v_benchmark bigint;
  v_legacy_expected double precision;
  v_guided_expected double precision;
  v_legacy_ratio double precision;
  v_guided_ratio double precision;
  v_legacy_power bigint;
  v_guided_power bigint;
  v_max_shop bigint;
  v_max_item_contribution bigint;
  v_min_power bigint;
  v_max_power bigint;
begin
  if pg_catalog.jsonb_typeof(p_snapshot) is distinct from 'object'
     or pg_catalog.pg_column_size(p_snapshot) > 8192
     or not (p_snapshot ?& array[
       'character_id','display_name','hero_class','level','combat_power',
       'rules_version','snapshot_version','stats','adventure_trait_ids'
     ]::text[])
     or p_snapshot - array[
       'character_id','display_name','hero_class','level','combat_power',
       'rules_version','snapshot_version','stats','adventure_trait_ids'
     ]::text[] <> '{}'::jsonb then
    return false;
  end if;

  if pg_catalog.jsonb_typeof(p_snapshot -> 'character_id') is distinct from 'string'
     or p_snapshot ->> 'character_id' !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
     or pg_catalog.jsonb_typeof(p_snapshot -> 'display_name') is distinct from 'string'
     or pg_catalog.char_length(p_snapshot ->> 'display_name') not between 1 and 24
     or pg_catalog.octet_length(p_snapshot ->> 'display_name') > 96
     or p_snapshot ->> 'display_name' <> pg_catalog.btrim(p_snapshot ->> 'display_name')
     or (p_snapshot ->> 'display_name') ~ '[[:cntrl:]]'
     or pg_catalog.jsonb_typeof(p_snapshot -> 'hero_class') is distinct from 'string'
     or p_snapshot ->> 'hero_class' not in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')
     or pg_catalog.jsonb_typeof(p_snapshot -> 'level') is distinct from 'number'
     or p_snapshot ->> 'level' !~ '^[0-9]+$'
     or (p_snapshot ->> 'level')::numeric not between 10 and 10000
     or pg_catalog.jsonb_typeof(p_snapshot -> 'combat_power') is distinct from 'number'
     or p_snapshot ->> 'combat_power' !~ '^[0-9]+$'
     or pg_catalog.jsonb_typeof(p_snapshot -> 'rules_version') is distinct from 'number'
     or p_snapshot ->> 'rules_version' <> '1'
     or pg_catalog.jsonb_typeof(p_snapshot -> 'snapshot_version') is distinct from 'number'
     or p_snapshot ->> 'snapshot_version' <> '1' then
    return false;
  end if;
  v_level := (p_snapshot ->> 'level')::bigint;
  v_power := (p_snapshot ->> 'combat_power')::bigint;
  v_class := p_snapshot ->> 'hero_class';
  if v_power not between 1 and ranking_private.maximum_accepted_combat_power(v_level) then
    return false;
  end if;

  v_stats := p_snapshot -> 'stats';
  if pg_catalog.jsonb_typeof(v_stats) is distinct from 'object'
     or not (v_stats ?& array[
       'strength','constitution','dexterity','intelligence','wisdom','charisma',
       'max_health','max_mana'
     ]::text[])
     or v_stats - array[
       'strength','constitution','dexterity','intelligence','wisdom','charisma',
       'max_health','max_mana'
     ]::text[] <> '{}'::jsonb
     or exists (
       select 1 from pg_catalog.jsonb_each(v_stats) as stat(key, value)
        where pg_catalog.jsonb_typeof(stat.value) is distinct from 'number'
           or stat.value::text !~ '^[0-9]+$'
     ) then
    return false;
  end if;
  v_max_base := v_level * 2 + 16;
  v_max_total := v_level * 2 + 106;
  if (v_stats ->> 'strength')::numeric > v_max_base
     or (v_stats ->> 'constitution')::numeric > v_max_base
     or (v_stats ->> 'dexterity')::numeric > v_max_base
     or (v_stats ->> 'intelligence')::numeric > v_max_base
     or (v_stats ->> 'wisdom')::numeric > v_max_base
     or (v_stats ->> 'charisma')::numeric > v_max_base
     or (v_stats ->> 'max_health')::numeric < 1 then
    return false;
  end if;
  v_base_total := (v_stats ->> 'strength')::numeric + (v_stats ->> 'constitution')::numeric
    + (v_stats ->> 'dexterity')::numeric + (v_stats ->> 'intelligence')::numeric
    + (v_stats ->> 'wisdom')::numeric + (v_stats ->> 'charisma')::numeric;
  v_vitality_total := (v_stats ->> 'max_health')::numeric + (v_stats ->> 'max_mana')::numeric;
  if v_base_total > v_max_total
     or v_vitality_total > pg_catalog.least(
       4::numeric * v_level * v_level + 512,
       v_power::numeric * (v_level + 64)
     ) then
    return false;
  end if;

  v_primary := case v_class
    when 'WARRIOR' then (v_stats ->> 'strength')::numeric
    when 'ROGUE' then (v_stats ->> 'dexterity')::numeric
    when 'RANGER' then (v_stats ->> 'dexterity')::numeric
    when 'MAGE' then (v_stats ->> 'intelligence')::numeric
    when 'CLERIC' then (v_stats ->> 'wisdom')::numeric
    when 'PALADIN' then (v_stats ->> 'strength')::numeric end;
  v_secondary := case v_class
    when 'WARRIOR' then (v_stats ->> 'constitution')::numeric
    when 'ROGUE' then (v_stats ->> 'strength')::numeric
    when 'RANGER' then (v_stats ->> 'wisdom')::numeric
    when 'MAGE' then (v_stats ->> 'wisdom')::numeric
    when 'CLERIC' then (v_stats ->> 'charisma')::numeric
    when 'PALADIN' then (v_stats ->> 'charisma')::numeric end;
  v_weighted := (v_primary * 7 + v_secondary * 3)::double precision;
  v_benchmark := 1 + (v_level - 1) * 5;
  v_legacy_expected := (315 + (v_level - 1) * 9)::double precision;
  v_guided_expected := (315 + (v_level - 1) * 20)::double precision;
  if v_weighted <= 0 then
    v_legacy_power := 0;
    v_guided_power := 0;
  else
    v_legacy_ratio := v_weighted * 3.0 / v_legacy_expected;
    v_guided_ratio := v_weighted * 3.0 / v_guided_expected;
    v_legacy_power := pg_catalog.round((v_benchmark * (
      1.0 + (v_legacy_ratio - 1.0) / (1.0 + pg_catalog.abs(v_legacy_ratio - 1.0) / 0.35)
    ))::numeric)::bigint;
    v_guided_power := pg_catalog.round((v_benchmark * (
      1.0 + (v_guided_ratio - 1.0) / (1.0 + pg_catalog.abs(v_guided_ratio - 1.0) / 0.35)
    ))::numeric)::bigint;
  end if;
  v_max_shop := v_benchmark + 13;
  v_max_item_contribution := pg_catalog.greatest(
    pg_catalog.greatest(v_benchmark - 10, 0) + 30,
    (v_max_shop * 105 + 99) / 100
  ) + 11;
  v_min_power := pg_catalog.greatest(pg_catalog.least(v_legacy_power, v_guided_power) - 1, 1);
  v_max_power := pg_catalog.greatest(v_legacy_power, v_guided_power) + v_max_item_contribution + 1;
  if v_power not between v_min_power and v_max_power then return false; end if;

  v_traits := p_snapshot -> 'adventure_trait_ids';
  if pg_catalog.jsonb_typeof(v_traits) is distinct from 'array'
     or pg_catalog.jsonb_array_length(v_traits) > 24
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(v_traits) as trait(value)
        where pg_catalog.jsonb_typeof(trait.value) is distinct from 'string'
           or trait.value #>> '{}' not in (
             'L01','L02','L03','L04','L05','L06',
             'S01','S02','S03','S04','S05','S06',
             'E01','E02','E03','E04','E05','E06',
             'G01','G02','G03','G04',
             'R01','R02','R03','R04','R05','R06',
             'T01','T02','T03','T04','T05','T06',
             'C01','C02','C03','C04','C05','C06'
           )
     )
     or (select pg_catalog.count(distinct trait.value #>> '{}')
           from pg_catalog.jsonb_array_elements(v_traits) as trait(value)) <>
        pg_catalog.jsonb_array_length(v_traits)
     or exists (
       select 1 from (values
         ('L01','L02'),('L03','L04'),('L05','L06'),
         ('S01','S02'),('S03','S04'),('S05','S06'),
         ('E01','E02'),('E03','E04'),('E05','E06'),
         ('G01','G02'),('G03','G04'),
         ('R01','R02'),('R03','R04'),('R05','R06'),
         ('T01','T02'),('T03','T04'),('T05','T06'),
         ('C01','C02'),('C03','C04'),('C05','C06')
       ) as pair(left_id, right_id)
       where v_traits @> pg_catalog.jsonb_build_array(pair.left_id)
         and v_traits @> pg_catalog.jsonb_build_array(pair.right_id)
     ) then
    return false;
  end if;
  return true;
exception when others then
  return false;
end;
$$;

revoke all on function shared_player_private.is_valid_snapshot(jsonb)
  from public, anon, authenticated, service_role;

-- Optional maintenance hook for a database-owner scheduler. It is intentionally private,
-- SECURITY INVOKER, bounded per call, and has no grant to client or service roles.
create function shared_player_private.cleanup_expired_shared_player_data(p_limit integer default 5000)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_now timestamptz := pg_catalog.statement_timestamp();
  v_snapshot_count integer := 0;
  v_roster_count integer := 0;
begin
  if p_limit is null or p_limit not between 1 and 10000 then
    raise exception 'invalid shared-player cleanup limit' using errcode = '22023';
  end if;
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('shared-player-expiry-cleanup', 0)
  );
  with doomed as (
    select candidate.projection_id
      from shared_player_private.snapshots as candidate
     where candidate.expires_at <= v_now
     order by candidate.expires_at, candidate.projection_id
     limit p_limit
  )
  delete from shared_player_private.snapshots as expired
   using doomed
   where expired.projection_id = doomed.projection_id;
  get diagnostics v_snapshot_count = row_count;

  with doomed as (
    select candidate.roster_id
      from shared_player_private.daily_rosters as candidate
     where candidate.valid_until <= v_now
     order by candidate.valid_until, candidate.roster_id
     limit p_limit
  )
  delete from shared_player_private.daily_rosters as expired
   using doomed
   where expired.roster_id = doomed.roster_id;
  get diagnostics v_roster_count = row_count;

  return pg_catalog.jsonb_build_object(
    'expired_snapshots_deleted', v_snapshot_count,
    'expired_rosters_deleted', v_roster_count
  );
end;
$$;

revoke all on function shared_player_private.cleanup_expired_shared_player_data(integer)
  from public, anon, authenticated, service_role;

create function public.sync_public_player_snapshots(p_snapshots jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_now timestamptz := pg_catalog.statement_timestamp();
  v_count integer;
  v_hash text;
  v_limit shared_player_private.sync_limits%rowtype;
  v_had_limit boolean;
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  -- Reject oversized input before any jsonb-array expansion or per-field work.
  if p_snapshots is null
     or pg_catalog.jsonb_typeof(p_snapshots) is distinct from 'array'
     or pg_catalog.pg_column_size(p_snapshots) > 65536 then
    raise exception 'invalid public player snapshot batch' using errcode = '22023';
  end if;
  if pg_catalog.jsonb_array_length(p_snapshots) > 3
     or exists (
       select 1 from pg_catalog.jsonb_array_elements(p_snapshots) as item(value)
        where not shared_player_private.is_valid_snapshot(item.value)
     )
     or (select pg_catalog.count(distinct item.value ->> 'character_id')
           from pg_catalog.jsonb_array_elements(p_snapshots) as item(value)) <>
        pg_catalog.jsonb_array_length(p_snapshots) then
    raise exception 'invalid public player snapshot batch' using errcode = '22023';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('shared-player-account:' || v_user_id::text, 0)
  );
  v_hash := pg_catalog.md5(p_snapshots::text);
  select * into v_limit from shared_player_private.sync_limits as guard
   where guard.user_id = v_user_id for update;
  v_had_limit := found;
  if v_had_limit and v_now - v_limit.last_called_at < interval '5 seconds' then
    return pg_catalog.jsonb_build_object(
      'rules_version', 1, 'synced_count', -1,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'rate_limited', true, 'retry_after_seconds', 5
    );
  end if;
  if v_had_limit then
    update shared_player_private.sync_limits
       set last_called_at = v_now where user_id = v_user_id;
  else
    insert into shared_player_private.sync_limits(
      user_id, last_called_at, last_synced_at, payload_hash
    ) values (v_user_id, v_now, '-infinity'::timestamptz, v_hash);
  end if;

  -- A previously accepted character may stay at its level or advance at most one level per
  -- elapsed 15-minute publication window. Level decreases are rejected. This preserves normal
  -- monotonic growth while preventing rapid +/-1 band cycling with a client-edited level.
  if exists (
    select 1
      from pg_catalog.jsonb_to_recordset(p_snapshots) as entry(character_id text, level bigint)
      join shared_player_private.snapshots as saved
        on saved.user_id = v_user_id
       and saved.character_id = (entry.character_id)::uuid
     where entry.level < saved.level
        or entry.level > pg_catalog.least(
          10000::bigint,
          saved.level + pg_catalog.greatest(
            1::bigint,
            pg_catalog.floor(
              pg_catalog.date_part('epoch', v_now - saved.published_at) / 900.0
            )::bigint
          )
        )
  ) then
    return pg_catalog.jsonb_build_object(
      'rules_version', 1, 'synced_count', -1,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'invalid_level', true, 'reason', 'level_change_out_of_range'
    );
  end if;
  if v_had_limit and v_now - v_limit.last_synced_at < interval '15 minutes' then
    if v_hash = v_limit.payload_hash then
      return pg_catalog.jsonb_build_object(
        'rules_version', 1,
        'synced_count', pg_catalog.jsonb_array_length(p_snapshots),
        'server_now', (extract(epoch from v_now) * 1000)::bigint,
        'deduplicated', true
      );
    end if;
    return pg_catalog.jsonb_build_object(
      'rules_version', 1, 'synced_count', -1,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'rate_limited', true, 'retry_after_seconds', 900
    );
  end if;

  -- Bounded opportunistic retention work; deployment notes include a scheduled full sweep.
  delete from shared_player_private.snapshots as expired where expired.projection_id in (
    select candidate.projection_id from shared_player_private.snapshots as candidate
     where candidate.expires_at <= v_now order by candidate.expires_at limit 500
  );
  delete from shared_player_private.daily_rosters as expired where expired.roster_id in (
    select candidate.roster_id from shared_player_private.daily_rosters as candidate
     where candidate.valid_until <= v_now order by candidate.valid_until limit 500
  );

  -- Remove per-character derivatives before omitted snapshot rows so one account can retain at
  -- most the current three character keys even without waiting for global expiry maintenance.
  delete from shared_player_private.daily_rosters as roster
   where roster.user_id = v_user_id
     and not exists (
       select 1 from pg_catalog.jsonb_array_elements(p_snapshots) as item(value)
        where item.value ->> 'character_id' = roster.requester_character_id::text
     );
  delete from shared_player_private.snapshots as saved
   where saved.user_id = v_user_id
     and not exists (
       select 1 from pg_catalog.jsonb_array_elements(p_snapshots) as item(value)
        where item.value ->> 'character_id' = saved.character_id::text
     );
  insert into shared_player_private.snapshots as saved (
    user_id, character_id, display_name, hero_class, level, combat_power,
    rules_version, snapshot_version, stats, adventure_trait_ids, published_at, expires_at
  )
  select v_user_id, (entry.character_id)::uuid, entry.display_name, entry.hero_class,
         entry.level, entry.combat_power, entry.rules_version, entry.snapshot_version,
         entry.stats, entry.adventure_trait_ids, v_now, v_now + interval '72 hours'
    from pg_catalog.jsonb_to_recordset(p_snapshots) as entry(
      character_id text, display_name text, hero_class text, level bigint,
      combat_power bigint, rules_version integer, snapshot_version integer,
      stats jsonb, adventure_trait_ids jsonb
    )
  on conflict (user_id, character_id) do update set
    display_name = excluded.display_name,
    hero_class = excluded.hero_class,
    level = excluded.level,
    combat_power = excluded.combat_power,
    rules_version = excluded.rules_version,
    snapshot_version = excluded.snapshot_version,
    stats = excluded.stats,
    adventure_trait_ids = excluded.adventure_trait_ids,
    published_at = excluded.published_at,
    expires_at = excluded.expires_at;

  insert into shared_player_private.sync_limits(user_id, last_called_at, last_synced_at, payload_hash)
  values (v_user_id, v_now, v_now, v_hash)
  on conflict (user_id) do update set
    last_called_at = excluded.last_called_at,
    last_synced_at = excluded.last_synced_at,
    payload_hash = excluded.payload_hash;
  v_count := pg_catalog.jsonb_array_length(p_snapshots);
  return pg_catalog.jsonb_build_object(
    'rules_version', 1,
    'synced_count', v_count,
    'server_now', (extract(epoch from v_now) * 1000)::bigint,
    'deduplicated', false
  );
end;
$$;

revoke all on function public.sync_public_player_snapshots(jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.sync_public_player_snapshots(jsonb) to authenticated;

create function public.get_daily_public_player_roster(
  p_character_id uuid,
  p_rules_version integer default 1
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_now timestamptz := pg_catalog.statement_timestamp();
  v_date date := (pg_catalog.statement_timestamp() at time zone 'UTC')::date;
  v_valid_until timestamptz;
  v_requester shared_player_private.snapshots%rowtype;
  v_roster shared_player_private.daily_rosters%rowtype;
  v_snapshots jsonb;
  v_level_rotation integer;
  v_daily_issued_count smallint;
  v_daily_retry_seconds bigint;
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  if p_character_id is null or p_rules_version is distinct from 1 then
    raise exception 'invalid public player roster request' using errcode = '22023';
  end if;
  -- The same account lock is used by publication and roster issuance, so a roster cannot be
  -- generated from a requester row that changes between eligibility validation and insertion.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('shared-player-account:' || v_user_id::text, 0)
  );
  select * into v_requester from shared_player_private.snapshots as own
   where own.user_id = v_user_id
     and own.character_id = p_character_id
     and own.rules_version = p_rules_version
     and own.level between 10 and 10000
     and own.expires_at > v_now;
  if not found then
    raise exception 'eligible public player snapshot required' using errcode = '22023';
  end if;

  if exists (
    select 1 from shared_player_private.roster_call_limits as guard
     where guard.user_id = v_user_id
       and v_now - guard.last_called_at < interval '5 seconds'
  ) then
    return pg_catalog.jsonb_build_object(
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'rate_limited', true, 'retry_after_seconds', 5
    );
  end if;
  insert into shared_player_private.roster_call_limits(user_id, last_called_at)
  values (v_user_id, v_now)
  on conflict (user_id) do update set
    last_called_at = excluded.last_called_at;
  select * into v_roster from shared_player_private.daily_rosters as roster
   where roster.user_id = v_user_id
     and roster.requester_character_id = p_character_id
     and roster.rules_version = p_rules_version
     and roster.roster_date_utc = v_date
     and roster.requester_level = v_requester.level
     and roster.valid_until > v_now;

  if not found then
    v_valid_until := (
      pg_catalog.date_trunc('day', v_now at time zone 'UTC') + interval '1 day'
    ) at time zone 'UTC';
    insert into shared_player_private.roster_daily_issuance_limits as budget (
      user_id, roster_date_utc, issued_count, last_issued_at
    ) values (
      v_user_id, v_date, 1, v_now
    )
    on conflict (user_id) do update set
      roster_date_utc = excluded.roster_date_utc,
      issued_count = case
        when budget.roster_date_utc = excluded.roster_date_utc then budget.issued_count + 1
        else 1
      end,
      last_issued_at = excluded.last_issued_at
    where budget.roster_date_utc <> excluded.roster_date_utc
       or budget.issued_count < 6
    returning issued_count into v_daily_issued_count;
    if not found then
      v_daily_retry_seconds := pg_catalog.greatest(
        1,
        pg_catalog.ceil(pg_catalog.date_part('epoch', v_valid_until - v_now))::bigint
      );
      return pg_catalog.jsonb_build_object(
        'server_now', (extract(epoch from v_now) * 1000)::bigint,
        'rate_limited', true,
        'daily_limit', true,
        'retry_after_seconds', v_daily_retry_seconds
      );
    end if;
    v_level_rotation := ((pg_catalog.hashtextextended(
      v_date::text || ':' || v_user_id::text || ':' || p_character_id::text, 0
    ) % 3 + 3) % 3)::integer;

    with levels as (
      select step,
             v_requester.level + (((v_level_rotation + step) % 3) - 1) as target_level,
             pg_catalog.md5(
               v_date::text || ':' || v_user_id::text || ':' || p_character_id::text || ':' || step::text
             )::uuid as cursor_id
        from pg_catalog.generate_series(0, 2) as step
    ), sought as (
      select levels.step, picked.segment, picked.projection_id, picked.display_name,
             picked.hero_class, picked.level, picked.combat_power, picked.rules_version,
             picked.snapshot_version, picked.stats, picked.adventure_trait_ids
        from levels
        cross join lateral (
          (select 0 as segment, other.*
             from shared_player_private.snapshots as other
            where other.rules_version = p_rules_version
              and other.level = levels.target_level
              and other.projection_id >= levels.cursor_id
              and other.user_id <> v_user_id
              and other.expires_at > v_now
            order by other.projection_id
            limit 24)
          union all
          (select 1 as segment, other.*
             from shared_player_private.snapshots as other
            where other.rules_version = p_rules_version
              and other.level = levels.target_level
              and other.projection_id < levels.cursor_id
              and other.user_id <> v_user_id
              and other.expires_at > v_now
            order by other.projection_id
            limit 24)
        ) as picked
    ), chosen as (
      select * from sought order by step, segment, projection_id limit 24
    )
    select coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
             'projection_id', chosen.projection_id,
             'display_name', chosen.display_name,
             'hero_class', chosen.hero_class,
             'level', chosen.level,
             'combat_power', chosen.combat_power,
             'rules_version', chosen.rules_version,
             'snapshot_version', chosen.snapshot_version,
             'stats', chosen.stats,
             'adventure_trait_ids', chosen.adventure_trait_ids
           ) order by chosen.step, chosen.segment, chosen.projection_id), '[]'::jsonb)
      into v_snapshots from chosen;

    insert into shared_player_private.daily_rosters as roster (
      roster_id, user_id, requester_character_id, rules_version, roster_date_utc,
      requester_level, snapshots, generated_at, valid_until
    ) values (
      gen_random_uuid(), v_user_id, p_character_id, p_rules_version, v_date,
      v_requester.level, v_snapshots, v_now, v_valid_until
    )
    on conflict (user_id, requester_character_id, rules_version) do update set
      roster_id = excluded.roster_id,
      roster_date_utc = excluded.roster_date_utc,
      requester_level = excluded.requester_level,
      snapshots = excluded.snapshots,
      generated_at = excluded.generated_at,
      valid_until = excluded.valid_until
    returning * into v_roster;
  end if;

  return pg_catalog.jsonb_build_object(
    'roster_id', v_roster.roster_id,
    'roster_date_utc', v_roster.roster_date_utc,
    'requester_level', v_roster.requester_level,
    'rules_version', v_roster.rules_version,
    'generated_at', (extract(epoch from v_roster.generated_at) * 1000)::bigint,
    'valid_until', (extract(epoch from v_roster.valid_until) * 1000)::bigint,
    'server_now', (extract(epoch from v_now) * 1000)::bigint,
    'snapshots', v_roster.snapshots
  );
end;
$$;

revoke all on function public.get_daily_public_player_roster(uuid, integer)
  from public, anon, authenticated, service_role;
grant execute on function public.get_daily_public_player_roster(uuid, integer) to authenticated;

comment on table shared_player_private.snapshots is
  'Client-declared public projections. Stable projection ID; never an economic authority.';
comment on table shared_player_private.daily_rosters is
  'One immutable level +/-1 pool per requester character, requester level and UTC date.';
comment on table shared_player_private.roster_daily_issuance_limits is
  'Bounded one-row-per-account UTC-day budget; at most six newly generated rosters per day.';
comment on function shared_player_private.cleanup_expired_shared_player_data(integer) is
  'Bounded expiry sweep for database-owner scheduling; no client or service-role execute grant.';
