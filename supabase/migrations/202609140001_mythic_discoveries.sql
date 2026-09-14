-- Non-economic +5 discovery history. No gameplay reward trusts these client-declared records.
create schema mythic_private;
revoke all on schema mythic_private from public, anon, authenticated, service_role;
create table mythic_private.discoveries (
  event_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  character_id uuid not null,
  display_name text not null check (char_length(display_name) between 1 and 24 and display_name !~ '[[:cntrl:]]'),
  hero_class text not null check (hero_class in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')),
  level bigint not null check (level between 1 and 10000),
  item_name text not null check (char_length(item_name) between 1 and 80 and item_name !~ '[[:cntrl:]]' and item_name like '%+5'),
  slot text not null check (slot in ('WEAPON','HEAD','BODY','HANDS','FEET','ACCESSORY')),
  power bigint not null check (power between 1 and 1000000000),
  discovered_at bigint not null check (discovered_at > 0),
  received_at timestamptz not null default statement_timestamp()
);
create index mythic_discoveries_recent on mythic_private.discoveries(discovered_at desc, event_id desc);
create index mythic_discoveries_account_received on mythic_private.discoveries(user_id, received_at);
create index mythic_discoveries_character on mythic_private.discoveries(character_id);
alter table mythic_private.discoveries enable row level security;
revoke all on mythic_private.discoveries from public, anon, authenticated, service_role;

create function public.sync_mythic_discoveries(p_records jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_user uuid := auth.uid(); r jsonb; v_id uuid; v_character uuid; v_at bigint;
begin
  if v_user is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  if jsonb_typeof(p_records) is distinct from 'array' or jsonb_array_length(p_records) > 100
     or octet_length(p_records::text) > 131072 then raise exception 'Invalid batch'; end if;
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_user::text, 140001));
  for r in select value from jsonb_array_elements(p_records) loop
    v_id := (r->>'eventId')::uuid; v_character := (r->>'characterId')::uuid;
    v_at := (r->>'discoveredAt')::bigint;
    if v_id is null or v_character is null or v_at is null then raise exception 'Missing identity'; end if;
    perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended(v_character::text, 140002));
    if exists (select 1 from mythic_private.discoveries where event_id = v_id and user_id <> v_user)
       or exists (select 1 from shared_player_private.snapshots where character_id = v_character and user_id <> v_user)
       or exists (select 1 from mythic_private.discoveries where character_id = v_character and user_id <> v_user)
       then raise exception 'Character ownership mismatch' using errcode = '42501'; end if;
    -- Retry does not rewrite the original discovery or make it recent again.
    if exists (select 1 from mythic_private.discoveries where event_id = v_id and user_id = v_user) then continue; end if;
    if v_at > extract(epoch from statement_timestamp()) * 1000 + 300000 then raise exception 'Future discovery'; end if;
    if (select count(*) from mythic_private.discoveries where user_id = v_user and received_at >= date_trunc('day', statement_timestamp())) >= 300
      then raise exception 'Discovery daily limit'; end if;
    insert into mythic_private.discoveries(event_id,user_id,character_id,display_name,hero_class,level,item_name,slot,power,discovered_at)
      values(v_id,v_user,v_character,btrim(r->>'displayName'),r->>'heroClass',(r->>'level')::bigint,
        r->>'itemName',r->>'slot',(r->>'power')::bigint,v_at);
  end loop;
  return jsonb_build_object('accepted',jsonb_array_length(p_records));
end $$;
revoke all on function public.sync_mythic_discoveries(jsonb) from public, anon, authenticated;
grant execute on function public.sync_mythic_discoveries(jsonb) to authenticated;

-- Admission uses the server's UTC hour, so late uploads cannot enter an already visible hour.
-- Immutable records + a strict received_at cutoff give all callers the same hourly edition.
-- Moderation and account deletion still take effect on read, as on the existing rankings.
create function public.get_mythic_discoveries() returns jsonb
language plpgsql security definer set search_path = '' as $$
declare v_cutoff timestamptz := date_trunc('hour', statement_timestamp()); v_rows jsonb;
begin
  if auth.uid() is null then raise exception 'Authentication required' using errcode = '42501'; end if;
  select coalesce(jsonb_agg(row_data order by discovered_at desc, event_id desc), '[]'::jsonb) into v_rows
  from (
    select d.discovered_at, d.event_id,
      jsonb_build_object('eventId',d.event_id,'characterId',d.character_id,
        'displayName',coalesce(
          (select m.replacement_display_name from ranking_private.public_ranking_moderation m
           where m.user_id=d.user_id and m.is_active and (m.character_id is null or m.character_id=d.character_id)
           and m.replacement_display_name is not null order by (m.character_id is not null) desc limit 1),d.display_name),
        'heroClass',d.hero_class,'level',d.level,'itemName',d.item_name,'slot',d.slot,'power',d.power,'discoveredAt',d.discovered_at) row_data
    from mythic_private.discoveries d
    where d.received_at < v_cutoff
      and not exists (select 1 from ranking_private.public_ranking_moderation m where m.user_id=d.user_id
        and m.is_active and m.hidden_from_public_rankings and (m.character_id is null or m.character_id=d.character_id))
    order by d.discovered_at desc, d.event_id desc limit 100
  ) latest;
  return jsonb_build_object('entries',v_rows,'settledAt',(extract(epoch from v_cutoff)*1000)::bigint,
    'nextSettlementAt',(extract(epoch from v_cutoff + interval '1 hour')*1000)::bigint,
    'serverNow',(extract(epoch from statement_timestamp())*1000)::bigint);
end $$;
revoke all on function public.get_mythic_discoveries() from public, anon, authenticated;
grant execute on function public.get_mythic_discoveries() to authenticated;
