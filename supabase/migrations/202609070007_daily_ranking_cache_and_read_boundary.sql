-- Rebased after the live 202609070004/005 migrations. This is the reviewed successor to
-- the unapplied 202609050001 draft; never apply both migration files.
-- One worldwide settlement at 00:00 UTC (09:00 Korea/Japan), independently of
-- device timezone. Readers receive an immutable completed settlement.
--
-- The cutoff is SERVER STATEMENT ADMISSION, not a client timestamp. All ranking
-- mutations hold a shared transaction lock; publication holds the exclusive
-- counterpart and waits for already-admitted transactions to finish. A statement
-- waiting behind publication cannot be backdated before that published cutoff.
-- On the first change/delete after midnight, preserve the pre-midnight row once.
-- Thus a delayed cron retry never substitutes a newer score for a midnight score.
-- This is a bounded before-image, NOT an unbounded log of every upload.

create table ranking_private.daily_leaderboard_snapshots (
  snapshot_id text primary key,
  settled_at timestamptz not null unique,
  generated_at timestamptz not null,
  is_bootstrap boolean not null default false,
  participant_count integer not null check (participant_count >= 0),
  top_entries jsonb not null check (jsonb_typeof(top_entries) = 'array')
);

create table ranking_private.daily_leaderboard_state (
  singleton boolean primary key default true check (singleton),
  tracking_started_at timestamptz not null,
  latest_snapshot_id text references ranking_private.daily_leaderboard_snapshots(snapshot_id),
  last_settled_at timestamptz
);

create table ranking_private.daily_leaderboard_rows (
  snapshot_id text not null references ranking_private.daily_leaderboard_snapshots(snapshot_id) on delete cascade,
  user_id uuid not null,
  character_id uuid not null,
  slot_id smallint not null,
  rank_number bigint not null,
  list_index bigint not null,
  display_name text not null,
  system_entry_code text,
  hero_class text not null,
  level bigint not null,
  combat_power bigint not null,
  achieved_at timestamptz not null,
  updated_at timestamptz not null,
  compact_entry jsonb not null,
  primary key (snapshot_id, user_id, character_id),
  unique (snapshot_id, list_index)
);

-- No auth.users FK: deletion after the cutoff must not rewrite an already
-- published ranking or destroy its before-image. Private retained data expires
-- with the two-settlement retention policy.
create table ranking_private.daily_ranking_before_images (
  user_id uuid not null,
  character_id uuid not null,
  cutoff_at timestamptz not null,
  row_data jsonb not null,
  primary key (user_id, character_id)
);

create index daily_ranking_before_images_cutoff_idx
  on ranking_private.daily_ranking_before_images (cutoff_at);

alter table ranking_private.daily_leaderboard_snapshots enable row level security;
alter table ranking_private.daily_leaderboard_state enable row level security;
alter table ranking_private.daily_leaderboard_rows enable row level security;
alter table ranking_private.daily_ranking_before_images enable row level security;
revoke all on table ranking_private.daily_leaderboard_snapshots,
  ranking_private.daily_leaderboard_state, ranking_private.daily_leaderboard_rows,
  ranking_private.daily_ranking_before_images from public, anon, authenticated, service_role;

-- Existing values are usable for the honestly dated bootstrap only; no daily
-- snapshot may precede tracking_started_at.
alter table public.ranking_entries
  add column daily_admitted_at timestamptz not null default '-infinity'::timestamptz;

insert into ranking_private.daily_leaderboard_state (tracking_started_at)
values (pg_catalog.clock_timestamp());

create function ranking_private.lock_daily_ranking_write()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- Acquire before row locks to avoid deadlocks with a settlement waiting for
  -- an in-flight roster replacement. All INSERT/UPDATE/DELETE paths participate.
  perform pg_catalog.pg_advisory_xact_lock_shared(20260905, 1);
  return null;
end;
$$;

create function ranking_private.capture_daily_ranking_before_image()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admitted_at timestamptz;
  v_cutoff_at timestamptz;
begin
  select greatest(pg_catalog.statement_timestamp(), s.last_settled_at)
    into v_admitted_at
    from ranking_private.daily_leaderboard_state as s
   where s.singleton;
  v_cutoff_at := pg_catalog.date_trunc('day', v_admitted_at at time zone 'UTC') at time zone 'UTC';

  if tg_op <> 'INSERT' and old.daily_admitted_at < v_cutoff_at then
    insert into ranking_private.daily_ranking_before_images as previous (
      user_id, character_id, cutoff_at, row_data
    ) values (
      old.user_id, old.character_id, v_cutoff_at, pg_catalog.to_jsonb(old)
    )
    on conflict (user_id, character_id) do update
      set cutoff_at = excluded.cutoff_at, row_data = excluded.row_data
    where previous.cutoff_at < excluded.cutoff_at;
  end if;

  if tg_op = 'DELETE' then
    return old;
  end if;
  new.daily_admitted_at := v_admitted_at;
  return new;
