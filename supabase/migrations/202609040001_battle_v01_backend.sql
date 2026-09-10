-- AlarmQuest Battle V0.1 server-authoritative storage and RPC boundary.
--
-- Product contract:
--   * A character may request at most three official entries per Korea day.
--   * The server assigns one opponent; clients cannot select or reroll it.
--   * Only the entrant's monthly rating changes (Elo-style K=24). The sampled
--     opponent projection and rating are immutable inputs to that battle.
--   * Battle resolution, rating settlement, and trait evidence are accepted
--     only from service_role. The database replays the Kotlin V0.1 engine from
--     issued snapshots/guidance/seed/rules and requires exact result equality.
--     Narrative attachment is independent and uses an immutable phase plan.
--   * Battle data never grants or removes gold, equipment, experience, or loot.
--   * Existing ranking_entries.combat_power is client-submitted and is never
--     accepted as an official Battle projection.

create schema if not exists battle_private;
revoke all on schema battle_private from public, anon, authenticated;

create table if not exists public.battle_seasons (
  season_id uuid primary key default gen_random_uuid(),
  season_key text not null unique check (season_key ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  -- Battle V0.1 has one executable Kotlin rules contract. A future engine
  -- version must ship its own migration before seasons may reference it.
  rules_version integer not null check (rules_version = 1),
  growth_reference_power bigint not null default 10000 check (growth_reference_power = 10000),
  max_rounds smallint not null default 8 check (max_rounds between 1 and 8),
  initial_rating integer not null default 1000 check (initial_rating = 1000),
  k_factor integer not null default 24 check (k_factor = 24),
  daily_entry_limit smallint not null default 3 check (daily_entry_limit = 3),
  placement_match_count smallint not null default 5 check (placement_match_count = 5),
  carryover_basis_points smallint not null default 2500 check (
    carryover_basis_points between 0 and 10000
  ),
  locked_at timestamptz,
  created_at timestamptz not null default now(),
  check (ends_at > starts_at),
  check (locked_at is null or locked_at >= starts_at)
);

comment on table public.battle_seasons is
  'Asia/Seoul calendar-month Battle seasons. Created and locked only through service-role RPCs.';

create table if not exists battle_private.projection_snapshots (
  snapshot_id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  display_name text not null check (
    pg_catalog.char_length(pg_catalog.btrim(display_name)) between 1 and 24
    and display_name !~ '[[:cntrl:]]'
  ),
  hero_class text not null check (
    hero_class in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')
  ),
  level bigint not null check (level between 20 and 10000),
  rules_version integer not null check (rules_version = 1),
  server_revision text not null check (
    pg_catalog.char_length(server_revision) between 1 and 128
    and server_revision ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'
  ),
  snapshot_source text not null default 'SERVER_VERIFIED' check (
    snapshot_source = 'SERVER_VERIFIED'
  ),
  snapshot_payload jsonb not null,
  verified_power_raw bigint not null check (verified_power_raw between 1 and 12000000000),
  verified_power_basis_points smallint not null check (
    verified_power_basis_points between 0 and 10000
  ),
  representative_skill_id text,
  representative_skill_mastery smallint not null check (
    representative_skill_mastery between 0 and 100
  ),
  growth_index_basis_points smallint not null check (
    growth_index_basis_points between 0 and 10000
  ),
  effective_power bigint not null check (effective_power between 1 and 100000),
  verified_at timestamptz not null default now(),
  expires_at timestamptz not null,
  revoked_at timestamptz,
  created_at timestamptz not null default now(),
  unique (user_id, character_id, rules_version, server_revision),
  check (expires_at > verified_at),
  check (revoked_at is null or revoked_at >= verified_at)
);

create unique index if not exists battle_projection_one_active_revision_idx
  on battle_private.projection_snapshots (user_id, character_id, rules_version)
  where revoked_at is null;

create index if not exists battle_projection_active_lookup_idx
  on battle_private.projection_snapshots (
    rules_version,
    user_id,
    character_id,
    verified_at desc
  )
  where revoked_at is null;

comment on table battle_private.projection_snapshots is
  'Service-verified Battle inputs. Client ranking combat_power is intentionally not a source.';

create table if not exists public.battle_participants (
  season_id uuid not null references public.battle_seasons(season_id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  snapshot_id uuid not null references battle_private.projection_snapshots(snapshot_id),
  guidance text not null check (guidance in ('ASSAULT','BALANCED','GUARD')),
  rating integer not null check (rating between 0 and 2147483647),
  highest_rating integer not null check (highest_rating between 0 and 2147483647),
  matches_played integer not null default 0 check (matches_played >= 0),
  wins integer not null default 0 check (wins >= 0),
  draws integer not null default 0 check (draws >= 0),
  losses integer not null default 0 check (losses >= 0),
  joined_at timestamptz not null default now(),
  rating_achieved_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (season_id, user_id, character_id),
  unique (season_id, snapshot_id),
  check (matches_played = wins + draws + losses),
  check (highest_rating >= rating)
);

create index if not exists battle_participants_leaderboard_idx
  on public.battle_participants (
    season_id,
    rating desc,
    wins desc,
    losses,
    rating_achieved_at,
    character_id
  );

create table if not exists public.battle_entries (
  entry_id uuid primary key,
  season_id uuid not null,
  user_id uuid not null,
  character_id uuid not null,
  snapshot_id uuid not null references battle_private.projection_snapshots(snapshot_id),
  entry_day date not null,
  directive text not null check (directive in ('ASSAULT','BALANCED','GUARD')),
  status text not null check (status in ('MATCHED','SETTLED','VOID')),
  battle_id uuid not null unique,
  created_at timestamptz not null default now(),
  settled_at timestamptz,
  void_reason text check (void_reason in ('VOID','EXPIRED','CANCELLED')),
  voided_at timestamptz,
  foreign key (season_id, user_id, character_id)
    references public.battle_participants(season_id, user_id, character_id)
    on delete cascade,
  check (
    (status = 'MATCHED'
      and settled_at is null
      and void_reason is null
      and voided_at is null)
    or (status = 'SETTLED'
      and settled_at is not null
      and void_reason is null
      and voided_at is null)
    or (status = 'VOID'
      and settled_at is null
      and void_reason is not null
      and voided_at is not null)
  )
);

create index if not exists battle_entries_daily_limit_idx
  on public.battle_entries (season_id, user_id, character_id, entry_day, status);

create unique index if not exists battle_entries_one_open_match_idx
  on public.battle_entries (season_id, user_id, character_id)
  where status = 'MATCHED';

create table if not exists battle_private.matches (
  battle_id uuid primary key,
  entry_id uuid not null unique references public.battle_entries(entry_id) on delete cascade,
  season_id uuid not null references public.battle_seasons(season_id) on delete cascade,
  entrant_user_id uuid not null references auth.users(id) on delete cascade,
  entrant_character_id uuid not null,
  entrant_snapshot_id uuid not null references battle_private.projection_snapshots(snapshot_id),
  entrant_traits_snapshot jsonb not null default '[]'::jsonb check (
    pg_catalog.jsonb_typeof(entrant_traits_snapshot) = 'array'
  ),
  opponent_user_id uuid not null references auth.users(id) on delete cascade,
  opponent_character_id uuid not null,
  opponent_snapshot_id uuid not null references battle_private.projection_snapshots(snapshot_id),
  opponent_traits_snapshot jsonb not null default '[]'::jsonb check (
    pg_catalog.jsonb_typeof(opponent_traits_snapshot) = 'array'
  ),
  opponent_guidance text not null check (opponent_guidance in ('ASSAULT','BALANCED','GUARD')),
  entrant_rating_before integer not null,
  opponent_rating_reference integer not null,
  rating_window smallint not null check (rating_window in (100, 200, 300)),
  candidate_pool_size smallint not null check (candidate_pool_size between 1 and 5),
  selection_algorithm text not null default 'STRICT_WINDOW_TOP5_WEIGHTED_V1' check (
    selection_algorithm = 'STRICT_WINDOW_TOP5_WEIGHTED_V1'
  ),
  expected_score numeric(9,8) not null check (expected_score between 0 and 1),
  k_factor integer not null check (k_factor = 24),
  rules_version integer not null check (rules_version = 1),
  engine_seed bigint not null unique,
  status text not null default 'MATCHED' check (status in ('MATCHED','SETTLED','VOID')),
  outcome text check (outcome in ('USER_WIN','DRAW','USER_LOSS')),
  score_delta integer check (score_delta between -24 and 24),
  entrant_rating_after integer,
  engine_result jsonb,
  narrative jsonb,
  trait_code text,
  trait_evidence_strength integer check (trait_evidence_strength between 1 and 1000),
  created_at timestamptz not null default now(),
  settled_at timestamptz,
  narrative_attached_at timestamptz,
  void_reason text check (void_reason in ('VOID','EXPIRED','CANCELLED')),
  voided_at timestamptz,
  check (entrant_user_id <> opponent_user_id),
  check (entrant_character_id <> opponent_character_id or entrant_user_id <> opponent_user_id),
  check (pg_catalog.abs(entrant_rating_before - opponent_rating_reference) <= rating_window),
  check (
    (status = 'MATCHED'
      and outcome is null
      and score_delta is null
      and entrant_rating_after is null
      and engine_result is null
      and settled_at is null
      and void_reason is null
      and voided_at is null)
    or (status = 'SETTLED'
      and outcome is not null
      and score_delta is not null
      and entrant_rating_after is not null
      and engine_result is not null
      and settled_at is not null
      and void_reason is null
      and voided_at is null)
    or (status = 'VOID'
      and outcome is null
      and score_delta is null
      and entrant_rating_after is null
      and engine_result is null
      and trait_code is null
      and trait_evidence_strength is null
      and settled_at is null
      and narrative is null
      and narrative_attached_at is null
      and void_reason is not null
      and voided_at is not null)
  ),
  check (
    (narrative is null and narrative_attached_at is null)
    or (narrative is not null and narrative_attached_at is not null)
  ),
  check (
    entrant_rating_after is null
    or entrant_rating_after = entrant_rating_before + score_delta
  ),
  check (
    (trait_code is null and trait_evidence_strength is null)
    or (trait_code is not null and trait_evidence_strength is not null)
  )
);

create index if not exists battle_matches_opponent_history_idx
  on battle_private.matches (
    season_id,
    entrant_user_id,
    entrant_character_id,
    opponent_user_id,
    opponent_character_id,
    created_at desc
  );

create index if not exists battle_matches_opponent_sampling_idx
  on battle_private.matches (season_id, opponent_user_id, opponent_character_id, created_at desc);

create table if not exists public.battle_trait_catalog (
  trait_code text primary key check (trait_code ~ '^TRAIT_(0[0-9][1-9]|0[1-9][0-9]|100)$'),
  label_key text not null unique check (label_key ~ '^battle_trait_[0-9]{3}$'),
  category text not null check (category in (
    'OPENING','OFFENSE','DEFENSE','REVERSAL','ENDURANCE',
    'PRECISION','TACTICS','TEMPERAMENT','MOMENTUM','FINISH'
  )),
  trigger_rule_id text not null unique check (
    trigger_rule_id ~ '^battle_trait_rule_[0-9]{3}$'
  ),
  narration_tags text[] not null default '{}'::text[],
  incompatible_trait_codes text[] not null default '{}'::text[],
  enabled boolean not null default true,
  created_at timestamptz not null default now()
);

insert into public.battle_trait_catalog (
  trait_code,
  label_key,
  category,
  trigger_rule_id
)
select 'TRAIT_' || pg_catalog.lpad(series.number::text, 3, '0'),
       'battle_trait_' || pg_catalog.lpad(series.number::text, 3, '0'),
       (array[
         'OPENING','OFFENSE','DEFENSE','REVERSAL','ENDURANCE',
         'PRECISION','TACTICS','TEMPERAMENT','MOMENTUM','FINISH'
       ])[((series.number - 1) / 10) + 1],
       'battle_trait_rule_' || pg_catalog.lpad(series.number::text, 3, '0')
  from pg_catalog.generate_series(1, 100) as series(number)
on conflict (trait_code) do nothing;

create table if not exists public.battle_character_traits (
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  trait_code text not null references public.battle_trait_catalog(trait_code),
  slot_number smallint not null check (slot_number between 1 and 5),
  evidence_count integer not null default 1 check (evidence_count > 0),
  evidence_strength integer not null default 1 check (evidence_strength > 0),
  source_battle_id uuid not null references battle_private.matches(battle_id),
  first_earned_at timestamptz not null default now(),
  last_reinforced_at timestamptz not null default now(),
  primary key (user_id, character_id, trait_code),
  unique (user_id, character_id, slot_number)
);

create table if not exists public.battle_trait_candidates (
  candidate_id uuid primary key default gen_random_uuid(),
  season_id uuid not null references public.battle_seasons(season_id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  trait_code text not null references public.battle_trait_catalog(trait_code),
  candidate_slot smallint not null check (candidate_slot between 1 and 3),
  evidence_count integer not null default 1 check (evidence_count > 0),
  evidence_strength integer not null check (evidence_strength > 0),
  source_battle_id uuid not null references battle_private.matches(battle_id),
  status text not null default 'PENDING' check (
    status in ('PENDING','PROMOTED','DISCARDED','EXPIRED')
  ),
  first_earned_at timestamptz not null default now(),
  last_reinforced_at timestamptz not null default now(),
  expires_at timestamptz not null,
  resolved_at timestamptz,
  check (
    (status = 'PENDING' and resolved_at is null)
    or (status <> 'PENDING' and resolved_at is not null)
  )
);

create unique index if not exists battle_trait_candidates_open_slot_idx
  on public.battle_trait_candidates (user_id, character_id, candidate_slot)
  where status = 'PENDING';

create unique index if not exists battle_trait_candidates_open_code_idx
  on public.battle_trait_candidates (user_id, character_id, trait_code)
  where status = 'PENDING';

create table if not exists public.battle_trait_removals (
  removal_id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  trait_code text not null references public.battle_trait_catalog(trait_code),
  replacement_candidate_id uuid references public.battle_trait_candidates(candidate_id),
  status text not null default 'PENDING' check (
    status in ('PENDING','CANCELLED','COMPLETED')
  ),
  requested_at timestamptz not null default now(),
  completes_at timestamptz not null,
  cancelled_at timestamptz,
  completed_at timestamptz,
  check (completes_at = requested_at + interval '1 hour'),
  check (
    (status = 'PENDING' and cancelled_at is null and completed_at is null)
    or (status = 'CANCELLED' and cancelled_at is not null and completed_at is null)
    or (status = 'COMPLETED' and cancelled_at is null and completed_at is not null)
  )
);

create unique index if not exists battle_trait_removals_one_pending_idx
  on public.battle_trait_removals (user_id, character_id)
  where status = 'PENDING';

create table if not exists public.battle_records (
  battle_id uuid primary key references battle_private.matches(battle_id) on delete cascade,
  season_id uuid not null references public.battle_seasons(season_id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  opponent_user_id uuid not null references auth.users(id) on delete cascade,
  opponent_character_id uuid not null,
  opponent_display_name text not null,
  opponent_hero_class text not null,
  opponent_level bigint not null,
  opponent_rating_reference integer not null,
  directive text not null check (directive in ('ASSAULT','BALANCED','GUARD')),
  outcome text not null check (outcome in ('USER_WIN','DRAW','USER_LOSS')),
  rating_before integer not null,
  score_delta integer not null check (score_delta between -24 and 24),
  rating_after integer not null,
  rules_version integer not null check (rules_version = 1),
  engine_result jsonb not null,
  narrative jsonb,
  trait_code text references public.battle_trait_catalog(trait_code),
  trait_evidence_strength integer check (trait_evidence_strength between 1 and 1000),
  created_at timestamptz not null,
  settled_at timestamptz not null,
  narrative_attached_at timestamptz,
  check (
    (narrative is null and narrative_attached_at is null)
    or (narrative is not null and narrative_attached_at is not null)
  ),
  check (rating_after = rating_before + score_delta),
  check (
    (trait_code is null and trait_evidence_strength is null)
    or (trait_code is not null and trait_evidence_strength is not null)
  )
);

create index if not exists battle_records_owner_history_idx
  on public.battle_records (user_id, character_id, settled_at desc, battle_id);

-- Every non-settlement terminal transition has one immutable audit row. The
-- lifecycle status remains VOID; recovery_kind records whether it was a
-- permanent processing failure, a season expiry/lock, or a service cancel.
create table if not exists battle_private.match_recoveries (
  recovery_id uuid primary key,
  battle_id uuid not null unique references battle_private.matches(battle_id),
  entry_id uuid not null unique references public.battle_entries(entry_id),
  season_id uuid not null references public.battle_seasons(season_id),
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  entry_day date not null,
  recovery_kind text not null check (
    recovery_kind in ('VOID','EXPIRED','CANCELLED')
  ),
  recovery_source text not null check (
    recovery_source in (
      'SERVICE_RPC','SEASON_LOCK','SEASON_BOUNDARY','SETTLEMENT_GUARD','SEASON_SWEEP'
    )
  ),
  -- Captured inside the database from the executing session. No recovery RPC
  -- accepts an actor/principal string supplied by its caller.
  actor_database_role text not null check (
    pg_catalog.char_length(actor_database_role) between 1 and 63
  ),
  actor_session_user text not null check (
    pg_catalog.char_length(actor_session_user) between 1 and 63
  ),
  actor_auth_user_id uuid,
  -- Immutable server-observed lifecycle transition. These values are copied
  -- from the locked rows before the update and from UPDATE ... RETURNING after
  -- the update; a caller cannot submit any of them.
  match_status_before text not null check (
    match_status_before in ('MATCHED','SETTLED','VOID')
  ),
  match_status_after text not null check (
    match_status_after in ('MATCHED','SETTLED','VOID')
  ),
  entry_status_before text not null check (
    entry_status_before in ('MATCHED','SETTLED','VOID')
  ),
  entry_status_after text not null check (
    entry_status_after in ('MATCHED','SETTLED','VOID')
  ),
  ticket_counted_before boolean not null,
  ticket_counted_after boolean not null,
  audit_note text not null check (
    pg_catalog.char_length(pg_catalog.btrim(audit_note)) between 1 and 240
    and audit_note !~ '[[:cntrl:]]'
  ),
  recovered_at timestamptz not null,
  daily_entry_limit smallint not null check (daily_entry_limit = 3),
  entries_used_on_entry_day smallint not null check (
    entries_used_on_entry_day between 0 and 3
  ),
  remaining_entries_on_entry_day smallint not null check (
    remaining_entries_on_entry_day between 0 and 3
  ),
  current_entry_day date not null,
  entries_used_on_current_day smallint not null check (
    entries_used_on_current_day between 0 and 3
  ),
  remaining_entries_on_current_day smallint not null check (
    remaining_entries_on_current_day between 0 and 3
  ),
  check (entries_used_on_entry_day + remaining_entries_on_entry_day = daily_entry_limit),
  check (entries_used_on_current_day + remaining_entries_on_current_day = daily_entry_limit),
  check (
    match_status_before = 'MATCHED'
    and match_status_after = 'VOID'
    and entry_status_before = 'MATCHED'
    and entry_status_after = 'VOID'
  ),
  check (ticket_counted_before = (entry_status_before in ('MATCHED','SETTLED'))),
  check (ticket_counted_after = (entry_status_after in ('MATCHED','SETTLED')))
);

create index if not exists battle_match_recoveries_owner_idx
  on battle_private.match_recoveries (
    user_id,
    character_id,
    recovered_at desc,
    recovery_id
  );

create index if not exists battle_matches_open_by_season_idx
  on battle_private.matches (season_id, created_at, battle_id)
  where status = 'MATCHED';

-- The trusted narration service freezes its already-engine-derived phase plan
-- before Qwen prose is accepted. Each phase carries its own allowed_refs; a
-- reference issued for another phase is never implicitly available match-wide.
create table if not exists battle_private.narrative_phase_contracts (
  battle_id uuid primary key references battle_private.matches(battle_id) on delete cascade,
  phase_plan jsonb not null check (pg_catalog.jsonb_typeof(phase_plan) = 'array'),
  issuer_database_role text not null check (
    pg_catalog.char_length(issuer_database_role) between 1 and 63
  ),
  issuer_session_user text not null check (
    pg_catalog.char_length(issuer_session_user) between 1 and 63
  ),
  issuer_auth_user_id uuid,
  issued_at timestamptz not null
);

-- All Battle tables are deny-by-default. Authenticated clients use the narrowly
-- scoped RPCs below; service_role mutations also use explicit RPCs.
alter table public.battle_seasons enable row level security;
alter table battle_private.projection_snapshots enable row level security;
alter table public.battle_participants enable row level security;
alter table public.battle_entries enable row level security;
alter table battle_private.matches enable row level security;
alter table public.battle_trait_catalog enable row level security;
alter table public.battle_character_traits enable row level security;
alter table public.battle_trait_candidates enable row level security;
alter table public.battle_trait_removals enable row level security;
alter table public.battle_records enable row level security;
alter table battle_private.match_recoveries enable row level security;
alter table battle_private.narrative_phase_contracts enable row level security;

revoke all on table public.battle_seasons from public, anon, authenticated, service_role;
revoke all on table battle_private.projection_snapshots from public, anon, authenticated, service_role;
revoke all on table public.battle_participants from public, anon, authenticated, service_role;
revoke all on table public.battle_entries from public, anon, authenticated, service_role;
revoke all on table battle_private.matches from public, anon, authenticated, service_role;
revoke all on table public.battle_trait_catalog from public, anon, authenticated, service_role;
revoke all on table public.battle_character_traits from public, anon, authenticated, service_role;
revoke all on table public.battle_trait_candidates from public, anon, authenticated, service_role;
revoke all on table public.battle_trait_removals from public, anon, authenticated, service_role;
revoke all on table public.battle_records from public, anon, authenticated, service_role;
revoke all on table battle_private.match_recoveries from public, anon, authenticated, service_role;
revoke all on table battle_private.narrative_phase_contracts from public, anon, authenticated, service_role;

create or replace function battle_private.contains_forbidden_economy_key(p_value jsonb)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_key text;
  v_child jsonb;
begin
  if pg_catalog.jsonb_typeof(p_value) = 'object' then
    for v_key, v_child in
      select key, value from pg_catalog.jsonb_each(p_value)
    loop
      if pg_catalog.lower(v_key) = any (array[
        'gold','currency','currencies','experience','xp','loot','reward','rewards',
        'inventory','inventory_items','equipment_grant','gear_grant'
      ]) then
        return true;
      end if;
      if battle_private.contains_forbidden_economy_key(v_child) then
        return true;
      end if;
    end loop;
  elsif pg_catalog.jsonb_typeof(p_value) = 'array' then
    for v_child in
      select value from pg_catalog.jsonb_array_elements(p_value)
    loop
      if battle_private.contains_forbidden_economy_key(v_child) then
        return true;
      end if;
    end loop;
  end if;

  return false;
end;
$$;

revoke all on function battle_private.contains_forbidden_economy_key(jsonb)
  from public, anon, authenticated, service_role;

create or replace function battle_private.contains_private_rating_key(p_value jsonb)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_key text;
  v_normalized_key text;
  v_child jsonb;
begin
  if pg_catalog.jsonb_typeof(p_value) = 'object' then
    for v_key, v_child in
      select key, value from pg_catalog.jsonb_each(p_value)
    loop
      -- Normalize snake_case, lowerCamelCase, kebab-case, and case-only variants
      -- to one comparison alphabet. Public engine payloads must never smuggle a
      -- placement-hidden score through an alternate JSON spelling.
      v_normalized_key := pg_catalog.regexp_replace(
        pg_catalog.lower(v_key),
        '[^a-z0-9]',
        '',
        'g'
      );
      if v_normalized_key = any (array[
        'rating','ratingbefore','ratingafter','highestrating','ratingachievedat',
        'scoredelta','expectedscore','opponentreferencescore','opponentrating',
        'referencescore','score','kfactor','mmr','elo'
      ]) then
        return true;
      end if;
      if battle_private.contains_private_rating_key(v_child) then
        return true;
      end if;
    end loop;
  elsif pg_catalog.jsonb_typeof(p_value) = 'array' then
    for v_child in
      select value from pg_catalog.jsonb_array_elements(p_value)
    loop
      if battle_private.contains_private_rating_key(v_child) then
        return true;
      end if;
    end loop;
  end if;

  return false;
end;
$$;

revoke all on function battle_private.contains_private_rating_key(jsonb)
  from public, anon, authenticated, service_role;

create or replace function battle_private.is_valid_snapshot_payload(p_snapshot jsonb)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_build jsonb;
  v_skills jsonb;
  v_equipment jsonb;
begin
  if pg_catalog.jsonb_typeof(p_snapshot) is distinct from 'object'
     or not (p_snapshot ?& array[
       'build','skills','equipment','snapshotVersion'
     ]::text[])
     or p_snapshot - array[
       'build','skills','equipment','snapshotVersion'
     ]::text[] <> '{}'::jsonb
     or battle_private.contains_forbidden_economy_key(p_snapshot) then
    return false;
  end if;

  v_build := p_snapshot -> 'build';
  v_skills := p_snapshot -> 'skills';
  v_equipment := p_snapshot -> 'equipment';

  if pg_catalog.jsonb_typeof(p_snapshot -> 'snapshotVersion') is distinct from 'number'
     or p_snapshot ->> 'snapshotVersion' !~ '^[0-9]+$'
     or (p_snapshot ->> 'snapshotVersion')::numeric not between 1 and 2147483647 then
    return false;
  end if;

  if pg_catalog.jsonb_typeof(v_build) is distinct from 'object'
     or not (v_build ?& array[
       'strength','constitution','dexterity','intelligence','wisdom','charisma'
     ]::text[])
     or v_build - array[
       'strength','constitution','dexterity','intelligence','wisdom','charisma'
     ]::text[] <> '{}'::jsonb
     or exists (
       select 1
         from pg_catalog.jsonb_each(v_build) as stat(key, value)
        where pg_catalog.jsonb_typeof(stat.value) is distinct from 'number'
           or stat.value::text !~ '^[0-9]+$'
           or (stat.value::text)::numeric > 1000000000
     )
     or (
       select pg_catalog.sum(stat.value::text::numeric)
         from pg_catalog.jsonb_each(v_build) as stat(key, value)
     ) < 1 then
    return false;
  end if;

  if pg_catalog.jsonb_typeof(v_skills) is distinct from 'array'
     or pg_catalog.jsonb_array_length(v_skills) not between 0 and 3
     or exists (
       select 1
         from pg_catalog.jsonb_array_elements(v_skills) as skill(value)
        where pg_catalog.jsonb_typeof(skill.value) is distinct from 'object'
           or not (skill.value ?& array[
             'skillId','displayName','kind','powerBasisPoints',
             'cooldownRounds','masteryLevel'
           ]::text[])
           or skill.value - array[
             'skillId','displayName','kind','powerBasisPoints',
             'cooldownRounds','masteryLevel'
           ]::text[] <> '{}'::jsonb
           or pg_catalog.jsonb_typeof(skill.value -> 'skillId') is distinct from 'string'
           or skill.value ->> 'skillId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$'
           or pg_catalog.jsonb_typeof(skill.value -> 'displayName') is distinct from 'string'
           or pg_catalog.char_length(pg_catalog.btrim(skill.value ->> 'displayName')) not between 1 and 96
           or (skill.value ->> 'displayName') ~ '[[:cntrl:]]'
           or pg_catalog.jsonb_typeof(skill.value -> 'kind') is distinct from 'string'
           or skill.value ->> 'kind' not in ('STRIKE','ARCANE','PIERCE','CONTROL','RECOVER')
           or pg_catalog.jsonb_typeof(skill.value -> 'powerBasisPoints') is distinct from 'number'
           or skill.value ->> 'powerBasisPoints' !~ '^[0-9]+$'
           or (skill.value ->> 'powerBasisPoints')::numeric not between 8000 and 16000
           or pg_catalog.jsonb_typeof(skill.value -> 'cooldownRounds') is distinct from 'number'
           or skill.value ->> 'cooldownRounds' !~ '^[0-9]+$'
           or (skill.value ->> 'cooldownRounds')::numeric not between 0 and 8
           or pg_catalog.jsonb_typeof(skill.value -> 'masteryLevel') is distinct from 'number'
           or skill.value ->> 'masteryLevel' !~ '^[0-9]+$'
           or (skill.value ->> 'masteryLevel')::numeric not between 0 and 100
     )
     or (
       select pg_catalog.count(*)
         from (
           select distinct skill.value ->> 'skillId'
             from pg_catalog.jsonb_array_elements(v_skills) as skill(value)
         ) as distinct_skills
     ) <> pg_catalog.jsonb_array_length(v_skills) then
    return false;
  end if;

  if pg_catalog.jsonb_typeof(v_equipment) is distinct from 'array'
     or pg_catalog.jsonb_array_length(v_equipment) <> 6
     or exists (
       select 1
         from pg_catalog.jsonb_array_elements(v_equipment) as item(value)
        where pg_catalog.jsonb_typeof(item.value) is distinct from 'object'
           or not (item.value ?& array[
             'itemId','displayName','slot','rarity','narrativeTag','verifiedPowerContribution'
           ]::text[])
           or item.value - array[
             'itemId','displayName','slot','rarity','narrativeTag','verifiedPowerContribution'
           ]::text[] <> '{}'::jsonb
           or pg_catalog.jsonb_typeof(item.value -> 'itemId') is distinct from 'string'
           or item.value ->> 'itemId' !~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,95}$'
           or pg_catalog.jsonb_typeof(item.value -> 'displayName') is distinct from 'string'
           or pg_catalog.char_length(pg_catalog.btrim(item.value ->> 'displayName')) not between 1 and 96
           or (item.value ->> 'displayName') ~ '[[:cntrl:]]'
           or pg_catalog.jsonb_typeof(item.value -> 'slot') is distinct from 'string'
           or item.value ->> 'slot' not in ('WEAPON','HEAD','BODY','HANDS','FEET','ACCESSORY')
           or pg_catalog.jsonb_typeof(item.value -> 'rarity') is distinct from 'string'
           or pg_catalog.char_length(item.value ->> 'rarity') not between 1 and 32
           or (item.value ->> 'rarity') ~ '[[:cntrl:]]'
           or pg_catalog.jsonb_typeof(item.value -> 'narrativeTag') is distinct from 'string'
           or pg_catalog.char_length(item.value ->> 'narrativeTag') > 64
           or (item.value ->> 'narrativeTag') ~ '[[:cntrl:]]'
           or pg_catalog.jsonb_typeof(item.value -> 'verifiedPowerContribution') is distinct from 'number'
           or item.value ->> 'verifiedPowerContribution' !~ '^[0-9]+$'
           or (item.value ->> 'verifiedPowerContribution')::numeric > 1000000000
     )
     or (
       select pg_catalog.count(*)
         from (
           select distinct item.value ->> 'slot'
             from pg_catalog.jsonb_array_elements(v_equipment) as item(value)
         ) as distinct_slots
     ) <> 6 then
    return false;
  end if;

  return true;
exception
  when others then
    return false;
end;
$$;

revoke all on function battle_private.is_valid_snapshot_payload(jsonb)
  from public, anon, authenticated, service_role;

create or replace function battle_private.growth_components(p_snapshot jsonb)
returns jsonb
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  -- V0.1 normalization constants. Raw verified build/equipment power is
  -- clamped before weighting so arbitrary input scale cannot crowd out the
  -- explicit representative-skill 20 percent component.
  v_verified_power_cap constant bigint := 100000;
  v_effective_power_scale constant bigint := 10;
  v_raw_verified_power numeric;
  v_clamped_verified_power bigint;
  v_verified_basis_points integer;
  v_representative_skill_id text;
  v_representative_mastery integer := 0;
  v_mastery_basis_points integer;
  v_growth_basis_points integer;
  v_effective_power bigint;
  v_resonance_budget integer;
begin
  if not battle_private.is_valid_snapshot_payload(p_snapshot) then
    raise exception 'invalid trusted projection payload' using errcode = '22023';
  end if;

  select pg_catalog.sum((p_snapshot -> 'build' ->> attribute_name)::numeric)
    into v_raw_verified_power
    from pg_catalog.unnest(array[
      'strength','constitution','dexterity','intelligence','wisdom','charisma'
    ]::text[]) as attributes(attribute_name);

  select v_raw_verified_power + coalesce(
           pg_catalog.sum((item.value ->> 'verifiedPowerContribution')::numeric),
           0::numeric
         )
    into v_raw_verified_power
    from pg_catalog.jsonb_array_elements(p_snapshot -> 'equipment') as item(value);

  if v_raw_verified_power < 1 or v_raw_verified_power > 12000000000::numeric then
    raise exception 'trusted verified power is outside supported bounds'
      using errcode = '22023';
  end if;

  v_clamped_verified_power := least(
    v_verified_power_cap,
    v_raw_verified_power::bigint
  );
  -- Round half up into an independent 0..10,000 component.
  v_verified_basis_points := pg_catalog.floor(
    (
      v_clamped_verified_power::numeric * 10000::numeric +
      v_verified_power_cap::numeric / 2::numeric
    ) / v_verified_power_cap::numeric
  )::integer;

  select skill.value ->> 'skillId',
         (skill.value ->> 'masteryLevel')::integer
    into v_representative_skill_id, v_representative_mastery
    from pg_catalog.jsonb_array_elements(p_snapshot -> 'skills') as skill(value)
   order by (skill.value ->> 'masteryLevel')::integer desc,
            (skill.value ->> 'skillId') collate pg_catalog."C"
   limit 1;

  if v_representative_skill_id is null then
    v_representative_mastery := 0;
  end if;
  v_mastery_basis_points := v_representative_mastery * 100;

  -- Both inputs are already 0..10,000, so these weights remain exactly 80/20
  -- regardless of raw power magnitude or how many skills were issued.
  v_growth_basis_points := pg_catalog.floor(
    (
      v_verified_basis_points::bigint * 8000::bigint +
      v_mastery_basis_points::bigint * 2000::bigint +
      5000::bigint
    )::numeric / 10000::numeric
  )::integer;
  v_growth_basis_points := greatest(0, least(10000, v_growth_basis_points));
  v_effective_power := greatest(
    1::bigint,
    v_growth_basis_points::bigint * v_effective_power_scale
  );
  -- Mirrors ProjectionBattleEngine.growthResonanceBudget with the fixed V0.1
  -- reference 10,000 and guarantees a displayed 100..108 range.
  v_resonance_budget := 100 + greatest(
    0,
    least(
      8,
      pg_catalog.floor(
        4.0::double precision * pg_catalog.ln(
          1.0::double precision +
          v_effective_power::double precision / 10000.0::double precision
        )
      )::integer
    )
  );

  return pg_catalog.jsonb_build_object(
    'rawVerifiedPower', v_raw_verified_power::bigint,
    'clampedVerifiedPower', v_clamped_verified_power,
    'verifiedPowerBasisPoints', v_verified_basis_points,
    'representativeSkillId', v_representative_skill_id,
    'representativeSkillMastery', v_representative_mastery,
    'masteryBasisPoints', v_mastery_basis_points,
    'growthIndexBasisPoints', v_growth_basis_points,
    'effectivePower', v_effective_power,
    'growthResonanceBudget', v_resonance_budget
  );
end;
$$;

revoke all on function battle_private.growth_components(jsonb)
  from public, anon, authenticated, service_role;

comment on function battle_private.growth_components(jsonb) is
  'Battle V0.1 growth: normalize build+equipped power to 0..10000 at cap 100000, normalize one representative mastery to 0..10000 (highest mastery, C-collated skillId tie-break), weight 80/20, scale to effectivePower 1..100000, then map through 100+floor(4*ln(1+power/10000)) capped at 108.';

create or replace function battle_private.compute_effective_power(p_snapshot jsonb)
returns bigint
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select (battle_private.growth_components(p_snapshot) ->> 'effectivePower')::bigint;
$$;

revoke all on function battle_private.compute_effective_power(jsonb)
  from public, anon, authenticated, service_role;

create or replace function battle_private.normalized_build_stats(p_build jsonb)
returns jsonb
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_keys text[] := array[
    'strength','constitution','dexterity','intelligence','wisdom','charisma'
  ];
  v_values numeric[] := array[]::numeric[];
  v_allocated integer[] := array[]::integer[];
  v_remainders numeric[] := array[]::numeric[];
  v_sum numeric := 0;
  v_numerator numeric;
  v_used integer := 0;
  v_remaining integer;
  v_index integer;
begin
  if pg_catalog.jsonb_typeof(p_build) is distinct from 'object'
     or not (p_build ?& v_keys)
     or p_build - v_keys <> '{}'::jsonb then
    return null;
  end if;

  for v_index in 1..6 loop
    if pg_catalog.jsonb_typeof(p_build -> v_keys[v_index]) is distinct from 'number'
       or p_build ->> v_keys[v_index] !~ '^[0-9]+$' then
      return null;
    end if;
    v_values := pg_catalog.array_append(
      v_values,
      (p_build ->> v_keys[v_index])::numeric
    );
    v_sum := v_sum + v_values[v_index];
  end loop;

  if v_sum <= 0 then
    return null;
  end if;

  for v_index in 1..6 loop
    v_numerator := v_values[v_index] * 10000::numeric;
    v_allocated := pg_catalog.array_append(
      v_allocated,
      pg_catalog.floor(v_numerator / v_sum)::integer
    );
    v_remainders := pg_catalog.array_append(
      v_remainders,
      v_numerator - pg_catalog.floor(v_numerator / v_sum) * v_sum
    );
    v_used := v_used + v_allocated[v_index];
  end loop;

  v_remaining := 10000 - v_used;
  for v_index in
    select remainder.ordinality::integer
      from pg_catalog.unnest(v_remainders) with ordinality
        as remainder(value, ordinality)
     order by remainder.value desc, remainder.ordinality
  loop
    exit when v_remaining <= 0;
    v_allocated[v_index] := v_allocated[v_index] + 1;
    v_remaining := v_remaining - 1;
  end loop;

  if v_remaining <> 0 then
    return null;
  end if;

  return pg_catalog.jsonb_build_object(
    'strength', v_allocated[1],
    'constitution', v_allocated[2],
    'dexterity', v_allocated[3],
    'intelligence', v_allocated[4],
    'wisdom', v_allocated[5],
    'charisma', v_allocated[6]
  );
exception
  when others then
    return null;
end;
$$;

revoke all on function battle_private.normalized_build_stats(jsonb)
  from public, anon, authenticated, service_role;

create or replace function battle_private.expected_normalized_projection(
  p_snapshot_id uuid,
  p_display_name text,
  p_hero_class text,
  p_level bigint,
  p_effective_power bigint,
  p_snapshot_payload jsonb,
  p_guidance text,
  p_traits_snapshot jsonb,
  p_growth_reference_power bigint
)
returns jsonb
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_stats jsonb;
  v_equipment jsonb;
  v_trait_ids jsonb;
  v_resonance integer;
begin
  if p_guidance not in ('ASSAULT','BALANCED','GUARD')
     or p_effective_power < 0
     or p_growth_reference_power <= 0
     or not battle_private.is_valid_snapshot_payload(p_snapshot_payload)
     or pg_catalog.jsonb_typeof(p_traits_snapshot) is distinct from 'array'
     or pg_catalog.jsonb_array_length(p_traits_snapshot) > 5
     or exists (
       select 1
         from pg_catalog.jsonb_array_elements(p_traits_snapshot) as trait(value)
        where pg_catalog.jsonb_typeof(trait.value) is distinct from 'object'
           or pg_catalog.jsonb_typeof(trait.value -> 'trait_code') is distinct from 'string'
           or trait.value ->> 'trait_code' !~ '^TRAIT_(0[0-9][1-9]|0[1-9][0-9]|100)$'
     ) then
    return null;
  end if;

  v_stats := battle_private.normalized_build_stats(p_snapshot_payload -> 'build');
  if v_stats is null then
    return null;
  end if;

  select pg_catalog.jsonb_agg(
           item.value - 'verifiedPowerContribution' order by item.ordinality
         )
    into v_equipment
    from pg_catalog.jsonb_array_elements(p_snapshot_payload -> 'equipment')
      with ordinality as item(value, ordinality);

  select coalesce(
           pg_catalog.jsonb_agg(trait.value ->> 'trait_code' order by trait.ordinality),
           '[]'::jsonb
         )
    into v_trait_ids
    from pg_catalog.jsonb_array_elements(p_traits_snapshot)
      with ordinality as trait(value, ordinality);

  -- Mirrors Kotlin ln1p(power / reference), floor, and the 100..108 clamp.
  v_resonance := 100 + greatest(
    0,
    least(
      8,
      pg_catalog.floor(
        4.0::double precision * pg_catalog.ln(
          1.0::double precision +
          p_effective_power::double precision / p_growth_reference_power::double precision
        )
      )::integer
    )
  );

  return pg_catalog.jsonb_build_object(
    'projectionId', p_snapshot_id,
    'displayName', p_display_name,
    'heroClass', p_hero_class,
    'level', p_level,
    'growthResonanceBudget', v_resonance,
    'stats', v_stats,
    'guidance', p_guidance,
    'skills', p_snapshot_payload -> 'skills',
    'equipment', v_equipment,
    'activeTraitIds', v_trait_ids,
    'snapshotVersion', p_snapshot_payload -> 'snapshotVersion'
  );
exception
  when others then
    return null;
end;
$$;

revoke all on function battle_private.expected_normalized_projection(
  uuid, text, text, bigint, bigint, jsonb, text, jsonb, bigint
) from public, anon, authenticated, service_role;

-- PostgreSQL bigint arithmetic raises on overflow, while Kotlin Long wraps in
-- two's-complement. These helpers make that wrap, unsigned shifts, and rotates
-- explicit so the database can replay ProjectionBattleEngine exactly.
create or replace function battle_private.i64_wrap(p_value numeric)
returns bigint
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  with normalized as (
    select pg_catalog.mod(
             pg_catalog.mod(p_value, 18446744073709551616::numeric)
               + 18446744073709551616::numeric,
             18446744073709551616::numeric
           ) as value
  )
  select case
    when value >= 9223372036854775808::numeric
      then (value - 18446744073709551616::numeric)::bigint
    else value::bigint
  end
    from normalized;
$$;

revoke all on function battle_private.i64_wrap(numeric)
  from public, anon, authenticated, service_role;

create or replace function battle_private.i64_unsigned_shift_right(
  p_value bigint,
  p_bits integer
)
returns bigint
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_unsigned numeric;
begin
  if p_bits not between 0 and 63 then
    raise exception 'i64 unsigned shift requires 0..63 bits' using errcode = '22023';
  end if;
  if p_bits = 0 then return p_value; end if;
  v_unsigned := case
    when p_value < 0 then p_value::numeric + 18446744073709551616::numeric
    else p_value::numeric
  end;
  -- numeric division chooses a display scale and may round before floor(),
  -- e.g. Long.MAX_VALUE / 2^31 became 4294967296. div() computes the exact
  -- integer quotient required by Kotlin Long.ushr.
  return pg_catalog.div(
    v_unsigned,
    pg_catalog.power(2::numeric, p_bits)
  )::bigint;
end;
$$;

revoke all on function battle_private.i64_unsigned_shift_right(bigint, integer)
  from public, anon, authenticated, service_role;

create or replace function battle_private.i64_rotate_left(
  p_value bigint,
  p_bits integer
)
returns bigint
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_bits integer := pg_catalog.mod(p_bits, 64);
  v_unsigned numeric;
  v_rotated numeric;
begin
  if v_bits < 0 then v_bits := v_bits + 64; end if;
  if v_bits = 0 then return p_value; end if;
  v_unsigned := case
    when p_value < 0 then p_value::numeric + 18446744073709551616::numeric
    else p_value::numeric
  end;
  v_rotated := pg_catalog.mod(
    v_unsigned * pg_catalog.power(2::numeric, v_bits),
    18446744073709551616::numeric
  ) + pg_catalog.div(
    v_unsigned,
    pg_catalog.power(2::numeric, 64 - v_bits)
  );
  return battle_private.i64_wrap(v_rotated);
end;
$$;

revoke all on function battle_private.i64_rotate_left(bigint, integer)
  from public, anon, authenticated, service_role;

create or replace function battle_private.engine_stable_hash(p_value text)
returns bigint
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_bytes bytea := pg_catalog.convert_to(p_value, 'UTF8');
  v_index integer;
  v_hash bigint := -3750763034362895579;
begin
  -- Engine identity inputs are canonical UUID strings, hence ASCII. Rejecting
  -- non-ASCII avoids pretending PostgreSQL Unicode code points are Kotlin
  -- UTF-16 Char values for a future, different contract.
  if pg_catalog.octet_length(p_value) <> pg_catalog.char_length(p_value) then
    raise exception 'engine hash input must be ASCII' using errcode = '22023';
  end if;
  if pg_catalog.octet_length(v_bytes) = 0 then return v_hash; end if;
  for v_index in 0..pg_catalog.octet_length(v_bytes) - 1 loop
    v_hash := battle_private.i64_wrap(
      (v_hash # pg_catalog.get_byte(v_bytes, v_index))::numeric
        * 1099511628211::numeric
    );
  end loop;
  return v_hash;
end;
$$;

revoke all on function battle_private.engine_stable_hash(text)
  from public, anon, authenticated, service_role;

create or replace function battle_private.engine_mix64(p_input bigint)
returns bigint
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_value bigint;
begin
  v_value := battle_private.i64_wrap(
    p_input::numeric - 7046029254386353131::numeric
  );
  v_value := battle_private.i64_wrap(
    (v_value # battle_private.i64_unsigned_shift_right(v_value, 30))::numeric
      * -4658895280553007687::numeric
  );
  v_value := battle_private.i64_wrap(
    (v_value # battle_private.i64_unsigned_shift_right(v_value, 27))::numeric
      * -7723592293110705685::numeric
  );
  return v_value # battle_private.i64_unsigned_shift_right(v_value, 31);
end;
$$;

revoke all on function battle_private.engine_mix64(bigint)
  from public, anon, authenticated, service_role;

create or replace function battle_private.engine_roll(
  p_seed bigint,
  p_round integer,
  p_side text,
  p_lane integer,
  p_bound integer
)
returns integer
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_side_salt bigint;
  v_input bigint;
  v_mixed bigint;
begin
  if p_side = 'USER' then
    v_side_salt := 2862933555777941757;
  elsif p_side = 'OPPONENT' then
    v_side_salt := 3037000493;
  else
    raise exception 'invalid battle side' using errcode = '22023';
  end if;
  if p_round < 1 or p_lane < 0 or p_bound < 1 then
    raise exception 'invalid deterministic roll input' using errcode = '22023';
  end if;
  if p_bound <= 1 then return 0; end if;
  v_input := battle_private.i64_wrap(
    p_seed::numeric
      + p_round::numeric * 6364136223846793005::numeric
      + v_side_salt::numeric
      + p_lane::numeric * 1442695040888963407::numeric
  );
  v_mixed := battle_private.engine_mix64(v_input);
  return pg_catalog.mod(
    battle_private.i64_unsigned_shift_right(v_mixed, 1),
    p_bound::bigint
  )::integer;
end;
$$;

revoke all on function battle_private.engine_roll(bigint, integer, text, integer, integer)
  from public, anon, authenticated, service_role;

create or replace function battle_private.engine_choose_action(
  p_projection jsonb,
  p_hp integer,
  p_round integer,
  p_side text,
  p_seed bigint,
  p_last_skill_rounds jsonb
)
returns jsonb
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_available jsonb;
  v_attack_weight integer;
  v_skill_weight integer;
  v_guard_weight integer;
  v_shift integer;
  v_roll integer;
  v_skill_index integer;
  v_skill jsonb;
begin
  select coalesce(pg_catalog.jsonb_agg(skill.value order by skill.ordinality), '[]'::jsonb)
    into v_available
    from pg_catalog.jsonb_array_elements(p_projection -> 'skills') with ordinality
      as skill(value, ordinality)
   where case
     when p_last_skill_rounds ? (skill.value ->> 'skillId') then
       p_round - (p_last_skill_rounds ->> (skill.value ->> 'skillId'))::integer
         > (skill.value ->> 'cooldownRounds')::integer
     else true
   end;

  case p_projection ->> 'guidance'
    when 'ASSAULT' then
      v_attack_weight := 4000; v_skill_weight := 5000; v_guard_weight := 1000;
    when 'BALANCED' then
      v_attack_weight := 4000; v_skill_weight := 3500; v_guard_weight := 2500;
    when 'GUARD' then
      v_attack_weight := 3000; v_skill_weight := 2500; v_guard_weight := 4500;
    else
      raise exception 'invalid projection guidance' using errcode = '22023';
  end case;

  if p_hp <= 333 then
    v_shift := least(1000, v_attack_weight);
    v_attack_weight := v_attack_weight - v_shift;
    v_guard_weight := v_guard_weight + v_shift;
  end if;
  if pg_catalog.jsonb_array_length(v_available) = 0 then
    v_attack_weight := v_attack_weight + v_skill_weight;
    v_skill_weight := 0;
  end if;

  v_roll := battle_private.engine_roll(p_seed, p_round, p_side, 0, 10000);
  if v_roll < v_attack_weight then
    return pg_catalog.jsonb_build_object('kind', 'BASIC_ATTACK', 'skill', null);
  elsif v_roll < v_attack_weight + v_skill_weight then
    v_skill_index := battle_private.engine_roll(
      p_seed, p_round, p_side, 1, pg_catalog.jsonb_array_length(v_available)
    );
    v_skill := v_available -> v_skill_index;
    return pg_catalog.jsonb_build_object('kind', 'SKILL', 'skill', v_skill);
  end if;
  return pg_catalog.jsonb_build_object('kind', 'GUARD', 'skill', null);
end;
$$;

revoke all on function battle_private.engine_choose_action(
  jsonb, integer, integer, text, bigint, jsonb
) from public, anon, authenticated, service_role;

create or replace function battle_private.engine_resolve_action(
  p_actor_side text,
  p_actor jsonb,
  p_defender jsonb,
  p_plan jsonb,
  p_defender_plan jsonb,
  p_round integer,
  p_seed bigint
)
returns jsonb
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_skill jsonb := p_plan -> 'skill';
  v_skill_kind text;
  v_actor_class text := p_actor ->> 'heroClass';
  v_primary bigint;
  v_secondary bigint;
  v_focus bigint;
  v_mastery integer;
  v_offense bigint;
  v_defense bigint;
  v_damage bigint;
  v_healing bigint;
  v_coefficient bigint;
  v_critical_chance integer;
  v_critical boolean;
  v_resonance_bp bigint;
begin
  if p_plan ->> 'kind' = 'GUARD' then
    return pg_catalog.jsonb_build_object(
      'actor', p_actor_side, 'kind', 'GUARD', 'skillId', '',
      'damage', 0, 'healing', 0, 'critical', false
    );
  end if;

  if pg_catalog.jsonb_typeof(v_skill) = 'null' then
    v_skill := null;
  end if;
  v_skill_kind := coalesce(v_skill ->> 'kind', 'STRIKE');
  v_mastery := coalesce((v_skill ->> 'masteryLevel')::integer, 0);

  if v_skill_kind = 'RECOVER' then
    v_focus := (p_actor #>> '{stats,wisdom}')::bigint
      + (p_actor #>> '{stats,charisma}')::bigint / 2;
    v_healing := (
      60 + v_focus / 35
    ) * (
      (v_skill ->> 'powerBasisPoints')::bigint + least(100, greatest(0, v_mastery)) * 5
    ) / 10000;
    return pg_catalog.jsonb_build_object(
      'actor', p_actor_side, 'kind', 'SKILL',
      'skillId', v_skill ->> 'skillId', 'damage', 0,
      'healing', least(220::bigint, greatest(1::bigint, v_healing)),
      'critical', false
    );
  end if;

  v_primary := case v_actor_class
    when 'WARRIOR' then (p_actor #>> '{stats,strength}')::bigint
    when 'PALADIN' then (p_actor #>> '{stats,strength}')::bigint
    when 'ROGUE' then (p_actor #>> '{stats,dexterity}')::bigint
    when 'RANGER' then (p_actor #>> '{stats,dexterity}')::bigint
    when 'MAGE' then (p_actor #>> '{stats,intelligence}')::bigint
    when 'CLERIC' then (p_actor #>> '{stats,wisdom}')::bigint
  end;
  v_secondary := case v_actor_class
    when 'WARRIOR' then (p_actor #>> '{stats,constitution}')::bigint
    when 'ROGUE' then (p_actor #>> '{stats,strength}')::bigint
    when 'RANGER' then (p_actor #>> '{stats,wisdom}')::bigint
    when 'MAGE' then (p_actor #>> '{stats,wisdom}')::bigint
    when 'CLERIC' then (p_actor #>> '{stats,charisma}')::bigint
    when 'PALADIN' then (p_actor #>> '{stats,charisma}')::bigint
  end;

  if v_skill_kind in ('ARCANE','CONTROL') then
    v_offense := (p_actor #>> '{stats,intelligence}')::bigint * 2
      + (p_actor #>> '{stats,wisdom}')::bigint
      + (p_actor #>> '{stats,charisma}')::bigint / 2;
    v_defense := (p_defender #>> '{stats,wisdom}')::bigint * 2
      + (p_defender #>> '{stats,intelligence}')::bigint
      + (p_defender #>> '{stats,charisma}')::bigint / 2;
  elsif v_skill_kind = 'PIERCE' then
    v_offense := (p_actor #>> '{stats,dexterity}')::bigint * 2
      + v_primary + (p_actor #>> '{stats,strength}')::bigint / 2;
    v_defense := (
      (p_defender #>> '{stats,constitution}')::bigint * 2
        + (p_defender #>> '{stats,wisdom}')::bigint
        + (p_defender #>> '{stats,strength}')::bigint / 2
    ) / 2;
  else
    v_offense := v_primary * 2 + v_secondary
      + (p_actor #>> '{stats,dexterity}')::bigint / 2;
    v_defense := (p_defender #>> '{stats,constitution}')::bigint * 2
      + (p_defender #>> '{stats,wisdom}')::bigint
      + (p_defender #>> '{stats,strength}')::bigint / 2;
  end if;

  v_damage := greatest(20::bigint, 60 + v_offense / 35 - v_defense / 70);
  v_coefficient := case
    when v_skill is null then 10000
    else (v_skill ->> 'powerBasisPoints')::bigint
      + least(100, greatest(0, v_mastery)) * 5
  end;
  v_damage := v_damage * v_coefficient / 10000;
  if v_skill_kind = 'CONTROL' then
    v_damage := v_damage * 8500 / 10000;
  end if;

  v_critical_chance := least(
    2500,
    greatest(500, 500 + (p_actor #>> '{stats,dexterity}')::integer / 4)
  );
  v_critical := battle_private.engine_roll(
    p_seed, p_round, p_actor_side, 2, 10000
  ) < v_critical_chance;
  if v_critical then v_damage := v_damage * 13000 / 10000; end if;
  if p_defender_plan ->> 'kind' = 'GUARD' then
    v_damage := v_damage * 4500 / 10000;
  end if;
  v_resonance_bp := 10000
    + ((p_actor ->> 'growthResonanceBudget')::bigint - 100) * 90;
  v_damage := v_damage * v_resonance_bp / 10000;
  v_damage := least(350::bigint, greatest(20::bigint, v_damage));

  return pg_catalog.jsonb_build_object(
    'actor', p_actor_side,
    'kind', p_plan ->> 'kind',
    'skillId', coalesce(v_skill ->> 'skillId', ''),
    'damage', v_damage,
    'healing', 0,
    'critical', v_critical
  );
end;
$$;

revoke all on function battle_private.engine_resolve_action(
  text, jsonb, jsonb, jsonb, jsonb, integer, bigint
) from public, anon, authenticated, service_role;

create or replace function battle_private.simulate_engine_result_payload(
  p_battle_id uuid,
  p_engine_seed bigint,
  p_rules_version integer,
  p_max_rounds integer,
  p_user jsonb,
  p_opponent jsonb
)
returns jsonb
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
declare
  v_base_seed bigint;
  v_round_limit integer;
  v_round integer;
  v_user_hp integer := 1000;
  v_opponent_hp integer := 1000;
  v_next_user_hp integer;
  v_next_opponent_hp integer;
  v_user_cooldowns jsonb := '{}'::jsonb;
  v_opponent_cooldowns jsonb := '{}'::jsonb;
  v_user_plan jsonb;
  v_opponent_plan jsonb;
  v_user_action jsonb;
  v_opponent_action jsonb;
  v_user_skill jsonb;
  v_opponent_skill jsonb;
  v_rounds jsonb := '[]'::jsonb;
  v_user_damage bigint := 0;
  v_opponent_damage bigint := 0;
  v_outcome text;
begin
  if p_rules_version <> 1
     or p_max_rounds not between 1 and 8
     or pg_catalog.jsonb_typeof(p_user) is distinct from 'object'
     or pg_catalog.jsonb_typeof(p_opponent) is distinct from 'object' then
    return null;
  end if;

  v_base_seed := p_engine_seed
    # battle_private.engine_stable_hash(p_battle_id::text)
    # battle_private.i64_rotate_left(
        battle_private.engine_stable_hash(p_user ->> 'projectionId'), 17
      )
    # battle_private.i64_rotate_left(
        battle_private.engine_stable_hash(p_opponent ->> 'projectionId'), 41
      )
    # p_rules_version::bigint;
  v_round_limit := least(8, greatest(1, p_max_rounds));

  for v_round in 1..v_round_limit loop
    v_user_plan := battle_private.engine_choose_action(
      p_user, v_user_hp, v_round, 'USER', v_base_seed, v_user_cooldowns
    );
    v_opponent_plan := battle_private.engine_choose_action(
      p_opponent, v_opponent_hp, v_round, 'OPPONENT', v_base_seed, v_opponent_cooldowns
    );
    v_user_skill := v_user_plan -> 'skill';
    v_opponent_skill := v_opponent_plan -> 'skill';
    if pg_catalog.jsonb_typeof(v_user_skill) = 'object' then
      v_user_cooldowns := pg_catalog.jsonb_set(
        v_user_cooldowns,
        array[v_user_skill ->> 'skillId'],
        pg_catalog.to_jsonb(v_round),
        true
      );
    end if;
    if pg_catalog.jsonb_typeof(v_opponent_skill) = 'object' then
      v_opponent_cooldowns := pg_catalog.jsonb_set(
        v_opponent_cooldowns,
        array[v_opponent_skill ->> 'skillId'],
        pg_catalog.to_jsonb(v_round),
        true
      );
    end if;

    v_user_action := battle_private.engine_resolve_action(
      'USER', p_user, p_opponent, v_user_plan, v_opponent_plan,
      v_round, v_base_seed
    );
    v_opponent_action := battle_private.engine_resolve_action(
      'OPPONENT', p_opponent, p_user, v_opponent_plan, v_user_plan,
      v_round, v_base_seed
    );
    v_next_user_hp := least(
      1000,
      greatest(
        0,
        v_user_hp - (v_opponent_action ->> 'damage')::integer
          + (v_user_action ->> 'healing')::integer
      )
    );
    v_next_opponent_hp := least(
      1000,
      greatest(
        0,
        v_opponent_hp - (v_user_action ->> 'damage')::integer
          + (v_opponent_action ->> 'healing')::integer
      )
    );
    v_rounds := v_rounds || pg_catalog.jsonb_build_array(
      pg_catalog.jsonb_build_object(
        'number', v_round,
        'userHpBefore', v_user_hp,
        'opponentHpBefore', v_opponent_hp,
        'userAction', v_user_action,
        'opponentAction', v_opponent_action,
        'userHpAfter', v_next_user_hp,
        'opponentHpAfter', v_next_opponent_hp
      )
    );
    v_user_damage := v_user_damage + (v_user_action ->> 'damage')::bigint;
    v_opponent_damage := v_opponent_damage + (v_opponent_action ->> 'damage')::bigint;
    v_user_hp := v_next_user_hp;
    v_opponent_hp := v_next_opponent_hp;
    exit when v_user_hp = 0 or v_opponent_hp = 0;
  end loop;

  v_outcome := case
    when v_user_hp > v_opponent_hp then 'USER_WIN'
    when v_user_hp < v_opponent_hp then 'USER_LOSS'
    when v_user_damage > v_opponent_damage then 'USER_WIN'
    when v_user_damage < v_opponent_damage then 'USER_LOSS'
    else 'DRAW'
  end;
  return pg_catalog.jsonb_build_object(
    'battleId', p_battle_id,
    'serverSeed', p_engine_seed,
    'rulesVersion', p_rules_version,
    'user', p_user,
    'opponent', p_opponent,
    'rounds', v_rounds,
    'outcome', v_outcome,
    'rewardPolicy', 'RECORD_ONLY'
  );
exception
  when others then
    return null;
end;
$$;

revoke all on function battle_private.simulate_engine_result_payload(
  uuid, bigint, integer, integer, jsonb, jsonb
) from public, anon, authenticated, service_role;

create or replace function battle_private.is_valid_engine_result_payload(
  p_result jsonb,
  p_battle_id uuid,
  p_engine_seed bigint,
  p_rules_version integer,
  p_outcome text,
  p_max_rounds integer,
  p_expected_user jsonb,
  p_expected_opponent jsonb
)
returns boolean
language plpgsql
immutable
security invoker
set search_path = ''
as $$
declare
  v_expected jsonb;
begin
  if p_result is null
     or p_battle_id is null
     or p_engine_seed is null
     or p_rules_version is distinct from 1
     or p_outcome not in ('USER_WIN','DRAW','USER_LOSS')
     or p_max_rounds not between 1 and 8
     or p_expected_user is null
     or p_expected_opponent is null then
    return false;
  end if;
  v_expected := battle_private.simulate_engine_result_payload(
    p_battle_id,
    p_engine_seed,
    p_rules_version,
    p_max_rounds,
    p_expected_user,
    p_expected_opponent
  );
  -- jsonb equality is structural and exact: no submitted action, damage,
  -- critical flag, cooldown use, HP transition, outcome, or extra key can
  -- influence settlement unless it is the canonical replay output.
  return v_expected is not null
     and p_result = v_expected
     and p_outcome = v_expected ->> 'outcome'
     and not battle_private.contains_forbidden_economy_key(p_result)
     and not battle_private.contains_private_rating_key(p_result);
exception
  when others then
    return false;
end;
$$;

revoke all on function battle_private.is_valid_engine_result_payload(
  jsonb, uuid, bigint, integer, text, integer, jsonb, jsonb
) from public, anon, authenticated, service_role;

create or replace function battle_private.is_valid_engine_result(
  p_result jsonb,
  p_battle_id uuid,
  p_outcome text
)
returns boolean
language plpgsql
stable
security invoker
set search_path = ''
as $$
declare
  v_match battle_private.matches%rowtype;
  v_entry public.battle_entries%rowtype;
  v_season public.battle_seasons%rowtype;
  v_entrant battle_private.projection_snapshots%rowtype;
  v_opponent battle_private.projection_snapshots%rowtype;
  v_expected_user jsonb;
  v_expected_opponent jsonb;
begin
  if p_result is null or p_battle_id is null or p_outcome is null then
    return false;
  end if;

  select * into v_match
    from battle_private.matches
   where battle_id = p_battle_id;
  if not found then return false; end if;

  select * into v_entry
    from public.battle_entries
   where entry_id = v_match.entry_id;
  if not found then return false; end if;

  select * into v_season
    from public.battle_seasons
   where season_id = v_match.season_id;
  if not found then return false; end if;

  select * into v_entrant
    from battle_private.projection_snapshots
   where snapshot_id = v_match.entrant_snapshot_id;
  if not found then return false; end if;

  select * into v_opponent
    from battle_private.projection_snapshots
   where snapshot_id = v_match.opponent_snapshot_id;
  if not found then return false; end if;

  if v_match.status not in ('MATCHED','SETTLED')
     or v_entry.season_id <> v_match.season_id
     or v_entry.battle_id <> v_match.battle_id
     or v_entry.snapshot_id <> v_match.entrant_snapshot_id
     or v_entry.user_id <> v_match.entrant_user_id
     or v_entry.character_id <> v_match.entrant_character_id
     or v_season.rules_version <> v_match.rules_version
     or v_entrant.user_id <> v_match.entrant_user_id
     or v_entrant.character_id <> v_match.entrant_character_id
     or v_entrant.rules_version <> v_match.rules_version
     or v_opponent.user_id <> v_match.opponent_user_id
     or v_opponent.character_id <> v_match.opponent_character_id
     or v_opponent.rules_version <> v_match.rules_version then
    return false;
  end if;

  v_expected_user := battle_private.expected_normalized_projection(
    v_entrant.snapshot_id,
    v_entrant.display_name,
    v_entrant.hero_class,
    v_entrant.level,
    v_entrant.effective_power,
    v_entrant.snapshot_payload,
    v_entry.directive,
    v_match.entrant_traits_snapshot,
    v_season.growth_reference_power
  );
  v_expected_opponent := battle_private.expected_normalized_projection(
    v_opponent.snapshot_id,
    v_opponent.display_name,
    v_opponent.hero_class,
    v_opponent.level,
    v_opponent.effective_power,
    v_opponent.snapshot_payload,
    v_match.opponent_guidance,
    v_match.opponent_traits_snapshot,
    v_season.growth_reference_power
  );

  return battle_private.is_valid_engine_result_payload(
    p_result,
    v_match.battle_id,
    v_match.engine_seed,
    v_match.rules_version,
    p_outcome,
    v_season.max_rounds,
    v_expected_user,
    v_expected_opponent
  );
exception
  when others then
    return false;
end;
$$;

revoke all on function battle_private.is_valid_engine_result(jsonb, uuid, text)
  from public, anon, authenticated, service_role;

create or replace function battle_private.public_engine_result(
  p_result jsonb,
  p_battle_id uuid,
  p_outcome text
)
returns jsonb
language sql
stable
security invoker
set search_path = ''
as $$
  select case
    when battle_private.is_valid_engine_result(p_result, p_battle_id, p_outcome)
      then p_result
    else null
  end;
$$;

revoke all on function battle_private.public_engine_result(jsonb, uuid, text)
  from public, anon, authenticated, service_role;

create or replace function battle_private.is_valid_narrative_shape(
  p_narrative jsonb,
  p_battle_id uuid
)
returns boolean
language plpgsql
immutable
strict
security invoker
set search_path = ''
as $$
begin
  if pg_catalog.jsonb_typeof(p_narrative) is distinct from 'object'
     or not (p_narrative ?& array['encounter_id','scenes']::text[])
     or p_narrative - array['encounter_id','scenes']::text[] <> '{}'::jsonb
     or pg_catalog.jsonb_typeof(p_narrative -> 'encounter_id') is distinct from 'string'
     or p_narrative ->> 'encounter_id' is distinct from p_battle_id::text
     or pg_catalog.jsonb_typeof(p_narrative -> 'scenes') is distinct from 'array'
     or battle_private.contains_forbidden_economy_key(p_narrative)
     or battle_private.contains_private_rating_key(p_narrative) then
    return false;
  end if;

  if pg_catalog.jsonb_array_length(p_narrative -> 'scenes') not between 3 and 5 then
    return false;
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_narrative -> 'scenes') as scene(value)
     where pg_catalog.jsonb_typeof(scene.value) is distinct from 'object'
        or not (scene.value ?& array[
          'phase_id','title_ko','segments','dialogue_ko','effect_key'
        ]::text[])
        or scene.value - array[
          'phase_id','title_ko','segments','dialogue_ko','effect_key'
        ]::text[] <> '{}'::jsonb
        or pg_catalog.jsonb_typeof(scene.value -> 'phase_id') is distinct from 'string'
        or scene.value ->> 'phase_id' !~ '^P[1-5]$'
        or pg_catalog.jsonb_typeof(scene.value -> 'title_ko') is distinct from 'string'
        or pg_catalog.char_length(scene.value ->> 'title_ko') not between 2 and 28
        or (scene.value ->> 'title_ko') ~ '[[:cntrl:]]'
        or case
          when pg_catalog.jsonb_typeof(scene.value -> 'segments') = 'array'
            then pg_catalog.jsonb_array_length(scene.value -> 'segments') not between 1 and 12
          else true
        end
        or case pg_catalog.jsonb_typeof(scene.value -> 'dialogue_ko')
          when 'null' then false
          when 'string' then
            pg_catalog.char_length(scene.value ->> 'dialogue_ko') > 60
            or (scene.value ->> 'dialogue_ko') ~ '[[:cntrl:]]'
          else true
        end
        or pg_catalog.jsonb_typeof(scene.value -> 'effect_key') is distinct from 'string'
        or scene.value ->> 'effect_key' not in (
          'CLASH','SLASH','HEAVY_HIT','GUARD','EVADE','COUNTER','MAGIC','FINISH'
        )
  ) then
    return false;
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_narrative -> 'scenes') with ordinality
        as scene(value, ordinality)
     where scene.value ->> 'phase_id' is distinct from
           'P' || scene.ordinality::text
  )
     or (
       select pg_catalog.count(*)
         from pg_catalog.jsonb_array_elements(p_narrative -> 'scenes') as scene(value)
        where pg_catalog.jsonb_typeof(scene.value -> 'dialogue_ko') = 'string'
     ) > 2 then
    return false;
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_narrative -> 'scenes') as scene(value)
      cross join lateral pg_catalog.jsonb_array_elements(scene.value -> 'segments') as segment(value)
     where case segment.value ->> 'type'
       when 'TEXT' then not (
         pg_catalog.jsonb_typeof(segment.value) = 'object'
         and segment.value ?& array['type','text']::text[]
         and segment.value - array['type','text']::text[] = '{}'::jsonb
         and pg_catalog.jsonb_typeof(segment.value -> 'text') = 'string'
         and pg_catalog.char_length(segment.value ->> 'text') between 1 and 180
       )
       when 'ENTITY_REF' then not (
         pg_catalog.jsonb_typeof(segment.value) = 'object'
         and segment.value ?& array['type','ref','particle']::text[]
         and segment.value - array['type','ref','particle']::text[] = '{}'::jsonb
         and pg_catalog.jsonb_typeof(segment.value -> 'ref') = 'string'
         and segment.value ->> 'ref' ~
             '^[AB]_(SKILL_[0-9]{2}|ITEM_(WEAPON|HEAD|BODY|HANDS|FEET|ACCESSORY)|TRAIT_[0-9]{2})$'
         and pg_catalog.jsonb_typeof(segment.value -> 'particle') = 'string'
         and segment.value ->> 'particle' in (
           'NONE','SUBJECT','TOPIC','OBJECT','WITH','DIRECTION'
         )
       )
       else true
     end
  ) then
    return false;
  end if;

  return true;
exception
  when others then
    return false;
end;
$$;

revoke all on function battle_private.is_valid_narrative_shape(jsonb, uuid)
  from public, anon, authenticated, service_role;

-- Stable display references are derived from the exact projections and trait
-- snapshots frozen for this match. Ref ordinals are never accepted merely
-- because they match a string pattern.
create or replace function battle_private.narrative_allowed_refs(p_battle_id uuid)
returns table(ref text, owner text, ref_kind text, source_id text)
language sql
stable
security definer
set search_path = ''
as $$
  with issued as (
    select matches.battle_id,
           entrant.snapshot_payload as entrant_payload,
           opponent.snapshot_payload as opponent_payload,
           matches.entrant_traits_snapshot,
           matches.opponent_traits_snapshot
      from battle_private.matches as matches
      join battle_private.projection_snapshots as entrant
        on entrant.snapshot_id = matches.entrant_snapshot_id
      join battle_private.projection_snapshots as opponent
        on opponent.snapshot_id = matches.opponent_snapshot_id
     where matches.battle_id = p_battle_id
       and matches.status = 'SETTLED'
  ),
  refs as (
    select 'A_SKILL_' || pg_catalog.lpad(skill.ordinality::text, 2, '0') as ref,
           'A'::text as owner,
           'SKILL'::text as ref_kind,
           skill.value ->> 'skillId' as source_id
      from issued
      cross join lateral pg_catalog.jsonb_array_elements(issued.entrant_payload -> 'skills')
        with ordinality as skill(value, ordinality)
    union all
    select 'B_SKILL_' || pg_catalog.lpad(skill.ordinality::text, 2, '0'),
           'B',
           'SKILL',
           skill.value ->> 'skillId'
      from issued
      cross join lateral pg_catalog.jsonb_array_elements(issued.opponent_payload -> 'skills')
        with ordinality as skill(value, ordinality)
    union all
    select 'A_ITEM_' || (item.value ->> 'slot'),
           'A',
           'ITEM',
           item.value ->> 'itemId'
      from issued
      cross join lateral pg_catalog.jsonb_array_elements(issued.entrant_payload -> 'equipment')
        as item(value)
    union all
    select 'B_ITEM_' || (item.value ->> 'slot'),
           'B',
           'ITEM',
           item.value ->> 'itemId'
      from issued
      cross join lateral pg_catalog.jsonb_array_elements(issued.opponent_payload -> 'equipment')
        as item(value)
    union all
    select 'A_TRAIT_' || pg_catalog.lpad(trait.ordinality::text, 2, '0'),
           'A',
           'TRAIT',
           trait.value ->> 'trait_code'
      from issued
      cross join lateral pg_catalog.jsonb_array_elements(issued.entrant_traits_snapshot)
        with ordinality as trait(value, ordinality)
    union all
    select 'B_TRAIT_' || pg_catalog.lpad(trait.ordinality::text, 2, '0'),
           'B',
           'TRAIT',
           trait.value ->> 'trait_code'
      from issued
      cross join lateral pg_catalog.jsonb_array_elements(issued.opponent_traits_snapshot)
        with ordinality as trait(value, ordinality)
  )
  select refs.ref, refs.owner, refs.ref_kind, refs.source_id
    from refs;
$$;

revoke all on function battle_private.narrative_allowed_refs(uuid)
  from public, anon, authenticated, service_role;

create or replace function battle_private.is_valid_narrative_phase_plan(
  p_phase_plan jsonb,
  p_battle_id uuid
)
returns boolean
language plpgsql
stable
strict
security invoker
set search_path = ''
as $$
begin
  if pg_catalog.jsonb_typeof(p_phase_plan) is distinct from 'array'
     or pg_catalog.jsonb_array_length(p_phase_plan) not between 3 and 5
     or not exists (
       select 1
         from battle_private.matches
        where battle_id = p_battle_id
          and status = 'SETTLED'
     ) then
    return false;
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_phase_plan) with ordinality
        as phase(value, ordinality)
     where pg_catalog.jsonb_typeof(phase.value) is distinct from 'object'
        or not (phase.value ?& array[
          'phase_id','kind','actor','action','skill_ref','item_ref',
          'hp_a_after','hp_b_after','allowed_refs'
        ]::text[])
        or phase.value - array[
          'phase_id','kind','actor','action','skill_ref','item_ref',
          'hp_a_after','hp_b_after','allowed_refs'
        ]::text[] <> '{}'::jsonb
        or pg_catalog.jsonb_typeof(phase.value -> 'phase_id') is distinct from 'string'
        or phase.value ->> 'phase_id' is distinct from 'P' || phase.ordinality::text
        or pg_catalog.jsonb_typeof(phase.value -> 'kind') is distinct from 'string'
        or phase.value ->> 'kind' not in (
          'OPENING','EXCHANGE','PRESSURE','TURNING_POINT','REVERSAL',
          'FINAL_EXCHANGE','FINISH'
        )
        or pg_catalog.jsonb_typeof(phase.value -> 'actor') is distinct from 'string'
        or phase.value ->> 'actor' not in ('A','B')
        or pg_catalog.jsonb_typeof(phase.value -> 'action') is distinct from 'string'
        or phase.value ->> 'action' not in (
          'BASIC_ATTACK','SKILL','GUARD','RECOVER','COUNTER','EVADE'
        )
        or case pg_catalog.jsonb_typeof(phase.value -> 'skill_ref')
          when 'null' then false
          when 'string' then false
          else true
        end
        or case pg_catalog.jsonb_typeof(phase.value -> 'item_ref')
          when 'null' then false
          when 'string' then false
          else true
        end
        or pg_catalog.jsonb_typeof(phase.value -> 'hp_a_after') is distinct from 'number'
        or phase.value ->> 'hp_a_after' !~ '^[0-9]+$'
        or (phase.value ->> 'hp_a_after')::numeric not between 0 and 1000
        or pg_catalog.jsonb_typeof(phase.value -> 'hp_b_after') is distinct from 'number'
        or phase.value ->> 'hp_b_after' !~ '^[0-9]+$'
        or (phase.value ->> 'hp_b_after')::numeric not between 0 and 1000
        or pg_catalog.jsonb_typeof(phase.value -> 'allowed_refs') is distinct from 'array'
        or pg_catalog.jsonb_array_length(phase.value -> 'allowed_refs') > 14
  ) then
    return false;
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_phase_plan) as phase(value)
      cross join lateral pg_catalog.jsonb_array_elements(phase.value -> 'allowed_refs')
        as phase_ref(value)
      left join battle_private.narrative_allowed_refs(p_battle_id) as issued
        on issued.ref = phase_ref.value #>> '{}'
       and issued.owner = phase.value ->> 'actor'
     where pg_catalog.jsonb_typeof(phase_ref.value) is distinct from 'string'
        or phase_ref.value #>> '{}' !~
           '^[AB]_(SKILL_[0-9]{2}|ITEM_(WEAPON|HEAD|BODY|HANDS|FEET|ACCESSORY)|TRAIT_[0-9]{2})$'
        or issued.ref is null
  )
     or exists (
       select 1
         from pg_catalog.jsonb_array_elements(p_phase_plan) as phase(value)
        where (
          select pg_catalog.count(*)
            from pg_catalog.jsonb_array_elements_text(phase.value -> 'allowed_refs')
              as phase_ref(ref)
        ) <> (
          select pg_catalog.count(distinct phase_ref.ref)
            from pg_catalog.jsonb_array_elements_text(phase.value -> 'allowed_refs')
              as phase_ref(ref)
        )
     ) then
    return false;
  end if;

  -- Optional primary refs must be in this exact phase's allow-list, belong to
  -- its actor, and have the correct issued entity kind.
  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_phase_plan) as phase(value)
     where case pg_catalog.jsonb_typeof(phase.value -> 'skill_ref')
       when 'null' then false
       else not exists (
         select 1
           from battle_private.narrative_allowed_refs(p_battle_id) as issued
          where issued.ref = phase.value ->> 'skill_ref'
            and issued.owner = phase.value ->> 'actor'
            and issued.ref_kind = 'SKILL'
            and (phase.value -> 'allowed_refs') ? (phase.value ->> 'skill_ref')
       )
     end
        or case pg_catalog.jsonb_typeof(phase.value -> 'item_ref')
          when 'null' then false
          else not exists (
            select 1
              from battle_private.narrative_allowed_refs(p_battle_id) as issued
             where issued.ref = phase.value ->> 'item_ref'
               and issued.owner = phase.value ->> 'actor'
               and issued.ref_kind = 'ITEM'
               and (phase.value -> 'allowed_refs') ? (phase.value ->> 'item_ref')
          )
        end
  ) then
    return false;
  end if;
  return true;
exception
  when others then
    return false;
end;
$$;

revoke all on function battle_private.is_valid_narrative_phase_plan(jsonb, uuid)
  from public, anon, authenticated, service_role;

create or replace function battle_private.is_valid_narrative_payload(
  p_narrative jsonb,
  p_battle_id uuid
)
returns boolean
language plpgsql
stable
strict
security invoker
set search_path = ''
as $$
declare
  v_phase_plan jsonb;
begin
  if not battle_private.is_valid_narrative_shape(p_narrative, p_battle_id)
  then
    return false;
  end if;

  select contract.phase_plan
    into v_phase_plan
    from battle_private.narrative_phase_contracts as contract
   where contract.battle_id = p_battle_id;
  if not found
     or not battle_private.is_valid_narrative_phase_plan(v_phase_plan, p_battle_id)
     or pg_catalog.jsonb_array_length(v_phase_plan)
          <> pg_catalog.jsonb_array_length(p_narrative -> 'scenes') then
    return false;
  end if;

  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_narrative -> 'scenes') as scene(value)
      cross join lateral pg_catalog.jsonb_array_elements(scene.value -> 'segments')
        as segment(value)
     where segment.value ->> 'type' = 'ENTITY_REF'
       and not exists (
         select 1
           from pg_catalog.jsonb_array_elements(v_phase_plan) as phase(value)
          where phase.value ->> 'phase_id' = scene.value ->> 'phase_id'
            and (phase.value -> 'allowed_refs') ? (segment.value ->> 'ref')
            and pg_catalog.left(segment.value ->> 'ref', 1)
                  = phase.value ->> 'actor'
       )
  ) then
    return false;
  end if;

  return true;
exception
  when others then
    return false;
end;
$$;

revoke all on function battle_private.is_valid_narrative_payload(jsonb, uuid)
  from public, anon, authenticated, service_role;

create or replace function battle_private.korea_day(p_at timestamptz)
returns date
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select (p_at at time zone 'Asia/Seoul')::date;
$$;

revoke all on function battle_private.korea_day(timestamptz)
  from public, anon, authenticated, service_role;

create or replace function battle_private.is_due_trait_removal(
  p_status text,
  p_completes_at timestamptz,
  p_as_of timestamptz
)
returns boolean
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select p_status = 'PENDING' and p_completes_at <= p_as_of;
$$;

revoke all on function battle_private.is_due_trait_removal(
  text, timestamptz, timestamptz
) from public, anon, authenticated, service_role;

create or replace function battle_private.match_counterpart(
  p_entrant_user_id uuid,
  p_entrant_character_id uuid,
  p_opponent_user_id uuid,
  p_opponent_character_id uuid,
  p_current_user_id uuid,
  p_current_character_id uuid
)
returns table(user_id uuid, character_id uuid)
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select case
           when p_entrant_user_id = p_current_user_id
            and p_entrant_character_id = p_current_character_id
             then p_opponent_user_id
           else p_entrant_user_id
         end,
         case
           when p_entrant_user_id = p_current_user_id
            and p_entrant_character_id = p_current_character_id
             then p_opponent_character_id
           else p_entrant_character_id
         end
   where (
     p_entrant_user_id = p_current_user_id
     and p_entrant_character_id = p_current_character_id
   ) or (
     p_opponent_user_id = p_current_user_id
     and p_opponent_character_id = p_current_character_id
   );
$$;

revoke all on function battle_private.match_counterpart(
  uuid, uuid, uuid, uuid, uuid, uuid
) from public, anon, authenticated, service_role;

create or replace function battle_private.rating_window_for_gap(p_gap integer)
returns smallint
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select case
    when p_gap between 0 and 100 then 100
    when p_gap between 101 and 200 then 200
    when p_gap between 201 and 300 then 300
    else null
  end::smallint;
$$;

revoke all on function battle_private.rating_window_for_gap(integer)
  from public, anon, authenticated, service_role;

create or replace function battle_private.system_recovery_id(
  p_battle_id uuid,
  p_recovery_kind text
)
returns uuid
language sql
immutable
strict
security invoker
set search_path = ''
as $$
  select pg_catalog.md5(
    'alarmquest:battle-recovery:' || p_recovery_kind || ':' || p_battle_id::text
  )::uuid;
$$;

revoke all on function battle_private.system_recovery_id(uuid, text)
  from public, anon, authenticated, service_role;

create or replace function battle_private.recovery_response(p_recovery_id uuid)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'recovery_id', recovery.recovery_id,
    'battle_id', recovery.battle_id,
    'entry_id', recovery.entry_id,
    'season_id', recovery.season_id,
    'status', 'VOID',
    'recovery_kind', recovery.recovery_kind,
    'recovery_source', recovery.recovery_source,
    'match_status_before', recovery.match_status_before,
    'match_status_after', recovery.match_status_after,
    'entry_status_before', recovery.entry_status_before,
    'entry_status_after', recovery.entry_status_after,
    'ticket_counted_before', recovery.ticket_counted_before,
    'ticket_counted_after', recovery.ticket_counted_after,
    'actor', pg_catalog.jsonb_build_object(
      'database_role', recovery.actor_database_role,
      'session_user', recovery.actor_session_user,
      'auth_user_id', recovery.actor_auth_user_id
    ),
    'audit_note', recovery.audit_note,
    'recovered_at', recovery.recovered_at,
    'ticket_refund', pg_catalog.jsonb_build_object(
      'entry_day', recovery.entry_day,
      'refunded_entries', 1,
      'entries_used_on_entry_day', recovery.entries_used_on_entry_day,
      'remaining_entries_on_entry_day', recovery.remaining_entries_on_entry_day,
      'current_entry_day', recovery.current_entry_day,
      'applies_to_current_day', recovery.entry_day = recovery.current_entry_day,
      'entries_used_on_current_day', recovery.entries_used_on_current_day,
      'remaining_entries_on_current_day', recovery.remaining_entries_on_current_day
    )
  )
    from battle_private.match_recoveries as recovery
   where recovery.recovery_id = p_recovery_id;
$$;

revoke all on function battle_private.recovery_response(uuid)
  from public, anon, authenticated, service_role;

create or replace function battle_private.recover_match(
  p_recovery_id uuid,
  p_battle_id uuid,
  p_recovery_kind text,
  p_recovery_source text,
  p_audit_note text,
  p_as_of timestamptz default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_existing battle_private.match_recoveries%rowtype;
  v_match battle_private.matches%rowtype;
  v_entry public.battle_entries%rowtype;
  v_season public.battle_seasons%rowtype;
  v_season_id uuid;
  v_now timestamptz;
  v_current_entry_day date;
  v_used_on_entry_day integer;
  v_used_on_current_day integer;
  v_match_status_before text;
  v_match_status_after text;
  v_entry_status_before text;
  v_entry_status_after text;
  v_ticket_counted_before boolean;
  v_ticket_counted_after boolean;
  v_actor_database_role text := coalesce(
    nullif(nullif(pg_catalog.current_setting('role', true), ''), 'none'),
    session_user::text
  );
  v_actor_session_user text := session_user::text;
  v_actor_auth_user_id uuid := auth.uid();
begin
  if p_recovery_id is null
     or p_battle_id is null
     or p_recovery_kind is null
     or p_recovery_kind not in ('VOID','EXPIRED','CANCELLED')
     or p_recovery_source is null
     or p_recovery_source not in (
       'SERVICE_RPC','SEASON_LOCK','SEASON_BOUNDARY','SETTLEMENT_GUARD','SEASON_SWEEP'
     )
     or (
       p_recovery_source <> 'SERVICE_RPC'
       and p_recovery_kind <> 'EXPIRED'
     )
     or p_audit_note is null
     or pg_catalog.char_length(pg_catalog.btrim(p_audit_note)) not between 1 and 240
     or p_audit_note ~ '[[:cntrl:]]'
     or pg_catalog.to_regrole(v_actor_database_role) is null
     or pg_catalog.to_regrole(v_actor_session_user) is null then
    raise exception 'invalid battle recovery request' using errcode = '22023';
  end if;

  select *
    into v_existing
    from battle_private.match_recoveries
   where recovery_id = p_recovery_id;

  if found then
    if v_existing.battle_id <> p_battle_id
       or v_existing.recovery_kind <> p_recovery_kind
       or v_existing.recovery_source <> p_recovery_source
       or v_existing.audit_note <> pg_catalog.btrim(p_audit_note) then
      raise exception 'battle recovery idempotency conflict' using errcode = '23505';
    end if;
    return battle_private.recovery_response(v_existing.recovery_id);
  end if;

  select matches.season_id
    into v_season_id
    from battle_private.matches as matches
   where matches.battle_id = p_battle_id;
  if not found then
    raise exception 'battle not found' using errcode = 'P0002';
  end if;

  -- Global lock order for lifecycle transitions is season -> match -> entry.
  -- Settlement and season locking use the same order, so the exact end/lock
  -- boundary has one serial winner.
  select *
    into v_season
    from public.battle_seasons
   where season_id = v_season_id
   for update;

  select *
    into v_match
    from battle_private.matches
   where battle_id = p_battle_id
   for update;

  select *
    into v_entry
    from public.battle_entries
   where entry_id = v_match.entry_id
   for update;

  v_now := coalesce(p_as_of, pg_catalog.clock_timestamp());

  select *
    into v_existing
    from battle_private.match_recoveries
   where battle_id = p_battle_id;
  if found then
    if v_existing.recovery_id <> p_recovery_id
       or v_existing.recovery_kind <> p_recovery_kind
       or v_existing.recovery_source <> p_recovery_source
       or v_existing.audit_note <> pg_catalog.btrim(p_audit_note) then
      raise exception 'battle already has a different recovery audit'
        using errcode = '23505';
    end if;
    return battle_private.recovery_response(v_existing.recovery_id);
  end if;

  if v_match.status = 'SETTLED' or v_entry.status = 'SETTLED' then
    raise exception 'a settled battle cannot be recovered' using errcode = '55000';
  end if;
  if v_match.status = 'VOID' or v_entry.status = 'VOID' then
    raise exception 'void battle is missing its recovery audit' using errcode = '55000';
  end if;
  if v_match.status <> 'MATCHED' or v_entry.status <> 'MATCHED' then
    raise exception 'battle is not recoverable' using errcode = '55000';
  end if;

  if v_season.locked_at is not null or v_season.ends_at <= v_now then
    if p_recovery_kind <> 'EXPIRED' then
      raise exception 'ended or locked season recovery must be EXPIRED'
        using errcode = '22023';
    end if;
  elsif p_recovery_kind = 'EXPIRED' then
    raise exception 'active unlocked battle is not expired' using errcode = '22023';
  end if;

  v_match_status_before := v_match.status;
  v_entry_status_before := v_entry.status;
  v_ticket_counted_before := v_entry.status in ('MATCHED','SETTLED');

  update battle_private.matches
     set status = 'VOID',
         void_reason = p_recovery_kind,
         voided_at = v_now
   where battle_id = p_battle_id
     and status = 'MATCHED'
  returning status into v_match_status_after;

  update public.battle_entries
     set status = 'VOID',
         void_reason = p_recovery_kind,
         voided_at = v_now
   where entry_id = v_entry.entry_id
     and status = 'MATCHED'
  returning status into v_entry_status_after;

  if v_match_status_after is null or v_entry_status_after is null then
    raise exception 'battle recovery transition was not applied atomically'
      using errcode = '55000';
  end if;
  v_ticket_counted_after := v_entry_status_after in ('MATCHED','SETTLED');

  v_current_entry_day := battle_private.korea_day(v_now);
  select pg_catalog.count(*)::integer
    into v_used_on_entry_day
    from public.battle_entries as entries
   where entries.season_id = v_entry.season_id
     and entries.user_id = v_entry.user_id
     and entries.character_id = v_entry.character_id
     and entries.entry_day = v_entry.entry_day
     and entries.status in ('MATCHED','SETTLED');

  select pg_catalog.count(*)::integer
    into v_used_on_current_day
    from public.battle_entries as entries
   where entries.season_id = v_entry.season_id
     and entries.user_id = v_entry.user_id
     and entries.character_id = v_entry.character_id
     and entries.entry_day = v_current_entry_day
     and entries.status in ('MATCHED','SETTLED');

  insert into battle_private.match_recoveries (
    recovery_id,
    battle_id,
    entry_id,
    season_id,
    user_id,
    character_id,
    entry_day,
    recovery_kind,
    recovery_source,
    actor_database_role,
    actor_session_user,
    actor_auth_user_id,
    match_status_before,
    match_status_after,
    entry_status_before,
    entry_status_after,
    ticket_counted_before,
    ticket_counted_after,
    audit_note,
    recovered_at,
    daily_entry_limit,
    entries_used_on_entry_day,
    remaining_entries_on_entry_day,
    current_entry_day,
    entries_used_on_current_day,
    remaining_entries_on_current_day
  ) values (
    p_recovery_id,
    p_battle_id,
    v_entry.entry_id,
    v_entry.season_id,
    v_entry.user_id,
    v_entry.character_id,
    v_entry.entry_day,
    p_recovery_kind,
    p_recovery_source,
    v_actor_database_role,
    v_actor_session_user,
    v_actor_auth_user_id,
    v_match_status_before,
    v_match_status_after,
    v_entry_status_before,
    v_entry_status_after,
    v_ticket_counted_before,
    v_ticket_counted_after,
    pg_catalog.btrim(p_audit_note),
    v_now,
    v_season.daily_entry_limit,
    v_used_on_entry_day,
    v_season.daily_entry_limit - v_used_on_entry_day,
    v_current_entry_day,
    v_used_on_current_day,
    v_season.daily_entry_limit - v_used_on_current_day
  );

  return battle_private.recovery_response(p_recovery_id);
end;
$$;

revoke all on function battle_private.recover_match(
  uuid, uuid, text, text, text, timestamptz
) from public, anon, authenticated, service_role;

create or replace function battle_private.expire_stale_matches(
  p_limit integer default 500,
  p_as_of timestamptz default null
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
begin
  if p_limit is null or p_limit not between 1 and 5000 then
    raise exception 'invalid expired battle recovery limit' using errcode = '22023';
  end if;
  -- Compatibility wrapper only: p_limit no longer limits match rows. Natural
  -- season closure must be atomic per season, so all due seasons are delegated
  -- to the unbounded boundary finalizer in this same transaction.
  return battle_private.finalize_due_seasons(p_as_of);
end;
$$;

revoke all on function battle_private.expire_stale_matches(integer, timestamptz)
  from public, anon, authenticated, service_role;

create or replace function public.battle_recover_match(
  p_recovery_id uuid,
  p_battle_id uuid,
  p_recovery_kind text,
  p_audit_note text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_invoking_role text := coalesce(
    nullif(nullif(pg_catalog.current_setting('role', true), ''), 'none'),
    session_user::text
  );
begin
  if v_invoking_role <> 'service_role' then
    raise exception 'service role required' using errcode = '42501';
  end if;
  return battle_private.recover_match(
    p_recovery_id,
    p_battle_id,
    p_recovery_kind,
    'SERVICE_RPC',
    p_audit_note,
    null
  );
end;
$$;

revoke all on function public.battle_recover_match(uuid, uuid, text, text)
  from public, anon, authenticated;
grant execute on function public.battle_recover_match(uuid, uuid, text, text)
  to service_role;

comment on function public.battle_recover_match(uuid, uuid, text, text) is
  'Service-only idempotent recovery. VOID, EXPIRED, and CANCELLED all terminate MATCHED without settlement, preserve rating/record/traits, release the open-match slot, and refund only the original entry_day.';

create or replace function public.battle_recover_expired_matches(
  p_limit integer default 500
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_invoking_role text := coalesce(
    nullif(nullif(pg_catalog.current_setting('role', true), ''), 'none'),
    session_user::text
  );
begin
  if v_invoking_role <> 'service_role' then
    raise exception 'service role required' using errcode = '42501';
  end if;
  return battle_private.expire_stale_matches(p_limit, null);
end;
$$;

revoke all on function public.battle_recover_expired_matches(integer)
  from public, anon, authenticated;
grant execute on function public.battle_recover_expired_matches(integer)
  to service_role;

comment on function public.battle_recover_expired_matches(integer) is
  'Compatibility service RPC. The limit is validated but never caps match rows; each due season is finalized atomically with every MATCHED row.';

create or replace function battle_private.active_season(p_at timestamptz default now())
returns setof public.battle_seasons
language sql
stable
security definer
set search_path = ''
as $$
  select seasons.*
    from public.battle_seasons as seasons
   where seasons.starts_at <= p_at
     and seasons.ends_at > p_at
     and seasons.locked_at is null
   order by seasons.starts_at desc
   limit 1;
$$;

revoke all on function battle_private.active_season(timestamptz)
  from public, anon, authenticated, service_role;

create or replace function battle_private.starting_rating(
  p_season_id uuid,
  p_user_id uuid,
  p_character_id uuid
)
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_season public.battle_seasons%rowtype;
  v_previous_rating integer;
begin
  select *
    into v_season
    from public.battle_seasons
   where season_id = p_season_id;

  if not found then
    raise exception 'battle season not found' using errcode = 'P0002';
  end if;

  select participants.rating
    into v_previous_rating
    from public.battle_participants as participants
    join public.battle_seasons as seasons
      on seasons.season_id = participants.season_id
   where participants.user_id = p_user_id
     and participants.character_id = p_character_id
     and participants.matches_played >= seasons.placement_match_count
     and seasons.ends_at <= v_season.starts_at
   order by seasons.ends_at desc
   limit 1;

  if v_previous_rating is null then
    return v_season.initial_rating;
  end if;

  return greatest(
    800,
    least(
      1200,
      v_season.initial_rating + pg_catalog.round(
        (v_previous_rating - v_season.initial_rating)::numeric
        * v_season.carryover_basis_points::numeric / 10000
      )::integer
    )
  );
end;
$$;

revoke all on function battle_private.starting_rating(uuid, uuid, uuid)
  from public, anon, authenticated, service_role;

create or replace function public.battle_create_monthly_season(
  p_month date,
  p_rules_version integer
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_season_id uuid;
  v_season_key text;
  v_starts_at timestamptz;
  v_ends_at timestamptz;
  v_existing public.battle_seasons%rowtype;
begin
  if p_month is null
     or p_month <> pg_catalog.date_trunc('month', p_month::timestamp)::date
     or p_rules_version is distinct from 1 then
    raise exception 'month must be its first Korea date and rules_version must equal 1'
      using errcode = '22023';
  end if;

  v_season_key := pg_catalog.to_char(p_month, 'YYYY-MM');
  v_starts_at := p_month::timestamp at time zone 'Asia/Seoul';
  v_ends_at := (p_month + interval '1 month')::timestamp at time zone 'Asia/Seoul';

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('alarmquest:battle:season', 0)
  );

  select *
    into v_existing
    from public.battle_seasons
   where season_key = v_season_key;

  if found then
    if v_existing.starts_at <> v_starts_at
       or v_existing.ends_at <> v_ends_at
       or v_existing.rules_version <> p_rules_version then
      raise exception 'battle season idempotency conflict' using errcode = '23505';
    end if;
    return v_existing.season_id;
  end if;

  if exists (
    select 1
      from public.battle_seasons
     where starts_at < v_ends_at
       and ends_at > v_starts_at
  ) then
    raise exception 'battle season overlaps an existing season' using errcode = '23P01';
  end if;

  insert into public.battle_seasons (
    season_key,
    starts_at,
    ends_at,
    rules_version
  ) values (
    v_season_key,
    v_starts_at,
    v_ends_at,
    p_rules_version
  )
  returning season_id into v_season_id;

  return v_season_id;
end;
$$;

revoke all on function public.battle_create_monthly_season(date, integer)
  from public, anon, authenticated;
grant execute on function public.battle_create_monthly_season(date, integer)
  to service_role;

comment on function public.battle_create_monthly_season(date, integer) is
  'Service-only idempotent creation of one Asia/Seoul calendar-month Battle season.';

-- The only season-finalization state machine. Natural month-end and an early
-- service lock both enter here, acquire the season row first, and then use the
-- same audited MATCHED -> VOID recovery transition for every open battle. A
-- failure rolls the whole transition back. The caller may allow an early lock,
-- but cannot choose recovery source/status values.
create or replace function battle_private.finalize_season(
  p_season_id uuid,
  p_as_of timestamptz default null,
  p_allow_pre_end boolean default false,
  p_manual_reason text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_season public.battle_seasons%rowtype;
  v_battle_id uuid;
  v_now timestamptz := coalesce(p_as_of, pg_catalog.clock_timestamp());
  v_is_natural boolean;
  v_lock_target timestamptz;
  v_locked_at timestamptz;
  v_lock_changed integer := 0;
  v_voided integer := 0;
  v_recovery_source text;
  v_audit_note text;
begin
  if p_season_id is null
     or p_allow_pre_end is null
     or (p_manual_reason is not null and (
       pg_catalog.char_length(pg_catalog.btrim(p_manual_reason)) not between 1 and 240
       or p_manual_reason ~ '[[:cntrl:]]'
     )) then
    raise exception 'invalid season finalization request' using errcode = '22023';
  end if;

  -- Global lifecycle lock order is season -> match -> entry. recover_match
  -- reacquires this season lock reentrantly before taking each match/entry.
  select *
    into v_season
    from public.battle_seasons
   where season_id = p_season_id
   for update;
  if not found then
    raise exception 'battle season not found' using errcode = 'P0002';
  end if;
  v_is_natural := v_now >= v_season.ends_at;
  if not v_is_natural and not p_allow_pre_end then
    raise exception 'battle season boundary has not arrived' using errcode = '55000';
  end if;
  if not v_is_natural and p_manual_reason is null then
    raise exception 'manual season lock reason is required' using errcode = '22023';
  end if;

  if v_is_natural then
    v_lock_target := v_season.ends_at;
    v_recovery_source := 'SEASON_BOUNDARY';
    v_audit_note := 'natural season boundary reached before settlement';
  else
    v_lock_target := v_now;
    v_recovery_source := 'SEASON_LOCK';
    v_audit_note := pg_catalog.btrim(p_manual_reason);
  end if;

  update public.battle_seasons
     -- A natural close records the logical boundary, while an early manual
     -- close records its actual service action time. Recovery retains v_now as
     -- its distinct physical audit time.
     set locked_at = v_lock_target
   where season_id = p_season_id
     and locked_at is null
  returning locked_at into v_locked_at;

  get diagnostics v_lock_changed = row_count;
  if v_lock_changed = 0 then
    v_locked_at := v_season.locked_at;
  end if;

  for v_battle_id in
    select matches.battle_id
      from battle_private.matches as matches
     where matches.season_id = p_season_id
       and matches.status = 'MATCHED'
     order by matches.created_at, matches.battle_id
  loop
    perform battle_private.recover_match(
      battle_private.system_recovery_id(v_battle_id, 'EXPIRED'),
      v_battle_id,
      'EXPIRED',
      v_recovery_source,
      v_audit_note,
      v_now
    );
    v_voided := v_voided + 1;
  end loop;

  if exists (
    select 1
      from battle_private.matches
     where season_id = p_season_id
       and status = 'MATCHED'
  ) then
    raise exception 'season finalization left an open battle' using errcode = '40001';
  end if;
  return pg_catalog.jsonb_build_object(
    'season_id', p_season_id,
    'mode', case when v_is_natural then 'NATURAL' else 'MANUAL' end,
    'lock_changed', v_lock_changed > 0,
    'locked_at', v_locked_at,
    'matches_voided', v_voided,
    'recovery_source', v_recovery_source
  );
end;
$$;

revoke all on function battle_private.finalize_season(
  uuid, timestamptz, boolean, text
)
  from public, anon, authenticated, service_role;

create or replace function public.battle_lock_season(p_season_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_invoking_role text := coalesce(
    nullif(nullif(pg_catalog.current_setting('role', true), ''), 'none'),
    session_user::text
  );
  v_result jsonb;
begin
  if v_invoking_role <> 'service_role' then
    raise exception 'service role required' using errcode = '42501';
  end if;
  v_result := battle_private.finalize_season(
    p_season_id,
    null,
    true,
    'manual service season lock before settlement'
  );
  return (v_result ->> 'lock_changed')::boolean;
end;
$$;

revoke all on function public.battle_lock_season(uuid)
  from public, anon, authenticated;
grant execute on function public.battle_lock_season(uuid)
  to service_role;

comment on function public.battle_lock_season(uuid) is
  'Service-only early/manual season lock routed through the common atomic season finalizer.';

create or replace function battle_private.finalize_due_seasons(
  p_as_of timestamptz default null
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz := coalesce(p_as_of, pg_catalog.clock_timestamp());
  v_season_id uuid;
  v_result jsonb;
  v_voided integer := 0;
begin
  for v_season_id in
    select seasons.season_id
      from public.battle_seasons as seasons
     where seasons.ends_at <= v_now
       and (
         seasons.locked_at is null
         or exists (
           select 1
             from battle_private.matches as matches
            where matches.season_id = seasons.season_id
              and matches.status = 'MATCHED'
         )
       )
     order by seasons.ends_at, seasons.season_id
  loop
    v_result := battle_private.finalize_season(
      v_season_id,
      v_now,
      false,
      null
    );
    v_voided := v_voided + (v_result ->> 'matches_voided')::integer;
  end loop;
  return v_voided;
end;
$$;

revoke all on function battle_private.finalize_due_seasons(timestamptz)
  from public, anon, authenticated, service_role;

create or replace function public.battle_finalize_season_boundary(p_season_id uuid)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_invoking_role text := coalesce(
    nullif(nullif(pg_catalog.current_setting('role', true), ''), 'none'),
    session_user::text
  );
begin
  if v_invoking_role <> 'service_role' then
    raise exception 'service role required' using errcode = '42501';
  end if;
  return (
    battle_private.finalize_season(p_season_id, null, false, null)
      ->> 'matches_voided'
  )::integer;
end;
$$;

revoke all on function public.battle_finalize_season_boundary(uuid)
  from public, anon, authenticated;
grant execute on function public.battle_finalize_season_boundary(uuid)
  to service_role;

comment on function public.battle_finalize_season_boundary(uuid) is
  'Service scheduler boundary RPC: after ends_at, atomically sets locked_at and audits/voids every open match in that season; no match-count batch limit is permitted.';

create or replace function public.battle_publish_verified_snapshot(
  p_user_id uuid,
  p_character_id uuid,
  p_display_name text,
  p_hero_class text,
  p_level bigint,
  p_rules_version integer,
  p_server_revision text,
  p_snapshot_payload jsonb,
  p_expires_at timestamptz
)
returns uuid
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_existing battle_private.projection_snapshots%rowtype;
  v_snapshot_id uuid;
  v_growth jsonb;
  v_effective_power bigint;
  v_now timestamptz := pg_catalog.clock_timestamp();
begin
  if p_user_id is null
     or p_character_id is null
     or not exists (select 1 from auth.users where id = p_user_id)
     or p_display_name is null
     or pg_catalog.char_length(pg_catalog.btrim(p_display_name)) not between 1 and 24
     or p_display_name ~ '[[:cntrl:]]'
     or p_hero_class is null
     or p_hero_class not in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')
     or p_level is null
     or p_level not between 20 and 10000
     or p_rules_version is distinct from 1
     or p_server_revision is null
     or pg_catalog.char_length(p_server_revision) not between 1 and 128
     or p_server_revision !~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$'
     or p_expires_at is null
     or p_expires_at <= v_now
     or p_expires_at > v_now + interval '30 days'
     or not exists (
       select 1
         from public.battle_seasons
        where rules_version = p_rules_version
     ) then
    raise exception 'invalid trusted projection metadata' using errcode = '22023';
  end if;

  if not battle_private.is_valid_snapshot_payload(p_snapshot_payload) then
    raise exception 'invalid trusted projection payload' using errcode = '22023';
  end if;

  -- No combat_power argument exists. The server deterministically normalizes
  -- verified build/equipment and one representative skill before 80/20 weighting.
  v_growth := battle_private.growth_components(p_snapshot_payload);
  v_effective_power := (v_growth ->> 'effectivePower')::bigint;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      p_user_id::text || ':' || p_character_id::text || ':' || p_rules_version::text,
      0
    )
  );

  select *
    into v_existing
    from battle_private.projection_snapshots
   where user_id = p_user_id
     and character_id = p_character_id
     and rules_version = p_rules_version
     and server_revision = p_server_revision;

  if found then
    if v_existing.revoked_at is not null
       or v_existing.display_name <> pg_catalog.btrim(p_display_name)
       or v_existing.hero_class <> p_hero_class
       or v_existing.level <> p_level
       or v_existing.snapshot_payload <> p_snapshot_payload
       or v_existing.verified_power_raw <>
          (v_growth ->> 'rawVerifiedPower')::bigint
       or v_existing.verified_power_basis_points <>
          (v_growth ->> 'verifiedPowerBasisPoints')::smallint
       or v_existing.representative_skill_id is distinct from
          v_growth ->> 'representativeSkillId'
       or v_existing.representative_skill_mastery <>
          (v_growth ->> 'representativeSkillMastery')::smallint
       or v_existing.growth_index_basis_points <>
          (v_growth ->> 'growthIndexBasisPoints')::smallint
       or v_existing.effective_power <> v_effective_power
       or v_existing.expires_at <> p_expires_at then
      raise exception 'trusted projection revision conflict' using errcode = '23505';
    end if;
    return v_existing.snapshot_id;
  end if;

  update battle_private.projection_snapshots
     set revoked_at = v_now
   where user_id = p_user_id
     and character_id = p_character_id
     and rules_version = p_rules_version
     and revoked_at is null;

  insert into battle_private.projection_snapshots (
    user_id,
    character_id,
    display_name,
    hero_class,
    level,
    rules_version,
    server_revision,
    snapshot_payload,
    verified_power_raw,
    verified_power_basis_points,
    representative_skill_id,
    representative_skill_mastery,
    growth_index_basis_points,
    effective_power,
    verified_at,
    expires_at
  ) values (
    p_user_id,
    p_character_id,
    pg_catalog.btrim(p_display_name),
    p_hero_class,
    p_level,
    p_rules_version,
    p_server_revision,
    p_snapshot_payload,
    (v_growth ->> 'rawVerifiedPower')::bigint,
    (v_growth ->> 'verifiedPowerBasisPoints')::smallint,
    v_growth ->> 'representativeSkillId',
    (v_growth ->> 'representativeSkillMastery')::smallint,
    (v_growth ->> 'growthIndexBasisPoints')::smallint,
    v_effective_power,
    v_now,
    p_expires_at
  )
  returning snapshot_id into v_snapshot_id;

  return v_snapshot_id;
end;
$$;

revoke all on function public.battle_publish_verified_snapshot(
  uuid, uuid, text, text, bigint, integer, text, jsonb, timestamptz
) from public, anon, authenticated;
grant execute on function public.battle_publish_verified_snapshot(
  uuid, uuid, text, text, bigint, integer, text, jsonb, timestamptz
) to service_role;

comment on function public.battle_publish_verified_snapshot(
  uuid, uuid, text, text, bigint, integer, text, jsonb, timestamptz
) is
  'Service-only snapshot publication. DB recomputes the fixed V0.1 80 percent verified-power plus 20 percent representative-mastery growth contract and never reads ranking_entries.combat_power.';

create or replace function battle_private.entry_response(
  p_entry_id uuid,
  p_user_id uuid
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'entry_id', entries.entry_id,
    'battle_id', entries.battle_id,
    'season_id', entries.season_id,
    'status', entries.status,
    'entry_day', entries.entry_day,
    'directive', entries.directive,
    'recovery', case
      when recovery.recovery_id is null then null
      else pg_catalog.jsonb_build_object(
        'recovery_id', recovery.recovery_id,
        'kind', recovery.recovery_kind,
        'match_status_before', recovery.match_status_before,
        'match_status_after', recovery.match_status_after,
        'entry_status_before', recovery.entry_status_before,
        'entry_status_after', recovery.entry_status_after,
        'ticket_counted_before', recovery.ticket_counted_before,
        'ticket_counted_after', recovery.ticket_counted_after,
        'recovered_at', recovery.recovered_at,
        'refunded_entry_day', recovery.entry_day,
        'remaining_entries_on_entry_day', recovery.remaining_entries_on_entry_day,
        'applies_to_current_day', recovery.entry_day = recovery.current_entry_day,
        'remaining_entries_on_current_day', recovery.remaining_entries_on_current_day
      )
    end,
    'opponent', pg_catalog.jsonb_build_object(
      'character_id', matches.opponent_character_id,
      'display_name', opponent.display_name,
      'hero_class', opponent.hero_class,
      'level', opponent.level,
      'rating', case
        when opponent_participants.matches_played >= seasons.placement_match_count
          then matches.opponent_rating_reference
        else null
      end
    ),
    'result', case
      when records.battle_id is null then null
      else pg_catalog.jsonb_build_object(
        'outcome', records.outcome,
        'placement_complete',
          entrant_participants.matches_played >= seasons.placement_match_count,
        'rating_before', case
          when entrant_participants.matches_played >= seasons.placement_match_count
            then records.rating_before
          else null
        end,
        'score_delta', case
          when entrant_participants.matches_played >= seasons.placement_match_count
            then records.score_delta
          else null
        end,
        'rating_after', case
          when entrant_participants.matches_played >= seasons.placement_match_count
            then records.rating_after
          else null
        end,
        'rules_version', records.rules_version,
        'engine_result', battle_private.public_engine_result(
          records.engine_result,
          records.battle_id,
          records.outcome
        ),
        'narrative', records.narrative,
        'trait_code', records.trait_code,
        'settled_at', records.settled_at
      )
    end
  )
    from public.battle_entries as entries
    join battle_private.matches as matches
      on matches.battle_id = entries.battle_id
    join public.battle_seasons as seasons
      on seasons.season_id = entries.season_id
    join public.battle_participants as entrant_participants
      on entrant_participants.season_id = entries.season_id
     and entrant_participants.user_id = entries.user_id
     and entrant_participants.character_id = entries.character_id
    join public.battle_participants as opponent_participants
      on opponent_participants.season_id = matches.season_id
     and opponent_participants.user_id = matches.opponent_user_id
     and opponent_participants.character_id = matches.opponent_character_id
    join battle_private.projection_snapshots as opponent
      on opponent.snapshot_id = matches.opponent_snapshot_id
    left join public.battle_records as records
      on records.battle_id = entries.battle_id
    left join battle_private.match_recoveries as recovery
      on recovery.battle_id = entries.battle_id
   where entries.entry_id = p_entry_id
     and entries.user_id = p_user_id;
$$;

revoke all on function battle_private.entry_response(uuid, uuid)
  from public, anon, authenticated, service_role;

create or replace function public.battle_request_entry(
  p_request_id uuid,
  p_character_id uuid,
  p_directive text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_now timestamptz := pg_catalog.clock_timestamp();
  v_entry_day date;
  v_season public.battle_seasons%rowtype;
  v_snapshot battle_private.projection_snapshots%rowtype;
  v_participant public.battle_participants%rowtype;
  v_existing public.battle_entries%rowtype;
  v_starting_rating integer;
  v_used_entries integer;
  v_battle_id uuid;
  v_opponent_user_id uuid;
  v_opponent_character_id uuid;
  v_opponent_snapshot_id uuid;
  v_opponent_rating integer;
  v_opponent_guidance text;
  v_opponent_name text;
  v_opponent_class text;
  v_opponent_level bigint;
  v_rating_window smallint;
  v_candidate_pool_size smallint;
  v_engine_seed bigint;
  v_trait_now timestamptz;
  v_entrant_traits_snapshot jsonb;
  v_opponent_traits_snapshot jsonb;
  v_expected_score numeric(9,8);
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;

  if p_request_id is null
     or p_character_id is null
     or p_directive is null
     or p_directive not in ('ASSAULT','BALANCED','GUARD') then
    raise exception 'invalid battle entry request' using errcode = '22023';
  end if;

  -- A repeated request id returns the original assignment. Different parameters
  -- with the same id are rejected and can never be used to reroll an opponent.
  select *
    into v_existing
    from public.battle_entries
   where entry_id = p_request_id;

  if found then
    if v_existing.user_id <> v_user_id
       or v_existing.character_id <> p_character_id
       or v_existing.directive <> p_directive then
      raise exception 'battle request idempotency conflict' using errcode = '23505';
    end if;
    return battle_private.entry_response(p_request_id, v_user_id);
  end if;

  select *
    into v_season
    from battle_private.active_season(v_now);

  if not found then
    raise exception 'no active battle season' using errcode = 'P0002';
  end if;

  -- Serialize assignment against battle_lock_season. Re-sample the wall clock
  -- after acquiring the row lock so a request that waited across the boundary
  -- cannot create a post-lock or post-expiry MATCHED row.
  select *
    into v_season
    from public.battle_seasons
   where season_id = v_season.season_id
   for update;
  v_now := pg_catalog.clock_timestamp();
  if v_season.locked_at is not null
     or v_season.starts_at > v_now
     or v_season.ends_at <= v_now then
    raise exception 'no active battle season' using errcode = 'P0002';
  end if;

  v_entry_day := battle_private.korea_day(v_now);

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      v_season.season_id::text || ':' || v_user_id::text || ':' ||
      p_character_id::text || ':' || v_entry_day::text,
      0
    )
  );

  select *
    into v_snapshot
    from battle_private.projection_snapshots
   where user_id = v_user_id
     and character_id = p_character_id
     and rules_version = v_season.rules_version
     and snapshot_source = 'SERVER_VERIFIED'
     and revoked_at is null
     and expires_at > v_now
   order by verified_at desc
   limit 1;

  if not found then
    raise exception 'trusted battle projection unavailable' using errcode = '55000';
  end if;

  v_starting_rating := battle_private.starting_rating(
    v_season.season_id,
    v_user_id,
    p_character_id
  );

  insert into public.battle_participants (
    season_id,
    user_id,
    character_id,
    snapshot_id,
    guidance,
    rating,
    highest_rating
  ) values (
    v_season.season_id,
    v_user_id,
    p_character_id,
    v_snapshot.snapshot_id,
    p_directive,
    v_starting_rating,
    v_starting_rating
  )
  on conflict (season_id, user_id, character_id) do update
    set snapshot_id = excluded.snapshot_id,
        guidance = excluded.guidance,
        updated_at = v_now
  returning * into v_participant;

  if exists (
    select 1
      from public.battle_entries
     where season_id = v_season.season_id
       and user_id = v_user_id
       and character_id = p_character_id
       and status = 'MATCHED'
  ) then
    raise exception 'an official battle is already awaiting settlement' using errcode = '55000';
  end if;

  select pg_catalog.count(*)::integer
    into v_used_entries
    from public.battle_entries
   where season_id = v_season.season_id
     and user_id = v_user_id
     and character_id = p_character_id
     and entry_day = v_entry_day
     and status in ('MATCHED','SETTLED');

  if v_used_entries >= v_season.daily_entry_limit then
    raise exception 'daily official battle limit reached' using errcode = '54000';
  end if;

  -- V0.1 serializes assignment within a season. This keeps the 24-hour and
  -- seven-day pair limits hard when both characters request simultaneously.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-matchmaker:' || v_season.season_id::text, 0)
  );

  -- Match only inside the first non-empty rating window: +/-100, then +/-200,
  -- then +/-300. Pair history is undirected. A pair is ineligible if it was
  -- assigned in the last 24 hours or already assigned twice in the last 7 days.
  -- From the five closest eligible candidates, use a deterministic weighted
  -- draw favoring proximity, no prior weekly meeting, and lower defense sampling.
  with last_opponent as (
    -- Treat a pair as unordered: the most recent counterpart is excluded even
    -- when the current character was the sampled defender in that battle.
    select counterpart.user_id as opponent_user_id,
           counterpart.character_id as opponent_character_id
      from battle_private.matches as recent
      cross join lateral battle_private.match_counterpart(
        recent.entrant_user_id,
        recent.entrant_character_id,
        recent.opponent_user_id,
        recent.opponent_character_id,
        v_user_id,
        p_character_id
      ) as counterpart
     where recent.season_id = v_season.season_id
       and recent.status in ('MATCHED','SETTLED')
     order by recent.created_at desc, recent.battle_id
     limit 1
  ),
  eligible as (
    select candidates.user_id,
           candidates.character_id,
           candidates.snapshot_id,
           candidates.rating,
           candidates.guidance,
           snapshots.display_name,
           snapshots.hero_class,
           snapshots.level,
           pg_catalog.abs(candidates.rating - v_participant.rating)::integer as rating_gap,
           history.last_match_at,
           coalesce(history.matches_7d, 0) as matches_7d,
           coalesce(sampling.sampled_today, 0) as sampled_today
      from public.battle_participants as candidates
      join battle_private.projection_snapshots as snapshots
        on snapshots.snapshot_id = candidates.snapshot_id
      left join last_opponent
        on true
      left join lateral (
        select pg_catalog.max(pair_history.created_at) filter (
                 where pair_history.status in ('MATCHED','SETTLED')
               ) as last_match_at,
               pg_catalog.count(*) filter (
                 where pair_history.status in ('MATCHED','SETTLED')
                   and pair_history.created_at >= v_now - interval '7 days'
               )::integer as matches_7d
          from battle_private.matches as pair_history
         where pair_history.season_id = v_season.season_id
           and (
             (
               pair_history.entrant_user_id = v_user_id
               and pair_history.entrant_character_id = p_character_id
               and pair_history.opponent_user_id = candidates.user_id
               and pair_history.opponent_character_id = candidates.character_id
             )
             or (
               pair_history.entrant_user_id = candidates.user_id
               and pair_history.entrant_character_id = candidates.character_id
               and pair_history.opponent_user_id = v_user_id
               and pair_history.opponent_character_id = p_character_id
             )
           )
      ) as history on true
      left join lateral (
        select pg_catalog.count(*)::integer as sampled_today
          from battle_private.matches as samples
         where samples.season_id = v_season.season_id
           and samples.opponent_user_id = candidates.user_id
           and samples.opponent_character_id = candidates.character_id
           and samples.status in ('MATCHED','SETTLED')
           and samples.created_at >= v_entry_day::timestamp at time zone 'Asia/Seoul'
           and samples.created_at < (v_entry_day + 1)::timestamp at time zone 'Asia/Seoul'
      ) as sampling on true
     where candidates.season_id = v_season.season_id
       and candidates.user_id <> v_user_id
       and snapshots.rules_version = v_season.rules_version
       and snapshots.snapshot_source = 'SERVER_VERIFIED'
       and snapshots.revoked_at is null
       and snapshots.expires_at > v_now
       and not (
         candidates.user_id is not distinct from last_opponent.opponent_user_id
         and candidates.character_id is not distinct from last_opponent.opponent_character_id
       )
       and pg_catalog.abs(candidates.rating - v_participant.rating) <= 300
       and (history.last_match_at is null or history.last_match_at < v_now - interval '24 hours')
       and coalesce(history.matches_7d, 0) < 2
  ),
  windowed as (
    select eligible.*,
           battle_private.rating_window_for_gap(eligible.rating_gap) as candidate_window
      from eligible
  ),
  chosen_window as (
    select pg_catalog.min(windowed.candidate_window)::smallint as rating_window
      from windowed
  ),
  top_five as (
    select windowed.*,
           chosen_window.rating_window
      from windowed
      cross join chosen_window
     where windowed.rating_gap <= chosen_window.rating_window
     order by windowed.rating_gap,
              windowed.matches_7d,
              windowed.sampled_today,
              windowed.last_match_at asc nulls first,
              pg_catalog.md5(windowed.user_id::text || ':' ||
                             windowed.character_id::text || ':' || p_request_id::text)
     limit 5
  ),
  weighted as (
    select top_five.*,
           pg_catalog.count(*) over ()::smallint as candidate_pool_size,
           (
             (1::numeric +
               (top_five.rating_window - top_five.rating_gap)::numeric /
               top_five.rating_window::numeric)
             * case when top_five.matches_7d = 0 then 2::numeric else 1::numeric end
             / (1::numeric + top_five.sampled_today::numeric)
           ) as selection_weight,
           (
             (pg_catalog.abs(pg_catalog.hashtextextended(
               top_five.user_id::text || ':' || top_five.character_id::text || ':' ||
               p_request_id::text,
               0
             )::numeric) + 1::numeric) / 9223372036854775809::numeric
           ) as deterministic_uniform
      from top_five
  )
  select weighted.user_id,
         weighted.character_id,
         weighted.snapshot_id,
         weighted.rating,
         weighted.guidance,
         weighted.display_name,
         weighted.hero_class,
         weighted.level,
         weighted.rating_window,
         weighted.candidate_pool_size
    into v_opponent_user_id,
         v_opponent_character_id,
         v_opponent_snapshot_id,
         v_opponent_rating,
         v_opponent_guidance,
         v_opponent_name,
         v_opponent_class,
         v_opponent_level,
         v_rating_window,
         v_candidate_pool_size
    from weighted
   order by -pg_catalog.ln(weighted.deterministic_uniform) / weighted.selection_weight,
            pg_catalog.md5(weighted.user_id::text || ':' ||
                           weighted.character_id::text || ':' || p_request_id::text)
   limit 1;

  if not found then
    -- Registration remains committed so a later entrant can discover this
    -- participant. No entry is consumed and no request id is reserved. V0.1
    -- has no system gatekeeper practice fallback; that requires a later rules
    -- contract and must never silently become a rated opponent.
    return pg_catalog.jsonb_build_object(
      'status', 'NO_OPPONENT',
      'season_id', v_season.season_id,
      'entry_day', v_entry_day,
      'remaining_entries', v_season.daily_entry_limit - v_used_entries,
      'searched_rating_windows', pg_catalog.jsonb_build_array(100, 200, 300),
      'gatekeeper_practice_available', false
    );
  end if;

  -- The one-hour boundary is logical, not cron-dependent. Complete due work
  -- for both sides before freezing narration traits into this match. A job may
  -- be delayed, but an already-expired trait can never enter a new snapshot.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-trait-maintenance', 0)
  );
  v_trait_now := pg_catalog.clock_timestamp();
  perform battle_private.complete_due_trait_removals(
    1,
    v_user_id,
    p_character_id,
    v_trait_now
  );
  perform battle_private.complete_due_trait_removals(
    1,
    v_opponent_user_id,
    v_opponent_character_id,
    v_trait_now
  );

  select coalesce(pg_catalog.jsonb_agg(
           pg_catalog.jsonb_build_object(
             'trait_code', active.trait_code,
             'category', catalog.category,
             'narration_tags', catalog.narration_tags,
             'evidence_count', active.evidence_count
           ) order by active.slot_number
         ), '[]'::jsonb)
    into v_entrant_traits_snapshot
    from public.battle_character_traits as active
    join public.battle_trait_catalog as catalog
      on catalog.trait_code = active.trait_code
   where active.user_id = v_user_id
     and active.character_id = p_character_id
     and not exists (
       select 1
         from public.battle_trait_removals as due_removal
        where due_removal.user_id = active.user_id
          and due_removal.character_id = active.character_id
          and due_removal.trait_code = active.trait_code
          and battle_private.is_due_trait_removal(
            due_removal.status,
            due_removal.completes_at,
            v_trait_now
          )
     );

  select coalesce(pg_catalog.jsonb_agg(
           pg_catalog.jsonb_build_object(
             'trait_code', active.trait_code,
             'category', catalog.category,
             'narration_tags', catalog.narration_tags,
             'evidence_count', active.evidence_count
           ) order by active.slot_number
         ), '[]'::jsonb)
    into v_opponent_traits_snapshot
    from public.battle_character_traits as active
    join public.battle_trait_catalog as catalog
      on catalog.trait_code = active.trait_code
   where active.user_id = v_opponent_user_id
     and active.character_id = v_opponent_character_id
     and not exists (
       select 1
         from public.battle_trait_removals as due_removal
        where due_removal.user_id = active.user_id
          and due_removal.character_id = active.character_id
          and due_removal.trait_code = active.trait_code
          and battle_private.is_due_trait_removal(
            due_removal.status,
            due_removal.completes_at,
            v_trait_now
          )
     );

  v_battle_id := gen_random_uuid();
  v_engine_seed := pg_catalog.hashtextextended(
    v_battle_id::text || ':' || p_request_id::text || ':' || v_now::text,
    0
  );
  v_expected_score := (
    1::numeric / (
      1::numeric + pg_catalog.power(
        10::numeric,
        (v_opponent_rating - v_participant.rating)::numeric / 400::numeric
      )
    )
  )::numeric(9,8);

  insert into public.battle_entries (
    entry_id,
    season_id,
    user_id,
    character_id,
    snapshot_id,
    entry_day,
    directive,
    status,
    battle_id,
    created_at
  ) values (
    p_request_id,
    v_season.season_id,
    v_user_id,
    p_character_id,
    v_snapshot.snapshot_id,
    v_entry_day,
    p_directive,
    'MATCHED',
    v_battle_id,
    v_now
  );

  insert into battle_private.matches (
    battle_id,
    entry_id,
    season_id,
    entrant_user_id,
    entrant_character_id,
    entrant_snapshot_id,
    entrant_traits_snapshot,
    opponent_user_id,
    opponent_character_id,
    opponent_snapshot_id,
    opponent_traits_snapshot,
    opponent_guidance,
    entrant_rating_before,
    opponent_rating_reference,
    rating_window,
    candidate_pool_size,
    expected_score,
    k_factor,
    rules_version,
    engine_seed,
    status,
    created_at
  ) values (
    v_battle_id,
    p_request_id,
    v_season.season_id,
    v_user_id,
    p_character_id,
    v_snapshot.snapshot_id,
    v_entrant_traits_snapshot,
    v_opponent_user_id,
    v_opponent_character_id,
    v_opponent_snapshot_id,
    v_opponent_traits_snapshot,
    v_opponent_guidance,
    v_participant.rating,
    v_opponent_rating,
    v_rating_window,
    v_candidate_pool_size,
    v_expected_score,
    v_season.k_factor,
    v_season.rules_version,
    v_engine_seed,
    'MATCHED',
    v_now
  );

  return battle_private.entry_response(p_request_id, v_user_id);
end;
$$;

revoke all on function public.battle_request_entry(uuid, uuid, text)
  from public, anon;
grant execute on function public.battle_request_entry(uuid, uuid, text)
  to authenticated;

comment on function public.battle_request_entry(uuid, uuid, text) is
  'Authenticated, idempotent, server-assigned entry. Rejects clients without a current trusted snapshot.';

create or replace function public.battle_get_engine_contract(p_battle_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_result jsonb;
begin
  if p_battle_id is null then
    raise exception 'battle_id is required' using errcode = '22023';
  end if;

  select pg_catalog.jsonb_build_object(
    'battleId', matches.battle_id,
    'serverSeed', matches.engine_seed,
    'requestedAtMillis', pg_catalog.floor(
      extract(epoch from matches.created_at) * 1000
    )::bigint,
    'user', pg_catalog.jsonb_build_object(
      'projectionId', entrant.snapshot_id,
      'displayName', entrant.display_name,
      'heroClass', entrant.hero_class,
      'level', entrant.level,
      'verifiedPower', entrant.effective_power,
      'build', entrant.snapshot_payload -> 'build',
      'guidance', entries.directive,
      'skills', entrant.snapshot_payload -> 'skills',
      'equipment', (
        select pg_catalog.jsonb_agg(
          item.value - 'verifiedPowerContribution' order by item.ordinality
        )
          from pg_catalog.jsonb_array_elements(entrant.snapshot_payload -> 'equipment')
            with ordinality as item(value, ordinality)
      ),
      'activeTraitIds', coalesce((
        select pg_catalog.jsonb_agg(
          trait.value ->> 'trait_code' order by trait.ordinality
        )
          from pg_catalog.jsonb_array_elements(matches.entrant_traits_snapshot)
            with ordinality as trait(value, ordinality)
      ), '[]'::jsonb),
      'snapshotVersion', entrant.snapshot_payload -> 'snapshotVersion',
      'issuedAtMillis', pg_catalog.floor(
        extract(epoch from entrant.verified_at) * 1000
      )::bigint
    ),
    'opponent', pg_catalog.jsonb_build_object(
      'projectionId', opponent.snapshot_id,
      'displayName', opponent.display_name,
      'heroClass', opponent.hero_class,
      'level', opponent.level,
      'verifiedPower', opponent.effective_power,
      'build', opponent.snapshot_payload -> 'build',
      'guidance', matches.opponent_guidance,
      'skills', opponent.snapshot_payload -> 'skills',
      'equipment', (
        select pg_catalog.jsonb_agg(
          item.value - 'verifiedPowerContribution' order by item.ordinality
        )
          from pg_catalog.jsonb_array_elements(opponent.snapshot_payload -> 'equipment')
            with ordinality as item(value, ordinality)
      ),
      'activeTraitIds', coalesce((
        select pg_catalog.jsonb_agg(
          trait.value ->> 'trait_code' order by trait.ordinality
        )
          from pg_catalog.jsonb_array_elements(matches.opponent_traits_snapshot)
            with ordinality as trait(value, ordinality)
      ), '[]'::jsonb),
      'snapshotVersion', opponent.snapshot_payload -> 'snapshotVersion',
      'issuedAtMillis', pg_catalog.floor(
        extract(epoch from opponent.verified_at) * 1000
      )::bigint
    ),
    'opponentReferenceScore', matches.opponent_rating_reference,
    'rules', pg_catalog.jsonb_build_object(
      'rulesVersion', matches.rules_version,
      'growthReferencePower', seasons.growth_reference_power,
      'maxRounds', seasons.max_rounds
    )
  )
    into v_result
    from battle_private.matches as matches
    join public.battle_entries as entries
      on entries.entry_id = matches.entry_id
    join public.battle_seasons as seasons
      on seasons.season_id = matches.season_id
    join battle_private.projection_snapshots as entrant
      on entrant.snapshot_id = matches.entrant_snapshot_id
    join battle_private.projection_snapshots as opponent
      on opponent.snapshot_id = matches.opponent_snapshot_id
   where matches.battle_id = p_battle_id
     and matches.status in ('MATCHED','SETTLED');

  if v_result is null then
    raise exception 'battle not found' using errcode = 'P0002';
  end if;
  return v_result;
end;
$$;

revoke all on function public.battle_get_engine_contract(uuid)
  from public, anon, authenticated;
grant execute on function public.battle_get_engine_contract(uuid)
  to service_role;

comment on function public.battle_get_engine_contract(uuid) is
  'Service-only immutable-input contract for the deterministic Battle engine.';

create or replace function public.battle_get_entry(p_entry_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_result jsonb;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_entry_id is null then
    raise exception 'entry_id is required' using errcode = '22023';
  end if;

  v_result := battle_private.entry_response(p_entry_id, v_user_id);
  if v_result is null then
    raise exception 'battle entry not found' using errcode = 'P0002';
  end if;
  return v_result;
end;
$$;

revoke all on function public.battle_get_entry(uuid) from public, anon;
grant execute on function public.battle_get_entry(uuid) to authenticated;

alter table battle_private.matches
  add constraint battle_matches_trait_code_fkey
  foreign key (trait_code) references public.battle_trait_catalog(trait_code);

create or replace function battle_private.apply_trait_evidence(
  p_season_id uuid,
  p_user_id uuid,
  p_character_id uuid,
  p_battle_id uuid,
  p_trait_code text,
  p_evidence_strength integer
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_slot smallint;
  v_candidate_slot smallint;
  v_existing_candidate_id uuid;
  v_weak_candidate_id uuid;
  v_weak_strength integer;
  v_season_ends_at timestamptz;
  v_now timestamptz;
begin
  if p_trait_code is null then
    return 'NONE';
  end if;

  if p_evidence_strength is null
     or p_evidence_strength not between 1 and 1000
     or not exists (
       select 1
         from public.battle_trait_catalog
        where trait_code = p_trait_code
          and enabled
     ) then
    raise exception 'invalid battle trait evidence' using errcode = '22023';
  end if;

  -- Complete the logical one-hour transition before reinforcement,
  -- incompatibility, or slot selection sees the active set.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-trait-maintenance', 0)
  );
  v_now := pg_catalog.clock_timestamp();
  perform battle_private.complete_due_trait_removals(
    1,
    p_user_id,
    p_character_id,
    v_now
  );
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'battle-trait:' || p_user_id::text || ':' || p_character_id::text,
      0
    )
  );

  if exists (
    select 1
      from public.battle_character_traits as active
      join public.battle_trait_catalog as active_catalog
        on active_catalog.trait_code = active.trait_code
      join public.battle_trait_catalog as candidate_catalog
        on candidate_catalog.trait_code = p_trait_code
     where active.user_id = p_user_id
       and active.character_id = p_character_id
       and not exists (
         select 1
           from public.battle_trait_removals as due_removal
          where due_removal.user_id = active.user_id
            and due_removal.character_id = active.character_id
            and due_removal.trait_code = active.trait_code
            and battle_private.is_due_trait_removal(
              due_removal.status,
              due_removal.completes_at,
              v_now
            )
       )
       and (
         active.trait_code = any (candidate_catalog.incompatible_trait_codes)
         or p_trait_code = any (active_catalog.incompatible_trait_codes)
       )
  ) then
    return 'INCOMPATIBLE';
  end if;

  update public.battle_character_traits
     set evidence_count = evidence_count + 1,
         evidence_strength = greatest(evidence_strength, p_evidence_strength),
         last_reinforced_at = v_now
   where user_id = p_user_id
     and character_id = p_character_id
     and trait_code = p_trait_code
     and not exists (
       select 1
         from public.battle_trait_removals as due_removal
        where due_removal.user_id = public.battle_character_traits.user_id
          and due_removal.character_id = public.battle_character_traits.character_id
          and due_removal.trait_code = public.battle_character_traits.trait_code
          and battle_private.is_due_trait_removal(
            due_removal.status,
            due_removal.completes_at,
            v_now
          )
     );

  if found then
    return 'REINFORCED';
  end if;

  select available.slot_number::smallint
    into v_slot
    from pg_catalog.generate_series(1, 5) as available(slot_number)
   where not exists (
     select 1
       from public.battle_character_traits as active
      where active.user_id = p_user_id
        and active.character_id = p_character_id
        and active.slot_number = available.slot_number
        and not exists (
          select 1
            from public.battle_trait_removals as due_removal
           where due_removal.user_id = active.user_id
             and due_removal.character_id = active.character_id
             and due_removal.trait_code = active.trait_code
             and battle_private.is_due_trait_removal(
               due_removal.status,
               due_removal.completes_at,
               v_now
             )
        )
   )
   order by available.slot_number
   limit 1;

  if v_slot is not null then
    insert into public.battle_character_traits (
      user_id,
      character_id,
      trait_code,
      slot_number,
      evidence_strength,
      source_battle_id
    ) values (
      p_user_id,
      p_character_id,
      p_trait_code,
      v_slot,
      p_evidence_strength,
      p_battle_id
    );
    return 'ACTIVATED';
  end if;

  update public.battle_trait_candidates
     set evidence_count = evidence_count + 1,
         evidence_strength = greatest(evidence_strength, p_evidence_strength),
         last_reinforced_at = v_now,
         source_battle_id = p_battle_id
   where user_id = p_user_id
     and character_id = p_character_id
     and trait_code = p_trait_code
     and status = 'PENDING'
     and expires_at > v_now
  returning candidate_id into v_existing_candidate_id;

  if v_existing_candidate_id is not null then
    return 'CANDIDATE_REINFORCED';
  end if;

  select seasons.ends_at
    into v_season_ends_at
    from public.battle_seasons as seasons
   where seasons.season_id = p_season_id;

  select available.candidate_slot::smallint
    into v_candidate_slot
    from pg_catalog.generate_series(1, 3) as available(candidate_slot)
   where not exists (
     select 1
       from public.battle_trait_candidates as candidate
      where candidate.user_id = p_user_id
        and candidate.character_id = p_character_id
        and candidate.candidate_slot = available.candidate_slot
        and candidate.status = 'PENDING'
        and candidate.expires_at > v_now
   )
   order by available.candidate_slot
   limit 1;

  if v_candidate_slot is null then
    select candidate_id, evidence_strength
      into v_weak_candidate_id, v_weak_strength
      from public.battle_trait_candidates
     where user_id = p_user_id
       and character_id = p_character_id
       and status = 'PENDING'
       and expires_at > v_now
     order by evidence_strength, last_reinforced_at, candidate_id
     limit 1
     for update;

    if p_evidence_strength <= v_weak_strength then
      return 'CANDIDATE_IGNORED';
    end if;

    update public.battle_trait_candidates
       set status = 'DISCARDED',
           resolved_at = v_now
     where candidate_id = v_weak_candidate_id
    returning candidate_slot into v_candidate_slot;
  end if;

  insert into public.battle_trait_candidates (
    season_id,
    user_id,
    character_id,
    trait_code,
    candidate_slot,
    evidence_strength,
    source_battle_id,
    expires_at
  ) values (
    p_season_id,
    p_user_id,
    p_character_id,
    p_trait_code,
    v_candidate_slot,
    p_evidence_strength,
    p_battle_id,
    v_season_ends_at
  );

  return 'CANDIDATE_ADDED';
end;
$$;

revoke all on function battle_private.apply_trait_evidence(
  uuid, uuid, uuid, uuid, text, integer
) from public, anon, authenticated, service_role;

create or replace function public.battle_settle_match(
  p_battle_id uuid,
  p_rules_version integer,
  p_outcome text,
  p_engine_result jsonb,
  p_trait_code text default null,
  p_trait_evidence_strength integer default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_match battle_private.matches%rowtype;
  v_entry public.battle_entries%rowtype;
  v_season public.battle_seasons%rowtype;
  v_season_id uuid;
  v_opponent battle_private.projection_snapshots%rowtype;
  v_result_score numeric;
  v_delta integer;
  v_rating_after integer;
  v_changed integer;
  v_now timestamptz;
begin
  if p_battle_id is null then
    raise exception 'battle_id is required' using errcode = '22023';
  end if;

  select matches.season_id
    into v_season_id
    from battle_private.matches as matches
   where matches.battle_id = p_battle_id;
  if not found then
    raise exception 'battle not found' using errcode = 'P0002';
  end if;

  -- Lifecycle transitions always lock season -> match -> entry. The wall clock
  -- is sampled after the season and match locks, which makes settlement versus
  -- lock/expiry have one serial winner at the exact boundary.
  select *
    into v_season
    from public.battle_seasons
   where season_id = v_season_id
   for update;

  select *
    into v_match
    from battle_private.matches
   where battle_id = p_battle_id
   for update;

  v_now := pg_catalog.clock_timestamp();

  -- A scheduled removal is logically complete at its one-hour deadline even
  -- when this settlement carries no new trait evidence, is an idempotent
  -- retry, or is about to become VOID at a season boundary. Complete it before
  -- any settlement branch can observe the entrant's active trait set.
  perform battle_private.complete_due_trait_removals(
    1,
    v_match.entrant_user_id,
    v_match.entrant_character_id,
    v_now
  );

  if v_match.status = 'SETTLED' then
    if v_match.rules_version <> p_rules_version
       or v_match.outcome <> p_outcome
       or v_match.engine_result <> p_engine_result
       or v_match.trait_code is distinct from p_trait_code
       or v_match.trait_evidence_strength is distinct from p_trait_evidence_strength then
      raise exception 'battle settlement idempotency conflict' using errcode = '23505';
    end if;
    return battle_private.entry_response(v_match.entry_id, v_match.entrant_user_id);
  end if;

  if v_match.status = 'VOID' then
    return battle_private.entry_response(v_match.entry_id, v_match.entrant_user_id);
  end if;

  if v_match.status <> 'MATCHED' then
    raise exception 'battle is not awaiting settlement' using errcode = '55000';
  end if;

  if v_season.ends_at <= v_now then
    -- Do not recover only the touched match. The first post-boundary writer
    -- atomically materializes the whole season boundary, including every open
    -- match and its audit, under the season lock already held here.
    perform battle_private.finalize_season(
      v_season.season_id,
      v_now,
      false,
      null
    );
    return battle_private.entry_response(v_match.entry_id, v_match.entrant_user_id);
  end if;

  if v_season.locked_at is not null then
    perform battle_private.recover_match(
      battle_private.system_recovery_id(p_battle_id, 'EXPIRED'),
      p_battle_id,
      'EXPIRED',
      'SETTLEMENT_GUARD',
      'season ended or locked before settlement',
      v_now
    );
    return battle_private.entry_response(v_match.entry_id, v_match.entrant_user_id);
  end if;

  if v_season.starts_at > v_now then
    raise exception 'battle season has not started' using errcode = '55000';
  end if;

  if p_rules_version is distinct from 1
     or p_outcome is null
     or p_outcome not in ('USER_WIN','DRAW','USER_LOSS')
     or pg_catalog.jsonb_typeof(p_engine_result) is distinct from 'object'
     or battle_private.contains_forbidden_economy_key(p_engine_result)
     or v_match.rules_version <> p_rules_version
     or not battle_private.is_valid_engine_result(
       p_engine_result,
       v_match.battle_id,
       p_outcome
     ) then
    raise exception 'battle result does not match the issued contract' using errcode = '22023';
  end if;

  if p_trait_code is null and p_trait_evidence_strength is not null then
    raise exception 'trait strength requires a trait code' using errcode = '22023';
  end if;
  if p_trait_code is not null and (
    p_trait_evidence_strength is null
    or p_trait_evidence_strength not between 1 and 1000
  ) then
    raise exception 'trait evidence strength is required' using errcode = '22023';
  end if;

  select *
    into v_entry
    from public.battle_entries
   where entry_id = v_match.entry_id
   for update;

  if not found or v_entry.status <> 'MATCHED' then
    raise exception 'battle entry is not awaiting settlement' using errcode = '55000';
  end if;

  select *
    into v_opponent
    from battle_private.projection_snapshots
   where snapshot_id = v_match.opponent_snapshot_id;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      v_match.season_id::text || ':' || v_match.entrant_user_id::text || ':' ||
      v_match.entrant_character_id::text,
      0
    )
  );

  v_result_score := case p_outcome
    when 'USER_WIN' then 1::numeric
    when 'DRAW' then 0.5::numeric
    else 0::numeric
  end;
  -- Kotlin Double.roundToInt semantics: nearest integer, exact ties toward +infinity.
  v_delta := pg_catalog.floor(
    v_match.k_factor::numeric * (v_result_score - v_match.expected_score) + 0.5
  )::integer;
  v_rating_after := least(
    2147483647::bigint,
    greatest(
      0::bigint,
      v_match.entrant_rating_before::bigint + v_delta::bigint
    )
  )::integer;
  v_delta := v_rating_after - v_match.entrant_rating_before;

  -- Deliberately update only the entrant. The opponent projection is read-only
  -- evidence and receives no defensive rating or record mutation.
  update public.battle_participants
     set rating = v_rating_after,
         highest_rating = greatest(highest_rating, v_rating_after),
         matches_played = matches_played + 1,
         wins = wins + case when p_outcome = 'USER_WIN' then 1 else 0 end,
         draws = draws + case when p_outcome = 'DRAW' then 1 else 0 end,
         losses = losses + case when p_outcome = 'USER_LOSS' then 1 else 0 end,
         rating_achieved_at = case
           when v_delta <> 0 then v_now
           else rating_achieved_at
         end,
         updated_at = v_now
   where season_id = v_match.season_id
     and user_id = v_match.entrant_user_id
     and character_id = v_match.entrant_character_id
     and rating = v_match.entrant_rating_before;

  get diagnostics v_changed = row_count;
  if v_changed <> 1 then
    raise exception 'entrant rating changed after match assignment' using errcode = '40001';
  end if;

  update battle_private.matches
     set status = 'SETTLED',
         outcome = p_outcome,
         score_delta = v_delta,
         entrant_rating_after = v_rating_after,
         engine_result = p_engine_result,
         trait_code = p_trait_code,
         trait_evidence_strength = p_trait_evidence_strength,
         settled_at = v_now
   where battle_id = p_battle_id;

  insert into public.battle_records (
    battle_id,
    season_id,
    user_id,
    character_id,
    opponent_user_id,
    opponent_character_id,
    opponent_display_name,
    opponent_hero_class,
    opponent_level,
    opponent_rating_reference,
    directive,
    outcome,
    rating_before,
    score_delta,
    rating_after,
    rules_version,
    engine_result,
    trait_code,
    trait_evidence_strength,
    created_at,
    settled_at
  ) values (
    p_battle_id,
    v_match.season_id,
    v_match.entrant_user_id,
    v_match.entrant_character_id,
    v_match.opponent_user_id,
    v_match.opponent_character_id,
    v_opponent.display_name,
    v_opponent.hero_class,
    v_opponent.level,
    v_match.opponent_rating_reference,
    v_entry.directive,
    p_outcome,
    v_match.entrant_rating_before,
    v_delta,
    v_rating_after,
    p_rules_version,
    p_engine_result,
    p_trait_code,
    p_trait_evidence_strength,
    v_match.created_at,
    v_now
  );

  update public.battle_entries
     set status = 'SETTLED',
         settled_at = v_now
   where entry_id = v_match.entry_id;

  if p_trait_code is not null then
    perform battle_private.apply_trait_evidence(
      v_match.season_id,
      v_match.entrant_user_id,
      v_match.entrant_character_id,
      p_battle_id,
      p_trait_code,
      p_trait_evidence_strength
    );
  end if;

  -- Public history retains the latest 100 official battles per character.
  -- Private match rows remain available for short-window anti-repeat auditing.
  delete from public.battle_records as old_records
   where old_records.battle_id in (
     select records.battle_id
       from public.battle_records as records
      where records.user_id = v_match.entrant_user_id
        and records.character_id = v_match.entrant_character_id
      order by records.settled_at desc, records.battle_id desc
      offset 100
   );

  return battle_private.entry_response(v_match.entry_id, v_match.entrant_user_id);
end;
$$;

revoke all on function public.battle_settle_match(
  uuid, integer, text, jsonb, text, integer
) from public, anon, authenticated;
grant execute on function public.battle_settle_match(
  uuid, integer, text, jsonb, text, integer
) to service_role;

comment on function public.battle_settle_match(
  uuid, integer, text, jsonb, text, integer
) is
  'Service-only idempotent K=24 settlement. Mutates only the entrant Battle rating and descriptive trait state.';

create or replace function public.battle_issue_narrative_phase_plan(
  p_battle_id uuid,
  p_phase_plan jsonb
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_match battle_private.matches%rowtype;
  v_existing battle_private.narrative_phase_contracts%rowtype;
  v_invoking_role text := coalesce(
    nullif(nullif(pg_catalog.current_setting('role', true), ''), 'none'),
    session_user::text
  );
  v_session_user text := session_user::text;
  v_auth_user_id uuid := auth.uid();
  v_now timestamptz := pg_catalog.clock_timestamp();
begin
  if v_invoking_role <> 'service_role' then
    raise exception 'service role required' using errcode = '42501';
  end if;
  if p_battle_id is null
     or pg_catalog.to_regrole(v_invoking_role) is null
     or pg_catalog.to_regrole(v_session_user) is null
     or not battle_private.is_valid_narrative_phase_plan(
       p_phase_plan,
       p_battle_id
     ) then
    raise exception 'invalid narrative phase plan' using errcode = '22023';
  end if;

  select *
    into v_match
    from battle_private.matches
   where battle_id = p_battle_id
   for update;
  if not found or v_match.status <> 'SETTLED' then
    raise exception 'settled battle not found' using errcode = 'P0002';
  end if;
  if v_match.narrative is not null then
    raise exception 'narrative is already attached' using errcode = '55000';
  end if;

  select *
    into v_existing
    from battle_private.narrative_phase_contracts
   where battle_id = p_battle_id;
  if found then
    if v_existing.phase_plan <> p_phase_plan then
      raise exception 'narrative phase plan idempotency conflict' using errcode = '23505';
    end if;
    return false;
  end if;

  insert into battle_private.narrative_phase_contracts (
    battle_id,
    phase_plan,
    issuer_database_role,
    issuer_session_user,
    issuer_auth_user_id,
    issued_at
  ) values (
    p_battle_id,
    p_phase_plan,
    v_invoking_role,
    v_session_user,
    v_auth_user_id,
    v_now
  );
  return true;
end;
$$;

revoke all on function public.battle_issue_narrative_phase_plan(uuid, jsonb)
  from public, anon, authenticated;
grant execute on function public.battle_issue_narrative_phase_plan(uuid, jsonb)
  to service_role;

comment on function public.battle_issue_narrative_phase_plan(uuid, jsonb) is
  'Service-only immutable phase contract. Each P1..Pn freezes its actor and exact issued allowed_refs before Qwen narration is accepted.';

create or replace function public.battle_attach_narrative(
  p_battle_id uuid,
  p_narrative jsonb
)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_match battle_private.matches%rowtype;
begin
  if p_battle_id is null
     or not battle_private.is_valid_narrative_payload(p_narrative, p_battle_id) then
    raise exception 'invalid battle narrative' using errcode = '22023';
  end if;

  select *
    into v_match
    from battle_private.matches
   where battle_id = p_battle_id
   for update;

  if not found or v_match.status <> 'SETTLED' then
    raise exception 'settled battle not found' using errcode = 'P0002';
  end if;

  if v_match.narrative is not null then
    if v_match.narrative <> p_narrative then
      raise exception 'battle narrative idempotency conflict' using errcode = '23505';
    end if;
    return false;
  end if;

  update battle_private.matches
     set narrative = p_narrative,
         narrative_attached_at = pg_catalog.now()
   where battle_id = p_battle_id;

  update public.battle_records
     set narrative = p_narrative,
         narrative_attached_at = pg_catalog.now()
   where battle_id = p_battle_id;

  return true;
end;
$$;

revoke all on function public.battle_attach_narrative(uuid, jsonb)
  from public, anon, authenticated;
grant execute on function public.battle_attach_narrative(uuid, jsonb)
  to service_role;

create or replace function battle_private.complete_due_trait_removals(
  p_limit integer default 500,
  p_user_id uuid default null,
  p_character_id uuid default null,
  p_as_of timestamptz default null
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_removal public.battle_trait_removals%rowtype;
  v_candidate public.battle_trait_candidates%rowtype;
  v_slot smallint;
  v_completed integer := 0;
  v_now timestamptz := coalesce(p_as_of, pg_catalog.clock_timestamp());
begin
  if p_limit is null or p_limit not between 1 and 5000 then
    raise exception 'invalid removal completion limit' using errcode = '22023';
  end if;

  -- All paths that can change active traits or pending candidates take this
  -- transaction lock first. It prevents cron/request/settlement lock-order
  -- inversion around the exact removal deadline.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-trait-maintenance', 0)
  );

  update public.battle_trait_candidates
     set status = 'EXPIRED',
         resolved_at = v_now
   where status = 'PENDING'
     and expires_at <= v_now
     and (p_user_id is null or user_id = p_user_id)
     and (p_character_id is null or character_id = p_character_id);

  for v_removal in
    select removals.*
     from public.battle_trait_removals as removals
     where removals.status = 'PENDING'
       and removals.completes_at <= v_now
       and (p_user_id is null or removals.user_id = p_user_id)
       and (p_character_id is null or removals.character_id = p_character_id)
     order by removals.completes_at, removals.removal_id
     limit p_limit
     for update skip locked
  loop
    perform pg_catalog.pg_advisory_xact_lock(
      pg_catalog.hashtextextended(
        'battle-trait:' || v_removal.user_id::text || ':' || v_removal.character_id::text,
        0
      )
    );

    v_slot := null;
    select active.slot_number
      into v_slot
      from public.battle_character_traits as active
     where active.user_id = v_removal.user_id
       and active.character_id = v_removal.character_id
       and active.trait_code = v_removal.trait_code
     for update;

    if found then
      delete from public.battle_character_traits
       where user_id = v_removal.user_id
         and character_id = v_removal.character_id
         and trait_code = v_removal.trait_code;
    else
      select available.slot_number::smallint
        into v_slot
        from pg_catalog.generate_series(1, 5) as available(slot_number)
       where not exists (
         select 1
           from public.battle_character_traits as active
          where active.user_id = v_removal.user_id
            and active.character_id = v_removal.character_id
            and active.slot_number = available.slot_number
       )
       order by available.slot_number
       limit 1;
    end if;

    if v_removal.replacement_candidate_id is not null and v_slot is not null then
      select *
        into v_candidate
        from public.battle_trait_candidates
       where candidate_id = v_removal.replacement_candidate_id
         and user_id = v_removal.user_id
         and character_id = v_removal.character_id
         and status = 'PENDING'
         and expires_at > v_now
       for update;

      if found and not exists (
        select 1
          from public.battle_character_traits as active
          join public.battle_trait_catalog as active_catalog
            on active_catalog.trait_code = active.trait_code
          join public.battle_trait_catalog as candidate_catalog
            on candidate_catalog.trait_code = v_candidate.trait_code
         where active.user_id = v_removal.user_id
           and active.character_id = v_removal.character_id
           and (
             active.trait_code = any (candidate_catalog.incompatible_trait_codes)
             or v_candidate.trait_code = any (active_catalog.incompatible_trait_codes)
           )
      ) then
        insert into public.battle_character_traits (
          user_id,
          character_id,
          trait_code,
          slot_number,
          evidence_count,
          evidence_strength,
          source_battle_id,
          first_earned_at,
          last_reinforced_at
        ) values (
          v_candidate.user_id,
          v_candidate.character_id,
          v_candidate.trait_code,
          v_slot,
          v_candidate.evidence_count,
          v_candidate.evidence_strength,
          v_candidate.source_battle_id,
          v_candidate.first_earned_at,
          v_candidate.last_reinforced_at
        )
        on conflict (user_id, character_id, trait_code) do update
          set evidence_count = public.battle_character_traits.evidence_count
              + excluded.evidence_count,
              evidence_strength = greatest(
                public.battle_character_traits.evidence_strength,
                excluded.evidence_strength
              ),
              last_reinforced_at = greatest(
                public.battle_character_traits.last_reinforced_at,
                excluded.last_reinforced_at
              );

        update public.battle_trait_candidates
           set status = 'PROMOTED',
               resolved_at = v_now
         where candidate_id = v_candidate.candidate_id;
      end if;
    end if;

    update public.battle_trait_removals
       set status = 'COMPLETED',
           completed_at = v_now
     where removal_id = v_removal.removal_id;

    v_completed := v_completed + 1;
  end loop;

  return v_completed;
end;
$$;

revoke all on function battle_private.complete_due_trait_removals(
  integer, uuid, uuid, timestamptz
)
  from public, anon, authenticated, service_role;

create or replace function public.battle_request_trait_removal(
  p_character_id uuid,
  p_trait_code text,
  p_replacement_candidate_id uuid default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_now timestamptz;
  v_existing public.battle_trait_removals%rowtype;
  v_removal_id uuid;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_character_id is null or p_trait_code is null then
    raise exception 'character_id and trait_code are required' using errcode = '22023';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-trait-maintenance', 0)
  );
  v_now := pg_catalog.clock_timestamp();
  perform battle_private.complete_due_trait_removals(
    1,
    v_user_id,
    p_character_id,
    v_now
  );
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'battle-trait:' || v_user_id::text || ':' || p_character_id::text,
      0
    )
  );

  if not exists (
    select 1
      from public.battle_character_traits
     where user_id = v_user_id
       and character_id = p_character_id
       and trait_code = p_trait_code
  ) then
    raise exception 'active battle trait not found' using errcode = 'P0002';
  end if;

  if p_replacement_candidate_id is not null and not exists (
    select 1
      from public.battle_trait_candidates
     where candidate_id = p_replacement_candidate_id
       and user_id = v_user_id
       and character_id = p_character_id
       and status = 'PENDING'
       and expires_at > v_now
  ) then
    raise exception 'replacement trait candidate not found' using errcode = 'P0002';
  end if;

  select *
    into v_existing
    from public.battle_trait_removals
   where user_id = v_user_id
     and character_id = p_character_id
     and status = 'PENDING'
   for update;

  if found then
    if v_existing.trait_code <> p_trait_code
       or v_existing.replacement_candidate_id is distinct from p_replacement_candidate_id then
      raise exception 'another battle trait removal is already pending' using errcode = '55000';
    end if;
    return pg_catalog.jsonb_build_object(
      'removal_id', v_existing.removal_id,
      'trait_code', v_existing.trait_code,
      'status', v_existing.status,
      'requested_at', v_existing.requested_at,
      'completes_at', v_existing.completes_at,
      'replacement_candidate_id', v_existing.replacement_candidate_id
    );
  end if;

  insert into public.battle_trait_removals (
    user_id,
    character_id,
    trait_code,
    replacement_candidate_id,
    requested_at,
    completes_at
  ) values (
    v_user_id,
    p_character_id,
    p_trait_code,
    p_replacement_candidate_id,
    v_now,
    v_now + interval '1 hour'
  )
  returning removal_id into v_removal_id;

  return pg_catalog.jsonb_build_object(
    'removal_id', v_removal_id,
    'trait_code', p_trait_code,
    'status', 'PENDING',
    'requested_at', v_now,
    'completes_at', v_now + interval '1 hour',
    'replacement_candidate_id', p_replacement_candidate_id
  );
end;
$$;

revoke all on function public.battle_request_trait_removal(uuid, text, uuid)
  from public, anon;
grant execute on function public.battle_request_trait_removal(uuid, text, uuid)
  to authenticated;

create or replace function public.battle_cancel_trait_removal(p_removal_id uuid)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_changed integer;
  v_now timestamptz;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_removal_id is null then
    raise exception 'removal_id is required' using errcode = '22023';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-trait-maintenance', 0)
  );
  v_now := pg_catalog.clock_timestamp();

  update public.battle_trait_removals
     set status = 'CANCELLED',
         cancelled_at = v_now
   where removal_id = p_removal_id
     and user_id = v_user_id
     and status = 'PENDING'
     and completes_at > v_now;

  get diagnostics v_changed = row_count;
  return v_changed > 0;
end;
$$;

revoke all on function public.battle_cancel_trait_removal(uuid) from public, anon;
grant execute on function public.battle_cancel_trait_removal(uuid) to authenticated;

create extension if not exists pg_cron with schema pg_catalog;

select cron.unschedule(jobid)
  from cron.job
 where jobname = 'alarmquest-battle-trait-removals';

select cron.schedule(
  'alarmquest-battle-trait-removals',
  '* * * * *',
  'select battle_private.complete_due_trait_removals(500, null, null, null)'
);

select cron.unschedule(jobid)
  from cron.job
 where jobname in (
   'alarmquest-battle-expired-matches',
   'alarmquest-battle-season-boundaries'
 );

select cron.schedule(
  'alarmquest-battle-season-boundaries',
  '* * * * *',
  'select battle_private.finalize_due_seasons(null)'
);

create or replace function public.battle_get_state(p_character_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_now timestamptz := pg_catalog.clock_timestamp();
  v_entry_day date;
  v_season public.battle_seasons%rowtype;
  v_result jsonb;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_character_id is null then
    raise exception 'character_id is required' using errcode = '22023';
  end if;

  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('battle-trait-maintenance', 0)
  );
  v_now := pg_catalog.clock_timestamp();
  -- Derive every day-boundary decision from the same captured server instant.
  v_entry_day := battle_private.korea_day(v_now);
  perform battle_private.complete_due_trait_removals(
    1,
    v_user_id,
    p_character_id,
    v_now
  );

  select *
    into v_season
    from battle_private.active_season(v_now);

  if not found then
    select *
      into v_season
      from public.battle_seasons
     order by starts_at desc
     limit 1;
  end if;

  if v_season.season_id is null then
    return pg_catalog.jsonb_build_object(
      'server_time', v_now,
      'day_timezone', 'Asia/Seoul',
      'season', null,
      'participant', null,
      'trusted_snapshot_ready', false,
      'traits', '[]'::jsonb,
      'candidates', '[]'::jsonb,
      'pending_removal', null
    );
  end if;

  select pg_catalog.jsonb_build_object(
    'server_time', v_now,
    'day_timezone', 'Asia/Seoul',
    'season', pg_catalog.jsonb_build_object(
      'season_id', v_season.season_id,
      'season_key', v_season.season_key,
      'starts_at', v_season.starts_at,
      'ends_at', v_season.ends_at,
      'rules_version', v_season.rules_version,
      'daily_entry_limit', v_season.daily_entry_limit,
      'placement_match_count', v_season.placement_match_count,
      'k_factor', v_season.k_factor,
      'locked', v_season.locked_at is not null
    ),
    'participant', (
      select pg_catalog.jsonb_build_object(
        'placement_complete', participants.matches_played >= v_season.placement_match_count,
        'placement_matches_remaining', greatest(
          0,
          v_season.placement_match_count - participants.matches_played
        ),
        'rating', case
          when participants.matches_played >= v_season.placement_match_count
            then participants.rating
          else null
        end,
        'highest_rating', case
          when participants.matches_played >= v_season.placement_match_count
            then participants.highest_rating
          else null
        end,
        'matches', participants.matches_played,
        'wins', participants.wins,
        'draws', participants.draws,
        'losses', participants.losses,
        'entries_used_today', (
          select pg_catalog.count(*)
            from public.battle_entries as entries
           where entries.season_id = participants.season_id
             and entries.user_id = participants.user_id
             and entries.character_id = participants.character_id
             and entries.entry_day = v_entry_day
             and entries.status in ('MATCHED','SETTLED')
        ),
        'open_entry_id', (
          select entries.entry_id
            from public.battle_entries as entries
           where entries.season_id = participants.season_id
             and entries.user_id = participants.user_id
             and entries.character_id = participants.character_id
             and entries.status = 'MATCHED'
           limit 1
        )
      )
        from public.battle_participants as participants
       where participants.season_id = v_season.season_id
         and participants.user_id = v_user_id
         and participants.character_id = p_character_id
    ),
    'trusted_snapshot_ready', exists (
      select 1
        from battle_private.projection_snapshots as snapshots
       where snapshots.user_id = v_user_id
         and snapshots.character_id = p_character_id
         and snapshots.rules_version = v_season.rules_version
         and snapshots.snapshot_source = 'SERVER_VERIFIED'
         and snapshots.revoked_at is null
         and snapshots.expires_at > v_now
    ),
    'traits', coalesce((
      select pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
          'trait_code', active.trait_code,
          'label_key', catalog.label_key,
          'category', catalog.category,
          'slot', active.slot_number,
          'evidence_count', active.evidence_count,
          'first_earned_at', active.first_earned_at,
          'last_reinforced_at', active.last_reinforced_at
        ) order by active.slot_number
      )
        from public.battle_character_traits as active
        join public.battle_trait_catalog as catalog
          on catalog.trait_code = active.trait_code
       where active.user_id = v_user_id
         and active.character_id = p_character_id
         and not exists (
           select 1
             from public.battle_trait_removals as due_removal
            where due_removal.user_id = active.user_id
              and due_removal.character_id = active.character_id
              and due_removal.trait_code = active.trait_code
              and battle_private.is_due_trait_removal(
                due_removal.status,
                due_removal.completes_at,
                v_now
              )
         )
    ), '[]'::jsonb),
    'candidates', coalesce((
      select pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
          'candidate_id', candidate.candidate_id,
          'trait_code', candidate.trait_code,
          'label_key', catalog.label_key,
          'category', catalog.category,
          'slot', candidate.candidate_slot,
          'evidence_count', candidate.evidence_count,
          'first_earned_at', candidate.first_earned_at,
          'expires_at', candidate.expires_at
        ) order by candidate.candidate_slot
      )
        from public.battle_trait_candidates as candidate
        join public.battle_trait_catalog as catalog
          on catalog.trait_code = candidate.trait_code
       where candidate.user_id = v_user_id
         and candidate.character_id = p_character_id
         and candidate.status = 'PENDING'
         and candidate.expires_at > v_now
    ), '[]'::jsonb),
    'pending_removal', (
      select pg_catalog.jsonb_build_object(
        'removal_id', removal.removal_id,
        'trait_code', removal.trait_code,
        'replacement_candidate_id', removal.replacement_candidate_id,
        'requested_at', removal.requested_at,
        'completes_at', removal.completes_at
      )
        from public.battle_trait_removals as removal
       where removal.user_id = v_user_id
         and removal.character_id = p_character_id
         and removal.status = 'PENDING'
       limit 1
    )
  ) into v_result;

  return v_result;
end;
$$;

revoke all on function public.battle_get_state(uuid) from public, anon;
grant execute on function public.battle_get_state(uuid) to authenticated;

create or replace function public.battle_get_leaderboard(
  p_season_id uuid default null,
  p_limit integer default 100
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_season public.battle_seasons%rowtype;
  v_result jsonb;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_limit is null or p_limit not between 1 and 1000 then
    raise exception 'leaderboard limit must be between 1 and 1000' using errcode = '22023';
  end if;

  if p_season_id is null then
    select *
      into v_season
      from battle_private.active_season(pg_catalog.now());
    if not found then
      select *
        into v_season
        from public.battle_seasons
       order by starts_at desc
       limit 1;
    end if;
  else
    select *
      into v_season
      from public.battle_seasons
     where season_id = p_season_id;
  end if;

  if v_season.season_id is null then
    return pg_catalog.jsonb_build_object(
      'season', null,
      'total_participants', 0,
      'entries', '[]'::jsonb,
      'me', null
    );
  end if;

  with ranked as (
    select pg_catalog.row_number() over (
             order by participants.rating desc,
                      participants.wins desc,
                      participants.losses,
                      participants.rating_achieved_at,
                      participants.character_id
           ) as rank_number,
           participants.user_id,
           participants.character_id,
           participants.rating,
           participants.highest_rating,
           participants.matches_played,
           participants.wins,
           participants.draws,
           participants.losses,
           snapshots.display_name,
           snapshots.hero_class,
           snapshots.level,
           pg_catalog.count(*) over ()::integer as total_participants
      from public.battle_participants as participants
      join battle_private.projection_snapshots as snapshots
        on snapshots.snapshot_id = participants.snapshot_id
     where participants.season_id = v_season.season_id
       and participants.matches_played >= v_season.placement_match_count
  ),
  visible as (
    select * from ranked where rank_number <= p_limit
  )
  select pg_catalog.jsonb_build_object(
    'season', pg_catalog.jsonb_build_object(
      'season_id', v_season.season_id,
      'season_key', v_season.season_key,
      'starts_at', v_season.starts_at,
      'ends_at', v_season.ends_at,
      'rules_version', v_season.rules_version,
      'placement_match_count', v_season.placement_match_count,
      'locked', v_season.locked_at is not null
    ),
    'total_participants', coalesce((
      select pg_catalog.max(total_participants) from ranked
    ), 0),
    'entries', coalesce((
      select pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
          'rank', rank_number,
          'character_id', character_id,
          'display_name', display_name,
          'hero_class', hero_class,
          'level', level,
          'rating', rating,
          'highest_rating', highest_rating,
          'matches', matches_played,
          'wins', wins,
          'draws', draws,
          'losses', losses
        ) order by rank_number
      ) from visible
    ), '[]'::jsonb),
    'me', (
      select pg_catalog.jsonb_build_object(
        'rank', rank_number,
        'character_id', character_id,
        'display_name', display_name,
        'hero_class', hero_class,
        'level', level,
        'rating', rating,
        'highest_rating', highest_rating,
        'matches', matches_played,
        'wins', wins,
        'draws', draws,
        'losses', losses
      )
        from ranked
       where user_id = v_user_id
       order by rank_number
       limit 1
    )
  ) into v_result;

  return v_result;
end;
$$;

revoke all on function public.battle_get_leaderboard(uuid, integer)
  from public, anon;
grant execute on function public.battle_get_leaderboard(uuid, integer)
  to authenticated;

create or replace function public.battle_get_records(
  p_character_id uuid,
  p_limit integer default 50,
  p_before timestamptz default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_result jsonb;
begin
  if v_user_id is null then
    raise exception 'authentication required' using errcode = '42501';
  end if;
  if p_character_id is null
     or p_limit is null
     or p_limit not between 1 and 100 then
    raise exception 'invalid battle record query' using errcode = '22023';
  end if;

  select coalesce(pg_catalog.jsonb_agg(
    pg_catalog.jsonb_build_object(
      'battle_id', history.battle_id,
      'season_id', history.season_id,
      'opponent_character_id', history.opponent_character_id,
      'opponent_display_name', history.opponent_display_name,
      'opponent_hero_class', history.opponent_hero_class,
      'opponent_level', history.opponent_level,
      'opponent_rating', case
        when history.opponent_placement_complete then history.opponent_rating_reference
        else null
      end,
      'directive', history.directive,
      'outcome', history.outcome,
      'placement_complete', history.placement_complete,
      'rating_before', case when history.placement_complete then history.rating_before else null end,
      'score_delta', case when history.placement_complete then history.score_delta else null end,
      'rating_after', case when history.placement_complete then history.rating_after else null end,
      'rules_version', history.rules_version,
      'engine_result', battle_private.public_engine_result(
        history.engine_result,
        history.battle_id,
        history.outcome
      ),
      'narrative', history.narrative,
      'trait_code', history.trait_code,
      'created_at', history.created_at,
      'settled_at', history.settled_at
    ) order by history.settled_at desc, history.battle_id
  ), '[]'::jsonb)
    into v_result
    from (
      select records.*,
             participants.matches_played >= seasons.placement_match_count as placement_complete,
             opponent_participants.matches_played >= seasons.placement_match_count
               as opponent_placement_complete
        from public.battle_records as records
        join public.battle_seasons as seasons
          on seasons.season_id = records.season_id
        join public.battle_participants as participants
          on participants.season_id = records.season_id
         and participants.user_id = records.user_id
         and participants.character_id = records.character_id
        join public.battle_participants as opponent_participants
          on opponent_participants.season_id = records.season_id
         and opponent_participants.user_id = records.opponent_user_id
         and opponent_participants.character_id = records.opponent_character_id
       where records.user_id = v_user_id
         and records.character_id = p_character_id
         and (p_before is null or records.settled_at < p_before)
       order by records.settled_at desc, records.battle_id
       limit p_limit
    ) as history;

  return v_result;
end;
$$;

revoke all on function public.battle_get_records(uuid, integer, timestamptz)
  from public, anon;
grant execute on function public.battle_get_records(uuid, integer, timestamptz)
  to authenticated;

-- Migration-time contract checks. These contain no user data and make the
-- Android-shared 100-code range, engine identity, matching window, leaderboard
-- tie-break, trusted snapshot, and economy isolation fail closed if edited.
do $$
declare
  v_sample jsonb := pg_catalog.jsonb_build_object(
    'build', pg_catalog.jsonb_build_object(
      'strength', 10,
      'constitution', 10,
      'dexterity', 10,
      'intelligence', 10,
      'wisdom', 10,
      'charisma', 10
    ),
    'skills', pg_catalog.jsonb_build_array(
      pg_catalog.jsonb_build_object(
        'skillId', 'warrior.sample',
        'displayName', 'Sample Strike',
        'kind', 'STRIKE',
        'powerBasisPoints', 12000,
        'cooldownRounds', 2,
        'masteryLevel', 1
      )
    ),
    'equipment', pg_catalog.jsonb_build_array(
      pg_catalog.jsonb_build_object('itemId', 'sample.weapon', 'displayName', 'Weapon', 'slot', 'WEAPON', 'rarity', 'COMMON', 'narrativeTag', '', 'verifiedPowerContribution', 1),
      pg_catalog.jsonb_build_object('itemId', 'sample.head', 'displayName', 'Head', 'slot', 'HEAD', 'rarity', 'COMMON', 'narrativeTag', '', 'verifiedPowerContribution', 1),
      pg_catalog.jsonb_build_object('itemId', 'sample.body', 'displayName', 'Body', 'slot', 'BODY', 'rarity', 'COMMON', 'narrativeTag', '', 'verifiedPowerContribution', 1),
      pg_catalog.jsonb_build_object('itemId', 'sample.hands', 'displayName', 'Hands', 'slot', 'HANDS', 'rarity', 'COMMON', 'narrativeTag', '', 'verifiedPowerContribution', 1),
      pg_catalog.jsonb_build_object('itemId', 'sample.feet', 'displayName', 'Feet', 'slot', 'FEET', 'rarity', 'COMMON', 'narrativeTag', '', 'verifiedPowerContribution', 1),
      pg_catalog.jsonb_build_object('itemId', 'sample.accessory', 'displayName', 'Accessory', 'slot', 'ACCESSORY', 'rarity', 'COMMON', 'narrativeTag', '', 'verifiedPowerContribution', 1)
    ),
    'snapshotVersion', 1
  );
  v_narrative jsonb := '{
    "encounter_id":"00000000-0000-0000-0000-000000000001",
    "scenes":[
      {"phase_id":"P1","title_ko":"개전","segments":[{"type":"TEXT","text":"첫 합이 열린다."}],"dialogue_ko":null,"effect_key":"CLASH"},
      {"phase_id":"P2","title_ko":"전환","segments":[{"type":"TEXT","text":"흐름이 바뀐다."}],"dialogue_ko":null,"effect_key":"COUNTER"},
      {"phase_id":"P3","title_ko":"결착","segments":[{"type":"TEXT","text":"승부가 끝난다."}],"dialogue_ko":null,"effect_key":"FINISH"}
    ]
  }'::jsonb;
  v_expected_user jsonb;
  v_expected_opponent jsonb;
  v_engine_result jsonb;
  -- Generated by ProjectionBattleEngineTest from the same IDs/seed/projections.
  -- Keeping the complete transcript here is the cross-language golden vector.
  v_engine_golden_rounds jsonb := '[
    {"number":1,"userHpBefore":1000,"opponentHpBefore":1000,"userAction":{"actor":"USER","kind":"SKILL","skillId":"warrior.sample","damage":76,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":924},
    {"number":2,"userHpBefore":1000,"opponentHpBefore":924,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":64,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":860},
    {"number":3,"userHpBefore":1000,"opponentHpBefore":860,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":143,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"SKILL","skillId":"warrior.sample","damage":222,"healing":0,"critical":true},"userHpAfter":778,"opponentHpAfter":717},
    {"number":4,"userHpBefore":778,"opponentHpBefore":717,"userAction":{"actor":"USER","kind":"SKILL","skillId":"warrior.sample","damage":222,"healing":0,"critical":true},"opponentAction":{"actor":"OPPONENT","kind":"BASIC_ATTACK","skillId":"","damage":143,"healing":0,"critical":false},"userHpAfter":635,"opponentHpAfter":495},
    {"number":5,"userHpBefore":635,"opponentHpBefore":495,"userAction":{"actor":"USER","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":635,"opponentHpAfter":495},
    {"number":6,"userHpBefore":635,"opponentHpBefore":495,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":83,"healing":0,"critical":true},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":635,"opponentHpAfter":412},
    {"number":7,"userHpBefore":635,"opponentHpBefore":412,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":64,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":635,"opponentHpAfter":348},
    {"number":8,"userHpBefore":635,"opponentHpBefore":348,"userAction":{"actor":"USER","kind":"SKILL","skillId":"warrior.sample","damage":171,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"SKILL","skillId":"warrior.sample","damage":171,"healing":0,"critical":false},"userHpAfter":464,"opponentHpAfter":177}
  ]'::jsonb;
  v_resonance_golden_rounds jsonb := '[
    {"number":1,"userHpBefore":1000,"opponentHpBefore":1000,"userAction":{"actor":"USER","kind":"SKILL","skillId":"fixture.strike","damage":79,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":921},
    {"number":2,"userHpBefore":1000,"opponentHpBefore":921,"userAction":{"actor":"USER","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":921},
    {"number":3,"userHpBefore":1000,"opponentHpBefore":921,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":145,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"SKILL","skillId":"fixture.strike","damage":231,"healing":0,"critical":true},"userHpAfter":769,"opponentHpAfter":776},
    {"number":4,"userHpBefore":769,"opponentHpBefore":776,"userAction":{"actor":"USER","kind":"SKILL","skillId":"fixture.strike","damage":79,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":769,"opponentHpAfter":697},
    {"number":5,"userHpBefore":769,"opponentHpBefore":697,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":145,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"BASIC_ATTACK","skillId":"","damage":145,"healing":0,"critical":false},"userHpAfter":624,"opponentHpAfter":552},
    {"number":6,"userHpBefore":624,"opponentHpBefore":552,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":65,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":624,"opponentHpAfter":487},
    {"number":7,"userHpBefore":624,"opponentHpBefore":487,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":145,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"SKILL","skillId":"fixture.strike","damage":178,"healing":0,"critical":false},"userHpAfter":446,"opponentHpAfter":342},
    {"number":8,"userHpBefore":446,"opponentHpBefore":342,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":65,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":446,"opponentHpAfter":277}
  ]'::jsonb;
  v_resonance_sample jsonb;
  v_resonance_user jsonb;
  v_resonance_opponent jsonb;
  v_resonance_result jsonb;
  v_no_skill jsonb;
  v_one_top_skill jsonb;
  v_many_skills jsonb;
  v_tied_skills jsonb;
  v_extreme_power jsonb;
  v_growth_no_skill jsonb;
  v_growth_one_top jsonb;
  v_growth_many jsonb;
  v_growth_tied jsonb;
  v_growth_extreme jsonb;
begin
  v_expected_user := battle_private.expected_normalized_projection(
    '00000000-0000-0000-0000-000000000002'::uuid,
    'Sample User',
    'WARRIOR',
    20,
    battle_private.compute_effective_power(v_sample),
    v_sample,
    'ASSAULT',
    '[{"trait_code":"TRAIT_001"}]'::jsonb,
    10000
  );
  v_expected_opponent := battle_private.expected_normalized_projection(
    '00000000-0000-0000-0000-000000000003'::uuid,
    'Sample Opponent',
    'MAGE',
    20,
    battle_private.compute_effective_power(v_sample),
    v_sample,
    'GUARD',
    '[{"trait_code":"TRAIT_021"}]'::jsonb,
    10000
  );
  v_engine_result := battle_private.simulate_engine_result_payload(
    '00000000-0000-0000-0000-000000000001'::uuid,
    42,
    1,
    8,
    v_expected_user,
    v_expected_opponent
  );
  v_resonance_sample := pg_catalog.jsonb_set(
    v_sample,
    array['skills'],
    '[{"skillId":"fixture.strike","displayName":"Fixture Strike","kind":"STRIKE","powerBasisPoints":12000,"cooldownRounds":2,"masteryLevel":50}]'::jsonb
  );
  v_resonance_user := battle_private.expected_normalized_projection(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'::uuid,
    'Resonance User',
    'WARRIOR',
    20,
    10100,
    v_resonance_sample,
    'ASSAULT',
    '[]'::jsonb,
    10000
  );
  v_resonance_opponent := battle_private.expected_normalized_projection(
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'::uuid,
    'Resonance Opponent',
    'MAGE',
    20,
    10100,
    v_resonance_sample,
    'GUARD',
    '[]'::jsonb,
    10000
  );
  v_resonance_result := battle_private.simulate_engine_result_payload(
    'cccccccc-cccc-cccc-cccc-cccccccccccc'::uuid,
    2,
    1,
    8,
    v_resonance_user,
    v_resonance_opponent
  );

  if (select pg_catalog.count(*) from public.battle_trait_catalog) <> 100 then
    raise exception 'Battle trait catalog must contain exactly 100 rows';
  end if;

  if (
    select pg_catalog.count(*)
      from pg_catalog.pg_constraint as constraint_row
      join pg_catalog.pg_class as relation
        on relation.oid = constraint_row.conrelid
      join pg_catalog.pg_namespace as namespace
        on namespace.oid = relation.relnamespace
     where constraint_row.contype = 'c'
       and (namespace.nspname, relation.relname) in (
         ('public', 'battle_seasons'),
         ('battle_private', 'projection_snapshots'),
         ('battle_private', 'matches'),
         ('public', 'battle_records')
       )
       and pg_catalog.pg_get_constraintdef(constraint_row.oid)
             like '%rules_version = 1%'
  ) <> 4 then
    raise exception 'Battle V0.1 storage must reject unsupported rules versions';
  end if;

  if exists (
    select 1
      from pg_catalog.generate_series(1, 100) as expected(number)
      left join public.battle_trait_catalog as catalog
        on catalog.trait_code =
           'TRAIT_' || pg_catalog.lpad(expected.number::text, 3, '0')
     where catalog.trait_code is null
        or catalog.label_key is distinct from
           'battle_trait_' || pg_catalog.lpad(expected.number::text, 3, '0')
        or catalog.category is distinct from (array[
          'OPENING','OFFENSE','DEFENSE','REVERSAL','ENDURANCE',
          'PRECISION','TACTICS','TEMPERAMENT','MOMENTUM','FINISH'
        ])[((expected.number - 1) / 10) + 1]
  ) then
    raise exception 'Battle trait IDs/categories differ from the Android 001..100 contract';
  end if;

  if battle_private.rating_window_for_gap(0) is distinct from 100
     or battle_private.rating_window_for_gap(100) is distinct from 100
     or battle_private.rating_window_for_gap(101) is distinct from 200
     or battle_private.rating_window_for_gap(200) is distinct from 200
     or battle_private.rating_window_for_gap(201) is distinct from 300
     or battle_private.rating_window_for_gap(300) is distinct from 300
     or battle_private.rating_window_for_gap(301) is not null then
    raise exception 'Battle strict rating-window contract self-check failed';
  end if;

  if (
       select counterpart.user_id
         from battle_private.match_counterpart(
           '10000000-0000-0000-0000-000000000001'::uuid,
           '20000000-0000-0000-0000-000000000001'::uuid,
           '10000000-0000-0000-0000-000000000002'::uuid,
           '20000000-0000-0000-0000-000000000002'::uuid,
           '10000000-0000-0000-0000-000000000001'::uuid,
           '20000000-0000-0000-0000-000000000001'::uuid
         ) as counterpart
     ) is distinct from '10000000-0000-0000-0000-000000000002'::uuid
     or (
       select counterpart.user_id
         from battle_private.match_counterpart(
           '10000000-0000-0000-0000-000000000001'::uuid,
           '20000000-0000-0000-0000-000000000001'::uuid,
           '10000000-0000-0000-0000-000000000002'::uuid,
           '20000000-0000-0000-0000-000000000002'::uuid,
           '10000000-0000-0000-0000-000000000002'::uuid,
           '20000000-0000-0000-0000-000000000002'::uuid
         ) as counterpart
     ) is distinct from '10000000-0000-0000-0000-000000000001'::uuid then
    raise exception 'Battle unordered-pair counterpart self-check failed';
  end if;

  -- Kotlin Long.ushr/rotateLeft cross-language boundary vectors. PostgreSQL
  -- numeric division must never round before the unsigned quotient is taken.
  if exists (
    select 1
      from (values
        ( '9223372036854775807'::bigint,  0,  '9223372036854775807'::bigint),
        ( '9223372036854775807'::bigint,  1,  '4611686018427387903'::bigint),
        ( '9223372036854775807'::bigint, 31,           '4294967295'::bigint),
        ( '9223372036854775807'::bigint, 32,           '2147483647'::bigint),
        ( '9223372036854775807'::bigint, 63,                    '0'::bigint),
        ('-9223372036854775808'::bigint,  0, '-9223372036854775808'::bigint),
        ('-9223372036854775808'::bigint,  1,  '4611686018427387904'::bigint),
        ('-9223372036854775808'::bigint, 31,           '4294967296'::bigint),
        ('-9223372036854775808'::bigint, 32,           '2147483648'::bigint),
        ('-9223372036854775808'::bigint, 63,                    '1'::bigint),
        (                  '-1'::bigint,  0,                   '-1'::bigint),
        (                  '-1'::bigint,  1,  '9223372036854775807'::bigint),
        (                  '-1'::bigint, 31,           '8589934591'::bigint),
        (                  '-1'::bigint, 32,           '4294967295'::bigint),
        (                  '-1'::bigint, 63,                    '1'::bigint)
      ) as vector(input_value, bits, expected_value)
     where battle_private.i64_unsigned_shift_right(
             vector.input_value,
             vector.bits
           ) is distinct from vector.expected_value
  ) then
    raise exception 'Kotlin Long unsigned-shift boundary golden failed';
  end if;

  if exists (
    select 1
      from (values
        ( '9223372036854775807'::bigint,  1,                   '-2'::bigint),
        ( '9223372036854775807'::bigint, 17,               '-65537'::bigint),
        ( '9223372036854775807'::bigint, 31,          '-1073741825'::bigint),
        ( '9223372036854775807'::bigint, 32,          '-2147483649'::bigint),
        ( '9223372036854775807'::bigint, 63, '-4611686018427387905'::bigint),
        ('-9223372036854775808'::bigint,  1,                    '1'::bigint),
        ('-9223372036854775808'::bigint, 17,                '65536'::bigint),
        ('-9223372036854775808'::bigint, 31,           '1073741824'::bigint),
        ('-9223372036854775808'::bigint, 32,           '2147483648'::bigint),
        ('-9223372036854775808'::bigint, 63,  '4611686018427387904'::bigint),
        (                  '-1'::bigint,  1,                   '-1'::bigint),
        (                  '-1'::bigint, 17,                   '-1'::bigint),
        (                  '-1'::bigint, 31,                   '-1'::bigint),
        (                  '-1'::bigint, 32,                   '-1'::bigint),
        (                  '-1'::bigint, 63,                   '-1'::bigint)
      ) as vector(input_value, bits, expected_value)
     where battle_private.i64_rotate_left(
             vector.input_value,
             vector.bits
           ) is distinct from vector.expected_value
  ) then
    raise exception 'Kotlin Long rotate-left boundary golden failed';
  end if;

  -- Fixed java.util.Random(0x5EEDBA77E) inputs, evaluated with Kotlin Long
  -- ushr and the engine's rotateLeft expression. These broad samples prevent a
  -- boundary-only implementation from hiding signed/unsigned drift.
  if exists (
    select 1
      from (values
        (-3592848303465675134::bigint, 15, 453304924629024::bigint, 42,  4691074299397538768::bigint),
        ( -767722863221930492::bigint, 48,           62808::bigint, 56,   357289052755179014::bigint),
        ( -441395725889550914::bigint, 53,            1998::bigint,  8, -2316841385467724039::bigint),
        ( 1235753165009894249::bigint, 27,      9207078553::bigint,  1,  2471506330019788498::bigint),
        (-3560705822828995064::bigint, 27,    110909627757::bigint, 53, -4532359851024772197::bigint),
        (-6850877410152601285::bigint, 22,   2764670053376::bigint, 10, -5535719986634101117::bigint),
        ( 3238821241654788564::bigint, 54,             179::bigint, 32,  6904794295743716034::bigint),
        ( 4298273302732754858::bigint, 14, 262345782637497::bigint, 16, -8589581724740338778::bigint),
        (-4208048021964578643::bigint,  3,1779837006468121621::bigint,29, -3413583288718901165::bigint),
        ( 8350441471065385274::bigint, 46,          118666::bigint,  3, -6983444526315124269::bigint),
        (-4094068855156394576::bigint, 59,              24::bigint, 49,  7737622168709797075::bigint),
        (-6248574965237620413::bigint, 51,            5417::bigint, 48, -2214740462446777188::bigint)
      ) as vector(
        input_value,
        shift_bits,
        shift_expected,
        rotate_bits,
        rotate_expected
      )
     where battle_private.i64_unsigned_shift_right(
             vector.input_value,
             vector.shift_bits
           ) is distinct from vector.shift_expected
        or battle_private.i64_rotate_left(
             vector.input_value,
             vector.rotate_bits
           ) is distinct from vector.rotate_expected
  ) then
    raise exception 'Kotlin Long seeded sample golden failed';
  end if;

  if v_expected_user is null
     or v_expected_opponent is null
     or v_expected_user -> 'stats' is distinct from '{
       "strength":1667,"constitution":1667,"dexterity":1667,
       "intelligence":1667,"wisdom":1666,"charisma":1666
     }'::jsonb
     or battle_private.engine_stable_hash(
       'cccccccc-cccc-cccc-cccc-cccccccccccc'
     ) <> 395573847701825025
     or battle_private.engine_stable_hash(
       'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
     ) <> -8778351989229086207
     or battle_private.engine_stable_hash(
       'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
     ) <> 5111335917265102705
     or battle_private.i64_rotate_left(-8778351989229086207, 17)
          <> 1062921324785241178
     or battle_private.i64_rotate_left(5111335917265102705, 41)
          <> -8048246385117896936
     or battle_private.engine_mix64(0) <> -2152535657050944081
     or battle_private.engine_mix64('-9223372036854775808'::bigint)
          <> 5196802822362493915
     or battle_private.engine_mix64(7046029254386353130)
          <> -5417735806833148549
     or battle_private.engine_roll(
       -7209137599707253440, 1, 'USER', 0, 10000
     ) <> 5016
     or battle_private.engine_roll(
       -7209137599707253440, 3, 'OPPONENT', 2, 10000
     ) <> 915
     or battle_private.engine_roll(
       -2181040525238381632, 1, 'USER', 0, 10000
     ) <> 1533
     or v_engine_result is null
     or v_engine_result -> 'rounds' is distinct from v_engine_golden_rounds
     or v_engine_result ->> 'outcome' is distinct from 'USER_WIN'
     or v_resonance_user ->> 'growthResonanceBudget' is distinct from '102'
     or v_resonance_opponent ->> 'growthResonanceBudget' is distinct from '102'
     or v_resonance_result -> 'rounds' is distinct from v_resonance_golden_rounds
     or v_resonance_result ->> 'outcome' is distinct from 'USER_WIN'
     or not battle_private.is_valid_engine_result_payload(
       v_engine_result,
       '00000000-0000-0000-0000-000000000001'::uuid,
       42,
       1,
       'USER_WIN',
       8,
       v_expected_user,
       v_expected_opponent
     )
     -- An extra top-level field is rejected even when its spelling attempts to
     -- bypass the placement privacy filter.
     or battle_private.is_valid_engine_result_payload(
       v_engine_result || '{"scoreDelta":12}'::jsonb,
       '00000000-0000-0000-0000-000000000001'::uuid,
       42,
       1,
       'USER_WIN',
       8,
       v_expected_user,
       v_expected_opponent
     )
     -- Kotlin currently exposes only BATTLE_RULES_VERSION = 1. Merely changing
     -- the result field cannot opt a season into unimplemented mechanics.
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(v_engine_result, array['rulesVersion'], '2'::jsonb),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42,
       1,
       'USER_WIN',
       8,
       v_expected_user,
       v_expected_opponent
     )
     -- Issued projection fields, including guidance and normalized stats, are
     -- exact. A service cannot settle a rewritten projection.
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(
         v_engine_result,
         array['user','guidance'],
         '"BALANCED"'::jsonb
       ),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42,
       1,
       'USER_WIN',
       8,
       v_expected_user,
       v_expected_opponent
     )
     -- The same projection and seed under a seven-round season has a different
     -- canonical transcript and therefore cannot accept the eight-round result.
     or battle_private.is_valid_engine_result_payload(
       v_engine_result,
       '00000000-0000-0000-0000-000000000001'::uuid,
       42,
       1,
       'USER_WIN',
       7,
       v_expected_user,
       v_expected_opponent
     )
     -- An in-range but impossible maximum hit is rejected: bounds checking is
     -- not a substitute for canonical deterministic replay.
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(
         v_engine_result,
         array['rounds','0','userAction','damage'],
         '350'::jsonb
       ),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42,
       1,
       'USER_WIN',
       8,
       v_expected_user,
       v_expected_opponent
     )
     -- Action, critical, cooldown history, HP, and one-bit seed mutations all
     -- fail independently against the Kotlin golden transcript.
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(
         v_engine_result,
         array['rounds','1','userAction','kind'],
         '"GUARD"'::jsonb
       ),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42, 1, 'USER_WIN', 8, v_expected_user, v_expected_opponent
     )
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(
         v_engine_result,
         array['rounds','3','userAction','critical'],
         'false'::jsonb
       ),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42, 1, 'USER_WIN', 8, v_expected_user, v_expected_opponent
     )
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(
         v_engine_result,
         array['rounds','1','userAction'],
         v_engine_result #> array['rounds','0','userAction']
       ),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42, 1, 'USER_WIN', 8, v_expected_user, v_expected_opponent
     )
     or battle_private.is_valid_engine_result_payload(
       pg_catalog.jsonb_set(
         v_engine_result,
         array['rounds','0','opponentHpAfter'],
         '923'::jsonb
       ),
       '00000000-0000-0000-0000-000000000001'::uuid,
       42, 1, 'USER_WIN', 8, v_expected_user, v_expected_opponent
     )
     or battle_private.is_valid_engine_result_payload(
       v_engine_result,
       '00000000-0000-0000-0000-000000000001'::uuid,
       43,
       1,
       'USER_WIN',
       8,
       v_expected_user,
       v_expected_opponent
     ) then
    raise exception 'Battle full engine-result contract self-check failed';
  end if;

  if (
    select pg_catalog.string_agg(sample.label, ',' order by
             sample.rating desc,
             sample.wins desc,
             sample.losses,
             sample.achieved_at,
             sample.label
           )
      from (values
        ('more_wins',   1012, 4, 4, '2026-09-01 00:00:03+09'::timestamptz),
        ('earlier',     1012, 3, 2, '2026-09-01 00:00:00+09'::timestamptz),
        ('later',       1012, 3, 2, '2026-09-01 00:00:01+09'::timestamptz),
        ('more_losses', 1012, 3, 3, '2026-08-01 00:00:00+09'::timestamptz)
      ) as sample(label, rating, wins, losses, achieved_at)
  ) <> 'more_wins,earlier,later,more_losses' then
    raise exception 'Battle current-rating arrival tie-break self-check failed';
  end if;

  v_no_skill := pg_catalog.jsonb_set(
    v_sample,
    array['skills'],
    '[]'::jsonb
  );
  v_one_top_skill := pg_catalog.jsonb_set(
    v_sample,
    array['skills'],
    '[{
      "skillId":"z.skill",
      "displayName":"Top Skill",
      "kind":"STRIKE",
      "powerBasisPoints":12000,
      "cooldownRounds":2,
      "masteryLevel":90
    }]'::jsonb
  );
  v_many_skills := pg_catalog.jsonb_set(
    v_sample,
    array['skills'],
    '[
      {
        "skillId":"z.skill",
        "displayName":"Top Skill",
        "kind":"STRIKE",
        "powerBasisPoints":12000,
        "cooldownRounds":2,
        "masteryLevel":90
      },
      {
        "skillId":"low.skill",
        "displayName":"Low Skill",
        "kind":"CONTROL",
        "powerBasisPoints":8000,
        "cooldownRounds":0,
        "masteryLevel":1
      }
    ]'::jsonb
  );
  v_tied_skills := pg_catalog.jsonb_set(
    v_sample,
    array['skills'],
    '[
      {
        "skillId":"z.skill",
        "displayName":"Z Skill",
        "kind":"STRIKE",
        "powerBasisPoints":12000,
        "cooldownRounds":2,
        "masteryLevel":80
      },
      {
        "skillId":"a.skill",
        "displayName":"A Skill",
        "kind":"ARCANE",
        "powerBasisPoints":12000,
        "cooldownRounds":2,
        "masteryLevel":80
      }
    ]'::jsonb
  );
  v_extreme_power := pg_catalog.jsonb_set(
    pg_catalog.jsonb_set(
      v_sample,
      array['build'],
      '{
        "strength":1000000000,
        "constitution":1000000000,
        "dexterity":1000000000,
        "intelligence":1000000000,
        "wisdom":1000000000,
        "charisma":1000000000
      }'::jsonb
    ),
    array['skills'],
    '[{
      "skillId":"max.skill",
      "displayName":"Max Skill",
      "kind":"ARCANE",
      "powerBasisPoints":16000,
      "cooldownRounds":8,
      "masteryLevel":100
    }]'::jsonb
  );

  v_growth_no_skill := battle_private.growth_components(v_no_skill);
  v_growth_one_top := battle_private.growth_components(v_one_top_skill);
  v_growth_many := battle_private.growth_components(v_many_skills);
  v_growth_tied := battle_private.growth_components(v_tied_skills);
  v_growth_extreme := battle_private.growth_components(v_extreme_power);

  if not battle_private.is_valid_snapshot_payload(v_sample)
     or not battle_private.is_valid_snapshot_payload(v_no_skill)
     or not battle_private.is_valid_snapshot_payload(v_many_skills)
     or not battle_private.is_valid_snapshot_payload(v_tied_skills)
     or not battle_private.is_valid_snapshot_payload(v_extreme_power)
     or battle_private.compute_effective_power(v_sample) <= 0
     or v_growth_no_skill ->> 'representativeSkillId' is not null
     or (v_growth_no_skill ->> 'representativeSkillMastery')::integer <> 0
     or (v_growth_no_skill ->> 'growthIndexBasisPoints')::integer <>
        pg_catalog.floor(
          (
            (v_growth_no_skill ->> 'verifiedPowerBasisPoints')::bigint * 8000
            + 5000
          )::numeric / 10000::numeric
        )::integer
     or v_growth_one_top ->> 'representativeSkillId' <> 'z.skill'
     or v_growth_one_top ->> 'growthIndexBasisPoints' <>
        v_growth_many ->> 'growthIndexBasisPoints'
     or v_growth_one_top ->> 'effectivePower' <>
        v_growth_many ->> 'effectivePower'
     or v_growth_tied ->> 'representativeSkillId' <> 'a.skill'
     or (v_growth_extreme ->> 'verifiedPowerBasisPoints')::integer <> 10000
     or (v_growth_extreme ->> 'growthIndexBasisPoints')::integer <> 10000
     or (v_growth_extreme ->> 'effectivePower')::bigint <> 100000
     or (v_growth_extreme ->> 'growthResonanceBudget')::integer <> 108 then
    raise exception 'Battle trusted-snapshot contract self-check failed';
  end if;

  if not battle_private.contains_forbidden_economy_key('{"reward":{"gold":1}}'::jsonb)
     or battle_private.contains_forbidden_economy_key('{"scenes":[]}'::jsonb) then
    raise exception 'Battle economy-isolation self-check failed';
  end if;

  if not battle_private.contains_private_rating_key(
       '{"result":{"rating_after":1012}}'::jsonb
     )
     or not battle_private.contains_private_rating_key(
       '{"result":{"ratingAfter":1012,"scoreDelta":12,"expectedScore":0.5}}'::jsonb
     )
     or not battle_private.contains_private_rating_key(
       '{"opponentReferenceScore":1000}'::jsonb
     )
     or not battle_private.contains_private_rating_key('{"score":1012}'::jsonb)
     or battle_private.contains_private_rating_key('{"scenes":[]}'::jsonb) then
    raise exception 'Battle placement-score privacy self-check failed';
  end if;

  if battle_private.korea_day('2026-09-04 14:59:59.999+00'::timestamptz)
       is distinct from '2026-09-04'::date
     or battle_private.korea_day('2026-09-04 15:00:00+00'::timestamptz)
       is distinct from '2026-09-05'::date then
    raise exception 'Battle single-instant Korea day-boundary self-check failed';
  end if;

  if battle_private.is_due_trait_removal(
       'PENDING',
       '2026-09-04 01:00:00+00'::timestamptz,
       '2026-09-04 00:59:59.999999+00'::timestamptz
     )
     or not battle_private.is_due_trait_removal(
       'PENDING',
       '2026-09-04 01:00:00+00'::timestamptz,
       '2026-09-04 01:00:00+00'::timestamptz
     )
     or battle_private.is_due_trait_removal(
       'CANCELLED',
       '2026-09-04 01:00:00+00'::timestamptz,
       '2026-09-04 02:00:00+00'::timestamptz
     )
     or pg_catalog.strpos(
       pg_catalog.pg_get_functiondef(
         'public.battle_request_entry(uuid,uuid,text)'::regprocedure
       ),
       'is_due_trait_removal'
     ) = 0
     or pg_catalog.strpos(
       pg_catalog.pg_get_functiondef(
         'public.battle_request_entry(uuid,uuid,text)'::regprocedure
       ),
       'complete_due_trait_removals'
     ) = 0
     or pg_catalog.strpos(
       pg_catalog.pg_get_functiondef(
         'battle_private.apply_trait_evidence(uuid,uuid,uuid,uuid,text,integer)'::regprocedure
       ),
       'complete_due_trait_removals'
     ) = 0 then
    raise exception 'Battle due-trait request exclusion self-check failed';
  end if;

  if not battle_private.is_valid_narrative_shape(
       v_narrative,
       '00000000-0000-0000-0000-000000000001'::uuid
     )
     or battle_private.is_valid_narrative_shape(
       v_narrative,
       '00000000-0000-0000-0000-000000000099'::uuid
     )
     or battle_private.is_valid_narrative_shape(
       pg_catalog.jsonb_set(
         v_narrative,
         array['scenes','0','phase_id'],
         '"P3"'::jsonb
       ),
       '00000000-0000-0000-0000-000000000001'::uuid
     ) then
    raise exception 'Battle three-to-five-scene narrative contract self-check failed';
  end if;
end;
$$;
