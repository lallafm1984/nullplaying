#!/bin/sh
set -eu

PROJECT_REF="rlrmaynzdwulbuvymxfa"
ENV_FILE=".env.supabase.local"

if [ -f "$ENV_FILE" ]; then
  set -a
  # This file is gitignored and must contain only trusted shell-style assignments.
  . "./$ENV_FILE"
  set +a
fi

ACTION="${1:-}"
case "$ACTION" in
  link)
    if [ -n "${SUPABASE_DB_PASSWORD:-}" ]; then
      npx supabase --agent no link --project-ref "$PROJECT_REF" --password "$SUPABASE_DB_PASSWORD"
    else
      npx supabase --agent no link --project-ref "$PROJECT_REF"
    fi
    ;;
  adopt-existing)
    echo "Marking the SQL-Editor-applied first migration as applied."
    npx supabase --agent no migration repair 202608230001 --status applied --linked
    ;;
  dry-run)
    npx supabase --agent no db push --linked --dry-run
    ;;
  push)
    npx supabase --agent no db push --linked
    ;;
  status)
    npx supabase --agent no migration list --linked
    ;;
  *)
    echo "Usage: $0 {link|adopt-existing|dry-run|push|status}" >&2
    exit 2
    ;;
esac