end;
$$;

revoke all on function ranking_private.lock_daily_ranking_write(),
  ranking_private.capture_daily_ranking_before_image()
  from public, anon, authenticated, service_role;

create trigger ranking_entries_daily_lock
before insert or update or delete on public.ranking_entries
for each statement execute function ranking_private.lock_daily_ranking_write();

create trigger ranking_entries_daily_capture
before insert or update or delete on public.ranking_entries
for each row execute function ranking_private.capture_daily_ranking_before_image();

create function ranking_private.publish_daily_leaderboard(p_cutoff_at timestamptz default null)
returns text
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_now timestamptz;
  v_cutoff_at timestamptz;
  v_state ranking_private.daily_leaderboard_state%rowtype;
  v_snapshot_id text;
  v_bootstrap boolean;
begin
  -- A fresh snapshot after the writer barrier is required. Cron uses the default
  -- READ COMMITTED isolation level; a caller must not pin an older MVCC view.
  if pg_catalog.current_setting('transaction_isolation') <> 'read committed' then
    raise exception 'daily ranking publication requires READ COMMITTED';
  end if;
  -- Steady-state cron retries are one indexed metadata read and do not contend
  -- with uploads. Recheck under the exclusive barrier before doing real work.
  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from ranking_private.daily_leaderboard_state where singleton;
  v_cutoff_at := coalesce(p_cutoff_at,
    pg_catalog.date_trunc('day', v_now at time zone 'UTC') at time zone 'UTC');
  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future ranking cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  perform pg_catalog.pg_advisory_xact_lock(20260905, 1);
  v_now := pg_catalog.clock_timestamp();
  select * into strict v_state
    from ranking_private.daily_leaderboard_state where singleton;
  v_bootstrap := v_state.latest_snapshot_id is null;
  v_cutoff_at := coalesce(p_cutoff_at, case when v_bootstrap then v_now
    else pg_catalog.date_trunc('day', v_now at time zone 'UTC') at time zone 'UTC' end);

  if v_cutoff_at > v_now then
    raise exception 'cannot publish a future ranking cutoff';
  end if;
  if v_state.last_settled_at is not null and v_cutoff_at <= v_state.last_settled_at then
    return v_state.latest_snapshot_id;
  end if;
  if v_bootstrap then
    -- History did not exist before this migration. The first result is labelled
    -- with its actual creation instant, never with a fabricated midnight.
    if p_cutoff_at is not null then
      raise exception 'bootstrap cutoff must use actual server time';
    end if;
  elsif v_cutoff_at < v_state.tracking_started_at
    or v_cutoff_at <> (pg_catalog.date_trunc('day', v_now at time zone 'UTC') at time zone 'UTC') then
    -- Before-images retain only the current boundary. If an outage spans days,
    -- publish the latest recoverable midnight, not imaginary historical days.
    raise exception 'only the latest tracked UTC midnight can be settled';
  end if;

  v_snapshot_id := case when v_bootstrap then 'bootstrap:' else 'utc:' end ||
    ((extract(epoch from v_cutoff_at) * 1000)::bigint)::text;
  insert into ranking_private.daily_leaderboard_snapshots (
    snapshot_id, settled_at, generated_at, is_bootstrap, participant_count, top_entries
  ) values (v_snapshot_id, v_cutoff_at, v_now, v_bootstrap, 0, '[]'::jsonb);

  with cutoff_rows as (
    select r.user_id, r.character_id, r.slot_id, r.display_name, r.system_entry_code,
           r.hero_class, r.level, r.combat_power, r.achieved_at, r.updated_at
      from public.ranking_entries as r
     where v_bootstrap or r.daily_admitted_at < v_cutoff_at
    union all
    select r.user_id, r.character_id, r.slot_id, r.display_name, r.system_entry_code,
           r.hero_class, r.level, r.combat_power, r.achieved_at, r.updated_at
      from ranking_private.daily_ranking_before_images as saved
      cross join lateral pg_catalog.jsonb_populate_record(null::public.ranking_entries, saved.row_data) as r
     where not v_bootstrap and saved.cutoff_at = v_cutoff_at
  ), ranked as (
    select r.*,
           rank() over (order by r.combat_power desc) as rank_number,
           row_number() over (
             order by r.combat_power desc, r.achieved_at asc, r.character_id, r.user_id
           ) - 1 as list_index
      from cutoff_rows as r
     where r.level between 20 and 10000 and r.combat_power >= 0
       and r.combat_power <= ranking_private.maximum_accepted_combat_power(r.level)
  )
  insert into ranking_private.daily_leaderboard_rows (
    snapshot_id, user_id, character_id, slot_id, rank_number, list_index,
    display_name, system_entry_code, hero_class, level, combat_power,
    achieved_at, updated_at, compact_entry
  )
  select v_snapshot_id, r.user_id, r.character_id, r.slot_id, r.rank_number, r.list_index,
         r.display_name, r.system_entry_code, r.hero_class, r.level, r.combat_power,
         r.achieved_at, r.updated_at,
         pg_catalog.jsonb_build_object(
           'r', r.rank_number, 'i', r.list_index, 'c', r.character_id,
           'n', r.display_name, 'h', r.hero_class, 'l', r.level,
           'p', r.combat_power, 'a', (extract(epoch from r.achieved_at) * 1000)::bigint
         ) || case when r.system_entry_code is null then '{}'::jsonb
           else pg_catalog.jsonb_build_object('s', r.system_entry_code) end
    from ranked as r;

  update ranking_private.daily_leaderboard_snapshots as s
     set participant_count = (
           select pg_catalog.count(*)::integer
             from ranking_private.daily_leaderboard_rows as r where r.snapshot_id = v_snapshot_id
         ),
         top_entries = coalesce((
           select pg_catalog.jsonb_agg(r.compact_entry order by r.list_index)
             from ranking_private.daily_leaderboard_rows as r
            where r.snapshot_id = v_snapshot_id and r.list_index < 1000
         ), '[]'::jsonb),
         generated_at = pg_catalog.clock_timestamp()
   where s.snapshot_id = v_snapshot_id;

  -- Pointer switch, rows, aggregate and retention commit together. A failed run
  -- leaves the previous completed snapshot fully readable.
  update ranking_private.daily_leaderboard_state
     set latest_snapshot_id = v_snapshot_id, last_settled_at = v_cutoff_at
   where singleton;

  delete from ranking_private.daily_leaderboard_snapshots as s
   where s.snapshot_id not in (
     select keep.snapshot_id from ranking_private.daily_leaderboard_snapshots as keep
      order by keep.settled_at desc limit 2
   );
  delete from ranking_private.daily_ranking_before_images
   where cutoff_at <= v_cutoff_at;
  return v_snapshot_id;
