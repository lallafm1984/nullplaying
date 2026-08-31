import { createClient } from "jsr:@supabase/supabase-js@2";

type AdminEventRecord = {
  id: string;
  event_type: "subscriber_created" | "subscriber_deleted" | "push_test";
  subject_user_id: string | null;
  occurred_at: string;
};

type WebhookBody = {
  type?: string;
  table?: string;
  schema?: string;
  record?: AdminEventRecord;
};

type DeviceRow = {
  id: string;
  fcm_token: string;
};

const jsonHeaders = { "content-type": "application/json; charset=utf-8" };

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response(null, { status: 204 });
  }
  if (request.method !== "POST") {
    return json({ error: "method_not_allowed" }, 405);
  }

  try {
    const supabaseUrl = requiredEnv("SUPABASE_URL");
    const serviceRoleKey = requiredEnv("SUPABASE_SERVICE_ROLE_KEY");
    const service = createClient(supabaseUrl, serviceRoleKey, {
      auth: { persistSession: false, autoRefreshToken: false },
    });
    const updateDelivery = async (
      eventId: string,
      status: string,
      delivered: number,
      failed: number,
      error: string | null,
    ) => {
      const { error: updateError } = await service
        .from("admin_events")
        .update({
          delivery_status: status,
          delivered_device_count: delivered,
          failed_device_count: failed,
          delivery_error: error,
          processed_at: new Date().toISOString(),
        })
        .eq("id", eventId);
      if (updateError) throw updateError;
    };
    const body = await request.json() as WebhookBody & { action?: string };
    const webhookSecret = request.headers.get("x-admin-webhook-secret");
    const expectedWebhookSecret = requiredEnv("ADMIN_WEBHOOK_SECRET");

    let event: AdminEventRecord;
    let devicesQuery = service
      .from("admin_companion_devices")
      .select("id,fcm_token");

    if (webhookSecret && constantTimeEqual(webhookSecret, expectedWebhookSecret)) {
      if (
        body.type !== "INSERT" ||
        body.schema !== "public" ||
        body.table !== "admin_events" ||
        body.record?.event_type !== "subscriber_created"
      ) {
        return json({ ok: true, skipped: true });
      }
      event = body.record;
    } else {
      const token = bearerToken(request);
      const { data: userData, error: userError } = await service.auth.getUser(token);
      if (userError || !userData.user) {
        return json({ error: "unauthorized" }, 401);
      }

      const { data: admin, error: adminError } = await service
        .from("admin_companion_users")
        .select("user_id")
        .eq("user_id", userData.user.id)
        .eq("enabled", true)
        .maybeSingle();
      if (adminError || !admin) {
        return json({ error: "forbidden" }, 403);
      }
      if (body.action !== "test") {
        return json({ error: "unsupported_action" }, 400);
      }

      const { data: eventRow, error: eventError } = await service
        .from("admin_events")
        .insert({
          event_type: "push_test",
          subject_user_id: userData.user.id,
          delivery_status: "pending",
        })
        .select("id,event_type,subject_user_id,occurred_at")
        .single();
      if (eventError) throw eventError;
      event = eventRow as AdminEventRecord;
      devicesQuery = devicesQuery.eq("admin_user_id", userData.user.id);
    }

    const { data: devices, error: devicesError } = await devicesQuery;
    if (devicesError) throw devicesError;

    const deviceRows = (devices ?? []) as DeviceRow[];
    if (deviceRows.length === 0) {
      await updateDelivery(event.id, "skipped", 0, 0, "registered device not found");
      return json({ ok: true, delivered: 0, failed: 0 });
    }

    const accessToken = await firebaseAccessToken();
    const title = event.event_type === "push_test" ? "NULL PLAYING 관리자" : "새로운 가입자";
    const bodyText = event.event_type === "push_test"
      ? "푸시 알림이 정상적으로 연결되었습니다."
      : "NULL PLAYING에 새로운 가입자가 등록되었습니다.";

    const results = await Promise.all(deviceRows.map(async (device) => {
      const result = await sendFirebaseMessage(accessToken, device.fcm_token, {
        title,
        body: bodyText,
        eventId: event.id,
        eventType: event.event_type,
      });
      return { device, ...result };
    }));

    const delivered = results.filter((result) => result.ok).length;
    const failures = results.filter((result) => !result.ok);
    const invalidIds = failures.filter((result) => result.invalidToken).map((result) => result.device.id);
    if (invalidIds.length > 0) {
      await service.from("admin_companion_devices").delete().in("id", invalidIds);
    }

    const status = delivered === results.length ? "delivered" : delivered > 0 ? "partial" : "failed";
    const error = failures.length > 0
      ? failures.map((failure) => failure.error).filter(Boolean).slice(0, 3).join(" | ").slice(0, 500)
      : null;
    await updateDelivery(event.id, status, delivered, failures.length, error);

    return json({ ok: failures.length === 0, delivered, failed: failures.length });
  } catch (error) {
    console.error(error);
    return json({ error: "push_delivery_failed" }, 500);
  }
});

