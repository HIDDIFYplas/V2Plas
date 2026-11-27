const COOKIE_NAME = "__Host-v2plas_admin";
const SESSION_SECONDS = 60 * 60 * 8;
const MAX_BODY_BYTES = 256 * 1024;
const MAX_SUBSCRIPTION_BYTES = 1024 * 1024;
const DEFAULT_SETTINGS = {
  free_enabled: true,
  volume_limit_mb: 0,
  time_limit_hours: 0,
  ads_enabled: true,
  config_refresh_minutes: 360,
  subscription_refresh_minutes: 360
};

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers }
  });
}
function text(message, status = 200, headers = {}) {
  return new Response(message, { status, headers: { "cache-control": "no-store", ...headers } });
}
function corsHeaders(origin, env) {
  const allowed = (env.APP_ORIGINS || "").split(",").map(x => x.trim()).filter(Boolean);
  const headers = { "access-control-allow-methods": "GET, OPTIONS", "access-control-allow-headers": "Content-Type" };
  if (origin && allowed.includes(origin)) {
    headers["access-control-allow-origin"] = origin;
    headers["vary"] = "Origin";
  }
  return headers;
}
function isAllowedOrigin(request, env) {
  const origin = request.headers.get("Origin");
  if (!origin) return true;
  // Same-origin admin UI is allowed by default; separately hosted UI origins
  // must be explicitly listed in ADMIN_ORIGINS.
  if (origin === new URL(request.url).origin) return true;
  const allowed = (env.ADMIN_ORIGINS || "").split(",").map(x => x.trim()).filter(Boolean);
  return allowed.includes(origin);
}
function safeEqual(a, b) {
  const aa = new TextEncoder().encode(a), bb = new TextEncoder().encode(b);
  if (aa.length !== bb.length) return false;
  let out = 0;
  for (let i = 0; i < aa.length; i++) out |= aa[i] ^ bb[i];
  return out === 0;
}
function b64url(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/g, "");
}
function fromB64url(s) {
  const b = s.replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(b + "=".repeat((4 - b.length % 4) % 4)), c => c.charCodeAt(0));
}
async function hmac(secret, value) {
  const key = await crypto.subtle.importKey("raw", new TextEncoder().encode(secret), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
  return new Uint8Array(await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(value)));
}
async function makeSession(env) {
  const payload = b64url(new TextEncoder().encode(JSON.stringify({ exp: Math.floor(Date.now()/1000) + SESSION_SECONDS, v: 1 })));
  return payload + "." + b64url(await hmac(env.SESSION_SECRET, payload));
}
async function validSession(request, env) {
  const cookie = request.headers.get("Cookie") || "";
  const part = cookie.split(";").map(x => x.trim()).find(x => x.startsWith(COOKIE_NAME + "="));
  if (!part) return false;
  const token = part.slice(COOKIE_NAME.length + 1).split(".");
  if (token.length !== 2) return false;
  try {
    const expected = b64url(await hmac(env.SESSION_SECRET, token[0]));
    if (!safeEqual(expected, token[1])) return false;
    const payload = JSON.parse(new TextDecoder().decode(fromB64url(token[0])));
    return payload.v === 1 && payload.exp > Math.floor(Date.now()/1000);
  } catch { return false; }
}
function cookie(value, maxAge = SESSION_SECONDS) {
  return `${COOKIE_NAME}=${value}; Path=/; HttpOnly; Secure; SameSite=Strict; Max-Age=${maxAge}`;
}
async function requireAdmin(request, env) {
  if (!await validSession(request, env)) return json({ error: "unauthorized" }, 401);
  return null;
}
async function readJson(request, max = MAX_BODY_BYTES) {
  const length = Number(request.headers.get("Content-Length") || 0);
  if (length > max) throw new Error("body_too_large");
  const raw = await request.text();
  if (new TextEncoder().encode(raw).length > max) throw new Error("body_too_large");
  return JSON.parse(raw || "{}");
}
function validId(value) { return Number.isInteger(Number(value)) && Number(value) > 0; }
function cleanString(value, max = 500) {
  if (typeof value !== "string") return "";
  return value.trim().slice(0, max);
}
function boolInt(value, fallback = 1) {
  if (value === true || value === 1 || value === "1") return 1;
  if (value === false || value === 0 || value === "0") return 0;
  return fallback;
}
async function all(db, sql, ...args) {
  const r = await db.prepare(sql).bind(...args).all();
  return r.results || [];
}
async function settingMap(db) {
  const rows = await all(db, "SELECT setting_key, setting_value FROM settings");
  const result = { ...DEFAULT_SETTINGS };
  for (const row of rows) {
    try { result[row.setting_key] = JSON.parse(row.setting_value); }
    catch { result[row.setting_key] = row.setting_value; }
  }
  return result;
}
async function setSetting(db, key, value) {
  await db.prepare(`INSERT INTO settings(setting_key, setting_value, updated_at)
    VALUES(?, ?, CURRENT_TIMESTAMP)
    ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value, updated_at=CURRENT_TIMESTAMP`)
    .bind(key, JSON.stringify(value)).run();
}

function utf8Base64(value) {
  const bytes = new TextEncoder().encode(value);
  let binary = "";
  for (let offset = 0; offset < bytes.length; offset += 0x8000) {
    binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
  }
  return btoa(binary);
}

async function sha256Hex(value) {
  const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return [...new Uint8Array(digest)].map(byte => byte.toString(16).padStart(2, "0")).join("");
}

async function githubApi(env, path, method = "GET", body = undefined) {
  const response = await fetch(`https://api.github.com${path}`, {
    method,
    headers: {
      "accept": "application/vnd.github+json",
      "authorization": `Bearer ${env.PUBLIC_FEED_GITHUB_TOKEN}`,
      "content-type": "application/json",
      "user-agent": "V2Plas-Public-Feed-Publisher",
      "x-github-api-version": "2022-11-28"
    },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(25000)
  });
  const raw = await response.text();
  let data = {};
  try { data = raw ? JSON.parse(raw) : {}; } catch {}
  return { response, data };
}

