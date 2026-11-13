# Scaner Proxy X — online catalog

Static Black Neon web interface deployed to GitHub Pages. GitHub Actions starts a catalog verification run every five minutes. GitHub may delay scheduled starts; each run must finish successfully before the published data changes.

## Verification

- Sources are HTTPS raw files from public GitHub repositories.
- Only VLESS and SOCKS5 links are accepted, matching the protocols targeted by this app.
- Hosts resolving to private, loopback, link-local, multicast, or otherwise non-global addresses are rejected.
- Every published candidate must pass an actual HTTPS 204 request through a local sing-box inbound and the candidate proxy outbound.
- No arbitrary IP/port scanning, credential guessing, or password brute force is performed.
- If no candidates arrive or none pass, the workflow fails and preserves the previous published catalog.

## Subscription endpoints

After the first successful Pages deployment:

- https://kinderdmitry.github.io/Scaner-proxy/sub/all.txt
- https://kinderdmitry.github.io/Scaner-proxy/sub/all.base64
- https://kinderdmitry.github.io/Scaner-proxy/sub/vless.txt
- https://kinderdmitry.github.io/Scaner-proxy/sub/socks5.txt

The plain-text URI list is intended for Happ and Hiddify. The profile requests an update every six hours; the public catalog itself is rechecked by GitHub Actions on a five-minute schedule. The dynamic node limit requires the Cloudflare Worker URL in the website. Without it, the static link returns the full list.

## Upload API

The upload form requires a deployed Cloudflare Worker URL and the upload key configured by the service administrator. The Worker stores uploaded configuration data encrypted in the repository; the catalog workflow then verifies supported VLESS/SOCKS entries and republishes the site. Worker deployment is a separate manual workflow and requires the documented Cloudflare/GitHub secrets.

## Local tests

    python -m unittest discover -s tests -v
    python scripts/build_catalog.py --sing-box /path/to/sing-box

Public nodes are operated by third parties. A successful tunnel test is a point-in-time reachability check, not a guarantee of privacy, trustworthiness, speed, or future availability.
