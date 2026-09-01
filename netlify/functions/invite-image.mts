import type { Config } from "@netlify/functions";

const BASE = "https://raw.githubusercontent.com/ddotroo-cloud/siberia-cumple/main/assets";
const PARTS = ["invite-01.txt", "invite-02.txt", "invite-03.txt", "invite-04.txt"];

export default async () => {
  try {
    const responses = await Promise.all(
      PARTS.map((name) => fetch(`${BASE}/${name}`, { headers: { "User-Agent": "siberia-cumple" } }))
    );
    if (responses.some((r) => !r.ok)) {
      return new Response("No se pudo cargar la invitación", { status: 502 });
    }
    const parts = await Promise.all(responses.map((r) => r.text()));
    const b64 = parts.join("").replace(/\s+/g, "");
    const binary = atob(b64);
    const bytes = Uint8Array.from(binary, (c) => c.charCodeAt(0));
    return new Response(bytes, {
      headers: {
        "content-type": "image/webp",
        "cache-control": "public, max-age=3600, stale-while-revalidate=86400",
      },
    });
  } catch {
    return new Response("No se pudo cargar la invitación", { status: 500 });
  }
};

export const config: Config = { path: "/invite-image" };
