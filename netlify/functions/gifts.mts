import type { Context, Config } from "@netlify/functions";
import { getDeployStore, getStore } from "@netlify/blobs";

const STOCK: Record<number, number> = {
  1: 2, 2: 3, 3: 2, 4: 2, 5: 2, 6: 2,
  7: 1, 8: 1, 9: 1, 10: 1, 11: 1, 12: 1,
  13: 1, 14: 1, 15: 1, 16: 2, 17: 2,
};

function storeFor(context: Context) {
  const name = "siberia-gift-reservations";
  return context.deploy?.context === "production"
    ? getStore(name, { consistency: "strong" })
    : getDeployStore(name, { consistency: "strong" });
}

async function countReservations(store: ReturnType<typeof getStore>, giftId: number) {
  const result = await store.list({ prefix: `gift/${giftId}/` });
  return result.blobs.length;
}

async function state(store: ReturnType<typeof getStore>) {
  const entries = await Promise.all(Object.entries(STOCK).map(async ([idText, stock]) => {
    const id = Number(idText);
    const attempts = await countReservations(store, id);
    const reserved = Math.min(stock, attempts);
    return [id, { stock, reserved, available: Math.max(0, stock - reserved) }] as const;
  }));
  return Object.fromEntries(entries);
}

export default async (req: Request, context: Context) => {
  const store = storeFor(context);

  if (req.method === "GET") {
    return Response.json({ gifts: await state(store) }, {
      headers: { "Cache-Control": "no-store" },
    });
  }

  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  let body: { giftId?: number; token?: string };
  try {
    body = await req.json();
  } catch {
    return Response.json({ ok: false, error: "invalid_json" }, { status: 400 });
  }

  const giftId = Number(body.giftId);
  const stock = STOCK[giftId];
  if (!Number.isInteger(giftId) || !stock) {
    return Response.json({ ok: false, error: "invalid_gift" }, { status: 400 });
  }

  const clientToken = String(body.token || "").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 80);
  if (clientToken.length < 8) {
    return Response.json({ ok: false, error: "invalid_token" }, { status: 400 });
  }

  const existing = await store.get(`token/${clientToken}`, { type: "json" }) as null | { giftId: number; key: string };
  if (existing) {
    const current = await state(store);
    return Response.json({ ok: true, alreadyReserved: true, giftId: existing.giftId, gifts: current }, {
      headers: { "Cache-Control": "no-store" },
    });
  }

  const now = Date.now();
  const attemptKey = `gift/${giftId}/${String(now).padStart(13, "0")}-${crypto.randomUUID()}`;
  await store.setJSON(attemptKey, { giftId, token: clientToken, createdAt: new Date(now).toISOString() });

  await new Promise((resolve) => setTimeout(resolve, 250));

  const attempts = await store.list({ prefix: `gift/${giftId}/` });
  const ordered = attempts.blobs.map((blob) => blob.key).sort();
  const accepted = ordered.slice(0, stock).includes(attemptKey);

  if (!accepted) {
    return Response.json({ ok: false, error: "sold_out", gifts: await state(store) }, {
      status: 409,
      headers: { "Cache-Control": "no-store" },
    });
  }

  await store.setJSON(`token/${clientToken}`, { giftId, key: attemptKey, createdAt: new Date(now).toISOString() });

  return Response.json({ ok: true, giftId, gifts: await state(store) }, {
    headers: { "Cache-Control": "no-store" },
  });
};

export const config: Config = {
  path: "/api/gifts",
};
