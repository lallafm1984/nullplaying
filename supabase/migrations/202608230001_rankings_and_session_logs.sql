-- AlarmQuest anonymous-auth ranking and lifecycle logging schema.
-- Enable Anonymous Sign-Ins in Authentication > Providers before using the app.

create table if not exists public.ranking_entries (
  user_id uuid not null references auth.users(id) on delete cascade,
  slot_id smallint not null check (slot_id between 1 and 3),
  display_name text not null check (char_length(display_name) between 1 and 24),
  hero_class text not null check (hero_class in ('WARRIOR','ROGUE','RANGER','MAGE','CLERIC','PALADIN')),
  level bigint not null check (level >= 1),
  combat_power bigint not null check (combat_power >= 0),
  achieved_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  primary key (user_id, slot_id)
);

create index if not exists ranking_entries_score_idx
  on public.ranking_entries (combat_power desc, achieved_at asc, user_id, slot_id);

create or replace function public.set_ranking_timestamps()
returns trigger
language plpgsql
set search_path = public
as $$
begin
  new.updated_at := now();
  if tg_op = 'INSERT' or new.combat_power > old.combat_power then
    new.achieved_at := now();
  else
    new.achieved_at := old.achieved_at;
  end if;
  return new;
end;
$$;

drop trigger if exists ranking_entries_timestamps on public.ranking_entries;
create trigger ranking_entries_timestamps
before insert or update on public.ranking_entries
for each row execute function public.set_ranking_timestamps();

alter table public.ranking_entries enable row level security;
revoke all on public.ranking_entries from anon;
grant select, insert, update on public.ranking_entries to authenticated;

create policy "authenticated users can read rankings"
  on public.ranking_entries for select to authenticated using (true);
create policy "users insert their own ranking"
  on public.ranking_entries for insert to authenticated with check ((select auth.uid()) = user_id);
create policy "users update their own ranking"
  on public.ranking_entries for update to authenticated
  using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);

create table if not exists public.app_session_logs (
  event_id uuid primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  ended_at timestamptz not null,
  reason text not null check (reason in ('background','exit','replaced')),
  slot_id smallint,
  hero_level bigint,
  combat_power bigint,
  total_acts bigint,
  total_kills bigint,
  app_version text not null,
  device_model text not null,
  created_at timestamptz not null default now()
);

alter table public.app_session_logs enable row level security;
revoke all on public.app_session_logs from anon;
grant insert on public.app_session_logs to authenticated;
create policy "users insert their own session logs"
  on public.app_session_logs for insert to authenticated with check ((select auth.uid()) = user_id);

create or replace function public.get_leaderboard(p_slot_id smallint, p_limit integer default 100)
returns table (
  rank_number bigint,
  user_id uuid,
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
set search_path = public
as $$
  with ranked as (
    select dense_rank() over (order by r.combat_power desc) as rank_number,
           r.*, count(*) over ()::integer as total_participants
      from public.ranking_entries r
  )
  select rank_number, user_id, slot_id, display_name, hero_class, level,
         combat_power, achieved_at, updated_at, total_participants
    from ranked
   where rank_number <= least(greatest(p_limit, 1), 100)
      or (user_id = (select auth.uid()) and slot_id = p_slot_id)
   order by rank_number, achieved_at, user_id, slot_id;
$$;

revoke all on function public.get_leaderboard(smallint, integer) from public, anon;
grant execute on function public.get_leaderboard(smallint, integer) to authenticated;
