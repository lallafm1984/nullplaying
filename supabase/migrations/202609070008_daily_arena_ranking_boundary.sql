-- Compact, low-frequency arena season standings and immutable UTC daily reads.
--
-- Requires 007. Arena battles, outcomes, tickets, match history, rewards and settlement remain
-- local-only. The server accepts no match/result object and never authorizes arena economy. The
-- only client-declared values are one owned character's aggregate score and W/L/D counters.

create schema if not exists arena_ranking_private;
revoke all on schema arena_ranking_private from public, anon, authenticated, service_role;

create table arena_ranking_private.daily_arena_leaderboard_snapshots (
  snapshot_id text primary key,
  season_id integer not null check (season_id = 1),
  rules_version integer not null check (rules_version = 1),
  settled_at timestamptz not null,
  generated_at timestamptz not null,
  is_bootstrap boolean not null default false,
  participant_count integer not null check (participant_count >= 0),
  top_entries jsonb not null check (
    pg_catalog.jsonb_typeof(top_entries) = 'array'
    and pg_catalog.jsonb_array_length(top_entries) <= 997
    and pg_catalog.pg_column_size(top_entries) <= 491520
  ),
  unique (season_id, rules_version, settled_at)
);

create table arena_ranking_private.daily_arena_leaderboard_state (
  singleton boolean primary key default true check (singleton),
  season_id integer not null check (season_id = 1),
  rules_version integer not null check (rules_version = 1),
  tracking_started_at timestamptz not null,
  latest_snapshot_id text references arena_ranking_private.daily_arena_leaderboard_snapshots(snapshot_id),
  last_settled_at timestamptz
);

create table arena_ranking_private.season_standings (
  season_id integer not null check (season_id = 1),
  rules_version integer not null check (rules_version = 1),
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
  score integer not null check (score between 0 and 25000000),
  completed_battles integer not null check (completed_battles between 10 and 1000000),
  wins integer not null check (wins between 0 and 1000000),
  losses integer not null check (losses between 0 and 1000000),
  draws integer not null check (draws between 0 and 1000000),
  score_achieved_at timestamptz not null,
  profile_expires_at timestamptz not null,
  updated_at timestamptz not null,
  daily_record_date_utc date not null,
  daily_record_base integer not null check (daily_record_base between 0 and 1000000),
  daily_admitted_at timestamptz not null,
  primary key (season_id, user_id, character_id),
  check (wins::bigint + losses::bigint + draws::bigint = completed_battles::bigint),
  check (daily_record_base <= completed_battles),
  check (
    score::bigint between greatest(0::bigint, 1000::bigint - 24::bigint * completed_battles::bigint)
      and 1000::bigint + 24::bigint * completed_battles::bigint
  )
);

create index arena_season_standings_rank_idx
  on arena_ranking_private.season_standings (
    season_id, rules_version, score desc, wins desc, losses asc, draws desc,
    score_achieved_at asc, character_id,
    (pg_catalog.md5('arena-ranking-public-v1:' || user_id::text)::uuid)
  );

create index arena_season_standings_profile_idx
  on arena_ranking_private.season_standings (user_id, character_id);

-- The server owns three reusable season slots. Profile expiry/removal keeps the current slot and
-- its anti-cheat floor intact. A genuinely replacement character may reuse only an inactive slot
-- and must enter with its ten-match placement aggregate, which bounds UUID rotation without
-- permanently blocking normal character deletion/recreation.
create table arena_ranking_private.season_character_bindings (
  season_id integer not null check (season_id = 1),
  user_id uuid not null references auth.users(id) on delete cascade,
  binding_slot smallint not null check (binding_slot between 1 and 3),
  character_id uuid not null,
  bound_at timestamptz not null,
  primary key (season_id, user_id, binding_slot),
  unique (season_id, user_id, character_id)
);

create index arena_season_character_bindings_user_idx
  on arena_ranking_private.season_character_bindings (user_id, season_id, binding_slot);

create table arena_ranking_private.account_call_limits (
  user_id uuid primary key references auth.users(id) on delete cascade,
  call_date_utc date not null,
  call_count smallint not null check (call_count between 1 and 24),
  last_called_at timestamptz not null
);

create index arena_account_call_limits_cleanup_idx
  on arena_ranking_private.account_call_limits (last_called_at, user_id);

create table arena_ranking_private.entry_sync_limits (
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  last_called_at timestamptz not null,
  last_synced_at timestamptz,
  payload_hash text,
  primary key (user_id, character_id),
  check (
    (last_synced_at is null and payload_hash is null)
    or (
      last_synced_at is not null
      and last_called_at >= last_synced_at
      and payload_hash ~ '^[0-9a-f]{32}$'
    )
  )
);

create index arena_entry_sync_limits_cleanup_idx
  on arena_ranking_private.entry_sync_limits (last_called_at, user_id, character_id);

create table arena_ranking_private.daily_arena_leaderboard_rows (
  snapshot_id text not null references arena_ranking_private.daily_arena_leaderboard_snapshots(snapshot_id)
    on delete cascade,
  user_id uuid not null,
  character_id uuid not null,
  rank_number bigint not null check (rank_number >= 1),
  list_index bigint not null check (list_index >= 0),
  display_name text not null,
  hero_class text not null,
  level bigint not null,
  score integer not null,
  completed_battles integer not null,
  wins integer not null,
  losses integer not null,
  draws integer not null,
  score_achieved_at timestamptz not null,
  compact_entry jsonb not null check (
    pg_catalog.jsonb_typeof(compact_entry) = 'object'
    and pg_catalog.pg_column_size(compact_entry) <= 512
  ),
  primary key (snapshot_id, user_id, character_id),
  unique (snapshot_id, list_index)
);

