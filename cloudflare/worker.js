const REPO = "kinderDmitry/Scaner-proxy";
const TARGET = "incoming/upload.json";
const MAX_BYTES = 2 * 1024 * 1024;
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET,POST,OPTIONS",
  "Access-Control-Allow-Headers": "Authorization,Content-Type",
  "Access-Control-Max-Age": "86400"
};

function json(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { ...cors, "Content-Type": "application/json; charset=utf-8" } });
}

function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let result = 0;
  for (let i = 0; i < a.length; i++) result |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return result === 0;
}

async function githubRequest(path, token, init = {}) {
  return fetch("https://api.github.com/repos/" + REPO + "/contents/" + path, {
    ...init,
    headers: {
      "Accept": "application/vnd.github+json",
      "Authorization": "Bearer " + token,
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "ScanerProxyX-Upload",
      ...(init.headers || {})
    }
  });
}

export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
    const url = new URL(request.url);
    if (url.pathname === "/health" && request.method === "GET") return json({ ok: true, service: "Scaner Proxy X upload" });
    if (url.pathname === "/subscription" && request.method === "GET") {
      const raw = await fetch("https://raw.githubusercontent.com/" + REPO + "/main/website/sub/all.txt", { cf: { cacheTtl: 0 } });
      if (!raw.ok) return json({ error: "Каталог пока не опубликован" }, 503);
      const lines = (await raw.text()).split(/\\r?\\n/);
      const headers = lines.filter(line => !line.trim() || line.trim().startsWith("#"));
      const nodes = lines.filter(line => /^(vless|socks5?):\\/\\//i.test(line.trim()));
      const requested = url.searchParams.get("limit") || "all";
      const limit = requested === "all" ? nodes.length : Math.max(1, Math.min(5000, Number.parseInt(requested, 10) || 100));
      const body = [...headers, ...nodes.slice(0, limit)].join("\\n").trimEnd() + "\\n";
      return new Response(body, { status: 200, headers: { ...cors, "Content-Type": "text/plain; charset=utf-8", "Cache-Control": "no-store", "Profile-Update-Interval": "6" } });
    }
    if (url.pathname !== "/upload" || request.method !== "POST") return json({ error: "Not found" }, 404);
    const supplied = (request.headers.get("Authorization") || "").replace(/^Bearer\\s+/i, "");
    if (!env.UPLOAD_TOKEN || !safeEqual(supplied, env.UPLOAD_TOKEN)) return json({ error: "Unauthorized" }, 401);
    const length = Number(request.headers.get("Content-Length") || 0);
    if (length > MAX_BYTES) return json({ error: "JSON exceeds 2 MB limit" }, 413);
    const raw = await request.text();
    if (new TextEncoder().encode(raw).length > MAX_BYTES) return json({ error: "JSON exceeds 2 MB limit" }, 413);
    let payload;
    try { payload = JSON.parse(raw); } catch { return json({ error: "Invalid JSON" }, 400); }
    const configs = Array.isArray(payload) ? payload : payload && payload.configs;
    if (!Array.isArray(configs) || configs.length < 1 || configs.length > 5000) return json({ error: "Expected configs array with 1..5000 entries" }, 400);
    const normalized = [];
    for (const item of configs) {
      const value = typeof item === "string" ? item : item && (item.uri || item.rawConfig || item.config);
      if (typeof value !== "string" || !/^(vless|socks5?):\\/\\//i.test(value.trim())) continue;
      normalized.push(value.trim());
    }
    if (!normalized.length) return json({ error: "No supported VLESS/SOCKS configurations found" }, 400);
    if (!env.GITHUB_TOKEN) return json({ error: "Server is not configured: GITHUB_TOKEN missing" }, 503);
    const document = JSON.stringify({ uploadedAt: new Date().toISOString(), configs: [...new Set(normalized)] }, null, 2) + "\\n";
    const existing = await githubRequest(TARGET + "?ref=main", env.GITHUB_TOKEN);
    let sha;
    if (existing.ok) sha = (await existing.json()).sha;
    else if (existing.status !== 404) return json({ error: "GitHub read failed", status: existing.status }, 502);
    const saved = await githubRequest(TARGET, env.GITHUB_TOKEN, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        message: "catalog: accept uploaded proxy JSON",
        content: btoa(String.fromCharCode(...new TextEncoder().encode(document))),
        branch: "main",
        ...(sha ? { sha } : {})
      })
    });
    if (!saved.ok) return json({ error: "GitHub save failed", status: saved.status, detail: (await saved.text()).slice(0, 500) }, 502);
    return json({ accepted: normalized.length, stored: TARGET, message: "JSON сохранён. Проверка начнётся в ближайшем цикле." }, 202);
  }
};