function json(value: unknown, status = 200) {
  return new Response(JSON.stringify(value), { status, headers: jsonHeaders });
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name)?.trim();
  if (!value) throw new Error(`Missing environment variable: ${name}`);
  return value;
}

function bearerToken(request: Request): string {
  const value = request.headers.get("authorization") ?? "";
  if (!value.startsWith("Bearer ") || value.length <= 7) throw new Error("Missing bearer token");
  return value.slice(7);
}

function constantTimeEqual(left: string, right: string): boolean {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}

async function firebaseAccessToken(): Promise<string> {
  const now = Math.floor(Date.now() / 1000);
  const encodedHeader = base64Url(JSON.stringify({ alg: "RS256", typ: "JWT" }));
  const encodedPayload = base64Url(JSON.stringify({
    iss: requiredEnv("FIREBASE_CLIENT_EMAIL"),
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  }));
  const unsigned = `${encodedHeader}.${encodedPayload}`;
  const privateKey = requiredEnv("FIREBASE_PRIVATE_KEY").replaceAll("\\n", "\n");
  const keyData = pemToBytes(privateKey);
  const cryptoKey = await crypto.subtle.importKey(
    "pkcs8",
    keyData,
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signature = await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5",
    cryptoKey,
    new TextEncoder().encode(unsigned),
  );
  const assertion = `${unsigned}.${base64UrlBytes(new Uint8Array(signature))}`;

  const tokenResponse = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const tokenJson = await tokenResponse.json() as { access_token?: string; error_description?: string };
  if (!tokenResponse.ok || !tokenJson.access_token) {
    throw new Error(tokenJson.error_description ?? "Firebase OAuth failed");
  }
  return tokenJson.access_token;
}

async function sendFirebaseMessage(
  accessToken: string,
  fcmToken: string,
  message: { title: string; body: string; eventId: string; eventType: string },
): Promise<{ ok: boolean; invalidToken: boolean; error: string | null }> {
  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(requiredEnv("FIREBASE_PROJECT_ID"))}/messages:send`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${accessToken}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token: fcmToken,
          notification: { title: message.title, body: message.body },
          data: {
            event_id: message.eventId,
            event_type: message.eventType,
            destination: "dashboard",
          },
          android: {
            priority: "high",
            notification: { channel_id: "new_subscribers", sound: "default" },
          },
        },
      }),
    },
  );

  if (response.ok) return { ok: true, invalidToken: false, error: null };
  const responseBody = await response.text();
  const invalidToken = response.status === 404 || responseBody.includes("UNREGISTERED");
  return {
    ok: false,
    invalidToken,
    error: `FCM ${response.status}: ${responseBody.slice(0, 240)}`,
  };
}

function base64Url(value: string): string {
  return base64UrlBytes(new TextEncoder().encode(value));
}

function base64UrlBytes(value: Uint8Array): string {
  let binary = "";
  for (const byte of value) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}

function pemToBytes(pem: string): ArrayBuffer {
  const base64 = pem
    .replace("-----BEGIN PRIVATE KEY-----", "")
    .replace("-----END PRIVATE KEY-----", "")
    .replace(/\s/g, "");
  const binary = atob(base64);
  const buffer = new ArrayBuffer(binary.length);
  const bytes = new Uint8Array(buffer);
  for (let index = 0; index < binary.length; index += 1) {
    bytes[index] = binary.charCodeAt(index);
  }
  return buffer;
}
