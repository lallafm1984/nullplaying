# Shared-player QA preview E2E

`run_shared_player_preview_e2e.py` is a QA-only black-box check for the staged shared-player RPCs.
It creates two separate anonymous Auth sessions, publishes one fixed Lv.10 character from each,
requests the first character's daily public roster, waits 5.5 seconds for the account call guard,
and confirms that the second response reuses the same frozen roster.

The script accepts configuration only through these environment variables:

- `ALARMQUEST_QA_SUPABASE_URL`: an official `https://<project-ref>.supabase.co` QA origin.
- `ALARMQUEST_QA_SUPABASE_PUBLISHABLE_KEY`: the QA publishable key. The legacy
  `ALARMQUEST_QA_SUPABASE_ANON_KEY` name is also accepted when only that variable is set.

It rejects the production project ref, secret/service-role keys, redirects, proxies, URL paths,
queries, and every endpoint outside the three fixed Auth/RPC paths. It never prints the URL,
project ref, key, access tokens, or anonymous user IDs. Do not point it at a shared or production
project. The QA project must have anonymous Auth enabled and the reviewed migration applied.

Run the network-free checks first:

```bash
python3 tools/analysis/run_shared_player_preview_e2e.py --self-test
python3 tools/analysis/test_run_shared_player_preview_e2e.py
```

With no QA environment, the online command prints `SKIP` and exits successfully. CI jobs that
require the QA environment can add `--require-env` to receive a clear nonzero failure instead.

```bash
ALARMQUEST_QA_SUPABASE_URL="https://<qa-project-ref>.supabase.co" \
ALARMQUEST_QA_SUPABASE_PUBLISHABLE_KEY="<qa-publishable-key>" \
python3 tools/analysis/run_shared_player_preview_e2e.py --require-env
```

The online check validates at least one `PUBLIC_ROSTER` candidate, account self-exclusion, the
Lv.9–11 band, the exact minimal snapshot/stat fields, absence of equipment, skills, mastery,
economy, inventory, and relationship data, plus same-day roster immutability. It performs no
migration or administrative cleanup. Anonymous QA users remain in the QA Auth project; published
snapshots expire under the migration's 72-hour policy and the roster expires at the next UTC day.
