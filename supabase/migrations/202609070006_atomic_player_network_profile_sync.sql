-- One authenticated profile publication for existing ranking and shared-player storage.
--
-- This migration deliberately does not install or depend on the legacy Battle V0.1 backend.
-- Arena combat, tickets, score, history and rewards remain local and are never accepted here.
-- The new RPC only removes a duplicate HTTP/Auth upload by projecting one strict character batch
-- into the already-reviewed ranking and public-player functions in the same transaction. The two
-- older public RPC signatures and wire behavior remain compatible with released clients.

-- Reuse the existing one-row-per-account guard instead of creating another write-amplifying
-- ledger. These nullable columns do not rewrite existing rows. MD5 is sufficient here only because
-- equality causes a no-op for the same account; the hash never authenticates data or a result.
alter table shared_player_private.sync_limits
  add column if not exists ranking_last_called_at timestamptz,
  add column if not exists ranking_last_synced_at timestamptz,
  add column if not exists ranking_payload_hash text,
  add column if not exists profile_contract_version smallint not null default 0;

alter table shared_player_private.sync_limits
  add constraint sync_limits_profile_contract_version_check check (
    profile_contract_version between 0 and 1
  ) not valid;

alter table shared_player_private.sync_limits
  validate constraint sync_limits_profile_contract_version_check;

alter table shared_player_private.sync_limits
  add constraint sync_limits_ranking_guard_shape_check check (
    (
      ranking_last_called_at is null
      and ranking_last_synced_at is null
      and ranking_payload_hash is null
    ) or (
      ranking_last_called_at is not null
      and ranking_last_synced_at is not null
      and ranking_last_called_at >= ranking_last_synced_at
      and ranking_payload_hash ~ '^[0-9a-f]{32}$'
    )
  ) not valid;

alter table shared_player_private.sync_limits
  validate constraint sync_limits_ranking_guard_shape_check;

create index shared_player_roster_call_limits_cleanup_idx
  on shared_player_private.roster_call_limits (last_called_at, user_id);
create index shared_player_roster_issuance_limits_cleanup_idx
  on shared_player_private.roster_daily_issuance_limits (last_issued_at, user_id);
create index shared_player_sync_limits_cleanup_idx
  on shared_player_private.sync_limits (
    (greatest(
      last_called_at,
      last_synced_at,
      coalesce(ranking_last_called_at, '-infinity'::timestamptz),
      coalesce(ranking_last_synced_at, '-infinity'::timestamptz)
    )),
    user_id
  );