async function buildPublicFeed(env) {
  const settings = await settingMap(env.DB);
  const freeEnabled = !(settings.free_enabled === false || settings.free_enabled === 0 || settings.free_enabled === "false");
  const adsEnabled = settings.ads_enabled !== false && settings.ads_enabled !== 0 && settings.ads_enabled !== "false";
  const configs = freeEnabled
    ? await all(env.DB, `SELECT id, name, protocol, config_data, source_type
        FROM configs WHERE enabled=1 ORDER BY sort_order ASC, id ASC`)
    : [];
  const ads = freeEnabled && adsEnabled
    ? await all(env.DB, `SELECT id, title, body, image_url, target_url, placement, priority
        FROM ads WHERE enabled=1 AND (start_at IS NULL OR start_at<=CURRENT_TIMESTAMP)
        AND (end_at IS NULL OR end_at>=CURRENT_TIMESTAMP) ORDER BY priority DESC, id DESC`)
    : [];
  const core = {
    // Bump on feed contract changes so an old published manifest is replaced once.
    feed_format: 2,
    enabled: freeEnabled,
    configs,
    ads,
    settings: {
      free_enabled: freeEnabled,
      volume_limit_mb: Math.max(0, Number(settings.volume_limit_mb || 0)),
      time_limit_hours: Math.max(0, Number(settings.time_limit_hours || 0)),
      ads_enabled: adsEnabled,
      // The Android app enforces a fixed six-hour refresh policy.
      config_refresh_minutes: 360
    }
  };
  const version = await sha256Hex(JSON.stringify(core));
  const updatedAt = new Date().toISOString();
  const payload = { ...core, version, updated_at: updatedAt };
  const payloadJson = JSON.stringify(payload);
  // Keep the Worker limit aligned with Android's 2 MiB bounded reader.
  const payloadBytes = new TextEncoder().encode(payloadJson).byteLength;
  const payloadSha256 = await sha256Hex(payloadJson);
  // Keep Android memory use predictable on low-end phones; GitHub's base64
  // request is ~33% larger, and the static asset stays far below Pages' cap.
  if (payloadBytes > 2 * 1024 * 1024) throw new Error("public_feed_exceeds_2_mib");
  return {
    version,
    manifest: { format: 2, version, updated_at: updatedAt, payload: "configs.json", payload_sha256: payloadSha256, payload_bytes: payloadBytes },
    payload,
    payloadJson
  };
}

async function publishPublicFeed(env, { force = false } = {}) {
  const token = env.PUBLIC_FEED_GITHUB_TOKEN;
  const repo = String(env.PUBLIC_FEED_GITHUB_REPO || "").trim();
  const branch = String(env.PUBLIC_FEED_GITHUB_BRANCH || "main").trim();
  if (!token || !/^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/.test(repo)) {
    return { ok: false, configured: false, error: "public_feed_github_not_configured" };
  }
  if (!/^[A-Za-z0-9._/-]{1,100}$/.test(branch) || branch.includes("..")) {
    return { ok: false, configured: true, error: "public_feed_branch_invalid" };
  }

  const repoPath = `/repos/${repo}`;
  for (let attempt = 0; attempt < 3; attempt++) {
    const feed = await buildPublicFeed(env);
    const settingsNow = await settingMap(env.DB);
    if (!force && settingsNow.public_feed_version === feed.version) {
      return { ok: true, configured: true, unchanged: true, version: feed.version };
    }

    const refResult = await githubApi(env, `${repoPath}/git/ref/heads/${encodeURIComponent(branch)}`);
    if (!refResult.response.ok || !refResult.data?.object?.sha) {
      throw new Error(`github_ref_http_${refResult.response.status}`);
    }
    const parentSha = refResult.data.object.sha;
    const parentResult = await githubApi(env, `${repoPath}/git/commits/${parentSha}`);
    if (!parentResult.response.ok || !parentResult.data?.tree?.sha) {
      throw new Error(`github_commit_http_${parentResult.response.status}`);
    }

    const manifestBlob = await githubApi(env, `${repoPath}/git/blobs`, "POST", {
      content: utf8Base64(JSON.stringify(feed.manifest)), encoding: "base64"
    });
    if (!manifestBlob.response.ok || !manifestBlob.data?.sha) {
      throw new Error(`github_manifest_blob_http_${manifestBlob.response.status}`);
    }
    const payloadBlob = await githubApi(env, `${repoPath}/git/blobs`, "POST", {
      content: utf8Base64(feed.payloadJson), encoding: "base64"
    });
    if (!payloadBlob.response.ok || !payloadBlob.data?.sha) {
      throw new Error(`github_payload_blob_http_${payloadBlob.response.status}`);
    }

    const treeResult = await githubApi(env, `${repoPath}/git/trees`, "POST", {
      base_tree: parentResult.data.tree.sha,
      tree: [
        { path: "public/manifest.json", mode: "100644", type: "blob", sha: manifestBlob.data.sha },
        { path: "public/configs.json", mode: "100644", type: "blob", sha: payloadBlob.data.sha }
      ]
    });
    if (!treeResult.response.ok || !treeResult.data?.sha) {
      throw new Error(`github_tree_http_${treeResult.response.status}`);
    }
    const commitResult = await githubApi(env, `${repoPath}/git/commits`, "POST", {
      message: `Publish V2Plas feed ${feed.version.slice(0, 12)}`,
      tree: treeResult.data.sha,
      parents: [parentSha]
    });
    if (!commitResult.response.ok || !commitResult.data?.sha) {
      throw new Error(`github_new_commit_http_${commitResult.response.status}`);
    }
    const updateResult = await githubApi(env, `${repoPath}/git/refs/heads/${encodeURIComponent(branch)}`, "PATCH", {
      sha: commitResult.data.sha,
      force: false
    });
    if (!updateResult.response.ok) {
      // A concurrent panel edit may have advanced the branch. Re-read D1 and
      // rebuild the complete snapshot against the new branch head.
      if ([409, 422].includes(updateResult.response.status) && attempt < 2) continue;
      throw new Error(`github_update_ref_http_${updateResult.response.status}`);
    }

    await setSetting(env.DB, "public_feed_version", feed.version);
    // If the data changed while publishing, perform another pass so the latest
    // committed snapshot wins rather than leaving an older in-flight edit last.
    const latest = await buildPublicFeed(env);
    if (latest.version === feed.version) {
      return { ok: true, configured: true, unchanged: false, version: feed.version };
    }
    force = true;
  }
  throw new Error("public_feed_changed_too_often_retry_later");
}