create index daily_arena_rows_owner_idx
  on arena_ranking_private.daily_arena_leaderboard_rows (snapshot_id, user_id, list_index);

create table arena_ranking_private.daily_arena_before_images (
  season_id integer not null,
  user_id uuid not null,
  character_id uuid not null,
  cutoff_at timestamptz not null,
  row_data jsonb not null check (
    pg_catalog.jsonb_typeof(row_data) = 'object'
    and pg_catalog.pg_column_size(row_data) <= 4096
  ),
  primary key (season_id, user_id, character_id)
);

create index daily_arena_before_images_cutoff_idx
  on arena_ranking_private.daily_arena_before_images (cutoff_at, season_id);

alter table arena_ranking_private.daily_arena_leaderboard_snapshots enable row level security;
alter table arena_ranking_private.daily_arena_leaderboard_state enable row level security;
alter table arena_ranking_private.season_standings enable row level security;
alter table arena_ranking_private.season_character_bindings enable row level security;
alter table arena_ranking_private.account_call_limits enable row level security;
alter table arena_ranking_private.entry_sync_limits enable row level security;
alter table arena_ranking_private.daily_arena_leaderboard_rows enable row level security;
alter table arena_ranking_private.daily_arena_before_images enable row level security;

revoke all on table arena_ranking_private.daily_arena_leaderboard_snapshots,
  arena_ranking_private.daily_arena_leaderboard_state,
  arena_ranking_private.season_standings,
  arena_ranking_private.season_character_bindings,
  arena_ranking_private.account_call_limits,
  arena_ranking_private.entry_sync_limits,
  arena_ranking_private.daily_arena_leaderboard_rows,
  arena_ranking_private.daily_arena_before_images
  from public, anon, authenticated, service_role;

insert into arena_ranking_private.daily_arena_leaderboard_state (
  season_id, rules_version, tracking_started_at
) values (1, 1, pg_catalog.clock_timestamp());

-- Every standing writer, including the shared-profile identity mirror and server slot replacement,
-- takes the shared barrier before row locks. Publication takes the exclusive counterpart. Keeping
-- this as a statement trigger avoids lock-order inversions and makes the cutoff cover every write
-- path.
create function arena_ranking_private.lock_daily_arena_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  perform pg_catalog.pg_advisory_xact_lock_shared(20260908, 8);
  return null;
end;
$$;

-- The row trigger also covers the explicit DELETE used when an inactive server slot is recycled.
-- It acquires a different writer barrier from 007, so the general and arena publishers never block
-- one another. A write admitted after a delayed publication cannot be backdated before it.
create function arena_ranking_private.capture_daily_arena_before_image()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admitted_at timestamptz;
  v_cutoff_at timestamptz;
begin
  select greatest(pg_catalog.statement_timestamp(), state.last_settled_at)
    into v_admitted_at
    from arena_ranking_private.daily_arena_leaderboard_state as state
   where state.singleton;
  v_cutoff_at := pg_catalog.date_trunc('day', v_admitted_at at time zone 'UTC') at time zone 'UTC';

  if tg_op <> 'INSERT' and old.daily_admitted_at < v_cutoff_at then
    insert into arena_ranking_private.daily_arena_before_images as previous (
      season_id, user_id, character_id, cutoff_at, row_data
    ) values (
      old.season_id, old.user_id, old.character_id, v_cutoff_at, pg_catalog.to_jsonb(old)
    )
    on conflict (season_id, user_id, character_id) do update
      set cutoff_at = excluded.cutoff_at,
          row_data = excluded.row_data
    where previous.cutoff_at < excluded.cutoff_at;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  new.daily_admitted_at := v_admitted_at;
  return new;
end;
$$;

revoke all on function arena_ranking_private.lock_daily_arena_write(),
  arena_ranking_private.capture_daily_arena_before_image()
  from public, anon, authenticated, service_role;

create trigger arena_season_standings_daily_lock
before insert or update or delete on arena_ranking_private.season_standings
for each statement execute function arena_ranking_private.lock_daily_arena_write();

create trigger arena_season_standings_daily_capture
before insert or update or delete on arena_ranking_private.season_standings
for each row execute function arena_ranking_private.capture_daily_arena_before_image();

-- Arena anti-cheat history must outlive the 72-hour shared projection. INSERT/UPDATE reactivates
-- and refreshes identity without another arena match; DELETE expires the standing at server time
-- without deleting its record/score/daily floor. Account deletion still cascades from auth.users.
-- The standing trigger above preserves the prior UTC cutoff image for every mirror update.
create function arena_ranking_private.mirror_shared_snapshot_identity()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- Match the public writer's lock order: daily cutoff barrier, account advisory, then standing
  -- rows. This serializes a profile refresh/delete with inactive-slot replacement.
  perform pg_catalog.pg_advisory_xact_lock_shared(20260908, 8);
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended(
      'arena-ranking-account:' || case when tg_op = 'DELETE' then old.user_id else new.user_id end::text,
      0
    )
  );

  if tg_op = 'DELETE' then
    update arena_ranking_private.season_standings as standing
       set profile_expires_at = pg_catalog.statement_timestamp(),
           updated_at = pg_catalog.statement_timestamp()
     where standing.user_id = old.user_id
       and standing.character_id = old.character_id;
    return old;
  end if;

  update arena_ranking_private.season_standings as standing
     set display_name = new.display_name,
         hero_class = new.hero_class,
         level = new.level,
         profile_expires_at = new.expires_at,
         updated_at = pg_catalog.statement_timestamp()
   where standing.user_id = new.user_id
     and standing.character_id = new.character_id
     and (
       standing.display_name is distinct from new.display_name
       or standing.hero_class is distinct from new.hero_class
       or standing.level is distinct from new.level
       or standing.profile_expires_at is distinct from new.expires_at
     );
  return new;