-- Keep one outcome-producing implementation behind both the released void RPC and the unified
-- profile RPC. The caller chooses preflight or apply. Preflight never changes ranking rows; apply
-- either completes the whole ranking replacement or returns a structured rate-limit outcome.
-- This lets the unified RPC reject before publishing shared snapshots while the released wrapper
-- still maps a soft rejection to committed PostgREST 429 behavior.
create function ranking_private.sync_ranking_entries_outcome(
  p_entries jsonb,
  p_apply boolean
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  current_user_id uuid := auth.uid();
  v_now timestamptz := pg_catalog.statement_timestamp();
  v_canonical_entries jsonb;
  v_payload_hash text;
  v_guard shared_player_private.sync_limits%rowtype;
  v_retry_after_seconds bigint;
begin
  if current_user_id is null then
    raise exception 'authentication required';
  end if;

  if p_apply is null then
    raise exception 'ranking apply mode required' using errcode = '22023';
  end if;

  if p_entries is null
     or pg_catalog.jsonb_typeof(p_entries) is distinct from 'array'
     or pg_catalog.pg_column_size(p_entries) > 32768
     or pg_catalog.jsonb_array_length(p_entries) > 3 then
    raise exception 'invalid ranking batch' using errcode = '22023';
  end if;

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
    raise exception 'invalid ranking batch' using errcode = '22023';
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
        or pg_catalog.octet_length(e.display_name) > 96
        or e.display_name <> pg_catalog.btrim(e.display_name)
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
    raise exception 'invalid ranking batch' using errcode = '22023';
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
    raise exception 'duplicate ranking identity' using errcode = '22023';
  end if;

  select coalesce(
           pg_catalog.jsonb_agg(payload.entry
             order by (payload.entry ->> 'slot_id')::smallint),
           '[]'::jsonb
         )
    into v_canonical_entries
    from pg_catalog.jsonb_array_elements(p_entries) as payload(entry);
  v_payload_hash := pg_catalog.md5(v_canonical_entries::text);

  -- This is the historical ranking account lock. The unified function acquires it before the
  -- shared-player account lock, establishing one fixed order and preventing a sync_limits row-lock
  -- cycle with a concurrent legacy ranking call.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(current_user_id::text, 0)
  );

  select * into v_guard
    from shared_player_private.sync_limits as guard
   where guard.user_id = current_user_id
   for update;

  if found
     and v_guard.ranking_last_called_at is not null
     and v_now - v_guard.ranking_last_called_at < interval '5 seconds' then
    v_retry_after_seconds := greatest(
      1,
      pg_catalog.ceil(pg_catalog.date_part(
        'epoch', v_guard.ranking_last_called_at + interval '5 seconds' - v_now
      ))::bigint
    );
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'rate_limited', true,
      'retry_after_seconds', v_retry_after_seconds,
      'server_now', (extract(epoch from v_now) * 1000)::bigint
    );
  end if;

  if found and v_guard.ranking_payload_hash = v_payload_hash then
    if p_apply then
      update shared_player_private.sync_limits
         set ranking_last_called_at = v_now
       where user_id = current_user_id;
    end if;
    return pg_catalog.jsonb_build_object(
      'accepted', true,
      'deduplicated', true,
      'synced_count', pg_catalog.jsonb_array_length(v_canonical_entries),
      'server_now', (extract(epoch from v_now) * 1000)::bigint
    );
  end if;

  -- In apply mode persist the rejected attempt before returning the outcome. The released wrapper
  -- maps it to HTTP 429 without raising, so ranking_last_called_at remains committed. Preflight mode
  -- stays read-only and lets the unified caller decide whether to record the rejection.
  if found
     and v_guard.ranking_last_synced_at is not null
     and v_now - v_guard.ranking_last_synced_at < interval '5 minutes' then
    v_retry_after_seconds := greatest(
      1,
      pg_catalog.ceil(pg_catalog.date_part(
        'epoch', v_guard.ranking_last_synced_at + interval '5 minutes' - v_now
      ))::bigint
    );
    if p_apply then
      update shared_player_private.sync_limits
         set ranking_last_called_at = v_now
       where user_id = current_user_id;
    end if;
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'rate_limited', true,
      'retry_after_seconds', v_retry_after_seconds,
      'server_now', (extract(epoch from v_now) * 1000)::bigint
    );
  end if;

  if not p_apply then
    return pg_catalog.jsonb_build_object(
      'accepted', true,
      'deduplicated', false,
      'synced_count', pg_catalog.jsonb_array_length(v_canonical_entries),
      'server_now', (extract(epoch from v_now) * 1000)::bigint
    );
  end if;

  -- After an account has successfully used the unified contract, the compatibility writer may no
  -- longer publish a weaker, independently invented ranking roster. Its complete level-20+ set
  -- must be exactly derivable from the account's already-validated shared snapshots. Version-zero
  -- accounts preserve the released-client contract until they upgrade.
  if found and v_guard.profile_contract_version >= 1 and (
    exists (
      select 1
        from pg_catalog.jsonb_to_recordset(v_canonical_entries) as e(
          character_id uuid,
          slot_id smallint,
          display_name text,
          hero_class text,
          level bigint,
          combat_power bigint
        )
        left join shared_player_private.snapshots as snapshot
          on snapshot.user_id = current_user_id
         and snapshot.character_id = e.character_id
       where snapshot.character_id is null
          or (
            snapshot.display_name,
            snapshot.hero_class,
            snapshot.level,
            snapshot.combat_power
          ) is distinct from (
            e.display_name,
            e.hero_class,
            e.level,
            e.combat_power
          )
    ) or exists (
      select 1
        from shared_player_private.snapshots as snapshot
       where snapshot.user_id = current_user_id
         and snapshot.level >= 20
         and not exists (
           select 1
             from pg_catalog.jsonb_to_recordset(v_canonical_entries) as e(character_id uuid)
            where e.character_id = snapshot.character_id
         )
    )
  ) then
    raise exception 'ranking profile does not match validated public snapshots'
      using errcode = '22023';
  end if;

  delete from public.ranking_entries as r
   where r.user_id = current_user_id
     and not exists (
       select 1
         from pg_catalog.jsonb_to_recordset(v_canonical_entries) as e(character_id uuid)
        where e.character_id = r.character_id
     );

  insert into public.ranking_entries (
    user_id, character_id, slot_id, display_name, hero_class, level, combat_power
  )
  select current_user_id,
         e.character_id,
         e.slot_id,
         e.display_name,
         e.hero_class,
         e.level,
         e.combat_power
    from pg_catalog.jsonb_to_recordset(v_canonical_entries) as e(
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

  insert into shared_player_private.sync_limits as guard (
    user_id, last_called_at, last_synced_at, payload_hash,
    ranking_last_called_at, ranking_last_synced_at, ranking_payload_hash
  ) values (
    current_user_id, '-infinity'::timestamptz, '-infinity'::timestamptz,
    pg_catalog.md5('[]'), v_now, v_now, v_payload_hash
  )
  on conflict (user_id) do update set
    ranking_last_called_at = excluded.ranking_last_called_at,
    ranking_last_synced_at = excluded.ranking_last_synced_at,
    ranking_payload_hash = excluded.ranking_payload_hash;

  return pg_catalog.jsonb_build_object(
    'accepted', true,
    'deduplicated', false,
    'synced_count', pg_catalog.jsonb_array_length(v_canonical_entries),
    'server_now', (extract(epoch from v_now) * 1000)::bigint
  );
end;
$$;

revoke all on function ranking_private.sync_ranking_entries_outcome(jsonb,boolean)
  from public, anon, authenticated, service_role;

-- Preserve the released private void signature and committed HTTP 429 behavior. Only this wrapper
-- owns PostgREST response settings; the outcome helper remains usable by the unified transaction.
create or replace function ranking_private.sync_ranking_entries(p_entries jsonb)
returns void
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_outcome jsonb;
  v_retry_after_seconds bigint;
begin
  v_outcome := ranking_private.sync_ranking_entries_outcome(p_entries, true);
  if not coalesce((v_outcome ->> 'accepted')::boolean, false) then
    v_retry_after_seconds := greatest(
      1,
      coalesce((v_outcome ->> 'retry_after_seconds')::bigint, 5)
    );
    perform pg_catalog.set_config('response.status', '429', true);
    perform pg_catalog.set_config(
      'response.headers',
      pg_catalog.jsonb_build_array(
        pg_catalog.jsonb_build_object('Retry-After', v_retry_after_seconds::text)
      )::text,
      true
    );
  end if;
end;
$$;

revoke all on function ranking_private.sync_ranking_entries(jsonb)
  from public, anon, authenticated, service_role;
grant execute on function ranking_private.sync_ranking_entries(jsonb) to authenticated;

-- Replace both released read shapes before direct table SELECT is removed. Merely changing the
-- old row RPC to SECURITY DEFINER would preserve its rank_number predicate: a large tie could then
-- return far more than 1,000 rows with owner privileges if the following migration failed. The
-- row form below keeps the requested character while reserving one of the bounded result slots for
-- it when it sits below the visible top. Other accounts receive a stable pseudonymous UUID. The
-- compact form keeps its historical top-list-plus-own-object response shape.
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
security definer
set search_path = ''
as $$
  with params as (
    select auth.uid() as current_user_id,
           least(greatest(coalesce(p_limit, 1000), 1), 1000)::bigint as limit_count
  ), ranked as materialized (
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
  ), own as (
    select ranked.list_index
      from ranked, params
     where ranked.user_id = params.current_user_id
       and ranked.character_id = p_character_id
     limit 1
  ), visible as (
    select ranked.*
      from ranked, params
     where ranked.list_index < params.limit_count - case
             when exists (
               select 1 from own where own.list_index >= params.limit_count
             ) then 1 else 0 end
        or (
          ranked.user_id = params.current_user_id
          and ranked.character_id = p_character_id
        )
  )
  select visible.rank_number,
         visible.list_index,
         case
           when visible.user_id = params.current_user_id then visible.user_id
           else pg_catalog.md5('ranking-public-v1:' || visible.user_id::text)::uuid
         end as user_id,
         visible.character_id,
         visible.slot_id,
         visible.display_name,
         visible.hero_class,
         visible.level,
         visible.combat_power,
         visible.achieved_at,
         visible.updated_at,
         visible.total_participants
    from visible, params
   where params.current_user_id is not null
   order by visible.list_index
   limit (select limit_count from params);
$$;

create or replace function public.get_leaderboard_v2(
  p_character_id uuid,
  p_limit integer default 1000
)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  with params as (
    select auth.uid() as current_user_id,
           least(greatest(coalesce(p_limit, 1000), 1), 1000)::bigint as limit_count
  ), ranked as materialized (
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
  ), visible as (
    select ranked.*
      from ranked, params
     where ranked.list_index < params.limit_count
  )
  select case when params.current_user_id is null then null else pg_catalog.jsonb_build_object(
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
       where ranked.user_id = params.current_user_id
         and ranked.character_id = p_character_id
       limit 1
    )
  ) end
    from params;
