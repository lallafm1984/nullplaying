create extension if not exists pg_net with schema extensions;

create or replace function public.admin_companion_deliver_event()
returns trigger
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  webhook_secret text;
begin
  if new.event_type <> 'subscriber_created' then
    return new;
  end if;

  select decrypted_secret
    into webhook_secret
    from vault.decrypted_secrets
   where name = 'admin_companion_webhook_secret'
   order by created_at desc
   limit 1;

  if webhook_secret is null or webhook_secret = '' then
    raise warning 'admin_companion_webhook_secret is not configured';
    return new;
  end if;

  perform net.http_post(
    url := 'https://rlrmaynzdwulbuvymxfa.supabase.co/functions/v1/admin-push',
    headers := jsonb_build_object(
      'Content-Type', 'application/json',
      'x-admin-webhook-secret', webhook_secret
    ),
    body := jsonb_build_object(
      'type', tg_op,
      'table', tg_table_name,
      'schema', tg_table_schema,
      'record', to_jsonb(new),
      'old_record', null
    ),
    timeout_milliseconds := 5000
  );

  return new;
end;
$$;

revoke all on function public.admin_companion_deliver_event() from public, anon, authenticated;

drop trigger if exists admin_companion_push_webhook on public.admin_events;

create trigger admin_companion_push_webhook
after insert on public.admin_events
for each row
execute function public.admin_companion_deliver_event();

comment on function public.admin_companion_deliver_event() is
  'Asynchronously delivers new subscriber events to the admin-push Edge Function.';
