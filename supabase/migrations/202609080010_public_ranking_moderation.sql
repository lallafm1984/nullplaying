-- Private, administrator-owned presentation policy for both published leaderboards.
--
-- Raw general/arena snapshots remain immutable evidence.  Moderation is applied only while an
-- authenticated reader receives a published snapshot:
--   * an active hidden account/character is absent from every public row and public participant
--     count, and the remaining rows are ranked again with the original board's tie rules;
--   * the hidden account still receives its own unmodified snapshot rows in `o`, including its
--     original display name, rank/list index and participant count;
--   * an active replacement name is visible to other players while the owner keeps the original
--     name in `o`;
--   * every policy mutation increments a revision included in the opaque <=128-byte cache token,
--     so a moderation change cannot be masked by an unchanged hourly base snapshot.
--
-- Requires migrations through 202609080009.  This migration performs no live data moderation.

create table ranking_private.public_ranking_moderation (
  moderation_id bigint generated always as identity primary key,
  user_id uuid not null,
  character_id uuid,
  is_active boolean not null default true,
  hidden_from_public_rankings boolean not null default false,
  replacement_display_name text,
  reason_code text not null,
  private_note text,
  created_at timestamptz not null default pg_catalog.statement_timestamp(),
  updated_at timestamptz not null default pg_catalog.statement_timestamp(),
  created_by text not null,
  updated_by text not null,
  check (
    not is_active
    or hidden_from_public_rankings
    or replacement_display_name is not null
  ),
  check (reason_code in ('CHEAT','OBSCENE_NAME','HATEFUL_NAME','MANUAL_REVIEW','OTHER')),
  check (
    not is_active
    or reason_code <> 'CHEAT'
    or hidden_from_public_rankings
  ),
  check (
    not is_active
    or reason_code not in ('OBSCENE_NAME','HATEFUL_NAME')
    or replacement_display_name is not null
  ),
  check (
    replacement_display_name is null
    or (
      pg_catalog.char_length(replacement_display_name) between 1 and 24
      and pg_catalog.octet_length(replacement_display_name) <= 96
      and replacement_display_name = pg_catalog.btrim(replacement_display_name)
      and replacement_display_name !~ '[[:cntrl:]]'
    )
  ),
  check (
    pg_catalog.char_length(created_by) between 1 and 128
    and pg_catalog.octet_length(created_by) <= 512
    and created_by = pg_catalog.btrim(created_by)
    and created_by !~ '[[:cntrl:]]'
  ),
  check (
    pg_catalog.char_length(updated_by) between 1 and 128
    and pg_catalog.octet_length(updated_by) <= 512
    and updated_by = pg_catalog.btrim(updated_by)
    and updated_by !~ '[[:cntrl:]]'
  ),
  check (private_note is null or pg_catalog.octet_length(private_note) <= 8000),
  check (updated_at >= created_at)
);

-- One account-wide policy plus, when necessary, one override per character.  The account scope is
-- the normal place to hide a cheat: every character owned by that account then disappears.
create unique index public_ranking_moderation_account_scope_uidx
  on ranking_private.public_ranking_moderation (user_id)
  where character_id is null;
create unique index public_ranking_moderation_character_scope_uidx
  on ranking_private.public_ranking_moderation (user_id, character_id)
  where character_id is not null;
create index public_ranking_moderation_active_account_idx
  on ranking_private.public_ranking_moderation (user_id)
  where is_active and character_id is null;
create index public_ranking_moderation_active_character_idx
  on ranking_private.public_ranking_moderation (user_id, character_id)
  where is_active and character_id is not null;

create table ranking_private.public_ranking_moderation_state (
  singleton boolean primary key default true check (singleton),
  revision bigint not null check (revision >= 1),
  updated_at timestamptz not null
);

insert into ranking_private.public_ranking_moderation_state (
  singleton, revision, updated_at
) values (true, 1, pg_catalog.statement_timestamp());

alter table ranking_private.public_ranking_moderation enable row level security;
alter table ranking_private.public_ranking_moderation_state enable row level security;
revoke all on table ranking_private.public_ranking_moderation,
  ranking_private.public_ranking_moderation_state
  from public, anon, authenticated, service_role;
revoke all on sequence ranking_private.public_ranking_moderation_moderation_id_seq
  from public, anon, authenticated, service_role;

create function ranking_private.bump_public_ranking_moderation_revision()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  update ranking_private.public_ranking_moderation_state
     set revision = revision + 1,
         updated_at = pg_catalog.statement_timestamp()
   where singleton;
  return null;
end;
$$;

revoke all on function ranking_private.bump_public_ranking_moderation_revision()
  from public, anon, authenticated, service_role;

