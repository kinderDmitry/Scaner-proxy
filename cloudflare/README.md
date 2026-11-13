# Scaner Proxy X upload API

This Worker accepts JSON uploads from the Android app and website, stores the normalized configuration list in `incoming/upload.json` on the repository's `main` branch, and serves one dynamic subscription URL.

## Required Worker secrets

- `UPLOAD_TOKEN`: long random bearer token entered in the app/site.
- `GITHUB_TOKEN`: GitHub fine-grained token with **Contents: Read and write** for `kinderDmitry/Scaner-proxy`.

Deploy from this directory with Wrangler, then add both secrets using `wrangler secret put`.

## Endpoints

- `GET /health`
- `POST /upload` with `Authorization: Bearer <UPLOAD_TOKEN>` and JSON `{"configs":["vless://..."]}`
- `GET /subscription?limit=all` or `?limit=50`

The subscription endpoint returns the fastest entries first from the current published list, limited to the selected count. The website and Android app can use the same URL.

## Security note

Uploaded proxy URIs contain credentials/UUIDs. This repository currently publishes the working subscription, so imported entries stored under `incoming/upload.json` are also visible in repository history. Upload only configurations you intend to publish. Do not upload personal or otherwise sensitive credentials.