end;
$$;

revoke all on function arena_ranking_private.mirror_shared_snapshot_identity()
  from public, anon, authenticated, service_role;

create trigger shared_snapshot_mirror_arena_identity_upsert
after insert or update of display_name, hero_class, level, expires_at
on shared_player_private.snapshots
for each row execute function arena_ranking_private.mirror_shared_snapshot_identity();

create trigger shared_snapshot_expire_arena_identity_delete
after delete on shared_player_private.snapshots
for each row execute function arena_ranking_private.mirror_shared_snapshot_identity();

create function public.sync_arena_ranking_entry(
  p_character_id uuid,
  p_score integer,
  p_completed_battles integer,
  p_wins integer,
  p_losses integer,
  p_draws integer,
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
  v_today date;
  v_valid_until timestamptz;
  v_season_id integer;
  v_server_rules_version integer;
  v_last_settled_at timestamptz;
  v_account_created_at timestamptz;
  v_account_max_battles bigint;
  v_display_name text;
  v_hero_class text;
  v_level bigint;
  v_profile_expires_at timestamptz;
  v_payload_hash text;
  v_account_limit arena_ranking_private.account_call_limits%rowtype;
  v_sync_limit arena_ranking_private.entry_sync_limits%rowtype;
  v_existing arena_ranking_private.season_standings%rowtype;
  v_has_sync_limit boolean;
  v_has_existing boolean;
  v_binding_slot smallint;
  v_replaced_character_id uuid;
  v_completed_delta bigint;
  v_daily_base integer;
  v_score_achieved_at timestamptz;
  v_retry_after_seconds bigint;
begin
  if v_user_id is null then
    raise exception 'authentication required';
  end if;
  select users.created_at into strict v_account_created_at
    from auth.users as users where users.id = v_user_id;

  -- Separate barrier key and staggered cron keep arena publication independent from 007. Acquire
  -- the barrier before account/row locks. If this statement waited behind midnight publication,
  -- last_settled_at advances v_now so the write cannot be admitted into the completed cutoff.
  perform pg_catalog.pg_advisory_xact_lock_shared(20260908, 8);
  select state.season_id, state.rules_version, state.last_settled_at
    into strict v_season_id, v_server_rules_version, v_last_settled_at
    from arena_ranking_private.daily_arena_leaderboard_state as state
   where state.singleton;
  v_now := greatest(v_now, v_last_settled_at);
  v_today := (v_now at time zone 'UTC')::date;
  v_valid_until := (
    pg_catalog.date_trunc('day', v_now at time zone 'UTC') + interval '1 day'
  ) at time zone 'UTC';

  -- The account guard comes before semantic validation. Expected invalid or ineligible calls use
  -- a normal receipt below, so this mutation commits and repeated forged payloads consume the same
  -- small server-side budget as valid calls. Authentication failures and unexpected SQL errors
  -- still raise and roll the statement back.
  perform pg_catalog.pg_advisory_xact_lock(
    pg_catalog.hashtextextended('arena-ranking-account:' || v_user_id::text, 0)
  );
  select * into v_account_limit
    from arena_ranking_private.account_call_limits as guard
   where guard.user_id = v_user_id
   for update;

  if found and v_now - v_account_limit.last_called_at < interval '5 seconds' then
    v_retry_after_seconds := greatest(
      1,
      pg_catalog.ceil(pg_catalog.date_part(
        'epoch', v_account_limit.last_called_at + interval '5 seconds' - v_now
      ))::bigint
    );
    perform pg_catalog.set_config('response.status', '429', true);
    perform pg_catalog.set_config(
      'response.headers',
      pg_catalog.jsonb_build_array(
        pg_catalog.jsonb_build_object('Retry-After', v_retry_after_seconds::text)
      )::text,
      true
    );
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'rate_limited', true,
      'retry_after_seconds', v_retry_after_seconds
    );
  end if;

  if found
     and v_account_limit.call_date_utc = v_today
     and v_account_limit.call_count >= 24 then
    v_retry_after_seconds := greatest(
      1,
      pg_catalog.ceil(pg_catalog.date_part('epoch', v_valid_until - v_now))::bigint
    );
    update arena_ranking_private.account_call_limits
       set last_called_at = v_now
     where user_id = v_user_id;
    perform pg_catalog.set_config('response.status', '429', true);
    perform pg_catalog.set_config(
      'response.headers',
      pg_catalog.jsonb_build_array(
        pg_catalog.jsonb_build_object('Retry-After', v_retry_after_seconds::text)
      )::text,
      true
    );
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'rate_limited', true,
      'daily_limit', true,
      'retry_after_seconds', v_retry_after_seconds
    );
  end if;

  insert into arena_ranking_private.account_call_limits as guard (
    user_id, call_date_utc, call_count, last_called_at
  ) values (
    v_user_id, v_today, 1, v_now
  )
  on conflict (user_id) do update set
    call_date_utc = excluded.call_date_utc,
    call_count = case
      when guard.call_date_utc = excluded.call_date_utc then guard.call_count + 1
      else 1
    end,
    last_called_at = excluded.last_called_at;

  if p_character_id is null
     or p_rules_version is distinct from v_server_rules_version
     or p_score is null
     or p_completed_battles is null
     or p_wins is null
     or p_losses is null
     or p_draws is null
     or p_score not between 0 and 25000000
     or p_completed_battles not between 10 and 1000000
     or p_wins not between 0 and 1000000
     or p_losses not between 0 and 1000000
     or p_draws not between 0 and 1000000 then
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'invalid', true,
      'error_code', 'invalid_standing'
    );
  end if;
  if p_wins::bigint + p_losses::bigint + p_draws::bigint <> p_completed_battles::bigint
     or p_score::bigint < greatest(
          0::bigint,
          1000::bigint - 24::bigint * p_completed_battles::bigint
        )
     or p_score::bigint > 1000::bigint + 24::bigint * p_completed_battles::bigint then
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'invalid', true,
      'error_code', 'invalid_standing'
    );
  end if;

  -- Identity is copied from the already-hardened 006 shared projection. No client
  -- name, class, level, slot, equipment, skill or combat result can enter this write.
  select snapshot.display_name, snapshot.hero_class, snapshot.level, snapshot.expires_at
    into v_display_name, v_hero_class, v_level, v_profile_expires_at
    from shared_player_private.snapshots as snapshot
   where snapshot.user_id = v_user_id
     and snapshot.character_id = p_character_id
     and snapshot.rules_version = v_server_rules_version
     and snapshot.level between 10 and 10000
     and snapshot.expires_at > v_now;
  if not found then
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'invalid', true,
      'error_code', 'eligible_owned_profile_required'
    );
  end if;

  -- At most twenty local official matches can be consumed per trusted UTC day. Account age is a
  -- coarse first-publication ceiling; it limits forged legacy imports without claiming that the
  -- server can prove individual local results.
  if v_account_created_at > v_now then
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'invalid', true,
      'error_code', 'invalid_account_time'
    );
  end if;
  v_account_max_battles := least(
    1000000::bigint,
    (pg_catalog.floor(pg_catalog.date_part('epoch', v_now - v_account_created_at) / 86400.0)::bigint + 1)
      * 20::bigint
  );
  if p_completed_battles::bigint > v_account_max_battles then
    v_retry_after_seconds := greatest(
      1,
      pg_catalog.ceil(pg_catalog.date_part('epoch', v_valid_until - v_now))::bigint
        + greatest(
            0,
            pg_catalog.ceil(
              (p_completed_battles::bigint - v_account_max_battles)::numeric / 20::numeric
            )::bigint - 1
          ) * 86400
    );
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'invalid', true,
      'error_code', 'account_age_limit',
      'retry_after_seconds', v_retry_after_seconds
    );
  end if;

  -- Existing UUIDs keep their server slot across profile expiry/removal. A new UUID first uses an
  -- empty slot. Once all three slots exist, only the oldest slot whose previous shared projection
  -- is both expired and absent may be recycled. The account advisory above serializes allocation,
  -- mirror refresh and replacement; the selected binding and standing are also row-locked.
  select binding.binding_slot into v_binding_slot
    from arena_ranking_private.season_character_bindings as binding
   where binding.season_id = v_season_id
     and binding.user_id = v_user_id
     and binding.character_id = p_character_id
   for update;

  if not found then
    select candidate.binding_slot into v_binding_slot
      from (values (1::smallint), (2::smallint), (3::smallint)) as candidate(binding_slot)
     where not exists (
       select 1
         from arena_ranking_private.season_character_bindings as binding
        where binding.season_id = v_season_id
          and binding.user_id = v_user_id
          and binding.binding_slot = candidate.binding_slot
     )
     order by candidate.binding_slot
     limit 1;

    if found then
      insert into arena_ranking_private.season_character_bindings (
        season_id, user_id, binding_slot, character_id, bound_at
      ) values (
        v_season_id, v_user_id, v_binding_slot, p_character_id, v_now
      );
    else
      select binding.binding_slot, binding.character_id
        into v_binding_slot, v_replaced_character_id
        from arena_ranking_private.season_character_bindings as binding
        join arena_ranking_private.season_standings as previous
          on previous.season_id = binding.season_id
         and previous.user_id = binding.user_id
         and previous.character_id = binding.character_id
       where binding.season_id = v_season_id
         and binding.user_id = v_user_id
         and previous.profile_expires_at <= v_now
         and not exists (
           select 1
             from shared_player_private.snapshots as active_profile
            where active_profile.user_id = binding.user_id
              and active_profile.character_id = binding.character_id
              and active_profile.rules_version = v_server_rules_version
              and active_profile.expires_at > v_now
         )
       order by previous.profile_expires_at, binding.bound_at, binding.binding_slot
       limit 1
       for update of binding, previous;

      if not found then
        return pg_catalog.jsonb_build_object(
          'accepted', false,
          'season_id', v_season_id::text,
          'rules_version', v_server_rules_version,
          'server_now', (extract(epoch from v_now) * 1000)::bigint,
          'invalid', true,
          'error_code', 'season_character_limit'
        );
      end if;

      if p_completed_battles <> 10 then
        return pg_catalog.jsonb_build_object(
          'accepted', false,
          'season_id', v_season_id::text,
          'rules_version', v_server_rules_version,
          'server_now', (extract(epoch from v_now) * 1000)::bigint,
          'invalid', true,
          'error_code', 'replacement_requires_placement'
        );
      end if;

      -- The standing DELETE fires the daily before-image trigger before any history is removed.
      -- The guard is locked and removed next, then the same server slot is assigned atomically.
      perform 1
        from arena_ranking_private.entry_sync_limits as old_guard
       where old_guard.user_id = v_user_id
         and old_guard.character_id = v_replaced_character_id
       for update;
      delete from arena_ranking_private.season_standings
       where season_id = v_season_id
         and user_id = v_user_id
         and character_id = v_replaced_character_id;
      delete from arena_ranking_private.entry_sync_limits
       where user_id = v_user_id
         and character_id = v_replaced_character_id;
      update arena_ranking_private.season_character_bindings
         set character_id = p_character_id,
             bound_at = v_now
       where season_id = v_season_id
         and user_id = v_user_id
         and binding_slot = v_binding_slot;
    end if;
  end if;

  v_payload_hash := pg_catalog.md5(pg_catalog.jsonb_build_object(
    'season_id', v_season_id::text,
    'rules_version', p_rules_version,
    'character_id', p_character_id,
    'score', p_score,
    'completed_battles', p_completed_battles,
    'wins', p_wins,
    'losses', p_losses,
    'draws', p_draws
  )::text);

  select * into v_sync_limit
    from arena_ranking_private.entry_sync_limits as guard
   where guard.user_id = v_user_id
     and guard.character_id = p_character_id
   for update;
  v_has_sync_limit := found;

  -- Profile expiry/removal normally preserves the standing. Still lock and verify the standing
  -- before returning a hash dedupe receipt so a repaired/imported database cannot acknowledge a
  -- guard whose row is missing. An identical previously accepted aggregate may recreate such a
  -- missing row immediately instead of being trapped behind its own 15-minute cooldown.
  select * into v_existing
    from arena_ranking_private.season_standings as standing
   where standing.season_id = v_season_id
     and standing.user_id = v_user_id
     and standing.character_id = p_character_id
   for update;
  v_has_existing := found;

  if v_has_sync_limit
     and v_has_existing
     and v_sync_limit.payload_hash = v_payload_hash then
    update arena_ranking_private.entry_sync_limits
       set last_called_at = v_now
     where user_id = v_user_id and character_id = p_character_id;
    return pg_catalog.jsonb_build_object(
      'accepted', true,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'deduplicated', true
    );
  end if;

  if v_has_sync_limit
     and v_sync_limit.last_synced_at is not null
     and v_now - v_sync_limit.last_synced_at < interval '15 minutes'
     and v_sync_limit.payload_hash is distinct from v_payload_hash then
    v_retry_after_seconds := greatest(
      1,
      pg_catalog.ceil(pg_catalog.date_part(
        'epoch', v_sync_limit.last_synced_at + interval '15 minutes' - v_now
      ))::bigint
    );
    update arena_ranking_private.entry_sync_limits
       set last_called_at = v_now
     where user_id = v_user_id and character_id = p_character_id;
    perform pg_catalog.set_config('response.status', '429', true);
    perform pg_catalog.set_config(
      'response.headers',
      pg_catalog.jsonb_build_array(
        pg_catalog.jsonb_build_object('Retry-After', v_retry_after_seconds::text)
      )::text,
      true
    );
    return pg_catalog.jsonb_build_object(
      'accepted', false,
      'season_id', v_season_id::text,
      'rules_version', v_server_rules_version,
      'server_now', (extract(epoch from v_now) * 1000)::bigint,
      'rate_limited', true,
      'retry_after_seconds', v_retry_after_seconds
    );
  end if;

  if v_has_existing then
    if p_completed_battles < v_existing.completed_battles
       or p_wins < v_existing.wins
       or p_losses < v_existing.losses
       or p_draws < v_existing.draws then
      return pg_catalog.jsonb_build_object(
        'accepted', false,
        'season_id', v_season_id::text,
        'rules_version', v_server_rules_version,
        'server_now', (extract(epoch from v_now) * 1000)::bigint,
        'invalid', true,
        'error_code', 'record_decrease'
      );
    end if;
    v_completed_delta := p_completed_battles::bigint - v_existing.completed_battles::bigint;
    if pg_catalog.abs(p_score::bigint - v_existing.score::bigint) > 24::bigint * v_completed_delta then
      return pg_catalog.jsonb_build_object(
        'accepted', false,
        'season_id', v_season_id::text,
        'rules_version', v_server_rules_version,
        'server_now', (extract(epoch from v_now) * 1000)::bigint,
        'invalid', true,
        'error_code', 'score_movement_limit'
      );
    end if;
    v_daily_base := case
      when v_existing.daily_record_date_utc = v_today then v_existing.daily_record_base
      else v_existing.completed_battles
    end;
    if p_completed_battles::bigint - v_daily_base::bigint > 20 then
      v_retry_after_seconds := greatest(
        1,
        pg_catalog.ceil(pg_catalog.date_part('epoch', v_valid_until - v_now))::bigint
      );
      return pg_catalog.jsonb_build_object(
        'accepted', false,
        'season_id', v_season_id::text,
        'rules_version', v_server_rules_version,
        'server_now', (extract(epoch from v_now) * 1000)::bigint,
        'invalid', true,
        'error_code', 'daily_match_limit',
        'retry_after_seconds', v_retry_after_seconds
      );
    end if;
    v_score_achieved_at := case
      when p_score is distinct from v_existing.score then v_now
      else v_existing.score_achieved_at
    end;
  else
    v_completed_delta := p_completed_battles;
    v_daily_base := p_completed_battles;
    v_score_achieved_at := v_now;
  end if;

  insert into arena_ranking_private.season_standings as standing (
    season_id, rules_version, user_id, character_id, display_name, hero_class, level,
    score, completed_battles, wins, losses, draws, score_achieved_at, profile_expires_at, updated_at,
    daily_record_date_utc, daily_record_base, daily_admitted_at
  ) values (
    v_season_id, v_server_rules_version, v_user_id, p_character_id,
    v_display_name, v_hero_class, v_level,
    p_score, p_completed_battles, p_wins, p_losses, p_draws,
    v_score_achieved_at, v_profile_expires_at, v_now, v_today, v_daily_base, v_now
  )
  on conflict (season_id, user_id, character_id) do update set
    rules_version = excluded.rules_version,
    display_name = excluded.display_name,
    hero_class = excluded.hero_class,
    level = excluded.level,
    score = excluded.score,
    completed_battles = excluded.completed_battles,
    wins = excluded.wins,
    losses = excluded.losses,
    draws = excluded.draws,
    score_achieved_at = excluded.score_achieved_at,
    profile_expires_at = excluded.profile_expires_at,
    updated_at = excluded.updated_at,
    daily_record_date_utc = excluded.daily_record_date_utc,
    daily_record_base = excluded.daily_record_base,
    daily_admitted_at = excluded.daily_admitted_at;

  insert into arena_ranking_private.entry_sync_limits as guard (
    user_id, character_id, last_called_at, last_synced_at, payload_hash
  ) values (
    v_user_id, p_character_id, v_now, v_now, v_payload_hash
  )
  on conflict (user_id, character_id) do update set
    last_called_at = excluded.last_called_at,
    last_synced_at = excluded.last_synced_at,
    payload_hash = excluded.payload_hash;

  return pg_catalog.jsonb_build_object(
    'accepted', true,
    'season_id', v_season_id::text,
    'rules_version', v_server_rules_version,
    'server_now', (extract(epoch from v_now) * 1000)::bigint,
    'deduplicated', false
  );