end;
$$;

revoke all on function ranking_private.publish_daily_leaderboard(timestamptz)
  from public, anon, authenticated;
grant usage on schema ranking_private to service_role;
grant execute on function ranking_private.publish_daily_leaderboard(timestamptz) to service_role;

create function public.get_daily_leaderboard(p_known_snapshot_id text default null)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot ranking_private.daily_leaderboard_snapshots%rowtype;
  v_response jsonb;
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  select s.* into strict v_snapshot
    from ranking_private.daily_leaderboard_state as state
    join ranking_private.daily_leaderboard_snapshots as s on s.snapshot_id = state.latest_snapshot_id
   where state.singleton;
  v_response := pg_catalog.jsonb_build_object(
    'snapshot_id', v_snapshot.snapshot_id,
    'settled_at', (extract(epoch from v_snapshot.settled_at) * 1000)::bigint,
    'next_settlement_at', (extract(epoch from (
      (pg_catalog.date_trunc('day', v_snapshot.settled_at at time zone 'UTC') + interval '1 day') at time zone 'UTC'
    )) * 1000)::bigint,
    'generated_at', (extract(epoch from v_snapshot.generated_at) * 1000)::bigint,
    'server_now', (extract(epoch from pg_catalog.statement_timestamp()) * 1000)::bigint,
    'is_bootstrap', v_snapshot.is_bootstrap,
    'unchanged', coalesce(p_known_snapshot_id = v_snapshot.snapshot_id, false),
    't', v_snapshot.participant_count
  );
  if p_known_snapshot_id = v_snapshot.snapshot_id then return v_response; end if;
  return v_response || pg_catalog.jsonb_build_object(
    'e', v_snapshot.top_entries,
    'o', coalesce((
      select pg_catalog.jsonb_agg(r.compact_entry order by r.list_index)
        from ranking_private.daily_leaderboard_rows as r
       where r.snapshot_id = v_snapshot.snapshot_id and r.user_id = v_user_id
    ), '[]'::jsonb)
  );