create trigger public_ranking_moderation_revision_insert
after insert on ranking_private.public_ranking_moderation
for each row execute function ranking_private.bump_public_ranking_moderation_revision();
create trigger public_ranking_moderation_revision_update
after update on ranking_private.public_ranking_moderation
for each row execute function ranking_private.bump_public_ranking_moderation_revision();
create trigger public_ranking_moderation_revision_delete
after delete on ranking_private.public_ranking_moderation
for each row execute function ranking_private.bump_public_ranking_moderation_revision();
create trigger public_ranking_moderation_revision_truncate
after truncate on ranking_private.public_ranking_moderation
for each statement execute function ranking_private.bump_public_ranking_moderation_revision();

create function ranking_private.public_ranking_cache_token(
  p_base_snapshot_id text,
  p_moderation_revision bigint
)
returns text
language sql
immutable
strict
set search_path = ''
as $$
  select case
    when pg_catalog.octet_length(p_base_snapshot_id || ':m' || p_moderation_revision::text) <= 128
      then p_base_snapshot_id || ':m' || p_moderation_revision::text
    else 'moderated:' || pg_catalog.md5(p_base_snapshot_id) || ':m' || p_moderation_revision::text
  end
$$;

revoke all on function ranking_private.public_ranking_cache_token(text, bigint)
  from public, anon, authenticated, service_role;

