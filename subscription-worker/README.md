# HAPP Proxy Finder — subscription backend

This Worker hosts a live subscription URL for configurations that the Android app has **actually verified**. It does not scan networks or verify proxies itself.

## Deploy once

1. Install Node.js and Wrangler: `npm install -g wrangler`.
2. Log in: `wrangler login`.
3. From this folder create a KV namespace: `wrangler kv namespace create SUBSCRIPTIONS`.
4. Copy the returned namespace ID into `wrangler.toml`, replacing `REPLACE_WITH_KV_NAMESPACE_ID`.
5. Set a long random publish token: `wrangler secret put PUBLISH_TOKEN`.
6. Deploy: `wrangler deploy`.

Keep the publish token private. The public subscription URL is a bearer-style link: anyone who has it can read the exported proxy list. Do not publish it publicly.

## API

- `GET /health` — health check.
- `PUT /api/subscriptions/{uuid}` — publish an array of configuration URI strings; requires `Authorization: Bearer <PUBLISH_TOKEN>`.
- `GET /s/{uuid}` — live plain-text subscription for HAPP.

A subscription is updated in place whenever the app republishes the verified list. Records expire after 30 days without an update. The worker validates URI schemes, deduplicates entries, limits the list to 1,500 configurations and the request body to 2 MB.

## Important

The Android app must be configured with this Worker base URL and the publish token. Without deploying a Worker (or equivalent HTTPS hosting) there is no stable public URL: an Android device cannot expose a reachable internet subscription URL by itself. The app must never label TCP-only endpoints as verified.
