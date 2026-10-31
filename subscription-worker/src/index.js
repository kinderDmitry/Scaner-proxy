const MAX_CONFIGS = 1500;
const MAX_BODY_BYTES = 2_000_000;
const ALLOWED_SCHEMES = /^(vless|vmess|trojan|ss|socks|socks4|socks5|http|https|hysteria2|hy2|tuic):\/\//i;

function response(body, status = 200, headers = {}) {
  return new Response(body, { status, headers: {
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "GET, PUT, OPTIONS",
    "Access-Control-Allow-Headers": "Authorization, Content-Type",
    "X-Content-Type-Options": "nosniff",
    ...headers
  }});
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return response("", 204);
    if (url.pathname === "/health" && request.method === "GET") return response("ok", 200, {"Content-Type":"text/plain"});
    const match = url.pathname.match(/^\/api\/subscriptions\/([a-f0-9-]{36})$/i);
    if (match && request.method === "PUT") {
      if (!env.PUBLISH_TOKEN || request.headers.get("Authorization") !== `Bearer ${env.PUBLISH_TOKEN}`) {
        return response("unauthorized", 401, {"Content-Type":"text/plain"});
      }
      const length = Number(request.headers.get("Content-Length") || "0");
      if (length > MAX_BODY_BYTES) return response("payload too large", 413);
      const raw = await request.text();
      if (raw.length > MAX_BODY_BYTES) return response("payload too large", 413);
      let payload;
      try { payload = JSON.parse(raw); } catch { return response("invalid json", 400); }
      if (!Array.isArray(payload.configs)) return response("configs must be an array", 400);
      const configs = [...new Set(payload.configs
        .filter(x => typeof x === "string")
        .map(x => x.trim())
        .filter(x => x.length <= 8192 && ALLOWED_SCHEMES.test(x)))].slice(0, MAX_CONFIGS);
      if (!configs.length) return response("no valid configs", 422);
      await env.SUBSCRIPTIONS.put(match[1], JSON.stringify({
        configs,
        updatedAt: new Date().toISOString(),
        count: configs.length
      }), { expirationTtl: 60 * 60 * 24 * 30 });
      return response(JSON.stringify({
        id: match[1],
        count: configs.length,
        updatedAt: new Date().toISOString(),
        url: `${url.origin}/s/${match[1]}`
      }), 200, {"Content-Type":"application/json; charset=utf-8", "Cache-Control":"no-store"});
    }
    const sub = url.pathname.match(/^\/s\/([a-f0-9-]{36})$/i);
    if (sub && request.method === "GET") {
      const record = await env.SUBSCRIPTIONS.get(sub[1]);
      if (!record) return response("subscription not found or expired", 404, {"Content-Type":"text/plain; charset=utf-8"});
      let data;
      try { data = JSON.parse(record); } catch { return response("subscription data is invalid", 500); }
      const body = data.configs.join("\n") + "\n";
      return response(body, 200, {
        "Content-Type":"text/plain; charset=utf-8",
        "Cache-Control":"no-store, no-cache, must-revalidate",
        "profile-title":"HAPP Proxy Finder",
        "profile-update-interval":"6"
      });
    }
    return response("not found", 404, {"Content-Type":"text/plain"});
  }
};
