# Scaner Proxy X — online catalog

Static website hosted on GitHub Pages. Uploads are committed directly to the repository using a user-supplied GitHub fine-grained personal access token; no OAuth browser flow, Cloudflare Worker, or separate upload server is used.

## Upload flow

1. Select the JSON exported by the Android app (or paste its complete contents).
2. Create a GitHub fine-grained personal access token for **only** `kinderDmitry/Scaner-proxy`.
3. Grant the token the repository permission **Contents: Read and write**. An expiration date is recommended.
4. Paste the token into the password field on the website and press **Проверить токен и отправить JSON**.
5. The browser encrypts the JSON with AES-256-GCM and wraps the AES key with the repository's RSA public key. Only the encrypted envelope is committed to `incoming/upload.json` on `main`.
6. GitHub Actions decrypts it using the private key stored in the repository's Actions secret, checks each supported VLESS/SOCKS5 tunnel with sing-box, and publishes only verified entries.

The token is used only in page memory, is not written to localStorage/sessionStorage, URL, or the uploaded JSON, and is cleared from the field after a successful upload. The website does not receive or store the token on a server. Do not share the token or paste it into chat. If it is exposed, revoke it in GitHub settings.

## One-time repository setup

In **Settings → Developer settings → Personal access tokens → Fine-grained tokens**, create a token with repository access limited to `kinderDmitry/Scaner-proxy` and **Contents: Read and write**.

Separately, in **Settings → Secrets and variables → Actions**, create this repository secret:

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
