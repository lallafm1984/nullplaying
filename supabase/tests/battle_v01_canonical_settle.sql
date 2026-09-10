-- Battle V0.1 canonical settlement and lifecycle integration check.
--
-- Run only against an isolated database after applying
-- 202609040001_battle_v01_backend.sql:
--   psql "$LOCAL_DATABASE_URL" -v ON_ERROR_STOP=1 \
--     -f supabase/tests/battle_v01_canonical_settle.sql
--
-- It covers exact engine replay/mutations, due-trait settlement, phase-scoped
-- narrative refs, atomic season expiry, service actor audit, and idempotency.
-- The entire fixture is rolled back, including auth users and seasons.

begin;

-- This file is intentionally destructive inside its transaction. Require an
-- explicit test-only session marker and a pristine Battle/auth fixture before
-- inserting anything, then roll every row back at EOF.
set local alarmquest.battle_v01_fixture_scope = 'ISOLATED_ROLLBACK_ONLY';

do $$
begin
  if pg_catalog.current_setting(
       'alarmquest.battle_v01_fixture_scope', true
     ) is distinct from 'ISOLATED_ROLLBACK_ONLY'
     or exists (select 1 from auth.users)
     or exists (select 1 from public.battle_seasons)
     or exists (select 1 from battle_private.projection_snapshots)
     or exists (select 1 from public.battle_participants)
     or exists (select 1 from public.battle_entries)
     or exists (select 1 from battle_private.matches)
     or exists (select 1 from public.battle_character_traits)
     or exists (select 1 from public.battle_trait_candidates)
     or exists (select 1 from public.battle_trait_removals)
     or exists (select 1 from public.battle_records)
     or exists (select 1 from battle_private.match_recoveries)
     or exists (select 1 from battle_private.narrative_phase_contracts) then
    raise exception
      'Battle V0.1 canonical fixture requires an empty isolated rollback database'
      using errcode = '55000';
  end if;
end;
$$;

do $$
declare
  v_user_id uuid := 'f1000000-0000-0000-0000-000000000001';
  v_opponent_user_id uuid := 'f1000000-0000-0000-0000-000000000002';
  v_character_id uuid := 'f2000000-0000-0000-0000-000000000001';
  v_opponent_character_id uuid := 'f2000000-0000-0000-0000-000000000002';
  v_register_request_id uuid := 'f3000000-0000-0000-0000-000000000001';
  v_entry_request_id uuid := 'f3000000-0000-0000-0000-000000000002';
  v_month date := pg_catalog.date_trunc(
    'month',
    pg_catalog.clock_timestamp() at time zone 'Asia/Seoul'
  )::date;
  v_season_id uuid;
  v_snapshot_payload jsonb := '{
    "build": {
      "strength": 10,
      "constitution": 10,
      "dexterity": 10,
      "intelligence": 10,
      "wisdom": 10,
      "charisma": 10
    },
    "skills": [
      {
        "skillId": "fixture.strike",
        "displayName": "Fixture Strike",
        "kind": "STRIKE",
        "powerBasisPoints": 12000,
        "cooldownRounds": 2,
        "masteryLevel": 50
      }
    ],
    "equipment": [
      {"itemId":"fixture.weapon","displayName":"Weapon","slot":"WEAPON","rarity":"COMMON","narrativeTag":"","verifiedPowerContribution":10},
      {"itemId":"fixture.head","displayName":"Head","slot":"HEAD","rarity":"COMMON","narrativeTag":"","verifiedPowerContribution":10},
      {"itemId":"fixture.body","displayName":"Body","slot":"BODY","rarity":"COMMON","narrativeTag":"","verifiedPowerContribution":10},
      {"itemId":"fixture.hands","displayName":"Hands","slot":"HANDS","rarity":"COMMON","narrativeTag":"","verifiedPowerContribution":10},
      {"itemId":"fixture.feet","displayName":"Feet","slot":"FEET","rarity":"COMMON","narrativeTag":"","verifiedPowerContribution":10},
      {"itemId":"fixture.accessory","displayName":"Accessory","slot":"ACCESSORY","rarity":"COMMON","narrativeTag":"","verifiedPowerContribution":10}
    ],
    "snapshotVersion": 1
  }'::jsonb;
  v_registration jsonb;
  v_entry jsonb;
  v_battle_id uuid;
  v_expected_user jsonb;
  v_expected_opponent jsonb;
  v_engine_seed bigint;
  v_max_rounds integer;
  v_outcome text;
  v_skill_round_index integer;
  v_cooldown_target_index integer;
  v_seed_offset integer;
  v_candidate_seed bigint;
  v_result jsonb;
  v_mutated jsonb;
  v_settlement jsonb;
  v_phase_plan jsonb;
  v_narrative jsonb;
  v_narrative_attached boolean;
  v_user_snapshot_id uuid;
  v_opponent_snapshot_id uuid;
  v_ended_season_id uuid := 'f5000000-0000-0000-0000-000000000001';
  v_boundary_entry_id uuid := 'f5000000-0000-0000-0000-000000000002';
  v_boundary_battle_id uuid := 'f5000000-0000-0000-0000-000000000003';
  v_boundary_entry_id_2 uuid := 'f5000000-0000-0000-0000-000000000004';
  v_boundary_battle_id_2 uuid := 'f5000000-0000-0000-0000-000000000005';
  v_boundary_response jsonb;
  v_boundary_second integer;