async function safePublishPublicFeed(env, options = {}) {
  // A short D1 lease serializes simultaneous admin clicks, scheduled publishes,
  // and bulk imports. This prevents duplicate GitHub commits/Pages deployments.
  const token = crypto.randomUUID();
  const now = Date.now();
  const leaseUntil = now + 10 * 60 * 1000;
  let locked = false;
  try {
    const lock = await env.DB.prepare(`INSERT INTO settings(setting_key, setting_value, updated_at)
      VALUES('public_feed_publish_lock', ?, CURRENT_TIMESTAMP)
      ON CONFLICT(setting_key) DO UPDATE SET setting_value=excluded.setting_value, updated_at=CURRENT_TIMESTAMP
      WHERE COALESCE(CAST(json_extract(
        CASE WHEN json_valid(settings.setting_value) THEN settings.setting_value ELSE '{}' END,
        '$.lease_until') AS INTEGER), 0) < ?`)
      .bind(JSON.stringify({ token, lease_until: leaseUntil }), now).run();
    locked = Number(lock?.meta?.changes || 0) > 0;
    if (!locked) return { ok: true, configured: true, skipped: true, reason: "publish_in_progress" };

    return await publishPublicFeed(env, options);
  } catch (error) {
    console.error("V2Plas public feed publish failed", String(error?.message || error));
    return { ok: false, configured: true, error: String(error?.message || "publish_failed").slice(0, 120) };
  } finally {
    if (locked) {
      try {
        await env.DB.prepare(`DELETE FROM settings WHERE setting_key='public_feed_publish_lock'
          AND json_extract(setting_value, '$.token')=?`).bind(token).run();
      } catch (error) {
        console.error("V2Plas public feed lock cleanup failed", String(error?.message || error));
      }
    }
  }
}
function decodeBase64Utf8(input) {
  const normalized = input.replace(/-/g, "+").replace(/_/g, "/").replace(/\s/g, "");
  const binary = atob(normalized + "=".repeat((4 - normalized.length % 4) % 4));
  const bytes = Uint8Array.from(binary, c => c.charCodeAt(0));
  return new TextDecoder("utf-8", { fatal: true }).decode(bytes);
}
function parseSubscriptionBody(body) {
  const trimmed = body.trim();
  if (!trimmed) return [];
  let decoded = trimmed;
  const hasUriLine = /[a-z][a-z0-9+.-]*:\/\//i.test(trimmed);
  if (!hasUriLine && !trimmed.startsWith("{") && !trimmed.startsWith("[")) {
    try { decoded = decodeBase64Utf8(trimmed); } catch {}
  }
  // A complete Xray JSON document must remain one profile; splitting its
  // outbounds would lose routing, DNS and other root-level settings.
  if (decoded.startsWith("{") || decoded.startsWith("[")) {
    try {
      const parsed = JSON.parse(decoded);
      if (!Array.isArray(parsed) && parsed && typeof parsed === "object" && Array.isArray(parsed.outbounds)) {
        return [{ name: cleanString(parsed.name || "Xray JSON", 120), protocol: "json", config_data: JSON.stringify(parsed) }];
      }
      const items = Array.isArray(parsed) ? parsed : [parsed];
      return items.map((item, index) => {
        if (typeof item === "string") {
          const scheme = item.match(/^([a-z][a-z0-9+.-]*):\/\//i)?.[1] || "unknown";
          return { name: `Imported ${index + 1}`, protocol: scheme.toLowerCase(), config_data: item };
        }
        if (!item || typeof item !== "object") return null;
        const raw = JSON.stringify(item);
        const protocol = cleanString(item.protocol || item.type || item.outboundType || "json", 40).toLowerCase();
        const name = cleanString(item.name || item.tag || `Imported ${index + 1}`, 120);
        return { name, protocol, config_data: raw };
      }).filter(x => x && x.config_data && x.config_data.length <= 128 * 1024);
    } catch {
      // Fall through to URI-line parsing.
    }
  }
  return decoded.split(/\r?\n/).map((line, index) => {
    const value = line.trim();
    const match = value.match(/^([a-z][a-z0-9+.-]*):\/\//i);
    if (!match || value.length > 128 * 1024) return null;
    return { name: `Imported ${index + 1}`, protocol: match[1].toLowerCase(), config_data: value };
  }).filter(Boolean);
}
function validateSubscriptionUrl(raw) {
  let u;
  try { u = new URL(raw); } catch { throw new Error("invalid_subscription_url"); }
  const host = u.hostname.toLowerCase();
  if (u.protocol !== "https:" || u.username || u.password || (u.port && u.port !== "443")) throw new Error("subscription_url_must_be_https");
  if (!host || host === "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
      host.endsWith(".internal") || host.endsWith(".test") || host.endsWith(".invalid") ||
      host.endsWith(".example") || host.endsWith(".home.arpa") || host.endsWith(".lan") ||
      /^\d{1,3}(\.\d{1,3}){3}$/.test(host) || /^[0-9.]+$/.test(host) ||
      host.includes(":") || host === "0.0.0.0") throw new Error("subscription_host_not_allowed");
  return u;
}
async function fetchSubscription(urlString) {
  let url = validateSubscriptionUrl(urlString);
  for (let hop = 0; hop <= 3; hop++) {
    const response = await fetch(url.toString(), { redirect: "manual", headers: { "accept": "text/plain, application/json, */*" }, signal: AbortSignal.timeout(25000) });
    if ([301,302,303,307,308].includes(response.status)) {
      if (hop === 3) throw new Error("too_many_redirects");
      const loc = response.headers.get("Location");
      if (!loc) throw new Error("redirect_without_location");
      url = validateSubscriptionUrl(new URL(loc, url).toString());
      continue;
    }
    if (!response.ok) throw new Error(`subscription_http_${response.status}`);
    const len = Number(response.headers.get("Content-Length") || 0);
    if (len > MAX_SUBSCRIPTION_BYTES) throw new Error("subscription_too_large");
    const reader = response.body.getReader();
    const chunks = []; let total = 0;
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > MAX_SUBSCRIPTION_BYTES) { await reader.cancel(); throw new Error("subscription_too_large"); }
      chunks.push(value);
    }
    const merged = new Uint8Array(total); let offset = 0;
    for (const chunk of chunks) { merged.set(chunk, offset); offset += chunk.byteLength; }
    return new TextDecoder("utf-8", { fatal: false }).decode(merged);
  }
  throw new Error("subscription_fetch_failed");
}
async function refreshSource(env, id, { publish = true } = {}) {
  const db = env.DB;
  const source = await db.prepare("SELECT * FROM subscription_sources WHERE id=?").bind(id).first();
  if (!source) return json({ error: "source_not_found" }, 404);
  try {
    const body = await fetchSubscription(source.subscription_url);
    const configs = parseSubscriptionBody(body);
    if (!configs.length) throw new Error("no_supported_configs_found");
    if (configs.length > 200) throw new Error("too_many_configs_in_subscription");
    await db.batch([
      db.prepare("DELETE FROM configs WHERE source_id=?").bind(id),
      ...configs.map((c, i) => db.prepare(`INSERT INTO configs(name, protocol, config_data, source_type, source_id, enabled, sort_order)
        VALUES(?, ?, ?, 'subscription', ?, 1, ?)`).bind(c.name, c.protocol, c.config_data, id, i))
    ]);
    await db.prepare("UPDATE subscription_sources SET last_synced_at=CURRENT_TIMESTAMP, last_sync_status=? WHERE id=?")
      .bind(`ok:${configs.length}`, id).run();
    const publication = publish ? await safePublishPublicFeed(env) : null;
    return json({ ok: true, imported: configs.length, publication });
  } catch (e) {
    await db.prepare("UPDATE subscription_sources SET last_synced_at=CURRENT_TIMESTAMP, last_sync_status=? WHERE id=?")
      .bind(`error:${String(e.message).slice(0,120)}`, id).run();
    return json({ error: "subscription_refresh_failed", detail: String(e.message) }, 400);
  }
}
async function handlePublic(request, env, url) {
  const cors = corsHeaders(request.headers.get("Origin"), env);
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (request.method !== "GET") return json({ error: "method_not_allowed" }, 405, cors);
  if (url.pathname === "/api/public/health") return json({ ok: true, service: "v2plas-free-api" }, 200, cors);
  if (url.pathname !== "/api/public/configs") return json({ error: "not_found" }, 404, cors);
  const settings = await settingMap(env.DB);
  if (settings.free_enabled === false || settings.free_enabled === 0 || settings.free_enabled === "false") {
    return json({ enabled: false, configs: [], ads: [], settings: { free_enabled: false } }, 200, cors);
  }
  const configs = await all(env.DB, `SELECT id, name, protocol, config_data, source_type, created_at
    FROM configs WHERE enabled=1 ORDER BY sort_order ASC, id ASC`);
  const ads = await all(env.DB, `SELECT id, title, body, image_url, target_url, placement, priority
    FROM ads WHERE enabled=1 AND (start_at IS NULL OR start_at<=CURRENT_TIMESTAMP)
    AND (end_at IS NULL OR end_at>=CURRENT_TIMESTAMP) ORDER BY priority DESC, id DESC`);
  return json({
    enabled: true,
    configs,
    ads: settings.ads_enabled === false || settings.ads_enabled === 0 ? [] : ads,
    settings: {
      free_enabled: settings.free_enabled,
      volume_limit_mb: Number(settings.volume_limit_mb || 0),
      time_limit_hours: Number(settings.time_limit_hours || 0),
      ads_enabled: settings.ads_enabled !== false && settings.ads_enabled !== 0,
      config_refresh_minutes: Math.max(1, Number(settings.config_refresh_minutes || 60))
    },
    updated_at: new Date().toISOString()
  }, 200, cors);
}
async function handleAdmin(request, env, url) {
  if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: { "access-control-allow-methods": "GET, POST, PUT, PATCH, DELETE, OPTIONS", "access-control-allow-headers": "Content-Type" } });
  if (!isAllowedOrigin(request, env)) return json({ error: "origin_not_allowed" }, 403);
  if (url.pathname === "/api/admin/login" && request.method === "POST") {
    const body = await readJson(request, 4096);
    const password = typeof body.password === "string" ? body.password.slice(0, 1024) : "";
    if (!env.ADMIN_PASSWORD || !env.SESSION_SECRET) return json({ error: "admin_secrets_not_configured" }, 503);
    if (!safeEqual(password, env.ADMIN_PASSWORD)) return json({ error: "invalid_credentials" }, 401);
    const token = await makeSession(env);
    return json({ ok: true }, 200, { "set-cookie": cookie(token), "cache-control": "no-store" });
  }
  if (url.pathname === "/api/admin/logout" && request.method === "POST") {
    return json({ ok: true }, 200, { "set-cookie": cookie("", 0) });
  }
  const authError = await requireAdmin(request, env);
  if (authError) return authError;

  if (url.pathname === "/api/admin/me" && request.method === "GET") return json({ authenticated: true });
  if (url.pathname === "/api/admin/publish" && request.method === "POST") {
    // Idempotent publish: repeated clicks do not consume another Pages deployment.
    const publication = await safePublishPublicFeed(env);
    return json({ ok: publication.ok, publication }, publication.ok ? 200 : 503);
  }

  if (url.pathname === "/api/admin/configs") {
    if (request.method === "GET") return json({ configs: await all(env.DB, "SELECT * FROM configs ORDER BY sort_order, id") });
    if (request.method === "POST") {
      const b = await readJson(request);
      const name = cleanString(b.name, 120), config = cleanString(b.config_data, 128*1024);
      if (!name || !config) return json({ error: "name_and_config_data_required" }, 400);
      const result = await env.DB.prepare(`INSERT INTO configs(name, protocol, config_data, source_type, enabled, sort_order)
        VALUES(?, ?, ?, 'manual', ?, ?)`).bind(name, cleanString(b.protocol,40), config, boolInt(b.enabled,1), Number(b.sort_order)||0).run();
      const publication = await safePublishPublicFeed(env);
      return json({ ok: true, id: result.meta.last_row_id, publication }, 201);
    }
  }
  const configMatch = url.pathname.match(/^\/api\/admin\/configs\/(\d+)$/);
  if (configMatch && validId(configMatch[1])) {
    const id = Number(configMatch[1]);
    if (request.method === "PUT") {
      const b = await readJson(request);
      const name = cleanString(b.name,120), config = cleanString(b.config_data,128*1024);
      if (!name || !config) return json({ error: "name_and_config_data_required" },400);
      await env.DB.prepare(`UPDATE configs SET name=?, protocol=?, config_data=?, enabled=?, sort_order=? WHERE id=?`)
        .bind(name,cleanString(b.protocol,40),config,boolInt(b.enabled,1),Number(b.sort_order)||0,id).run();
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,publication});
    }
    if (request.method === "DELETE") {
      await env.DB.prepare("DELETE FROM configs WHERE id=?").bind(id).run();
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,publication});
    }
  }
  if (url.pathname === "/api/admin/configs/bulk" && request.method === "POST") {
    const b = await readJson(request);
    if (!Array.isArray(b.configs) || b.configs.length < 1 || b.configs.length > 200) return json({error:"configs_array_1_to_200_required"},400);
    const statements = [];
    for (const [i,c] of b.configs.entries()) {
      const name=cleanString(c.name,120), data=cleanString(c.config_data,128*1024);
      if (!name || !data) return json({error:`invalid_config_at_${i}`},400);
      statements.push(env.DB.prepare(`INSERT INTO configs(name,protocol,config_data,source_type,enabled,sort_order)
        VALUES(?,?,?,'manual',?,?)`).bind(name,cleanString(c.protocol,40),data,boolInt(c.enabled,1),Number(c.sort_order)||i));
    }
    await env.DB.batch(statements);
    const publication = await safePublishPublicFeed(env);
    return json({ok:true,imported:statements.length,publication},201);
  }
  if (url.pathname === "/api/admin/ads") {
    if (request.method === "GET") return json({ads:await all(env.DB,"SELECT * FROM ads ORDER BY priority DESC,id DESC")});
    if (request.method === "POST") {
      const b=await readJson(request), title=cleanString(b.title,160);
      if (!title) return json({error:"title_required"},400);
      const r=await env.DB.prepare(`INSERT INTO ads(title,body,image_url,target_url,placement,enabled,priority,start_at,end_at)
        VALUES(?,?,?,?,?,?,?,?,?)`).bind(title,cleanString(b.body,2000),cleanString(b.image_url,1000),cleanString(b.target_url,1000),cleanString(b.placement,60)||"free_configs",boolInt(b.enabled,1),Number(b.priority)||0,b.start_at||null,b.end_at||null).run();
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,id:r.meta.last_row_id,publication},201);
    }
  }
  const adMatch=url.pathname.match(/^\/api\/admin\/ads\/(\d+)$/);
  if(adMatch && validId(adMatch[1])) {
    const id=Number(adMatch[1]);
    if(request.method==="PUT") {
      const b=await readJson(request), title=cleanString(b.title,160);
      if(!title) return json({error:"title_required"},400);
      await env.DB.prepare(`UPDATE ads SET title=?,body=?,image_url=?,target_url=?,placement=?,enabled=?,priority=?,start_at=?,end_at=? WHERE id=?`)
        .bind(title,cleanString(b.body,2000),cleanString(b.image_url,1000),cleanString(b.target_url,1000),cleanString(b.placement,60)||"free_configs",boolInt(b.enabled,1),Number(b.priority)||0,b.start_at||null,b.end_at||null,id).run();
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,publication});
    }
    if(request.method==="DELETE"){
      await env.DB.prepare("DELETE FROM ads WHERE id=?").bind(id).run();
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,publication});
    }
  }
  if(url.pathname==="/api/admin/settings") {
    if(request.method==="GET") return json({settings:await settingMap(env.DB)});
    if(request.method==="PUT") {
      const b=await readJson(request);
      const allowed=["free_enabled","volume_limit_mb","time_limit_hours","ads_enabled","config_refresh_minutes","subscription_refresh_minutes"];
      for(const key of allowed) if(Object.prototype.hasOwnProperty.call(b,key)) {
        const value=b[key];
        if(["free_enabled","ads_enabled"].includes(key) && typeof value!=="boolean") return json({error:`${key}_must_be_boolean`},400);
        if(key.endsWith("_minutes") || key.endsWith("_mb") || key.endsWith("_hours")) {
          if(!Number.isFinite(Number(value)) || Number(value)<0) return json({error:`${key}_must_be_nonnegative_number`},400);
          if(key === "subscription_refresh_minutes" && Number(value) < 360) return json({error:"subscription_refresh_minutes_must_be_at_least_360"},400);
          if(key === "config_refresh_minutes" && Number(value) !== 360) return json({error:"config_refresh_minutes_is_fixed_at_360"},400);
        }
        await setSetting(env.DB,key,value);
      }
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,settings:await settingMap(env.DB),publication});
    }
  }
  if(url.pathname==="/api/admin/subscriptions") {
    if(request.method==="GET") return json({subscriptions:await all(env.DB,"SELECT id,name,subscription_url,enabled,last_synced_at,last_sync_status,created_at FROM subscription_sources ORDER BY id DESC")});
    if(request.method==="POST") {
      const b=await readJson(request), name=cleanString(b.name,120);
      if(!name || !b.subscription_url) return json({error:"name_and_subscription_url_required"},400);
      let safe; try { safe=validateSubscriptionUrl(cleanString(b.subscription_url,2000)); } catch(e){return json({error:e.message},400);}
      const r=await env.DB.prepare("INSERT INTO subscription_sources(name,subscription_url,enabled) VALUES(?,?,?)").bind(name,safe.toString(),boolInt(b.enabled,1)).run();
      return json({ok:true,id:r.meta.last_row_id},201);
    }
  }
  const subMatch=url.pathname.match(/^\/api\/admin\/subscriptions\/(\d+)(\/refresh)?$/);
  if(subMatch && validId(subMatch[1])) {
    const id=Number(subMatch[1]);
    if(subMatch[2]==="/refresh" && request.method==="POST") return refreshSource(env,id);
    if(request.method==="PUT") {
      const b=await readJson(request), name=cleanString(b.name,120);
      if(!name || !b.subscription_url) return json({error:"name_and_subscription_url_required"},400);
      let safe; try {safe=validateSubscriptionUrl(cleanString(b.subscription_url,2000));}catch(e){return json({error:e.message},400);}
      await env.DB.prepare("UPDATE subscription_sources SET name=?,subscription_url=?,enabled=? WHERE id=?").bind(name,safe.toString(),boolInt(b.enabled,1),id).run();
      return json({ok:true});
    }
    if(request.method==="DELETE") {
      await env.DB.prepare("DELETE FROM configs WHERE source_id=?").bind(id).run();
      await env.DB.prepare("DELETE FROM subscription_sources WHERE id=?").bind(id).run();
      const publication = await safePublishPublicFeed(env);
      return json({ok:true,publication});
    }
  }
  return json({error:"not_found"},404);
}
const ADMIN_HTML = String.raw`<!doctype html>
<html lang="fa" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#101827"><title>پنل مدیریت V2Plas</title>
<style>
:root{color-scheme:dark;--bg:#0b1220;--panel:#121d30;--panel2:#18263c;--line:#293a54;--text:#edf3ff;--muted:#9fb0c8;--accent:#4f8cff;--good:#24c58a;--bad:#ff6577}*{box-sizing:border-box}body{margin:0;background:radial-gradient(ellipse at top,#172943 0,#0b1220 55%);color:var(--text);font:14px system-ui,-apple-system,"Segoe UI",sans-serif;min-height:100vh}button,input,textarea,select{font:inherit}button{border:0;border-radius:10px;background:var(--accent);color:white;padding:10px 15px;font-weight:700;cursor:pointer}button.secondary{background:#263852}button.danger{background:#9e3448}button:disabled{opacity:.55;cursor:wait}.wrap{max-width:1100px;margin:auto;padding:24px}.top{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:20px}.brand{display:flex;align-items:center;gap:12px}.logo{width:44px;height:44px;border-radius:13px;background:linear-gradient(135deg,#398cff,#7b5cff);display:grid;place-items:center;font-weight:900;font-size:18px}.muted{color:var(--muted)}.card{background:linear-gradient(180deg,#142137,#101a2b);border:1px solid var(--line);border-radius:16px;padding:18px;margin:14px 0;box-shadow:0 12px 35px #0002}.login{max-width:420px;margin:10vh auto}.field{display:grid;gap:7px;margin:12px 0}label{color:#c5d3e8;font-weight:650}input,textarea,select{width:100%;background:#0b1525;border:1px solid #30435f;color:var(--text);border-radius:10px;padding:11px;outline:none}textarea{min-height:110px;resize:vertical;direction:ltr;text-align:left}input:focus,textarea:focus,select:focus{border-color:var(--accent)}.grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:14px}.stats{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.stat{background:#0d192b;border:1px solid var(--line);padding:14px;border-radius:12px}.stat b{font-size:22px;display:block;margin-top:5px}.tabs{display:flex;gap:8px;flex-wrap:wrap;margin:18px 0}.tabs button{background:#1c2b42}.tabs button.active{background:var(--accent)}.row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:12px 0;border-bottom:1px solid var(--line)}.row:last-child{border:0}.row .meta{min-width:0;overflow-wrap:anywhere}.actions{display:flex;gap:7px;flex-wrap:wrap}.actions button{padding:7px 10px;font-size:12px}.hidden{display:none!important}.notice{padding:11px 13px;border-radius:10px;background:#183653;color:#cfe4ff;margin:12px 0}.notice.error{background:#4b202b;color:#ffd5dc}.notice.ok{background:#123c32;color:#c9ffeb}.check{display:flex;align-items:center;gap:9px}.check input{width:auto}.section-title{margin:0 0 8px;font-size:18px}.empty{padding:22px;text-align:center;color:var(--muted)}@media(max-width:700px){.wrap{padding:14px}.grid,.stats{grid-template-columns:1fr}.top{align-items:flex-start}.row{align-items:flex-start;flex-direction:column}}
</style></head><body><div class="wrap">
<header class="top"><div class="brand"><div class="logo">V2</div><div><div style="font-size:20px;font-weight:850">V2Plas</div><div class="muted">پنل مدیریت سرویس کانفیگ رایگان</div></div></div><button id="logout" class="secondary hidden">خروج</button></header>
<section id="login" class="card login"><h2>ورود مدیر</h2><p class="muted">برای مدیریت سرویس، رمز عبور را وارد کنید.</p><form id="loginForm"><div class="field"><label>رمز عبور مدیریت</label><input id="password" type="password" autocomplete="current-password" required></div><button style="width:100%">ورود به پنل</button></form><div id="loginMsg"></div></section>
<main id="app" class="hidden"><div id="notice"></div><div class="card"><h3 class="section-title">انتشار اطلاعات برای برنامه</h3><p class="muted">پس از ثبت یا تغییر کانفیگ، تبلیغ یا تنظیمات، فایل عمومی به‌صورت خودکار به‌روزرسانی می‌شود. برای انتشار اولیه یا بررسی تغییرات منتشرنشده، از این دکمه استفاده کنید؛ اگر داده تغییر نکرده باشد Deploy جدید انجام نمی‌شود.</p><button id="publishNow">انتشار آخرین اطلاعات</button></div><div class="stats"><div class="stat"><span class="muted">تعداد کانفیگ‌ها</span><b id="countConfigs">۰</b></div><div class="stat"><span class="muted">اشتراک‌ها</span><b id="countSubs">۰</b></div><div class="stat"><span class="muted">تبلیغات فعال</span><b id="countAds">۰</b></div></div>
<nav class="tabs"><button data-tab="configs" class="active">کانفیگ‌ها</button><button data-tab="subs">اشتراک‌ها</button><button data-tab="settings">تنظیمات</button><button data-tab="ads">تبلیغات</button></nav>
<section id="tab-configs" class="tab"><div class="grid"><div class="card"><h3 class="section-title">افزودن کانفیگ تکی</h3><form id="configForm"><div class="field"><label>نام نمایشی</label><input name="name" maxlength="120" required></div><div class="field"><label>پروتکل (اختیاری)</label><input name="protocol" placeholder="vless / vmess / trojan / json"></div><div class="field"><label>لینک یا متن کانفیگ</label><textarea name="config_data" required></textarea></div><button>ذخیره کانفیگ</button></form></div><div class="card"><h3 class="section-title">افزودن گروهی</h3><p class="muted">هر لینک یا خط کانفیگ را در یک خط قرار دهید.</p><div class="field"><label>نام گروه</label><input id="bulkPrefix" value="کانفیگ"></div><div class="field"><label>لیست کانفیگ‌ها</label><textarea id="bulkData" placeholder="vless://...&#10;vmess://...&#10;trojan://..."></textarea></div><button id="bulkAdd">واردکردن گروهی</button></div></div><div class="card"><h3 class="section-title">فهرست کانفیگ‌ها</h3><div id="configsList" class="empty">در حال دریافت...</div></div></section>
<section id="tab-subs" class="tab hidden"><div class="card"><h3 class="section-title">افزودن لینک اشتراک</h3><form id="subForm"><div class="grid"><div class="field"><label>نام اشتراک</label><input name="name" required></div><div class="field"><label>آدرس اشتراک (HTTPS)</label><input name="subscription_url" type="url" placeholder="https://example.com/sub/..." required></div></div><button>ثبت اشتراک و دریافت کانفیگ‌ها</button></form></div><div class="card"><h3 class="section-title">اشتراک‌های ثبت‌شده</h3><div id="subsList" class="empty">در حال دریافت...</div></div></section>
<section id="tab-settings" class="tab hidden"><div class="card"><h3 class="section-title">تنظیمات سرویس رایگان</h3><form id="settingsForm"><div class="grid"><div class="field check"><input id="free_enabled" type="checkbox"><label for="free_enabled">فعال بودن سرویس رایگان</label></div><div class="field check"><input id="ads_enabled" type="checkbox"><label for="ads_enabled">فعال بودن تبلیغات</label></div><div class="field"><label>محدودیت حجم (MB)؛ صفر یعنی بدون محدودیت</label><input id="volume_limit_mb" type="number" min="0"></div><div class="field"><label>محدودیت زمان (ساعت)؛ صفر یعنی بدون محدودیت</label><input id="time_limit_hours" type="number" min="0"></div><div class="field"><label>فاصله بررسی اطلاعات کاربران (دقیقه، ثابت)</label><input id="config_refresh_minutes" type="number" min="360" value="360" readonly></div><div class="field"><label>فاصله بروزرسانی اشتراک‌ها (حداقل ۳۶۰ دقیقه)</label><input id="subscription_refresh_minutes" type="number" min="360" value="360"></div></div><button>ذخیره تنظیمات</button></form><p class="muted">بررسی فهرست کاربران هر ۶ ساعت ثابت است. اشتراک‌ها نیز در زمان‌بندی ۶ ساعته Worker همگام می‌شوند؛ مقدار کمتر از ۳۶۰ دقیقه پذیرفته نمی‌شود. محدودیت حجم و زمان داخل برنامه اندروید اعمال می‌شود.</p></div></section>
<section id="tab-ads" class="tab hidden"><div class="card"><h3 class="section-title">افزودن تبلیغ</h3><form id="adForm"><div class="grid"><div class="field"><label>عنوان</label><input name="title" required></div><div class="field"><label>جایگاه</label><select name="placement"><option value="free_configs">صفحه کانفیگ رایگان</option><option value="free_entry">هنگام ورود به بخش</option></select></div><div class="field"><label>لینک تصویر (اختیاری)</label><input name="image_url" type="url"></div><div class="field"><label>لینک مقصد (اختیاری)</label><input name="target_url" type="url"></div><div class="field"><label>اولویت نمایش</label><input name="priority" type="number" value="0"></div><div class="field"><label>متن تبلیغ</label><input name="body"></div></div><div class="field check"><input name="enabled" type="checkbox" checked><label>فعال باشد</label></div><button>ثبت تبلیغ</button></form></div><div class="card"><h3 class="section-title">تبلیغات ثبت‌شده</h3><div id="adsList" class="empty">در حال دریافت...</div></div></section>
</main></div>
<script>
const $=s=>document.querySelector(s), $$=s=>[...document.querySelectorAll(s)];let state={configs:[],subs:[],ads:[],settings:{}};
function esc(v){return String(v??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));}
function msg(t,kind='ok',target='#notice'){const el=$(target);el.className='notice '+(kind==='error'?'error':'ok');el.textContent=t;}
async function api(path,opts={}){const r=await fetch(path,{...opts,credentials:'same-origin',headers:{'content-type':'application/json',...(opts.headers||{})}});let d={};try{d=await r.json()}catch{}if(!r.ok)throw Error(d.detail||d.error||('HTTP '+r.status));if(d.publication&&d.publication.ok===false)throw Error('تغییرات در پنل ذخیره شد، اما انتشار فایل عمومی انجام نشد: '+(d.publication.error||'خطای ناشناخته'));return d;}
function showApp(on){$('#login').classList.toggle('hidden',on);$('#app').classList.toggle('hidden',!on);$('#logout').classList.toggle('hidden',!on);}
async function boot(){try{await api('/api/admin/me');showApp(true);await loadAll()}catch{showApp(false)}}
async function loadAll(){const [c,s,a,x]=await Promise.all([api('/api/admin/configs'),api('/api/admin/subscriptions'),api('/api/admin/ads'),api('/api/admin/settings')]);state={configs:c.configs||[],subs:s.subscriptions||[],ads:a.ads||[],settings:x.settings||{}};render();}
function render(){ $('#countConfigs').textContent=state.configs.length;$('#countSubs').textContent=state.subs.length;$('#countAds').textContent=state.ads.filter(x=>x.enabled).length;
$('#configsList').innerHTML=state.configs.length?state.configs.map(x=>\`<div class="row"><div class="meta"><b>\${esc(x.name)}</b><div class="muted">#\${x.id} · \${esc(x.protocol||'نامشخص')} · \${esc(x.source_type)}</div></div><div class="actions"><button class="secondary" data-edit-config="\${x.id}">ویرایش</button><button class="danger" data-del-config="\${x.id}">حذف</button></div></div>\`).join(''):'<div class="empty">هنوز کانفیگی ثبت نشده است.</div>';
$('#subsList').innerHTML=state.subs.length?state.subs.map(x=>\`<div class="row"><div class="meta"><b>\${esc(x.name)}</b><div class="muted">\${esc(x.last_sync_status||'هنوز همگام‌سازی نشده')} · \${esc(x.last_synced_at||'')}</div></div><div class="actions"><button data-refresh-sub="\${x.id}">به‌روزرسانی</button><button class="secondary" data-edit-sub="\${x.id}">ویرایش</button><button class="danger" data-del-sub="\${x.id}">حذف</button></div></div>\`).join(''):'<div class="empty">هنوز اشتراکی ثبت نشده است.</div>';
$('#adsList').innerHTML=state.ads.length?state.ads.map(x=>\`<div class="row"><div class="meta"><b>\${esc(x.title)}</b><div class="muted">\${esc(x.placement)} · \${x.enabled?'فعال':'غیرفعال'} · اولویت \${x.priority}</div></div><div class="actions"><button class="secondary" data-edit-ad="\${x.id}">ویرایش</button><button class="danger" data-del-ad="\${x.id}">حذف</button></div></div>\`).join(''):'<div class="empty">هنوز تبلیغی ثبت نشده است.</div>';
for(const k of ['free_enabled','ads_enabled'])$('#'+k).checked=!!state.settings[k];for(const k of ['volume_limit_mb','time_limit_hours'])$('#'+k).value=Number(state.settings[k]||0);$('#config_refresh_minutes').value=360;$('#subscription_refresh_minutes').value=Math.max(360,Number(state.settings.subscription_refresh_minutes||360));
}
$('#loginForm').addEventListener('submit',async e=>{e.preventDefault();try{await api('/api/admin/login',{method:'POST',body:JSON.stringify({password:$('#password').value})});$('#password').value='';showApp(true);await loadAll()}catch(err){msg('ورود ناموفق: '+err.message,'error','#loginMsg')}});
$('#logout').onclick=async()=>{try{await api('/api/admin/logout',{method:'POST',body:'{}'})}finally{showApp(false)}};
$('#publishNow').onclick=async()=>{const b=$('#publishNow');b.disabled=true;try{const d=await api('/api/admin/publish',{method:'POST',body:'{}'});msg(d.publication.unchanged?'فهرست عمومی از قبل به‌روز است.':'فهرست عمومی با موفقیت منتشر شد.')}catch(err){msg(err.message,'error')}finally{b.disabled=false}};
$$('[data-tab]').forEach(b=>b.onclick=()=>{$$('[data-tab]').forEach(x=>x.classList.toggle('active',x===b));$$('.tab').forEach(x=>x.classList.toggle('hidden',x.id!=='tab-'+b.dataset.tab));});
$('#configForm').onsubmit=async e=>{e.preventDefault();const f=new FormData(e.target);try{await api('/api/admin/configs',{method:'POST',body:JSON.stringify(Object.fromEntries(f))});e.target.reset();await loadAll();msg('کانفیگ ذخیره شد.')}catch(err){msg(err.message,'error')}};
$('#bulkAdd').onclick=async()=>{const lines=$('#bulkData').value.split(/\r?\n/).map(x=>x.trim()).filter(Boolean);if(!lines.length)return msg('ابتدا لینک کانفیگ‌ها را وارد کنید.','error');const prefix=$('#bulkPrefix').value.trim()||'کانفیگ';const configs=lines.map((v,i)=>({name:\`\${prefix} \${i+1}\`,config_data:v,protocol:(v.match(/^([a-z][a-z0-9+.-]*):\/\//i)||[])[1]||'auto'}));try{const d=await api('/api/admin/configs/bulk',{method:'POST',body:JSON.stringify({configs})});$('#bulkData').value='';await loadAll();msg(\`\${d.imported} کانفیگ وارد شد.\`)}catch(err){msg(err.message,'error')}};
$('#subForm').onsubmit=async e=>{e.preventDefault();const b=Object.fromEntries(new FormData(e.target));try{const d=await api('/api/admin/subscriptions',{method:'POST',body:JSON.stringify(b)});e.target.reset();await api(\`/api/admin/subscriptions/\${d.id}/refresh\`,{method:'POST',body:'{}'});await loadAll();msg('اشتراک ثبت و همگام‌سازی شد.')}catch(err){await loadAll().catch(()=>{});msg('اشتراک ثبت شد یا خطای دریافت رخ داد: '+err.message,'error')}};
$('#settingsForm').onsubmit=async e=>{e.preventDefault();const b={free_enabled:$('#free_enabled').checked,ads_enabled:$('#ads_enabled').checked,volume_limit_mb:Number($('#volume_limit_mb').value),time_limit_hours:Number($('#time_limit_hours').value),config_refresh_minutes:Number($('#config_refresh_minutes').value),subscription_refresh_minutes:Number($('#subscription_refresh_minutes').value)};try{await api('/api/admin/settings',{method:'PUT',body:JSON.stringify(b)});await loadAll();msg('تنظیمات ذخیره شد.')}catch(err){msg(err.message,'error')}};
$('#adForm').onsubmit=async e=>{e.preventDefault();const f=new FormData(e.target),b=Object.fromEntries(f);b.enabled=f.has('enabled');try{await api('/api/admin/ads',{method:'POST',body:JSON.stringify(b)});e.target.reset();await loadAll();msg('تبلیغ ثبت شد.')}catch(err){msg(err.message,'error')}};
document.addEventListener('click',async e=>{const b=e.target.closest('button');if(!b)return;try{if(b.dataset.delConfig){if(confirm('این کانفیگ حذف شود؟'))await api('/api/admin/configs/'+b.dataset.delConfig,{method:'DELETE',body:'{}'})}else if(b.dataset.editConfig){const x=state.configs.find(v=>v.id==b.dataset.editConfig);const name=prompt('نام کانفیگ',x.name);if(name===null)return;const data=prompt('متن یا لینک کانفیگ',x.config_data);if(data===null)return;await api('/api/admin/configs/'+x.id,{method:'PUT',body:JSON.stringify({...x,name,config_data:data})})}else if(b.dataset.refreshSub){const d=await api('/api/admin/subscriptions/'+b.dataset.refreshSub+'/refresh',{method:'POST',body:'{}'});msg(\`همگام‌سازی انجام شد؛ \${d.imported} کانفیگ دریافت شد.\`)}else if(b.dataset.delSub){if(confirm('اشتراک و کانفیگ‌های وابسته حذف شوند؟'))await api('/api/admin/subscriptions/'+b.dataset.delSub,{method:'DELETE',body:'{}'})}else if(b.dataset.editSub){const x=state.subs.find(v=>v.id==b.dataset.editSub);const name=prompt('نام اشتراک',x.name);if(name===null)return;const url=prompt('آدرس HTTPS اشتراک',x.subscription_url);if(url===null)return;await api('/api/admin/subscriptions/'+x.id,{method:'PUT',body:JSON.stringify({...x,name,subscription_url:url})})}else if(b.dataset.delAd){if(confirm('این تبلیغ حذف شود؟'))await api('/api/admin/ads/'+b.dataset.delAd,{method:'DELETE',body:'{}'})}else if(b.dataset.editAd){const x=state.ads.find(v=>v.id==b.dataset.editAd);const title=prompt('عنوان تبلیغ',x.title);if(title===null)return;const body=prompt('متن تبلیغ',x.body||'');if(body===null)return;await api('/api/admin/ads/'+x.id,{method:'PUT',body:JSON.stringify({...x,title,body})})}else return;await loadAll()}catch(err){msg(err.message,'error')}});
boot();
</script></body></html>`.replaceAll("\\`", "`").replaceAll("\\${", "${");