$$;

revoke select on table public.ranking_entries from public, anon, authenticated;
drop policy if exists "authenticated users can read rankings" on public.ranking_entries;

revoke all on function public.get_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard(uuid, integer) to authenticated;
revoke all on function public.get_leaderboard_v2(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard_v2(uuid, integer) to authenticated;

-- A normal full 20-player roster is about 8 KiB. Keep a conservative 24 KiB ceiling so one
-- malformed-but-otherwise-valid row cannot turn a daily read into excessive storage or egress.
-- NOT VALID protects deployment when a short-lived legacy roster already exceeds the new ceiling;
-- PostgreSQL still enforces the constraint for every new or changed row. The conditional validation
-- below completes immediately when no legacy violation exists. Any exceptional legacy row expires
-- at the next UTC boundary and can then be removed by the existing bounded owner cleanup.
alter table shared_player_private.daily_rosters
  add constraint daily_rosters_snapshots_wire_size_v2_check
  check (pg_catalog.pg_column_size(snapshots) <= 24576) not valid;

create function shared_player_private.enforce_daily_roster_wire_size_v2()
returns trigger
language plpgsql
security invoker
set search_path = ''
as $$
begin
  if pg_catalog.pg_column_size(new.snapshots) > 24576 then
    raise exception 'public player roster exceeds wire-size limit' using errcode = '22023';
  end if;
  return new;
end;
$$;

revoke all on function shared_player_private.enforce_daily_roster_wire_size_v2()
  from public, anon, authenticated, service_role;

create trigger shared_player_daily_rosters_wire_size_v2
before insert or update of snapshots on shared_player_private.daily_rosters
for each row execute function shared_player_private.enforce_daily_roster_wire_size_v2();

do $$
begin
  if not exists (
    select 1
      from shared_player_private.daily_rosters as roster
     where pg_catalog.pg_column_size(roster.snapshots) > 24576
  ) then
    execute 'alter table shared_player_private.daily_rosters ' ||
            'validate constraint daily_rosters_snapshots_wire_size_v2_check';
  end if;
end;
$$;

-- Match the finalized 20-opponent pool. Existing same-day rows with 21..24 entries remain readable
-- until their normal UTC expiry, while every newly generated or regenerated roster is capped at 20.
create or replace function public.get_daily_public_player_roster(
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
      v_daily_retry_seconds := greatest(
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
            limit 20)
          union all
          (select 1 as segment, other.*
             from shared_player_private.snapshots as other
            where other.rules_version = p_rules_version
              and other.level = levels.target_level
              and other.projection_id < levels.cursor_id
              and other.user_id <> v_user_id
              and other.expires_at > v_now
            order by other.projection_id
            limit 20)
        ) as picked
    ), chosen as (
      select * from sought order by step, segment, projection_id limit 20
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

    -- The 24 KiB table constraint and before-write trigger validate the completed aggregate before
    -- it can be stored. A failure rolls back this transaction and its daily issuance increment.
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

-- Extend the existing owner-only bounded cleanup without adding a cron-facing function or a new
-- ledger. Anonymous Auth rows may outlive an uninstalled app, so guard rows with no remaining
-- snapshot/roster (and, for the shared ranking guard, no ranking row) expire after 90 days.
create or replace function shared_player_private.cleanup_expired_shared_player_data(
  p_limit integer default 5000
)
returns jsonb
language plpgsql
security invoker
set search_path = ''
as $$
declare
  v_now timestamptz := pg_catalog.statement_timestamp();
  v_stale_before timestamptz := v_now - interval '90 days';
  v_snapshot_count integer := 0;
  v_roster_count integer := 0;
  v_roster_call_guard_count integer := 0;
  v_roster_issuance_guard_count integer := 0;
  v_sync_guard_count integer := 0;
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

  with doomed as (
    select guard.user_id
      from shared_player_private.roster_call_limits as guard
     where guard.last_called_at <= v_stale_before
       and not exists (
         select 1 from shared_player_private.snapshots as snapshot
          where snapshot.user_id = guard.user_id
       )
       and not exists (
         select 1 from shared_player_private.daily_rosters as roster
          where roster.user_id = guard.user_id
       )
     order by guard.last_called_at, guard.user_id
     limit p_limit
     for update of guard skip locked
  )
  delete from shared_player_private.roster_call_limits as stale
   using doomed
   where stale.user_id = doomed.user_id
     and stale.last_called_at <= v_stale_before;
  get diagnostics v_roster_call_guard_count = row_count;

  with doomed as (
    select guard.user_id
      from shared_player_private.roster_daily_issuance_limits as guard
     where guard.last_issued_at <= v_stale_before
       and not exists (
         select 1 from shared_player_private.snapshots as snapshot
          where snapshot.user_id = guard.user_id
       )
       and not exists (
         select 1 from shared_player_private.daily_rosters as roster
          where roster.user_id = guard.user_id
       )
     order by guard.last_issued_at, guard.user_id
     limit p_limit
     for update of guard skip locked
  )
  delete from shared_player_private.roster_daily_issuance_limits as stale
   using doomed
   where stale.user_id = doomed.user_id
     and stale.last_issued_at <= v_stale_before;
  get diagnostics v_roster_issuance_guard_count = row_count;

  with doomed as (
    select guard.user_id
      from shared_player_private.sync_limits as guard
     where greatest(
             guard.last_called_at,
             guard.last_synced_at,
             coalesce(guard.ranking_last_called_at, '-infinity'::timestamptz),
             coalesce(guard.ranking_last_synced_at, '-infinity'::timestamptz)
           ) <= v_stale_before
       and not exists (
         select 1 from shared_player_private.snapshots as snapshot
          where snapshot.user_id = guard.user_id
       )
       and not exists (
         select 1 from shared_player_private.daily_rosters as roster
          where roster.user_id = guard.user_id
       )
       and not exists (
         select 1 from public.ranking_entries as ranking
          where ranking.user_id = guard.user_id
       )
     order by greatest(
                guard.last_called_at,
                guard.last_synced_at,
                coalesce(guard.ranking_last_called_at, '-infinity'::timestamptz),
                coalesce(guard.ranking_last_synced_at, '-infinity'::timestamptz)
              ), guard.user_id
     limit p_limit
     for update of guard skip locked
  )
  delete from shared_player_private.sync_limits as stale
   using doomed
   where stale.user_id = doomed.user_id
     and greatest(
           stale.last_called_at,
           stale.last_synced_at,
           coalesce(stale.ranking_last_called_at, '-infinity'::timestamptz),
           coalesce(stale.ranking_last_synced_at, '-infinity'::timestamptz)
         ) <= v_stale_before
     and not exists (
       select 1 from public.ranking_entries as ranking
        where ranking.user_id = stale.user_id
     );
  get diagnostics v_sync_guard_count = row_count;

  return pg_catalog.jsonb_build_object(
    'expired_snapshots_deleted', v_snapshot_count,
    'expired_rosters_deleted', v_roster_count,
    'stale_roster_call_guards_deleted', v_roster_call_guard_count,
    'stale_roster_issuance_guards_deleted', v_roster_issuance_guard_count,
    'stale_sync_guards_deleted', v_sync_guard_count
  );
end;
$$;

revoke all on function shared_player_private.cleanup_expired_shared_player_data(integer)
  from public, anon, authenticated, service_role;

create function public.sync_player_network_profile(p_characters jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_shared_snapshots jsonb;
  v_ranking_entries jsonb;
  v_shared_result jsonb;
  v_ranking_preflight jsonb;
  v_ranking_result jsonb;
  v_ranking_count integer;
  v_retry_after_seconds bigint;
begin
  if v_user_id is null then
    raise exception 'authentication required';
  end if;

  -- Lock order is ranking account -> shared-player account -> sync_limits row. Acquire both
  -- advisory locks before the strict ranking preflight reads the shared guard row; otherwise a
  -- concurrent legacy shared publication can hold shared -> row while this path holds ranking ->
  -- row and later waits on shared. Nested functions reacquire these transaction locks safely.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(v_user_id::text, 0)
  );
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('shared-player-account:' || v_user_id::text, 0)
  );

  -- Reject size and cardinality before expanding attacker-controlled JSON.
  if p_characters is null
     or pg_catalog.jsonb_typeof(p_characters) is distinct from 'array'
     or pg_catalog.pg_column_size(p_characters) > 65536
     or pg_catalog.jsonb_array_length(p_characters) > 3 then
    raise exception 'invalid player network profile' using errcode = '22023';
  end if;

  -- This is a complete, strict superset of the two existing wire rows. The reviewed public
  -- snapshot validator remains the single authority for identity, level/power coherence, stats,
  -- traits, versions and opposing-trait exclusion. slot_id is used only by ranking storage.
  if exists (
    select 1
      from pg_catalog.jsonb_array_elements(p_characters) as item(value)
     where pg_catalog.jsonb_typeof(item.value) is distinct from 'object'
        or not (item.value ?& array[
          'character_id','slot_id','display_name','hero_class','level','combat_power',
          'rules_version','snapshot_version','stats','adventure_trait_ids'
        ]::text[])
        or item.value - array[
          'character_id','slot_id','display_name','hero_class','level','combat_power',
          'rules_version','snapshot_version','stats','adventure_trait_ids'
        ]::text[] <> '{}'::jsonb
        or pg_catalog.jsonb_typeof(item.value -> 'slot_id') is distinct from 'number'
        or item.value ->> 'slot_id' !~ '^[1-3]$'
        or not shared_player_private.is_valid_snapshot(item.value - 'slot_id')
  ) or (
    select pg_catalog.count(distinct item.value ->> 'character_id')
      from pg_catalog.jsonb_array_elements(p_characters) as item(value)
  ) <> pg_catalog.jsonb_array_length(p_characters) or (
    select pg_catalog.count(distinct item.value ->> 'slot_id')
      from pg_catalog.jsonb_array_elements(p_characters) as item(value)
  ) <> pg_catalog.jsonb_array_length(p_characters) then
    raise exception 'invalid player network profile' using errcode = '22023';
  end if;

  -- Canonical slot order makes the existing shared payload hash stable regardless of client list
  -- order. Empty input remains a valid complete-roster replacement and removes stale rows through
  -- both existing functions.
  select coalesce(
           pg_catalog.jsonb_agg(item.value - 'slot_id'
             order by (item.value ->> 'slot_id')::smallint),
           '[]'::jsonb
         )
    into v_shared_snapshots
    from pg_catalog.jsonb_array_elements(p_characters) as item(value);

  select coalesce(
           pg_catalog.jsonb_agg(
             pg_catalog.jsonb_build_object(
               'character_id', item.value ->> 'character_id',
               'slot_id', (item.value ->> 'slot_id')::smallint,
               'display_name', item.value ->> 'display_name',
               'hero_class', item.value ->> 'hero_class',
               'level', (item.value ->> 'level')::bigint,
               'combat_power', (item.value ->> 'combat_power')::bigint
             ) order by (item.value ->> 'slot_id')::smallint
           ),
           '[]'::jsonb
         )
    into v_ranking_entries
    from pg_catalog.jsonb_array_elements(p_characters) as item(value)
   where (item.value ->> 'level')::bigint >= 20;
  v_ranking_count := pg_catalog.jsonb_array_length(v_ranking_entries);

  -- Check the ranking server-time guards before shared publication. A legacy-compatible ranking
  -- rejection is a normal-return HTTP 429, so calling the void wrapper only after shared mutation
  -- could otherwise commit new snapshots with the previous ranking row. The preflight is read-only;
  -- a rejected apply records only the legacy call guard while both published stores stay unchanged.
  v_ranking_preflight := ranking_private.sync_ranking_entries_outcome(
    v_ranking_entries,
    false
  );
  if not coalesce((v_ranking_preflight ->> 'accepted')::boolean, false) then
    v_ranking_result := ranking_private.sync_ranking_entries_outcome(
      v_ranking_entries,
      true
    );
    if coalesce((v_ranking_result ->> 'accepted')::boolean, false) then
      raise exception 'ranking preflight outcome changed during locked profile sync'
        using errcode = '40001';
    end if;
    v_retry_after_seconds := greatest(
      1,
      coalesce((v_ranking_result ->> 'retry_after_seconds')::bigint, 5)
    );
    perform pg_catalog.set_config('response.status', '429', true);
    perform pg_catalog.set_config(
      'response.headers',
      pg_catalog.jsonb_build_array(
        pg_catalog.jsonb_build_object('Retry-After', v_retry_after_seconds::text)
      )::text,
      true
    );
    return v_ranking_result || pg_catalog.jsonb_build_object(
      'profile_version', 1,
      'rules_version', 1,
      'accepted', false,
      'synced_count', -1,
      'ranking_synced_count', 0
    );
  end if;

  -- After the read-only ranking preflight, shared publication runs before ranking mutation because
  -- it has the stronger monotonic-level and stat/power coherence checks. A soft rejection cannot
  -- update ranking because apply happens only afterward. Any exception in the strict ranking apply
  -- rolls back the preceding shared write.
  v_shared_result := public.sync_public_player_snapshots(v_shared_snapshots);
  if coalesce((v_shared_result ->> 'synced_count')::integer, -1) < 0 then
    return v_shared_result || pg_catalog.jsonb_build_object(
      'profile_version', 1,
      'accepted', false,
      'ranking_synced_count', 0
    );
  end if;

  v_ranking_result := ranking_private.sync_ranking_entries_outcome(
    v_ranking_entries,
    true
  );
  if not coalesce((v_ranking_result ->> 'accepted')::boolean, false) then
    raise exception 'ranking apply was rejected after successful locked preflight'
      using errcode = '40001';
  end if;

  -- This marker is committed only with a full two-store success. It permanently routes later
  -- compatibility ranking writes for this account through the shared-snapshot coherence check.
  update shared_player_private.sync_limits
     set profile_contract_version = 1
   where user_id = v_user_id;

  return v_shared_result || pg_catalog.jsonb_build_object(
    'profile_version', 1,
    'accepted', true,
    'ranking_synced_count', v_ranking_count
  );