end;
$$;

revoke all on function public.sync_arena_ranking_entry(
  uuid, integer, integer, integer, integer, integer, integer
) from public, anon, authenticated, service_role;
grant execute on function public.sync_arena_ranking_entry(
  uuid, integer, integer, integer, integer, integer, integer
) to authenticated;

comment on function public.sync_arena_ranking_entry(
  uuid, integer, integer, integer, integer, integer, integer
) is 'Low-frequency client-declared arena aggregate only. It accepts no battle result, ticket, reward, match log or client timestamp and authorizes no economy.';

create function arena_ranking_private.publish_daily_arena_leaderboard(
  p_cutoff_at timestamptz default null
)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_cutoff_at timestamptz;
  v_state arena_ranking_private.daily_arena_leaderboard_state%rowtype;
  v_snapshot_id text;
  v_bootstrap boolean;
begin
  if pg_catalog.current_setting('transaction_isolation') <> 'read committed' then
    raise exception 'daily arena publication requires READ COMMITTED';
  end if;

  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  v_cutoff_at := coalesce(
    p_cutoff_at,
    pg_catalog.date_trunc('day', v_now at time zone 'UTC') at time zone 'UTC'
  );
  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future arena cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;

  perform pg_catalog.pg_advisory_xact_lock(20260908, 8);
  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from arena_ranking_private.daily_arena_leaderboard_state where singleton;
  v_bootstrap := v_state.latest_snapshot_id is null;
  v_cutoff_at := coalesce(
    p_cutoff_at,
    case when v_bootstrap then v_now
      else pg_catalog.date_trunc('day', v_now at time zone 'UTC') at time zone 'UTC'
    end
  );

  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future arena cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  if v_bootstrap then
    if p_cutoff_at is not null then
      raise exception 'arena bootstrap cutoff must use actual server time';
    end if;
  elsif v_cutoff_at < v_state.tracking_started_at
     or v_cutoff_at <> (
       pg_catalog.date_trunc('day', v_now at time zone 'UTC') at time zone 'UTC'
     ) then
    raise exception 'only the latest tracked UTC midnight can be settled for arena';
  end if;

  v_snapshot_id := 'arena:s' || v_state.season_id::text || ':r' ||
    v_state.rules_version::text || ':' || case when v_bootstrap then 'bootstrap:' else 'utc:' end ||
    ((extract(epoch from v_cutoff_at) * 1000)::bigint)::text;

  insert into arena_ranking_private.daily_arena_leaderboard_snapshots (
    snapshot_id, season_id, rules_version, settled_at, generated_at,
    is_bootstrap, participant_count, top_entries
  ) values (
    v_snapshot_id, v_state.season_id, v_state.rules_version, v_cutoff_at, v_now,
    v_bootstrap, 0, '[]'::jsonb
  );

  with cutoff_rows as (
    select standing.season_id, standing.rules_version, standing.user_id,
           standing.character_id, standing.display_name, standing.hero_class,
           standing.level, standing.score, standing.completed_battles,
           standing.wins, standing.losses, standing.draws,
           standing.score_achieved_at, standing.profile_expires_at, standing.updated_at
      from arena_ranking_private.season_standings as standing
     where standing.season_id = v_state.season_id
       and standing.rules_version = v_state.rules_version
       and (v_bootstrap or standing.daily_admitted_at < v_cutoff_at)
    union all
    select standing.season_id, standing.rules_version, standing.user_id,
           standing.character_id, standing.display_name, standing.hero_class,
           standing.level, standing.score, standing.completed_battles,
           standing.wins, standing.losses, standing.draws,
           standing.score_achieved_at, standing.profile_expires_at, standing.updated_at
      from arena_ranking_private.daily_arena_before_images as saved
      cross join lateral pg_catalog.jsonb_populate_record(
        null::arena_ranking_private.season_standings,
        saved.row_data
      ) as standing
     where not v_bootstrap
       and saved.season_id = v_state.season_id
       and saved.cutoff_at = v_cutoff_at
  ), ranked as (
    select standing.*,
           rank() over (
             order by standing.score desc, standing.wins desc,
                      standing.losses asc, standing.draws desc
           ) as rank_number,
           row_number() over (
             order by standing.score desc, standing.wins desc,
                      standing.losses asc, standing.draws desc,
                      standing.score_achieved_at asc,
                      standing.character_id,
                      pg_catalog.md5('arena-ranking-public-v1:' || standing.user_id::text)::uuid
           ) - 1 as list_index
     from cutoff_rows as standing
     where standing.level between 10 and 10000
       and standing.profile_expires_at > v_cutoff_at
       and standing.completed_battles between 10 and 1000000
       and standing.wins::bigint + standing.losses::bigint + standing.draws::bigint =
           standing.completed_battles::bigint
       and standing.score::bigint between greatest(
         0::bigint,
         1000::bigint - 24::bigint * standing.completed_battles::bigint
       ) and 1000::bigint + 24::bigint * standing.completed_battles::bigint
  )
  insert into arena_ranking_private.daily_arena_leaderboard_rows (
    snapshot_id, user_id, character_id, rank_number, list_index,
    display_name, hero_class, level, score, completed_battles,
    wins, losses, draws, score_achieved_at, compact_entry
  )
  select v_snapshot_id, standing.user_id, standing.character_id,
         standing.rank_number, standing.list_index,
         standing.display_name, standing.hero_class, standing.level,
         standing.score, standing.completed_battles,
         standing.wins, standing.losses, standing.draws, standing.score_achieved_at,
         pg_catalog.jsonb_build_object(
           'r', standing.rank_number,
           'i', standing.list_index,
           'u', pg_catalog.md5('arena-ranking-public-v1:' || standing.user_id::text)::uuid,
           'c', standing.character_id,
           'n', standing.display_name,
           'h', standing.hero_class,
           'l', standing.level,
           'p', standing.score,
           'b', standing.completed_battles,
           'w', standing.wins,
           'x', standing.losses,
           'd', standing.draws,
           'a', (extract(epoch from standing.score_achieved_at) * 1000)::bigint
         )
    from ranked as standing;

  update arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
     set participant_count = (
       select pg_catalog.count(*)::integer
         from arena_ranking_private.daily_arena_leaderboard_rows as row
        where row.snapshot_id = v_snapshot_id
     ),
         top_entries = coalesce((
           select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
             from arena_ranking_private.daily_arena_leaderboard_rows as row
            where row.snapshot_id = v_snapshot_id
              and row.list_index < 997
         ), '[]'::jsonb),
         generated_at = pg_catalog.clock_timestamp()
   where snapshot.snapshot_id = v_snapshot_id;

  update arena_ranking_private.daily_arena_leaderboard_state
     set latest_snapshot_id = v_snapshot_id,
         last_settled_at = v_cutoff_at
   where singleton;

  delete from arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
   where snapshot.snapshot_id not in (
     select keep.snapshot_id
       from arena_ranking_private.daily_arena_leaderboard_snapshots as keep
      order by keep.settled_at desc
      limit 2
   );
  delete from arena_ranking_private.daily_arena_before_images
   where cutoff_at <= v_cutoff_at;

  -- Once-daily bounded guard cleanup; the 23 ordinary hourly no-op calls return before this work.
  with doomed as (
    select guard.user_id, guard.character_id
      from arena_ranking_private.entry_sync_limits as guard
     where guard.last_called_at <= v_now - interval '90 days'
       and not exists (
         select 1 from arena_ranking_private.season_standings as standing
          where standing.user_id = guard.user_id
            and standing.character_id = guard.character_id
       )
     order by guard.last_called_at, guard.user_id, guard.character_id
     limit 500
     for update of guard skip locked
  )
  delete from arena_ranking_private.entry_sync_limits as guard
   using doomed
   where guard.user_id = doomed.user_id
     and guard.character_id = doomed.character_id;

  with doomed as (
    select guard.user_id
      from arena_ranking_private.account_call_limits as guard
     where guard.last_called_at <= v_now - interval '90 days'
       and not exists (
         select 1 from arena_ranking_private.entry_sync_limits as entry
          where entry.user_id = guard.user_id
       )
       and not exists (
         select 1 from arena_ranking_private.season_standings as standing
          where standing.user_id = guard.user_id
       )
     order by guard.last_called_at, guard.user_id
     limit 500
     for update of guard skip locked
  )
  delete from arena_ranking_private.account_call_limits as guard
   using doomed
   where guard.user_id = doomed.user_id;

  return v_snapshot_id;