end;
$$;

revoke all on function public.get_daily_leaderboard(text) from public, anon;
grant execute on function public.get_daily_leaderboard(text) to authenticated;

-- Already-installed clients keep their payload shape while receiving the same
-- fixed settlement. No request executes rank()/row_number() over the live table.
create or replace function public.get_leaderboard_v2(
  p_character_id uuid, p_limit integer default 1000
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_response jsonb;
  v_limit integer := least(greatest(coalesce(p_limit, 1000), 1), 1000);
begin
  v_response := public.get_daily_leaderboard(null);
  return pg_catalog.jsonb_build_object(
    't', v_response->'t',
    'e', case when v_limit = 1000 then v_response->'e' else coalesce((
      select pg_catalog.jsonb_agg(e.entry order by e.ordinality)
        from pg_catalog.jsonb_array_elements(v_response->'e') with ordinality as e(entry, ordinality)
       where e.ordinality <= v_limit
    ), '[]'::jsonb) end,
    'm', (select e.entry from pg_catalog.jsonb_array_elements(v_response->'o') as e(entry)
           where e.entry->>'c' = p_character_id::text limit 1)
  );
end;
$$;

revoke all on function public.get_leaderboard_v2(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard_v2(uuid, integer) to authenticated;

create or replace function public.get_leaderboard(
  p_character_id uuid, p_limit integer default 1000
)
returns table (
  rank_number bigint, list_index bigint, user_id uuid, character_id uuid,
  slot_id smallint, display_name text, hero_class text, level bigint,
  combat_power bigint, achieved_at timestamptz, updated_at timestamptz,
  total_participants integer
)
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot_id text;
  v_total integer;
  v_limit integer := least(greatest(coalesce(p_limit, 1000), 1), 1000);
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  select s.snapshot_id, s.participant_count into strict v_snapshot_id, v_total
    from ranking_private.daily_leaderboard_state as state
    join ranking_private.daily_leaderboard_snapshots as s on s.snapshot_id = state.latest_snapshot_id
   where state.singleton;
  return query
    with own as (
      select r.list_index
        from ranking_private.daily_leaderboard_rows as r
       where r.snapshot_id = v_snapshot_id
         and r.user_id = v_user_id
         and r.character_id = p_character_id
       limit 1
    ), visible as (
      select r.*
        from ranking_private.daily_leaderboard_rows as r
       where r.snapshot_id = v_snapshot_id
         and (
           r.list_index < v_limit - case
             when exists (select 1 from own where own.list_index >= v_limit) then 1
             else 0
           end
           or (r.user_id = v_user_id and r.character_id = p_character_id)
         )
    )
    select r.rank_number, r.list_index,
           case
             when r.user_id = v_user_id then r.user_id
             else pg_catalog.md5('ranking-public-v1:' || r.user_id::text)::uuid
           end as user_id,
           r.character_id, r.slot_id,
           r.display_name, r.hero_class, r.level, r.combat_power,
           r.achieved_at, r.updated_at, v_total
      from visible as r
     order by r.list_index
     limit v_limit;
end;
$$;

revoke all on function public.get_leaderboard(uuid, integer) from public, anon;
grant execute on function public.get_leaderboard(uuid, integer) to authenticated;

-- The original policy exposed every ranking row, including user_id, to arbitrary authenticated
-- PostgREST pagination. All supported readers above are bounded SECURITY DEFINER RPCs, so remove
-- the direct table surface only after those compatibility functions have been replaced.
drop policy if exists "authenticated users can read rankings" on public.ranking_entries;
revoke select on table public.ranking_entries from public, anon, authenticated;

-- Initial publication is a one-off bootstrap with an honest timestamp. The
-- scheduler attempts at the start of each UTC hour so transient failures recover without adding
-- 1,440 connection/check executions per day; the idempotent pointer check makes the 23 ordinary
-- daily attempts constant-time.
select ranking_private.publish_daily_leaderboard();

create extension if not exists pg_cron with schema pg_catalog;
select cron.unschedule(jobid) from cron.job where jobname = 'alarmquest-daily-leaderboard';
select cron.schedule(
  'alarmquest-daily-leaderboard', '0 * * * *',
  'select ranking_private.publish_daily_leaderboard()'
);