begin
  insert into auth.users(id)
  values (v_user_id), (v_opponent_user_id);

  v_season_id := public.battle_create_monthly_season(v_month, 1);

  v_user_snapshot_id := public.battle_publish_verified_snapshot(
    v_user_id,
    v_character_id,
    'Fixture User',
    'WARRIOR',
    20,
    1,
    'fixture-user-v1',
    v_snapshot_payload,
    pg_catalog.clock_timestamp() + interval '1 day'
  );
  v_opponent_snapshot_id := public.battle_publish_verified_snapshot(
    v_opponent_user_id,
    v_opponent_character_id,
    'Fixture Opponent',
    'MAGE',
    20,
    1,
    'fixture-opponent-v1',
    v_snapshot_payload,
    pg_catalog.clock_timestamp() + interval '1 day'
  );

  perform pg_catalog.set_config(
    'request.jwt.claim.sub',
    v_opponent_user_id::text,
    false
  );
  v_registration := public.battle_request_entry(
    v_register_request_id,
    v_opponent_character_id,
    'GUARD'
  );
  if v_registration ->> 'status' <> 'NO_OPPONENT' then
    raise exception 'fixture opponent registration did not return NO_OPPONENT';
  end if;

  perform pg_catalog.set_config('request.jwt.claim.sub', v_user_id::text, false);
  v_entry := public.battle_request_entry(
    v_entry_request_id,
    v_character_id,
    'ASSAULT'
  );
  if v_entry ->> 'status' <> 'MATCHED' then
    raise exception 'canonical fixture did not create a MATCHED entry';
  end if;
  v_battle_id := (v_entry ->> 'battle_id')::uuid;
  if (v_entry #>> '{opponent,character_id}')::uuid <> v_opponent_character_id
     or not exists (
       select 1
         from battle_private.matches
        where battle_id = v_battle_id
          and opponent_user_id = v_opponent_user_id
          and opponent_character_id = v_opponent_character_id
          and opponent_snapshot_id = v_opponent_snapshot_id
     ) then
    raise exception 'canonical assignment selected an unexpected opponent projection';
  end if;

  select battle_private.expected_normalized_projection(
           entrant.snapshot_id,
           entrant.display_name,
           entrant.hero_class,
           entrant.level,
           entrant.effective_power,
           entrant.snapshot_payload,
           entries.directive,
           matches.entrant_traits_snapshot,
           seasons.growth_reference_power
         ),
         battle_private.expected_normalized_projection(
           opponent.snapshot_id,
           opponent.display_name,
           opponent.hero_class,
           opponent.level,
           opponent.effective_power,
           opponent.snapshot_payload,
           matches.opponent_guidance,
           matches.opponent_traits_snapshot,
           seasons.growth_reference_power
         ),
         matches.engine_seed,
         seasons.max_rounds
    into v_expected_user, v_expected_opponent, v_engine_seed, v_max_rounds
    from battle_private.matches as matches
    join public.battle_entries as entries
      on entries.entry_id = matches.entry_id
    join public.battle_seasons as seasons
      on seasons.season_id = matches.season_id
    join battle_private.projection_snapshots as entrant
      on entrant.snapshot_id = matches.entrant_snapshot_id
    join battle_private.projection_snapshots as opponent
      on opponent.snapshot_id = matches.opponent_snapshot_id
   where matches.battle_id = v_battle_id;

  -- Search a bounded deterministic seed neighborhood so this randomized match
  -- fixture always contains a user skill whose cooldown can be mutated below.
  for v_seed_offset in 0..64 loop
    v_candidate_seed := battle_private.i64_wrap(
      v_engine_seed::numeric + v_seed_offset::numeric
    );
    v_result := battle_private.simulate_engine_result_payload(
      v_battle_id,
      v_candidate_seed,
      1,
      v_max_rounds,
      v_expected_user,
      v_expected_opponent
    );
    exit when exists (
      select 1
        from pg_catalog.jsonb_array_elements(v_result -> 'rounds') as round_item(value)
       where round_item.value #>> '{userAction,kind}' = 'SKILL'
    );
  end loop;
  if not exists (
    select 1
      from pg_catalog.jsonb_array_elements(v_result -> 'rounds') as round_item(value)
     where round_item.value #>> '{userAction,kind}' = 'SKILL'
  ) then
    raise exception 'bounded canonical seed fixture found no user skill';
  end if;
  update battle_private.matches
     set engine_seed = v_candidate_seed
   where battle_id = v_battle_id;
  v_engine_seed := v_candidate_seed;
  v_outcome := v_result ->> 'outcome';

  if v_result is null
     or not battle_private.is_valid_engine_result(
       v_result,
       v_battle_id,
       v_outcome
     ) then
    raise exception 'official deterministic result did not validate';
  end if;

  -- In-range impossible damage, action, critical, cooldown history, and a
  -- one-bit seed mutation must all be rejected by the public settlement RPC.
  v_mutated := pg_catalog.jsonb_set(
    pg_catalog.jsonb_set(
      v_result,
      array['rounds'],
      '[
        {"number":1,"userHpBefore":1000,"opponentHpBefore":1000,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":350,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":650},
        {"number":2,"userHpBefore":1000,"opponentHpBefore":650,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":350,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":300},
        {"number":3,"userHpBefore":1000,"opponentHpBefore":300,"userAction":{"actor":"USER","kind":"BASIC_ATTACK","skillId":"","damage":300,"healing":0,"critical":false},"opponentAction":{"actor":"OPPONENT","kind":"GUARD","skillId":"","damage":0,"healing":0,"critical":false},"userHpAfter":1000,"opponentHpAfter":0}
      ]'::jsonb
    ),
    array['outcome'],
    '"USER_WIN"'::jsonb
  );
  begin
    perform public.battle_settle_match(
      v_battle_id, 1, 'USER_WIN', v_mutated, null, null
    );
    raise exception 'impossible 350 damage transcript was accepted';
  exception when sqlstate '22023' then null; end;

  v_mutated := pg_catalog.jsonb_set(
    v_result,
    array['rounds','1','userAction','kind'],
    pg_catalog.to_jsonb(
      case v_result #>> '{rounds,1,userAction,kind}'
        when 'GUARD' then 'BASIC_ATTACK'
        else 'GUARD'
      end
    )
  );
  begin
    perform public.battle_settle_match(v_battle_id, 1, v_outcome, v_mutated, null, null);
    raise exception 'mutated action transcript was accepted';
  exception when sqlstate '22023' then null; end;

  v_mutated := pg_catalog.jsonb_set(
    v_result,
    array['rounds','3','userAction','critical'],
    pg_catalog.to_jsonb(not (v_result #>> '{rounds,3,userAction,critical}')::boolean)
  );
  begin
    perform public.battle_settle_match(v_battle_id, 1, v_outcome, v_mutated, null, null);
    raise exception 'mutated critical transcript was accepted';
  exception when sqlstate '22023' then null; end;

  select round_item.ordinality::integer
    into v_skill_round_index
    from pg_catalog.jsonb_array_elements(v_result -> 'rounds') with ordinality
      as round_item(value, ordinality)
   where round_item.value #>> '{userAction,kind}' = 'SKILL'
   order by round_item.ordinality
   limit 1;
  if v_skill_round_index is null then
    raise exception 'canonical fixture needs one user skill for cooldown mutation';
  end if;
  v_cooldown_target_index := case
    when v_skill_round_index < pg_catalog.jsonb_array_length(v_result -> 'rounds')
      then v_skill_round_index + 1
    else v_skill_round_index - 1
  end;
  v_mutated := pg_catalog.jsonb_set(
    v_result,
    array['rounds',(v_cooldown_target_index - 1)::text,'userAction'],
    v_result #> array['rounds',(v_skill_round_index - 1)::text,'userAction']
  );
  begin
    perform public.battle_settle_match(v_battle_id, 1, v_outcome, v_mutated, null, null);
    raise exception 'cooldown-violating transcript was accepted';
  exception when sqlstate '22023' then null; end;

  v_mutated := pg_catalog.jsonb_set(
    v_result,
    array['serverSeed'],
    pg_catalog.to_jsonb(v_engine_seed # 1::bigint)
  );
  begin
    perform public.battle_settle_match(v_battle_id, 1, v_outcome, v_mutated, null, null);
    raise exception 'one-bit seed mutation was accepted';
  exception when sqlstate '22023' then null; end;

  -- A due removal must complete even when settlement submits no trait_code.
  insert into public.battle_character_traits (
    user_id, character_id, trait_code, slot_number, source_battle_id
  ) values (
    v_user_id, v_character_id, 'TRAIT_001', 1, v_battle_id
  );
  insert into public.battle_trait_removals (
    removal_id, user_id, character_id, trait_code, status,
    requested_at, completes_at
  ) values (
    'f4000000-0000-0000-0000-000000000001',
    v_user_id,
    v_character_id,
    'TRAIT_001',
    'PENDING',
    pg_catalog.now() - interval '2 hours',
    pg_catalog.now() - interval '1 hour'
  );

  v_settlement := public.battle_settle_match(
    v_battle_id,
    1,
    v_outcome,
    v_result,
    null,
    null
  );

  if v_settlement ->> 'status' <> 'SETTLED'
     or not exists (
       select 1
         from public.battle_records
        where battle_id = v_battle_id
          and outcome = v_outcome
     )
     or not exists (
       select 1
         from public.battle_participants
        where season_id = v_season_id
          and user_id = v_user_id
          and character_id = v_character_id
          and matches_played = 1
          and wins + draws + losses = 1
     )
     or not exists (
       select 1
         from public.battle_participants
        where season_id = v_season_id
          and user_id = v_opponent_user_id
          and character_id = v_opponent_character_id
          and rating = 1000
          and matches_played = 0
     )
     or exists (
       select 1
         from public.battle_character_traits
        where user_id = v_user_id
          and character_id = v_character_id
          and trait_code = 'TRAIT_001'
     )
     or not exists (
       select 1
         from public.battle_trait_removals
        where removal_id = 'f4000000-0000-0000-0000-000000000001'
          and status = 'COMPLETED'
  ) then
    raise exception 'canonical Battle settlement integration check failed';
  end if;

  v_phase_plan := '[
    {"phase_id":"P1","kind":"OPENING","actor":"A","action":"SKILL","skill_ref":"A_SKILL_01","item_ref":"A_ITEM_WEAPON","hp_a_after":1000,"hp_b_after":900,"allowed_refs":["A_SKILL_01","A_ITEM_WEAPON"]},
    {"phase_id":"P2","kind":"TURNING_POINT","actor":"B","action":"SKILL","skill_ref":"B_SKILL_01","item_ref":"B_ITEM_BODY","hp_a_after":800,"hp_b_after":700,"allowed_refs":["B_SKILL_01","B_ITEM_BODY"]},
    {"phase_id":"P3","kind":"FINISH","actor":"A","action":"GUARD","skill_ref":null,"item_ref":"A_ITEM_BODY","hp_a_after":600,"hp_b_after":400,"allowed_refs":["A_ITEM_BODY"]}
  ]'::jsonb;
  if not battle_private.is_valid_narrative_phase_plan(v_phase_plan, v_battle_id)
     or battle_private.is_valid_narrative_phase_plan(
       pg_catalog.jsonb_set(
         pg_catalog.jsonb_set(
           v_phase_plan,
           '{0,skill_ref}',
           '"B_SKILL_01"'::jsonb
         ),
         '{0,allowed_refs}',
         '["B_SKILL_01"]'::jsonb
       ),
       v_battle_id
     ) then
    raise exception 'phase-plan issued owner/ref validation failed';
  end if;

  execute 'set local role service_role';
  if not public.battle_issue_narrative_phase_plan(v_battle_id, v_phase_plan) then
    raise exception 'service phase-plan issuance did not create a contract';
  end if;
  execute 'reset role';
  if not exists (
    select 1
      from battle_private.narrative_phase_contracts
     where battle_id = v_battle_id
       and phase_plan = v_phase_plan
       and issuer_database_role = 'service_role'
       and pg_catalog.to_regrole(issuer_session_user) is not null
  ) then
    raise exception 'phase-plan issuer audit failed';
  end if;

  v_narrative := pg_catalog.jsonb_build_object(
    'encounter_id', v_battle_id,
    'scenes', pg_catalog.jsonb_build_array(
      '{"phase_id":"P1","title_ko":"첫 격돌","segments":[{"type":"ENTITY_REF","ref":"A_SKILL_01","particle":"SUBJECT"}],"dialogue_ko":null,"effect_key":"CLASH"}'::jsonb,
      '{"phase_id":"P2","title_ko":"흐름 전환","segments":[{"type":"ENTITY_REF","ref":"B_ITEM_BODY","particle":"OBJECT"}],"dialogue_ko":null,"effect_key":"COUNTER"}'::jsonb,
      '{"phase_id":"P3","title_ko":"최후 방어","segments":[{"type":"ENTITY_REF","ref":"A_ITEM_BODY","particle":"WITH"}],"dialogue_ko":null,"effect_key":"FINISH"}'::jsonb
    )
  );

  if not battle_private.is_valid_narrative_payload(v_narrative, v_battle_id)
     -- A_ITEM_BODY is issued and owned by A, but only P3 allows it.
     or battle_private.is_valid_narrative_payload(
       pg_catalog.jsonb_set(
         v_narrative,
         '{scenes,0,segments,0,ref}',
         '"A_ITEM_BODY"'::jsonb
       ),
       v_battle_id
     )
     or battle_private.is_valid_narrative_payload(
       pg_catalog.jsonb_set(
         v_narrative,
         '{scenes,0,segments,0,ref}',
         '"A_SKILL_99"'::jsonb
       ),
       v_battle_id
     )
     or battle_private.is_valid_narrative_payload(
       pg_catalog.jsonb_set(
         v_narrative,
         '{scenes,0,segments,0,ref}',
         '"B_SKILL_01"'::jsonb
       ),
       v_battle_id
     )
     or battle_private.is_valid_narrative_payload(
       pg_catalog.jsonb_set(
         v_narrative,
         '{scenes,0,segments,0,ref}',
         '"A_TRAIT_01"'::jsonb
       ),
       v_battle_id
     )
     or battle_private.is_valid_narrative_payload(
       pg_catalog.jsonb_set(
         v_narrative,
         '{scenes}',
         pg_catalog.jsonb_build_array(
           v_narrative #> '{scenes,2}',
           v_narrative #> '{scenes,0}',
           v_narrative #> '{scenes,1}'
         )
       ),
       v_battle_id
     ) then
    raise exception 'phase-specific narrative validation failed';
  end if;

  v_narrative_attached := public.battle_attach_narrative(v_battle_id, v_narrative);
  if not v_narrative_attached
     or not exists (
       select 1
         from public.battle_records
        where battle_id = v_battle_id
          and narrative = v_narrative
     ) then
    raise exception 'canonical phase narrative did not attach';
  end if;

  -- The natural boundary function is one transaction with no match batch cap:
  -- locked_at and every open match/entry/audit appear together or not at all.
  insert into public.battle_seasons (
    season_id, season_key, starts_at, ends_at, rules_version
  ) values (
    v_ended_season_id,
    '2000-01',
    '2000-01-01 00:00:00+09',
    '2000-02-01 00:00:00+09',
    1
  );
  insert into public.battle_participants (
    season_id, user_id, character_id, snapshot_id, guidance,
    rating, highest_rating
  ) values
    (v_ended_season_id, v_user_id, v_character_id, v_user_snapshot_id,
     'ASSAULT', 1000, 1000),
    (v_ended_season_id, v_opponent_user_id, v_opponent_character_id,
     v_opponent_snapshot_id, 'GUARD', 1000, 1000);
  insert into public.battle_entries (
    entry_id, season_id, user_id, character_id, snapshot_id,
    entry_day, directive, status, battle_id, created_at
  ) values (
    v_boundary_entry_id,
    v_ended_season_id,
    v_user_id,
    v_character_id,
    v_user_snapshot_id,
    '2000-01-31',
    'ASSAULT',
    'MATCHED',
    v_boundary_battle_id,
    '2000-01-31 23:59:00+09'
  ), (
    v_boundary_entry_id_2,
    v_ended_season_id,
    v_opponent_user_id,
    v_opponent_character_id,
    v_opponent_snapshot_id,
    '2000-01-31',
    'GUARD',
    'MATCHED',
    v_boundary_battle_id_2,
    '2000-01-31 23:59:01+09'
  );
  insert into battle_private.matches (
    battle_id, entry_id, season_id,
    entrant_user_id, entrant_character_id, entrant_snapshot_id,
    opponent_user_id, opponent_character_id, opponent_snapshot_id,
    opponent_guidance, entrant_rating_before, opponent_rating_reference,
    rating_window, candidate_pool_size, expected_score, k_factor,
    rules_version, engine_seed, status, created_at
  ) values (
    v_boundary_battle_id,
    v_boundary_entry_id,
    v_ended_season_id,
    v_user_id,
    v_character_id,
    v_user_snapshot_id,
    v_opponent_user_id,
    v_opponent_character_id,
    v_opponent_snapshot_id,
    'GUARD',
    1000,
    1000,
    100,
    1,
    0.5,
    24,
    1,
    987654321,
    'MATCHED',
    '2000-01-31 23:59:00+09'
  ), (
    v_boundary_battle_id_2,
    v_boundary_entry_id_2,
    v_ended_season_id,
    v_opponent_user_id,
    v_opponent_character_id,
    v_opponent_snapshot_id,
    v_user_id,
    v_character_id,
    v_user_snapshot_id,
    'ASSAULT',
    1000,
    1000,
    100,
    1,
    0.5,
    24,
    1,
    987654323,
    'MATCHED',
    '2000-01-31 23:59:01+09'
  );

  v_boundary_response := public.battle_settle_match(
    v_boundary_battle_id,
    1,
    'USER_WIN',
    '{}'::jsonb,
    null,
    null
  );
  v_boundary_second := (
    battle_private.finalize_season(
      v_ended_season_id,
      '2000-02-01 00:00:00+09',
      false,
      null
    ) ->> 'matches_voided'
  )::integer;
  if v_boundary_response ->> 'status' <> 'VOID'
     or v_boundary_second <> 0
     or not exists (
       select 1
         from public.battle_seasons
        where season_id = v_ended_season_id
          and locked_at = ends_at
     )
     or not exists (
       select 1
         from battle_private.matches
        where battle_id in (v_boundary_battle_id, v_boundary_battle_id_2)
          and status = 'VOID'
          and engine_result is null
          and score_delta is null
       group by season_id
       having pg_catalog.count(*) = 2
     )
     or not exists (
       select 1
         from public.battle_entries
        where entry_id in (v_boundary_entry_id, v_boundary_entry_id_2)
          and status = 'VOID'
       group by season_id
       having pg_catalog.count(*) = 2
     )
     or not exists (
       select 1
         from battle_private.match_recoveries
        where battle_id = v_boundary_battle_id
          and recovery_source = 'SEASON_BOUNDARY'
          and recovery_kind = 'EXPIRED'
          and entries_used_on_entry_day = 0
          and remaining_entries_on_entry_day = 3
          and match_status_before = 'MATCHED'
          and match_status_after = 'VOID'
          and entry_status_before = 'MATCHED'
          and entry_status_after = 'VOID'
          and ticket_counted_before
          and not ticket_counted_after
          and pg_catalog.to_regrole(actor_database_role) is not null
          and pg_catalog.to_regrole(actor_session_user) is not null
     )
     or (select pg_catalog.count(*) from battle_private.match_recoveries
          where battle_id in (v_boundary_battle_id, v_boundary_battle_id_2)) <> 2
     or exists (
       select 1 from public.battle_records
        where battle_id in (v_boundary_battle_id, v_boundary_battle_id_2)
     ) then
    raise exception 'atomic natural season boundary contract failed';
  end if;

  -- ACL plus the explicit principal guard reject any non-service caller before
  -- a supplied battle/reason can affect recovery state.
  begin
    perform public.battle_recover_match(
      'f6000000-0000-0000-0000-000000000001',
      v_boundary_battle_id,
      'VOID',
      'must be rejected before lookup'
    );
    raise exception 'non-service recovery caller was accepted';
  exception when sqlstate '42501' then null; end;
  if pg_catalog.has_function_privilege(
       'authenticated',
       'public.battle_recover_match(uuid,uuid,text,text)',
       'EXECUTE'
     )
     or not pg_catalog.has_function_privilege(
       'service_role',
       'public.battle_recover_match(uuid,uuid,text,text)',
       'EXECUTE'
     )
     or pg_catalog.has_function_privilege(
       'authenticated',
       'public.battle_lock_season(uuid)',
       'EXECUTE'
     )
     or not pg_catalog.has_function_privilege(
       'service_role',
       'public.battle_lock_season(uuid)',
       'EXECUTE'
     )
     or pg_catalog.has_function_privilege(
       'service_role',
       'battle_private.finalize_season(uuid,timestamptz,boolean,text)',
       'EXECUTE'
  ) then
    raise exception 'recovery/finalizer RPC ACL contract failed';
  end if;

  -- Leave one active match for the following SET ROLE service_role block,
  -- which proves that the audit actor is captured from the server session.
  insert into public.battle_entries (
    entry_id, season_id, user_id, character_id, snapshot_id,
    entry_day, directive, status, battle_id
  ) values (
    'f7000000-0000-0000-0000-000000000001',
    v_season_id,
    v_user_id,
    v_character_id,
    v_user_snapshot_id,
    battle_private.korea_day(pg_catalog.clock_timestamp()),
    'ASSAULT',
    'MATCHED',
    'f7000000-0000-0000-0000-000000000002'
  );
  insert into battle_private.matches (
    battle_id, entry_id, season_id,
    entrant_user_id, entrant_character_id, entrant_snapshot_id,
    opponent_user_id, opponent_character_id, opponent_snapshot_id,
    opponent_guidance, entrant_rating_before, opponent_rating_reference,
    rating_window, candidate_pool_size, expected_score, k_factor,
    rules_version, engine_seed, status
  ) values (
    'f7000000-0000-0000-0000-000000000002',
    'f7000000-0000-0000-0000-000000000001',
    v_season_id,
    v_user_id,
    v_character_id,
    v_user_snapshot_id,
    v_opponent_user_id,
    v_opponent_character_id,
    v_opponent_snapshot_id,
    'GUARD',
    (select rating from public.battle_participants
      where season_id = v_season_id and user_id = v_user_id
        and character_id = v_character_id),
    1000,
    100,
    1,
    0.5,
    24,
    1,
    987654322,
    'MATCHED'
  );

  -- A distinct active season proves that a pre-end manual lock takes the same
  -- private finalizer as natural month-end and atomically voids every match.
  insert into public.battle_seasons (
    season_id, season_key, starts_at, ends_at, rules_version
  ) values (
    'f8000000-0000-0000-0000-000000000001',
    '2099-12',
    pg_catalog.clock_timestamp() - interval '1 day',
    pg_catalog.clock_timestamp() + interval '1 day',
    1
  );
  insert into public.battle_participants (
    season_id, user_id, character_id, snapshot_id, guidance,
    rating, highest_rating
  ) values
    ('f8000000-0000-0000-0000-000000000001', v_user_id, v_character_id,
     v_user_snapshot_id, 'ASSAULT', 1000, 1000),
    ('f8000000-0000-0000-0000-000000000001', v_opponent_user_id,
     v_opponent_character_id, v_opponent_snapshot_id, 'GUARD', 1000, 1000);
  insert into public.battle_entries (
    entry_id, season_id, user_id, character_id, snapshot_id,
    entry_day, directive, status, battle_id
  ) values
    ('f8000000-0000-0000-0000-000000000002',
     'f8000000-0000-0000-0000-000000000001', v_user_id, v_character_id,
     v_user_snapshot_id, battle_private.korea_day(pg_catalog.clock_timestamp()),
     'ASSAULT', 'MATCHED', 'f8000000-0000-0000-0000-000000000003'),
    ('f8000000-0000-0000-0000-000000000004',
     'f8000000-0000-0000-0000-000000000001', v_opponent_user_id,
     v_opponent_character_id, v_opponent_snapshot_id,
     battle_private.korea_day(pg_catalog.clock_timestamp()),
     'GUARD', 'MATCHED', 'f8000000-0000-0000-0000-000000000005');
  insert into battle_private.matches (
    battle_id, entry_id, season_id,
    entrant_user_id, entrant_character_id, entrant_snapshot_id,
    opponent_user_id, opponent_character_id, opponent_snapshot_id,
    opponent_guidance, entrant_rating_before, opponent_rating_reference,
    rating_window, candidate_pool_size, expected_score, k_factor,
    rules_version, engine_seed, status
  ) values
    ('f8000000-0000-0000-0000-000000000003',
     'f8000000-0000-0000-0000-000000000002',
     'f8000000-0000-0000-0000-000000000001',
     v_user_id, v_character_id, v_user_snapshot_id,
     v_opponent_user_id, v_opponent_character_id, v_opponent_snapshot_id,
     'GUARD', 1000, 1000, 100, 1, 0.5, 24, 1,
     800000000000000001, 'MATCHED'),
    ('f8000000-0000-0000-0000-000000000005',
     'f8000000-0000-0000-0000-000000000004',
     'f8000000-0000-0000-0000-000000000001',
     v_opponent_user_id, v_opponent_character_id, v_opponent_snapshot_id,
     v_user_id, v_character_id, v_user_snapshot_id,
     'ASSAULT', 1000, 1000, 100, 1, 0.5, 24, 1,
     800000000000000002, 'MATCHED');

  raise notice 'BATTLE_V01_CANONICAL_SETTLE_PASS battle_id=%', v_battle_id;
end;
$$;

select pg_catalog.set_config('request.jwt.claim.sub', '', true);
set local role service_role;

do $$
declare
  v_response jsonb;
  v_manual_first boolean;
  v_manual_second boolean;
begin
  v_response := public.battle_recover_match(
    'f7000000-0000-0000-0000-000000000003',
    'f7000000-0000-0000-0000-000000000002',
    'VOID',
    'canonical permanent service failure'
  );
  if v_response ->> 'status' <> 'VOID'
     or v_response ->> 'match_status_before' <> 'MATCHED'
     or v_response ->> 'match_status_after' <> 'VOID'
     or v_response ->> 'entry_status_before' <> 'MATCHED'
     or v_response ->> 'entry_status_after' <> 'VOID'
     or (v_response ->> 'ticket_counted_before')::boolean is not true
     or (v_response ->> 'ticket_counted_after')::boolean is not false
     or v_response #>> '{actor,database_role}' <> 'service_role'
     or v_response #>> '{actor,session_user}' is null
     or public.battle_recover_match(
       'f7000000-0000-0000-0000-000000000003',
       'f7000000-0000-0000-0000-000000000002',
       'VOID',
       'canonical permanent service failure'
     ) <> v_response then
    raise exception 'service recovery actor/idempotency response failed';
  end if;

  v_manual_first := public.battle_lock_season(
    'f8000000-0000-0000-0000-000000000001'
  );
  v_manual_second := public.battle_lock_season(
    'f8000000-0000-0000-0000-000000000001'
  );
  if v_manual_first is not true or v_manual_second is not false then
    raise exception 'manual season lock idempotency response failed';
  end if;
end;
$$;

reset role;

do $$
begin
  if not exists (
       select 1
         from battle_private.match_recoveries
        where recovery_id = 'f7000000-0000-0000-0000-000000000003'
          and actor_database_role = 'service_role'
          and match_status_before = 'MATCHED'
          and match_status_after = 'VOID'
          and entry_status_before = 'MATCHED'
          and entry_status_after = 'VOID'
          and ticket_counted_before
          and not ticket_counted_after
          and pg_catalog.to_regrole(actor_database_role) is not null
          and pg_catalog.to_regrole(actor_session_user) is not null
     )
     or not exists (
       select 1
         from battle_private.matches
        where battle_id = 'f7000000-0000-0000-0000-000000000002'
          and status = 'VOID'
     )
     or (select pg_catalog.count(*)
           from battle_private.match_recoveries
          where recovery_id = 'f7000000-0000-0000-0000-000000000003') <> 1
     or not exists (
       select 1
         from public.battle_seasons
        where season_id = 'f8000000-0000-0000-0000-000000000001'
          and locked_at is not null
          and locked_at < ends_at
     )
     or (select pg_catalog.count(*)
           from battle_private.matches
          where season_id = 'f8000000-0000-0000-0000-000000000001'
            and status = 'VOID') <> 2
     or (select pg_catalog.count(*)
           from public.battle_entries
          where season_id = 'f8000000-0000-0000-0000-000000000001'
            and status = 'VOID') <> 2
     or (select pg_catalog.count(*)
           from battle_private.match_recoveries
          where season_id = 'f8000000-0000-0000-0000-000000000001'
            and recovery_source = 'SEASON_LOCK'
            and recovery_kind = 'EXPIRED'
            and actor_database_role = 'service_role'
            and match_status_before = 'MATCHED'
            and match_status_after = 'VOID'
            and entry_status_before = 'MATCHED'
            and entry_status_after = 'VOID'
            and ticket_counted_before
            and not ticket_counted_after) <> 2
     or exists (
       select 1
         from battle_private.matches
        where season_id = 'f8000000-0000-0000-0000-000000000001'
          and status = 'MATCHED'
     ) then
    raise exception 'stored recovery actor audit failed';
  end if;
end;
$$;

rollback;
