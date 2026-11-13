# Scaner Proxy X — online catalog

Static Black Neon web interface deployed to GitHub Pages. GitHub Actions refreshes the catalog every six hours.

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

The plain-text URI list is intended as the standard subscription format for Happ and Hiddify. The response contains a six-hour update interval.

## Local tests

    python -m unittest discover -s tests -v
    python scripts/build_catalog.py --sing-box /path/to/sing-box

Public nodes are operated by third parties. A successful tunnel test is a point-in-time reachability check, not a guarantee of privacy, trustworthiness, speed, or future availability.