function adminPage() {
  return new Response(ADMIN_HTML, { status: 200, headers: {
    "content-type": "text/html; charset=utf-8",
    "cache-control": "no-store",
    "content-security-policy": "default-src 'self'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; img-src 'self' https: data:; connect-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
    "x-content-type-options": "nosniff",
    "referrer-policy": "no-referrer",
    "x-frame-options": "DENY"
  }});
}

export default {
  async scheduled(controller, env, ctx) {
    if (!env.DB) return;
    ctx.waitUntil((async () => {
      const settings = await settingMap(env.DB);
      // Cron runs every six hours; do not advertise a shorter refresh interval
      // that the configured trigger cannot honor.
      const intervalMs = Math.max(360, Number(settings.subscription_refresh_minutes || 360)) * 60 * 1000;
      const sources = await all(env.DB, "SELECT id, last_synced_at FROM subscription_sources WHERE enabled=1 ORDER BY id ASC");
      const now = Date.now();
      for (const source of sources) {
        const last = source.last_synced_at ? Date.parse(source.last_synced_at.replace(" ", "T") + (source.last_synced_at.endsWith("Z") ? "" : "Z")) : 0;
        if (!last || now - last >= intervalMs) {
          // Sequential refreshes avoid bursts against subscription providers.
          await refreshSource(env, source.id, { publish: false });
        }
      }
      // One snapshot publication per scheduled cycle, including activation/expiry
      // of time-windowed ads. Unchanged content creates no GitHub commit or Pages build.
      const publication = await safePublishPublicFeed(env);
      if (!publication.ok) console.error("Scheduled V2Plas feed publication failed", publication.error);
    })());
  },
  async fetch(request, env) {
    try {
      if (!env.DB) return json({ error: "D1 binding DB is missing" }, 500);
      const url = new URL(request.url);
      if (url.pathname === "/admin" || url.pathname === "/admin/") return adminPage();
      if (url.pathname.startsWith("/api/public/")) return await handlePublic(request,env,url);
      if (url.pathname.startsWith("/api/admin/")) return await handleAdmin(request,env,url);
      if (url.pathname === "/") return json({ service:"V2Plas Free API", status:"ready", public_endpoint:"/api/public/configs" });
      return json({error:"not_found"},404);
    } catch (e) {
      const msg = e?.message || "internal_error";
      const status = msg === "body_too_large" ? 413 : (e instanceof SyntaxError || msg === "Unexpected end of JSON input") ? 400 : 500;
      return json({error: status===500 ? "internal_error" : msg});
    }
  }
};
