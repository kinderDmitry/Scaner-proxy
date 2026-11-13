# Scaner Proxy X — online catalog

Static website hosted on GitHub Pages. Uploads are committed directly to the repository through GitHub OAuth Device Flow; no Cloudflare Worker or separate upload server is used.

## Upload flow

1. Select the JSON exported by the Android app.
2. Press **Авторизоваться и отправить JSON**.
3. GitHub displays a one-time code. Open the verification URL, sign in and approve access to the public repository.
4. The browser encrypts the JSON with AES-256-GCM and wraps the AES key with the repository's RSA public key. Only the encrypted envelope is committed to `incoming/upload.json`.
5. GitHub Actions decrypts it using the private key stored in the repository's Actions secret, checks each supported VLESS/SOCKS5 tunnel with sing-box, and publishes only verified entries.

The OAuth access token is kept in page memory only and is not written to localStorage. Reloading the page requires a new GitHub Device Flow authorization. The OAuth app must have **Enable Device Flow** enabled and its Client ID configured in the website.

## One-time repository setup

In **Settings → Secrets and variables → Actions**, create this repository secret:

- `UPLOAD_PRIVATE_KEY_PEM` — the RSA private key matching the public key embedded in `website/index.html`.

Never commit the private key to the repository or paste it into the website. Without this secret, the upload can be stored but the verification workflow cannot decrypt it.

## Verification and subscriptions

- GitHub Actions checks the catalog on a five-minute schedule. GitHub may delay scheduled starts; it is not an exact five-minute SLA.
- Only VLESS and SOCKS5 links are accepted.
- Hosts resolving to private, loopback, link-local, multicast, or otherwise non-global addresses are rejected.
- Every published candidate must pass a real HTTPS 204 request through a local sing-box inbound and the candidate proxy outbound.
- The fastest verified node is always first and labeled **⚡ Автоподбор**. Other nodes are labeled by geolocated country and city.
- GitHub Pages is static and cannot process a dynamic `?limit=` parameter. The site therefore publishes fixed subscription variants: all nodes, or Auto Pick plus 0/10/25/50/100/200/500 additional nodes.

## Local tests

    python -m unittest discover -s tests -v
    python scripts/build_catalog.py --sing-box /path/to/sing-box

Public nodes are operated by third parties. A successful tunnel test is a point-in-time reachability check, not a guarantee of privacy, trustworthiness, speed, or future availability.