end;
$$;

revoke all on function public.sync_player_network_profile(jsonb)
  from public, anon, authenticated, service_role;
grant execute on function public.sync_player_network_profile(jsonb) to authenticated;

comment on function public.sync_player_network_profile(jsonb) is
  'Atomic compatibility bridge: one strict level-10+ character batch feeds existing shared-player storage and its level-20+ subset feeds existing combat-power ranking storage. It accepts no arena result, ticket, score, history, reward or client timestamp.';

-- Migration-time structural guardrails. These inspect catalog metadata only and create no test row.
do $$
declare
  v_definition text;
  v_acl_ok boolean;
begin
  select pg_catalog.pg_get_functiondef(
           'public.sync_player_network_profile(jsonb)'::pg_catalog.regprocedure
         ) into v_definition;
  if pg_catalog.strpos(v_definition, 'auth.uid()') = 0
     or pg_catalog.strpos(v_definition, 'shared_player_private.is_valid_snapshot') = 0
     or pg_catalog.strpos(v_definition, 'public.sync_public_player_snapshots') = 0
     or pg_catalog.strpos(v_definition, 'v_ranking_preflight') = 0
     or pg_catalog.strpos(
          v_definition,
          'ranking_private.sync_ranking_entries_outcome'
        ) = 0 then
    raise exception 'player network profile security contract self-check failed';
  end if;

  select pg_catalog.has_function_privilege(
           'authenticated',
           'public.sync_player_network_profile(jsonb)',
           'EXECUTE'
         )
         and not pg_catalog.has_function_privilege(
           'anon',
           'public.sync_player_network_profile(jsonb)',
           'EXECUTE'
         )
         and not pg_catalog.has_function_privilege(
           'authenticated',
           'ranking_private.sync_ranking_entries_outcome(jsonb,boolean)',
           'EXECUTE'
         )
    into v_acl_ok;
  if not v_acl_ok then
    raise exception 'player network profile ACL self-check failed';
  end if;
end;
$$;