-- Service-role-only mutation surface.  Inactive rows retain the latest administrative audit
-- fields but have no presentation effect.  A no-op update is suppressed and does not invalidate
-- every client cache needlessly.
create function public.admin_set_ranking_moderation(
  p_user_id uuid,
  p_character_id uuid default null,
  p_is_active boolean default true,
  p_hidden_from_public_rankings boolean default false,
  p_replacement_display_name text default null,
  p_reason_code text default 'OTHER',
  p_private_note text default null,
  p_admin_actor text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row ranking_private.public_ranking_moderation%rowtype;
  v_revision bigint;
begin
  if p_user_id is null then
    raise exception 'moderation user id is required' using errcode = '22023';
  end if;
  if p_is_active is null or p_hidden_from_public_rankings is null then
    raise exception 'moderation boolean fields are required' using errcode = '22023';
  end if;
  if p_admin_actor is null
     or pg_catalog.char_length(p_admin_actor) not between 1 and 128
     or pg_catalog.octet_length(p_admin_actor) > 512
     or p_admin_actor <> pg_catalog.btrim(p_admin_actor)
     or p_admin_actor ~ '[[:cntrl:]]' then
    raise exception 'invalid moderation administrator actor' using errcode = '22023';
  end if;
  if p_reason_code is null
     or p_reason_code not in ('CHEAT','OBSCENE_NAME','HATEFUL_NAME','MANUAL_REVIEW','OTHER') then
    raise exception 'invalid moderation reason code' using errcode = '22023';
  end if;
  if p_replacement_display_name is not null and (
       pg_catalog.char_length(p_replacement_display_name) not between 1 and 24
       or pg_catalog.octet_length(p_replacement_display_name) > 96
       or p_replacement_display_name <> pg_catalog.btrim(p_replacement_display_name)
       or p_replacement_display_name ~ '[[:cntrl:]]'
     ) then
    raise exception 'invalid moderation replacement display name' using errcode = '22023';
  end if;
  if p_private_note is not null and pg_catalog.octet_length(p_private_note) > 8000 then
    raise exception 'moderation private note is too large' using errcode = '22023';
  end if;
  if p_is_active
     and not p_hidden_from_public_rankings
     and p_replacement_display_name is null then
    raise exception 'active moderation requires a hide or replacement action' using errcode = '22023';
  end if;
  if p_is_active and p_reason_code = 'CHEAT' and not p_hidden_from_public_rankings then
    raise exception 'cheat moderation must hide public rankings' using errcode = '22023';
  end if;
  if p_is_active
     and p_reason_code in ('OBSCENE_NAME','HATEFUL_NAME')
     and p_replacement_display_name is null then
    raise exception 'name moderation requires a replacement display name' using errcode = '22023';
  end if;

  select * into v_row
    from ranking_private.public_ranking_moderation as moderation
   where moderation.user_id = p_user_id
     and moderation.character_id is not distinct from p_character_id;
  if found and (
       v_row.is_active,
       v_row.hidden_from_public_rankings,
       v_row.replacement_display_name,
       v_row.reason_code,
       v_row.private_note,
       v_row.updated_by
     ) is not distinct from (
       p_is_active,
       p_hidden_from_public_rankings,
       p_replacement_display_name,
       p_reason_code,
       p_private_note,
       p_admin_actor
     ) then
    select state.revision into strict v_revision
      from ranking_private.public_ranking_moderation_state as state
     where state.singleton;
    return pg_catalog.jsonb_build_object(
      'moderation_id', v_row.moderation_id,
      'user_id', v_row.user_id,
      'character_id', v_row.character_id,
      'is_active', v_row.is_active,
      'hidden_from_public_rankings', v_row.hidden_from_public_rankings,
      'replacement_display_name', v_row.replacement_display_name,
      'reason_code', v_row.reason_code,
      'revision', v_revision,
      'updated_at', (extract(epoch from v_row.updated_at) * 1000)::bigint
    );
  end if;

  if p_character_id is null then
    insert into ranking_private.public_ranking_moderation as moderation (
      user_id, character_id, is_active, hidden_from_public_rankings,
      replacement_display_name, reason_code, private_note,
      created_by, updated_by
    ) values (
      p_user_id, null, p_is_active, p_hidden_from_public_rankings,
      p_replacement_display_name, p_reason_code, p_private_note,
      p_admin_actor, p_admin_actor
    )
    on conflict (user_id) where character_id is null do update
      set is_active = excluded.is_active,
          hidden_from_public_rankings = excluded.hidden_from_public_rankings,
          replacement_display_name = excluded.replacement_display_name,
          reason_code = excluded.reason_code,
          private_note = excluded.private_note,
          updated_at = pg_catalog.statement_timestamp(),
          updated_by = excluded.updated_by
      where (
        moderation.is_active,
        moderation.hidden_from_public_rankings,
        moderation.replacement_display_name,
        moderation.reason_code,
        moderation.private_note,
        moderation.updated_by
      ) is distinct from (
        excluded.is_active,
        excluded.hidden_from_public_rankings,
        excluded.replacement_display_name,
        excluded.reason_code,
        excluded.private_note,
        excluded.updated_by
      )
    returning * into v_row;
  else
    insert into ranking_private.public_ranking_moderation as moderation (
      user_id, character_id, is_active, hidden_from_public_rankings,
      replacement_display_name, reason_code, private_note,
      created_by, updated_by
    ) values (
      p_user_id, p_character_id, p_is_active, p_hidden_from_public_rankings,
      p_replacement_display_name, p_reason_code, p_private_note,
      p_admin_actor, p_admin_actor
    )
    on conflict (user_id, character_id) where character_id is not null do update
      set is_active = excluded.is_active,
          hidden_from_public_rankings = excluded.hidden_from_public_rankings,
          replacement_display_name = excluded.replacement_display_name,
          reason_code = excluded.reason_code,
          private_note = excluded.private_note,
          updated_at = pg_catalog.statement_timestamp(),
          updated_by = excluded.updated_by
      where (
        moderation.is_active,
        moderation.hidden_from_public_rankings,
        moderation.replacement_display_name,
        moderation.reason_code,
        moderation.private_note,
        moderation.updated_by
      ) is distinct from (
        excluded.is_active,
        excluded.hidden_from_public_rankings,
        excluded.replacement_display_name,
        excluded.reason_code,
        excluded.private_note,
        excluded.updated_by
      )
    returning * into v_row;
  end if;

  -- ON CONFLICT ... WHERE may suppress an exact no-op; return the existing row in that case.
  if v_row.moderation_id is null then
    select * into strict v_row
      from ranking_private.public_ranking_moderation as moderation
     where moderation.user_id = p_user_id
       and moderation.character_id is not distinct from p_character_id;
  end if;
  select state.revision into strict v_revision
    from ranking_private.public_ranking_moderation_state as state
   where state.singleton;

  return pg_catalog.jsonb_build_object(
    'moderation_id', v_row.moderation_id,
    'user_id', v_row.user_id,
    'character_id', v_row.character_id,
    'is_active', v_row.is_active,
    'hidden_from_public_rankings', v_row.hidden_from_public_rankings,
    'replacement_display_name', v_row.replacement_display_name,
    'reason_code', v_row.reason_code,
    'revision', v_revision,
    'updated_at', (extract(epoch from v_row.updated_at) * 1000)::bigint
  );
end;
$$;

create function public.admin_list_ranking_moderation(
  p_limit integer default 100,
  p_before_moderation_id bigint default null
)
returns table (
  moderation_id bigint,
  user_id uuid,
  character_id uuid,
  is_active boolean,
  hidden_from_public_rankings boolean,
  replacement_display_name text,
  reason_code text,
  private_note text,
  created_at timestamptz,
  updated_at timestamptz,
  created_by text,
  updated_by text
)
language sql
stable
security definer
set search_path = ''
as $$
  select moderation.moderation_id, moderation.user_id, moderation.character_id,
         moderation.is_active, moderation.hidden_from_public_rankings,
         moderation.replacement_display_name, moderation.reason_code,
         moderation.private_note, moderation.created_at, moderation.updated_at,
         moderation.created_by, moderation.updated_by
    from ranking_private.public_ranking_moderation as moderation
   where p_before_moderation_id is null
      or moderation.moderation_id < p_before_moderation_id
   order by moderation.moderation_id desc
   limit least(greatest(coalesce(p_limit, 100), 1), 500)
$$;

revoke all on function public.admin_set_ranking_moderation(
  uuid, uuid, boolean, boolean, text, text, text, text
) from public, anon, authenticated, service_role;
revoke all on function public.admin_list_ranking_moderation(integer, bigint)
  from public, anon, authenticated, service_role;
grant execute on function public.admin_set_ranking_moderation(
  uuid, uuid, boolean, boolean, text, text, text, text
) to service_role;
grant execute on function public.admin_list_ranking_moderation(integer, bigint)
  to service_role;

create or replace function public.get_daily_leaderboard(
  p_known_snapshot_id text default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot ranking_private.daily_leaderboard_snapshots%rowtype;
  v_tracking_started_at timestamptz;
  v_revision bigint;
  v_cache_token text;
  v_has_active_moderation boolean;
  v_hidden_count integer;
  v_requester_hidden boolean;
  v_response jsonb;
  v_public_entries jsonb;
  v_own_entries jsonb;
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  if p_known_snapshot_id is not null
     and pg_catalog.octet_length(p_known_snapshot_id) > 128 then
    raise exception 'invalid ranking snapshot id' using errcode = '22023';
  end if;

  select snapshot.snapshot_id, snapshot.settled_at, snapshot.generated_at,
         snapshot.is_bootstrap, snapshot.participant_count, snapshot.top_entries,
         state.tracking_started_at, moderation_state.revision
    into strict v_snapshot.snapshot_id, v_snapshot.settled_at, v_snapshot.generated_at,
         v_snapshot.is_bootstrap, v_snapshot.participant_count, v_snapshot.top_entries,
         v_tracking_started_at, v_revision
    from ranking_private.daily_leaderboard_state as state
    join ranking_private.daily_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
    cross join ranking_private.public_ranking_moderation_state as moderation_state
   where state.singleton and moderation_state.singleton;

  v_cache_token := ranking_private.public_ranking_cache_token(
    v_snapshot.snapshot_id, v_revision
  );

  select exists (
    select 1
      from ranking_private.public_ranking_moderation as moderation
     where moderation.is_active
  ) into v_has_active_moderation;

  -- Start from the normally tiny moderation set, then use each snapshot's user-leading primary
  -- key.  UNION prevents a character hidden by both account and character rules being counted
  -- twice.
  if v_has_active_moderation then
    with hidden_rows as (
    select row.user_id, row.character_id
      from ranking_private.public_ranking_moderation as moderation
      join ranking_private.daily_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is null
    union
    select row.user_id, row.character_id
      from ranking_private.public_ranking_moderation as moderation
      join ranking_private.daily_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
       and row.character_id = moderation.character_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is not null
    )
    select pg_catalog.count(*)::integer,
           coalesce(pg_catalog.bool_or(hidden.user_id = v_user_id), false)
      into v_hidden_count, v_requester_hidden
      from hidden_rows as hidden;
  else
    v_hidden_count := 0;
    v_requester_hidden := false;
  end if;

  v_response := pg_catalog.jsonb_build_object(
    'snapshot_id', v_cache_token,
    'settled_at', (extract(epoch from v_snapshot.settled_at) * 1000)::bigint,
    'next_settlement_at', (extract(epoch from case
      when v_snapshot.is_bootstrap then
        ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 1 minute'
      else greatest(
        v_snapshot.settled_at + interval '1 hour 1 minute',
        ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 1 minute'
      )
    end) * 1000)::bigint,
    'generated_at', (extract(epoch from v_snapshot.generated_at) * 1000)::bigint,
    'server_now', (extract(epoch from pg_catalog.statement_timestamp()) * 1000)::bigint,
    'is_bootstrap', v_snapshot.is_bootstrap,
    'unchanged', coalesce(p_known_snapshot_id = v_cache_token, false),
    't', case when v_requester_hidden then v_snapshot.participant_count
      else greatest(v_snapshot.participant_count - v_hidden_count, 0) end
  );
  if p_known_snapshot_id = v_cache_token then return v_response; end if;

  -- The steady state is no active moderation. Preserve 007/009's precomputed O(1) body path and
  -- avoid joining/windowing snapshot rows until an administrator activates a policy.
  if not v_has_active_moderation then
    return v_response || pg_catalog.jsonb_build_object(
      'e', v_snapshot.top_entries,
      'o', coalesce((
        select pg_catalog.jsonb_agg(row.compact_entry order by row.list_index)
          from ranking_private.daily_leaderboard_rows as row
         where row.snapshot_id = v_snapshot.snapshot_id
           and row.user_id = v_user_id
      ), '[]'::jsonb)
    );
  end if;

  with annotated as (
    select row.*,
           coalesce(account_moderation.hidden_from_public_rankings, false)
             or coalesce(character_moderation.hidden_from_public_rankings, false) as is_hidden,
           coalesce(
             character_moderation.replacement_display_name,
             account_moderation.replacement_display_name,
             row.display_name
           ) as public_display_name
      from ranking_private.daily_leaderboard_rows as row
      left join ranking_private.public_ranking_moderation as account_moderation
        on account_moderation.user_id = row.user_id
       and account_moderation.character_id is null
       and account_moderation.is_active
      left join ranking_private.public_ranking_moderation as character_moderation
        on character_moderation.user_id = row.user_id
       and character_moderation.character_id = row.character_id
       and character_moderation.is_active
     where row.snapshot_id = v_snapshot.snapshot_id
  ), bounded as (
    select annotated.*
      from annotated
     where not annotated.is_hidden
     order by annotated.list_index
     limit 1000
  ), reranked as (
    select bounded.*,
           pg_catalog.rank() over (order by bounded.combat_power desc) as public_rank,
           pg_catalog.row_number() over (order by bounded.list_index) - 1 as public_list_index
      from bounded
  )
  select coalesce(
           pg_catalog.jsonb_agg(
             reranked.compact_entry || pg_catalog.jsonb_build_object(
               'r', reranked.public_rank,
               'i', reranked.public_list_index,
               'n', reranked.public_display_name
             ) order by reranked.public_list_index
           ),
           '[]'::jsonb
         )
    into v_public_entries
    from reranked;

  -- Visible owners receive their rank after public hiding; a hidden owner alone keeps the raw
  -- snapshot rank/list index.  Every owner receives the raw name.
  with hidden_rows as (
    select row.user_id, row.character_id, row.rank_number, row.list_index, row.combat_power
      from ranking_private.public_ranking_moderation as moderation
      join ranking_private.daily_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is null
    union
    select row.user_id, row.character_id, row.rank_number, row.list_index, row.combat_power
      from ranking_private.public_ranking_moderation as moderation
      join ranking_private.daily_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
       and row.character_id = moderation.character_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is not null
  ), owned as (
    select row.*,
           exists (
             select 1 from hidden_rows as hidden
              where hidden.user_id = row.user_id
                and hidden.character_id = row.character_id
           ) as is_hidden
      from ranking_private.daily_leaderboard_rows as row
     where row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = v_user_id
  ), adjusted as (
    select owned.*,
           case when owned.is_hidden then owned.rank_number else
             owned.rank_number - (
               select pg_catalog.count(*) from hidden_rows as hidden
                where hidden.combat_power > owned.combat_power
             )
           end as owner_rank,
           case when owned.is_hidden then owned.list_index else
             owned.list_index - (
               select pg_catalog.count(*) from hidden_rows as hidden
                where hidden.list_index < owned.list_index
             )
           end as owner_list_index
      from owned
  )
  select coalesce(
           pg_catalog.jsonb_agg(
             adjusted.compact_entry || pg_catalog.jsonb_build_object(
               'r', adjusted.owner_rank,
               'i', adjusted.owner_list_index,
               'n', adjusted.display_name
             ) order by adjusted.list_index
           ),
           '[]'::jsonb
         )
    into v_own_entries
    from adjusted;

  return v_response || pg_catalog.jsonb_build_object(
    'e', v_public_entries,
    'o', v_own_entries
  );
end;
$$;

create or replace function public.get_daily_arena_leaderboard(
  p_known_snapshot_id text default null
)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_user_id uuid := auth.uid();
  v_snapshot arena_ranking_private.daily_arena_leaderboard_snapshots%rowtype;
  v_tracking_started_at timestamptz;
  v_revision bigint;
  v_cache_token text;
  v_has_active_moderation boolean;
  v_hidden_count integer;
  v_requester_hidden boolean;
  v_response jsonb;
  v_public_entries jsonb;
  v_own_entries jsonb;
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  if p_known_snapshot_id is not null
     and pg_catalog.octet_length(p_known_snapshot_id) > 128 then
    raise exception 'invalid arena snapshot id' using errcode = '22023';
  end if;

  select snapshot.snapshot_id, snapshot.season_id, snapshot.rules_version,
         snapshot.settled_at, snapshot.generated_at, snapshot.is_bootstrap,
         snapshot.participant_count, snapshot.top_entries,
         state.tracking_started_at, moderation_state.revision
    into strict v_snapshot.snapshot_id, v_snapshot.season_id, v_snapshot.rules_version,
         v_snapshot.settled_at, v_snapshot.generated_at, v_snapshot.is_bootstrap,
         v_snapshot.participant_count, v_snapshot.top_entries,
         v_tracking_started_at, v_revision
    from arena_ranking_private.daily_arena_leaderboard_state as state
    join arena_ranking_private.daily_arena_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
    cross join ranking_private.public_ranking_moderation_state as moderation_state
   where state.singleton and moderation_state.singleton;

  v_cache_token := ranking_private.public_ranking_cache_token(
    v_snapshot.snapshot_id, v_revision
  );

  select exists (
    select 1
      from ranking_private.public_ranking_moderation as moderation
     where moderation.is_active
  ) into v_has_active_moderation;

  if v_has_active_moderation then
    with hidden_rows as (
    select row.user_id, row.character_id
      from ranking_private.public_ranking_moderation as moderation
      join arena_ranking_private.daily_arena_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is null
    union
    select row.user_id, row.character_id
      from ranking_private.public_ranking_moderation as moderation
      join arena_ranking_private.daily_arena_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
       and row.character_id = moderation.character_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is not null
    )
    select pg_catalog.count(*)::integer,
           coalesce(pg_catalog.bool_or(hidden.user_id = v_user_id), false)
      into v_hidden_count, v_requester_hidden
      from hidden_rows as hidden;
  else
    v_hidden_count := 0;
    v_requester_hidden := false;
  end if;

  v_response := pg_catalog.jsonb_build_object(
    'snapshot_id', v_cache_token,
    'season_id', v_snapshot.season_id::text,
    'rules_version', v_snapshot.rules_version,
    'settled_at', (extract(epoch from v_snapshot.settled_at) * 1000)::bigint,
    'next_settlement_at', (extract(epoch from case
      when v_snapshot.is_bootstrap then
        arena_ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 18 minutes'
      else greatest(
        v_snapshot.settled_at + interval '1 hour 18 minutes',
        arena_ranking_private.utc_hour_bucket(v_tracking_started_at) + interval '1 hour 18 minutes'
      )
    end) * 1000)::bigint,
    'generated_at', (extract(epoch from v_snapshot.generated_at) * 1000)::bigint,
    'server_now', (extract(epoch from pg_catalog.statement_timestamp()) * 1000)::bigint,
    'is_bootstrap', v_snapshot.is_bootstrap,
    'unchanged', coalesce(p_known_snapshot_id = v_cache_token, false),
    't', case when v_requester_hidden then v_snapshot.participant_count
      else greatest(v_snapshot.participant_count - v_hidden_count, 0) end
  );
  if p_known_snapshot_id = v_cache_token then return v_response; end if;

  if not v_has_active_moderation then
    select coalesce(
             pg_catalog.jsonb_agg(owned.compact_entry order by owned.list_index),
             '[]'::jsonb
           )
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
  end if;

  with annotated as (
    select row.*,
           coalesce(account_moderation.hidden_from_public_rankings, false)
             or coalesce(character_moderation.hidden_from_public_rankings, false) as is_hidden,
           coalesce(
             character_moderation.replacement_display_name,
             account_moderation.replacement_display_name,
             row.display_name
           ) as public_display_name
      from arena_ranking_private.daily_arena_leaderboard_rows as row
      left join ranking_private.public_ranking_moderation as account_moderation
        on account_moderation.user_id = row.user_id
       and account_moderation.character_id is null
       and account_moderation.is_active
      left join ranking_private.public_ranking_moderation as character_moderation
        on character_moderation.user_id = row.user_id
       and character_moderation.character_id = row.character_id
       and character_moderation.is_active
     where row.snapshot_id = v_snapshot.snapshot_id
  ), bounded as (
    select annotated.*
      from annotated
     where not annotated.is_hidden
     order by annotated.list_index
     limit 997
  ), reranked as (
    select bounded.*,
           pg_catalog.rank() over (
             order by bounded.score desc, bounded.wins desc,
                      bounded.losses asc, bounded.draws desc
           ) as public_rank,
           pg_catalog.row_number() over (order by bounded.list_index) - 1 as public_list_index
      from bounded
  )
  select coalesce(
           pg_catalog.jsonb_agg(
             reranked.compact_entry || pg_catalog.jsonb_build_object(
               'r', reranked.public_rank,
               'i', reranked.public_list_index,
               'n', reranked.public_display_name
             ) order by reranked.public_list_index
           ),
           '[]'::jsonb
         )
    into v_public_entries
    from reranked;

  with hidden_rows as (
    select row.user_id, row.character_id, row.rank_number, row.list_index,
           row.score, row.wins, row.losses, row.draws
      from ranking_private.public_ranking_moderation as moderation
      join arena_ranking_private.daily_arena_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is null
    union
    select row.user_id, row.character_id, row.rank_number, row.list_index,
           row.score, row.wins, row.losses, row.draws
      from ranking_private.public_ranking_moderation as moderation
      join arena_ranking_private.daily_arena_leaderboard_rows as row
        on row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = moderation.user_id
       and row.character_id = moderation.character_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is not null
  ), owned as (
    select row.*,
           exists (
             select 1 from hidden_rows as hidden
              where hidden.user_id = row.user_id
                and hidden.character_id = row.character_id
           ) as is_hidden
      from arena_ranking_private.daily_arena_leaderboard_rows as row
     where row.snapshot_id = v_snapshot.snapshot_id
       and row.user_id = v_user_id
     order by row.list_index
     limit 3
  ), adjusted as (
    select owned.*,
           case when owned.is_hidden then owned.rank_number else
             owned.rank_number - (
               select pg_catalog.count(*) from hidden_rows as hidden
                where hidden.score > owned.score
                   or (hidden.score = owned.score and hidden.wins > owned.wins)
                   or (hidden.score = owned.score and hidden.wins = owned.wins
                       and hidden.losses < owned.losses)
                   or (hidden.score = owned.score and hidden.wins = owned.wins
                       and hidden.losses = owned.losses and hidden.draws > owned.draws)
             )
           end as owner_rank,
           case when owned.is_hidden then owned.list_index else
             owned.list_index - (
               select pg_catalog.count(*) from hidden_rows as hidden
                where hidden.list_index < owned.list_index
             )
           end as owner_list_index
      from owned
  )
  select coalesce(
           pg_catalog.jsonb_agg(
             adjusted.compact_entry || pg_catalog.jsonb_build_object(
               'r', adjusted.owner_rank,
               'i', adjusted.owner_list_index,
               'n', adjusted.display_name
             ) order by adjusted.list_index
           ),
           '[]'::jsonb
         )
    into v_own_entries
    from adjusted;

  return v_response || pg_catalog.jsonb_build_object(
    'e', v_public_entries,
    'o', v_own_entries
  );
end;
$$;

-- The V2 compatibility RPC inherits the moderated daily payload.  Replace the legacy tabular RPC
-- as well so an old client cannot bypass hiding or name replacement through a second reader.
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
  v_base_snapshot_id text;
  v_raw_total integer;
  v_hidden_count integer;
  v_requester_hidden boolean;
  v_total integer;
  v_limit integer := least(greatest(coalesce(p_limit, 1000), 1), 1000);
begin
  if v_user_id is null then raise exception 'authentication required'; end if;
  select snapshot.snapshot_id, snapshot.participant_count
    into strict v_base_snapshot_id, v_raw_total
    from ranking_private.daily_leaderboard_state as state
    join ranking_private.daily_leaderboard_snapshots as snapshot
      on snapshot.snapshot_id = state.latest_snapshot_id
   where state.singleton;

  with hidden_rows as (
    select row.user_id, row.character_id
      from ranking_private.public_ranking_moderation as moderation
      join ranking_private.daily_leaderboard_rows as row
        on row.snapshot_id = v_base_snapshot_id
       and row.user_id = moderation.user_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is null
    union
    select row.user_id, row.character_id
      from ranking_private.public_ranking_moderation as moderation
      join ranking_private.daily_leaderboard_rows as row
        on row.snapshot_id = v_base_snapshot_id
       and row.user_id = moderation.user_id
       and row.character_id = moderation.character_id
     where moderation.is_active
       and moderation.hidden_from_public_rankings
       and moderation.character_id is not null
  )
  select pg_catalog.count(*)::integer,
         coalesce(pg_catalog.bool_or(hidden.user_id = v_user_id), false)
    into v_hidden_count, v_requester_hidden
    from hidden_rows as hidden;
  v_total := case when v_requester_hidden then v_raw_total
    else greatest(v_raw_total - v_hidden_count, 0) end;

  return query
    with hidden_rows as (
      select row.user_id, row.character_id, row.rank_number, row.list_index,
             row.combat_power
        from ranking_private.public_ranking_moderation as moderation
        join ranking_private.daily_leaderboard_rows as row
          on row.snapshot_id = v_base_snapshot_id
         and row.user_id = moderation.user_id
       where moderation.is_active
         and moderation.hidden_from_public_rankings
         and moderation.character_id is null
      union
      select row.user_id, row.character_id, row.rank_number, row.list_index,
             row.combat_power
        from ranking_private.public_ranking_moderation as moderation
        join ranking_private.daily_leaderboard_rows as row
          on row.snapshot_id = v_base_snapshot_id
         and row.user_id = moderation.user_id
         and row.character_id = moderation.character_id
       where moderation.is_active
         and moderation.hidden_from_public_rankings
         and moderation.character_id is not null
    ), annotated as (
      select row.*,
             coalesce(account_moderation.hidden_from_public_rankings, false)
               or coalesce(character_moderation.hidden_from_public_rankings, false) as is_hidden,
             coalesce(
               character_moderation.replacement_display_name,
               account_moderation.replacement_display_name,
               row.display_name
             ) as public_display_name
        from ranking_private.daily_leaderboard_rows as row
        left join ranking_private.public_ranking_moderation as account_moderation
          on account_moderation.user_id = row.user_id
         and account_moderation.character_id is null
         and account_moderation.is_active
        left join ranking_private.public_ranking_moderation as character_moderation
          on character_moderation.user_id = row.user_id
         and character_moderation.character_id = row.character_id
         and character_moderation.is_active
       where row.snapshot_id = v_base_snapshot_id
    ), bounded as (
      select annotated.*
        from annotated
       where not annotated.is_hidden
       order by annotated.list_index
       limit v_limit
    ), reranked as (
      select bounded.*,
             pg_catalog.rank() over (order by bounded.combat_power desc) as public_rank,
             pg_catalog.row_number() over (order by bounded.list_index) - 1 as public_list_index
        from bounded
    ), owned as (
      select row.*,
             exists (
               select 1 from hidden_rows as hidden
                where hidden.user_id = row.user_id
                  and hidden.character_id = row.character_id
             ) as is_hidden
        from ranking_private.daily_leaderboard_rows as row
       where row.snapshot_id = v_base_snapshot_id
         and row.user_id = v_user_id
         and row.character_id = p_character_id
       limit 1
    ), adjusted_owned as (
      select owned.*,
             case when owned.is_hidden then owned.rank_number else
               owned.rank_number - (
                 select pg_catalog.count(*) from hidden_rows as hidden
                  where hidden.combat_power > owned.combat_power
               )
             end as owner_rank,
             case when owned.is_hidden then owned.list_index else
               owned.list_index - (
                 select pg_catalog.count(*) from hidden_rows as hidden
                  where hidden.list_index < owned.list_index
               )
             end as owner_list_index
        from owned
    ), public_selected as (
      select reranked.public_rank as rank_number,
             reranked.public_list_index as list_index,
             reranked.list_index as raw_list_index,
             reranked.user_id, reranked.character_id, reranked.slot_id,
             reranked.public_display_name as display_name,
             reranked.hero_class, reranked.level, reranked.combat_power,
             reranked.achieved_at, reranked.updated_at, false as is_owner
        from reranked
       where not exists (
         select 1 from adjusted_owned as owned
          where owned.user_id = reranked.user_id
            and owned.character_id = reranked.character_id
       )
       order by reranked.public_list_index
       limit v_limit - case when exists (select 1 from adjusted_owned) then 1 else 0 end
    ), selected as (
      select * from public_selected
      union all
      select owned.owner_rank, owned.owner_list_index, owned.list_index,
             owned.user_id, owned.character_id, owned.slot_id, owned.display_name,
             owned.hero_class, owned.level, owned.combat_power,
             owned.achieved_at, owned.updated_at, true
        from adjusted_owned as owned
    )
    select selected.rank_number, selected.list_index,
           case when selected.user_id = v_user_id then selected.user_id
             else pg_catalog.md5('ranking-public-v1:' || selected.user_id::text)::uuid end,
           selected.character_id, selected.slot_id, selected.display_name,
           selected.hero_class, selected.level, selected.combat_power,
           selected.achieved_at, selected.updated_at,
           v_total
      from selected
     order by selected.list_index, selected.is_owner desc, selected.raw_list_index
     limit v_limit;
end;
$$;

revoke all on function public.get_daily_leaderboard(text),
  public.get_daily_arena_leaderboard(text),
  public.get_leaderboard(uuid, integer)
  from public, anon, authenticated, service_role;
grant execute on function public.get_daily_leaderboard(text),
  public.get_daily_arena_leaderboard(text),
  public.get_leaderboard(uuid, integer)
  to authenticated;

comment on table ranking_private.public_ranking_moderation is
  'Private account/character presentation policy shared by general and arena ranking readers. Raw snapshots are never rewritten.';
comment on column ranking_private.public_ranking_moderation.hidden_from_public_rankings is
  'When active, remove this account or character before recalculating every public rank and list index.';
comment on column ranking_private.public_ranking_moderation.replacement_display_name is
  'Administrator-selected public nickname. Owners continue to receive their original snapshot name in the own-entry array.';
comment on function public.admin_set_ranking_moderation(
  uuid, uuid, boolean, boolean, text, text, text, text
) is 'Service-role-only audited upsert/deactivation for public ranking moderation.';
comment on function public.get_daily_leaderboard(text) is
  'Moderated UTC hourly general ranking. The opaque snapshot token includes moderation revision; hidden owners alone receive original own rank/name/count.';
comment on function public.get_daily_arena_leaderboard(text) is
  'Moderated UTC hourly arena ranking. Public rows are re-ranked after hiding; own hidden rows preserve original rank/name/count.';