end;
$$;

revoke all on function arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)
  from public, anon, authenticated, service_role;
grant usage on schema arena_ranking_private to service_role;
grant execute on function arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)
  to service_role;

create function public.get_daily_arena_leaderboard(p_known_snapshot_id text default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot arena_ranking_private.daily_arena_leaderboard_snapshots%rowtype;
  v_response jsonb;
  v_own_entries jsonb;
begin
  if v_user_id is null then
    raise exception 'authentication required';
  end if;
  if p_known_snapshot_id is not null
     and pg_catalog.octet_length(p_known_snapshot_id) > 128 then
    raise exception 'invalid arena snapshot id' using errcode = '22023';
  end if;

  select snapshot.* into strict v_snapshot
    from arena_ranking_private.daily_arena_leaderboard_state as state
    join arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;

  v_response := pg_catalog.jsonb_build_object(
    'snapshot_id', v_snapshot.snapshot_id,
    'season_id', v_snapshot.season_id::text,
    'rules_version', v_snapshot.rules_version,
    'settled_at', (extract(epoch from v_snapshot.settled_at) * 1000)::bigint,
    'next_settlement_at', (extract(epoch from (
      (pg_catalog.date_trunc('day', v_snapshot.settled_at at time zone 'UTC') + interval '1 day')
        at time zone 'UTC'
    )) * 1000)::bigint,
    'generated_at', (extract(epoch from v_snapshot.generated_at) * 1000)::bigint,
    'server_now', (extract(epoch from pg_catalog.statement_timestamp()) * 1000)::bigint,
    'is_bootstrap', v_snapshot.is_bootstrap,
    'unchanged', coalesce(p_known_snapshot_id = v_snapshot.snapshot_id, false),
    't', v_snapshot.participant_count
  );
  if p_known_snapshot_id = v_snapshot.snapshot_id then
    return v_response;
  end if;

  select coalesce(pg_catalog.jsonb_agg(owned.compact_entry order by owned.list_index), '[]'::jsonb)
    into v_own_entries
    from (
      select row.list_index, row.compact_entry
        from arena_ranking_private.daily_arena_leaderboard_rows as row
       where row.snapshot_id = v_snapshot.snapshot_id
         and row.user_id = v_user_id
       order by row.list_index
       limit 3
    ) as owned;

  return v_response || pg_catalog.jsonb_build_object(
    'e', v_snapshot.top_entries,
    'o', v_own_entries
  );
end;
$$;

revoke all on function public.get_daily_arena_leaderboard(text)
  from public, anon, authenticated, service_role;
grant execute on function public.get_daily_arena_leaderboard(text) to authenticated;

comment on function public.get_daily_arena_leaderboard(text) is
  'Immutable UTC daily compact arena standings. Global top and own arrays together contain at most 1,000 rows; own rows can also occur in the global top and account identifiers are stable pseudonyms.';

-- Honest bootstrap only. The arena job is staggered seventeen minutes behind 007's general
-- ranking job and shares neither tables nor advisory keys. The hourly schedule gives retries after
-- transient failure; one daily publication does real work and the remaining calls are metadata no-ops.
select arena_ranking_private.publish_daily_arena_leaderboard();

create extension if not exists pg_cron with schema pg_catalog;
select cron.unschedule(jobid)
  from cron.job
 where jobname = 'alarmquest-daily-arena-leaderboard';
select cron.schedule(
  'alarmquest-daily-arena-leaderboard',
  '17 * * * *',
  'select arena_ranking_private.publish_daily_arena_leaderboard()'
);

-- Catalog-only deployment guardrails; no fixture or application row is created here.
do $$
declare
  v_sync_definition text;
  v_read_definition text;
  v_publish_definition text;
  v_mirror_definition text;
begin
  select pg_catalog.pg_get_functiondef(
    'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)'::pg_catalog.regprocedure
  ) into v_sync_definition;
  select pg_catalog.pg_get_functiondef(
    'public.get_daily_arena_leaderboard(text)'::pg_catalog.regprocedure
  ) into v_read_definition;
  select pg_catalog.pg_get_functiondef(
    'arena_ranking_private.publish_daily_arena_leaderboard(timestamptz)'::pg_catalog.regprocedure
  ) into v_publish_definition;
  select pg_catalog.pg_get_functiondef(
    'arena_ranking_private.mirror_shared_snapshot_identity()'::pg_catalog.regprocedure
  ) into v_mirror_definition;

  if pg_catalog.strpos(v_sync_definition, 'auth.uid()') = 0
     or pg_catalog.strpos(v_sync_definition, 'shared_player_private.snapshots') = 0
     or pg_catalog.strpos(v_sync_definition, 'season_character_bindings') = 0
     or pg_catalog.strpos(v_sync_definition, '''season_character_limit''') = 0
     or pg_catalog.strpos(v_sync_definition, '''replacement_requires_placement''') = 0
     or pg_catalog.strpos(v_sync_definition, 'for update of binding, previous') = 0
     or pg_catalog.strpos(v_sync_definition, 'delete from arena_ranking_private.season_standings') = 0
     or pg_catalog.strpos(v_sync_definition, 'delete from arena_ranking_private.entry_sync_limits') = 0
     or pg_catalog.strpos(v_sync_definition, 'interval ''15 minutes''') = 0
     or pg_catalog.strpos(v_sync_definition, 'p_completed_battles') = 0
     or pg_catalog.strpos(v_mirror_definition, 'pg_advisory_xact_lock_shared(20260908, 8)') = 0
     or pg_catalog.strpos(v_mirror_definition, 'arena-ranking-account:') = 0
     or pg_catalog.strpos(v_read_definition, 'v_snapshot.top_entries') = 0
     or pg_catalog.strpos(v_read_definition, 'limit 3') = 0
     or pg_catalog.strpos(v_publish_definition, 'arena-ranking-public-v1:') = 0
     or pg_catalog.strpos(v_publish_definition, 'row.list_index < 997') = 0 then
    raise exception 'arena ranking security contract self-check failed';
  end if;

  if not pg_catalog.has_function_privilege(
       'authenticated',
       'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)',
       'EXECUTE'
     )
     or pg_catalog.has_function_privilege(
       'anon',
       'public.sync_arena_ranking_entry(uuid,integer,integer,integer,integer,integer,integer)',
       'EXECUTE'
     )
     or not pg_catalog.has_function_privilege(
       'authenticated',
       'public.get_daily_arena_leaderboard(text)',
       'EXECUTE'
     )
     or pg_catalog.has_function_privilege(
       'anon',
       'public.get_daily_arena_leaderboard(text)',
       'EXECUTE'
     )
     or pg_catalog.has_table_privilege(
       'authenticated',
       'arena_ranking_private.season_standings',
       'SELECT'
     )
     or pg_catalog.has_table_privilege(
       'authenticated',
       'arena_ranking_private.season_character_bindings',
       'SELECT'
     ) then
    raise exception 'arena ranking ACL self-check failed';
  end if;

  if (
    select pg_catalog.count(*)
      from cron.job
     where jobname = 'alarmquest-daily-arena-leaderboard'
       and schedule = '17 * * * *'
  ) <> 1 then
    raise exception 'arena ranking cron self-check failed';
  end if;
end;
$$;
