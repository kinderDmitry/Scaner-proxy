#!/usr/bin/env python3
"""Build a public catalog from explicitly published links; verify real HTTPS tunnels."""
from __future__ import annotations
import argparse, base64, concurrent.futures, datetime as dt, ipaddress, json, os, re, socket, subprocess, tempfile, time, threading, urllib.error, urllib.parse, urllib.request
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SITE = ROOT / "website"
MAX_SOURCE_BYTES = 8 * 1024 * 1024
MAX_CANDIDATES = 1000
MAX_SCANNER_CANDIDATES = 300
MAX_SCANNER_PER_SOURCE = 50
MAX_PUBLISHED = 1000
ALLOWED_SOURCE_HOSTS = {"raw.githubusercontent.com"}
SUPPORTED = {"vless", "socks", "socks5"}
TEST_URL = "https://www.gstatic.com/generate_204"
SPEED_URL = "https://speed.cloudflare.com/__down?bytes=262144"
SERVICE_TESTS = {
    "YouTube": "https://www.youtube.com/generate_204",
    "Instagram": "https://www.instagram.com/robots.txt",
    "ChatGPT": "https://chatgpt.com/",
}
GEO_CACHE: dict[str, dict[str, str]] = {}
GEO_LOCK = threading.Lock()


def decode_feed(body: bytes) -> str:
    text = body.decode("utf-8-sig", errors="replace").strip()
    compact = re.sub(r"\s+", "", text)
    if len(compact) >= 24 and re.fullmatch(r"[A-Za-z0-9+/=_-]+", compact):
        try:
            decoded = base64.urlsafe_b64decode(compact + "=" * (-len(compact) % 4)).decode("utf-8")
            if any(x in decoded.lower() for x in ("vless://", "socks://", "socks5://")):
                return decoded
        except (ValueError, UnicodeDecodeError):
            pass
    return text


def parse_link(line: str) -> dict[str, Any] | None:
    value = line.strip().strip("\ufeff")
    if not value or value.startswith(("#", "//")):
        return None
    match = re.search(r"(?:vless|socks5?)://\S+", value, flags=re.I)
    if not match:
        return None
    value = match.group(0).rstrip(",')]}")
    try:
        parsed = urllib.parse.urlsplit(value)
        scheme = parsed.scheme.lower()
        if scheme not in SUPPORTED or not parsed.hostname or not parsed.port:
            return None
        if scheme == "vless":
            if not parsed.username:
                return None
            q = {k: v[-1] for k, v in urllib.parse.parse_qs(parsed.query, keep_blank_values=True).items() if v}
            return {"uri": value, "protocol": "VLESS", "host": parsed.hostname, "port": parsed.port,
                    "name": urllib.parse.unquote(parsed.fragment)[:80] or parsed.hostname,
                    "uuid": urllib.parse.unquote(parsed.username), "query": q}
        return {"uri": "socks://" + value.split("://", 1)[1], "protocol": "SOCKS5",
                "host": parsed.hostname, "port": parsed.port,
                "name": urllib.parse.unquote(parsed.fragment)[:80] or parsed.hostname,
                "username": urllib.parse.unquote(parsed.username or ""),
                "password": urllib.parse.unquote(parsed.password or "")}
    except (ValueError, UnicodeError):
        return None


def is_public_host(host: str) -> bool:
    try:
        addresses = {x[4][0] for x in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)}
    except (OSError, UnicodeError):
        return False
    if not addresses:
        return False
    for address in addresses:
        try:
            if not ipaddress.ip_address(address.split("%", 1)[0]).is_global:
                return False
        except ValueError:
            return False
    return True


def validate_source_url(url: str) -> bool:
    try:
        p = urllib.parse.urlsplit(url)
        return p.scheme == "https" and p.hostname in ALLOWED_SOURCE_HOSTS and not p.username and not p.password
    except ValueError:
        return False


def fetch_sources(config_path: Path) -> list[tuple[str, str]]:
    config = json.loads(config_path.read_text(encoding="utf-8"))
    collected = []
    for source in config.get("sources", []):
        name, url = str(source.get("name", "Public feed")), str(source.get("url", ""))
        protocol_hint = str(source.get("protocol") or "").strip().lower()
        if not validate_source_url(url):
            print("Skipping non-whitelisted source: " + name)
            continue
        request = urllib.request.Request(url, headers={"User-Agent": "ScanerProxyCatalog/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = response.read(MAX_SOURCE_BYTES + 1)
            if len(body) > MAX_SOURCE_BYTES:
                print("Source is larger than the configured limit: " + name)
                continue
            for raw_line in decode_feed(body).splitlines():
                line = raw_line.strip()
                if protocol_hint == "socks5" and re.fullmatch(r"(?:\d{1,3}\.){3}\d{1,3}:\d{1,5}", line):
                    line = "socks5://" + line
                if line:
                    collected.append((name, line))
        except (OSError, urllib.error.URLError, TimeoutError) as exc:
            print("Source unavailable: " + name + ": " + str(exc))
    return collected



def fetch_uploaded(config_path: Path = ROOT / "incoming" / "upload.json") -> list[tuple[str, str, dict[str, str]]]:
    """Read explicitly uploaded JSON; decrypt the browser's hybrid RSA/AES envelope."""
    if not config_path.exists():
        return []
    payload = json.loads(config_path.read_text(encoding="utf-8"))
    if isinstance(payload, dict) and payload.get("encrypted") is True:
        pem = os.environ.get("UPLOAD_PRIVATE_KEY_PEM", "")
        if "BEGIN PRIVATE KEY" not in pem and "BEGIN RSA PRIVATE KEY" not in pem:
            raise RuntimeError("UPLOAD_PRIVATE_KEY_PEM GitHub Actions secret is required")
        try:
            from cryptography.hazmat.primitives import hashes, serialization
            from cryptography.hazmat.primitives.asymmetric import padding
            from cryptography.hazmat.primitives.ciphers.aead import AESGCM
            private_key = serialization.load_pem_private_key(pem.encode("utf-8"), password=None)
            if payload.get("algorithm") != "AES-256-GCM+RSA-OAEP-256":
                raise ValueError("Unsupported upload encryption algorithm")
            iv = base64.b64decode(payload["iv"], validate=True)
            ciphertext = base64.b64decode(payload["ciphertext"], validate=True)
            wrapped_key = base64.b64decode(payload["wrappedKey"], validate=True)
            if len(iv) != 12 or len(wrapped_key) < 256:
                raise ValueError("Invalid encrypted envelope")
            aes_key = private_key.decrypt(
                wrapped_key,
                padding.OAEP(mgf=padding.MGF1(algorithm=hashes.SHA256()),
                             algorithm=hashes.SHA256(), label=None))
            if len(aes_key) != 32:
                raise ValueError("Invalid AES key length")
            decrypted = AESGCM(aes_key).decrypt(iv, ciphertext, None)
            payload = json.loads(decrypted.decode("utf-8"))
        except Exception as exc:
            raise ValueError("Uploaded JSON could not be decrypted or validated") from exc
    source_name = (
        str(payload.get("source") or "App upload").strip()[:80]
        if isinstance(payload, dict) else "App upload"
    ) or "App upload"
    values = payload.get("configs", []) if isinstance(payload, dict) else payload
    if not isinstance(values, list):
        raise ValueError("Uploaded JSON must contain a configs array")
    if len(values) > MAX_CANDIDATES:
        raise ValueError("Uploaded JSON contains more than 1000 configurations")
    collected: list[tuple[str, str, dict[str, str]]] = []
    for value in values:
        if isinstance(value, str):
            line, metadata = value, {}
        elif isinstance(value, dict):
            line = str(value.get("uri") or value.get("rawConfig") or value.get("config") or "")
            metadata = {
                key: str(value.get(key) or "").strip()
                for key in ("country", "countryCode", "city")
            }
        else:
            continue
        # Scaner Proxy X exports objects with uri + optional geo fields.
        # Empty geo fields are valid: the verifier can resolve them later.
        if line.strip() and parse_link(line.strip()):
            collected.append((source_name, line.strip(), metadata))
    return collected



def country_flag(code: str) -> str:
    normalized = code.upper()
    if not re.fullmatch(r"[A-Z]{2}", normalized):
        return "❗"
    return "".join(chr(0x1F1E6 + ord(char) - ord("A")) for char in normalized)


def lookup_geo(host: str) -> dict[str, str]:
    try:
        addresses = sorted({entry[4][0] for entry in socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)})
        address = next((value for value in addresses if ipaddress.ip_address(value.split("%", 1)[0]).is_global), "")
        if not address:
            return {"country": "", "countryCode": "", "city": "", "name": "❗"}
        with GEO_LOCK:
            cached = GEO_CACHE.get(address)
        if cached:
            return cached
        request = urllib.request.Request(
            "https://ipwho.is/" + urllib.parse.quote(address) + "?fields=success,country,country_code,city",
            headers={"User-Agent": "ScanerProxyCatalog/1.0"})
        with urllib.request.urlopen(request, timeout=4) as response:
            data = json.loads(response.read(32768).decode("utf-8"))
        country = str(data.get("country") or "").strip() if data.get("success") else ""
        code = str(data.get("country_code") or "").strip().upper() if country else ""
        city = str(data.get("city") or "").strip() if country else ""
        name = country_flag(code) + (" " + country if country else "")
        if city:
            name += " · " + city
        result = {"country": country, "countryCode": code, "city": city, "name": name or "❗"}
        with GEO_LOCK:
            GEO_CACHE[address] = result
        return result
    except (OSError, ValueError, KeyError, json.JSONDecodeError):
        return {"country": "", "countryCode": "", "city": "", "name": "❗"}


def to_singbox_outbound(item: dict[str, Any]) -> dict[str, Any]:
    if item["protocol"] == "SOCKS5":
        out = {"type": "socks", "tag": "proxy", "server": item["host"], "server_port": item["port"], "version": "5"}
        if item.get("username"):
            out["username"], out["password"] = item["username"], item.get("password", "")
        return out
    q = item["query"]
    out = {"type": "vless", "tag": "proxy", "server": item["host"], "server_port": item["port"],
           "uuid": item["uuid"], "tls": {"enabled": False}}
    security = q.get("security", "none").lower()
    if security in {"tls", "reality"}:
        tls = {"enabled": True, "server_name": q.get("sni") or q.get("servername") or item["host"]}
        if q.get("alpn"):
            tls["alpn"] = [x for x in q["alpn"].split(",") if x]
        if q.get("fp"):
            tls["utls"] = {"enabled": True, "fingerprint": q["fp"]}
        if security == "reality":
            reality = {"enabled": True}
            if q.get("pbk"): reality["public_key"] = q["pbk"]
            if q.get("sid"): reality["short_id"] = q["sid"]
            tls["reality"] = reality
        out["tls"] = tls
    if q.get("flow"):
        out["flow"] = q["flow"]
    transport = q.get("type", "tcp").lower()
    if transport == "ws":
        ws = {"type": "ws", "path": q.get("path", "/")}
        if q.get("host"): ws["headers"] = {"Host": q["host"]}
        out["transport"] = ws
    elif transport == "grpc":
        out["transport"] = {"type": "grpc", "service_name": q.get("serviceName", "")}
    elif transport in {"httpupgrade", "http-upgrade"}:
        upgrade = {"type": "httpupgrade", "path": q.get("path", "/")}
        if q.get("host"): upgrade["host"] = q["host"]
        out["transport"] = upgrade
    elif transport not in {"tcp", "raw", ""}:
        raise ValueError("Unsupported VLESS transport: " + transport)
    return out



def check_services(local_port: int) -> dict[str, dict[str, Any]]:
    results: dict[str, dict[str, Any]] = {}
    proxy = "socks5h://127.0.0.1:" + str(local_port)
    for name, url in SERVICE_TESTS.items():
        try:
            result = subprocess.run(["curl", "--silent", "--show-error", "--location", "--output", "/dev/null",
                "--write-out", "%{http_code}", "--max-time", "5", "--connect-timeout", "2",
                "--proxy", proxy, url], capture_output=True, text=True, timeout=6)
            status = int(result.stdout.strip()) if result.returncode == 0 and result.stdout.strip().isdigit() else None
            results[name] = {"ok": status is not None and 200 <= status < 400, "status": status}
        except (OSError, ValueError, subprocess.SubprocessError):
            results[name] = {"ok": False, "status": None}
    return results



def with_location_label(uri: str, label: str) -> str:
    parts = urllib.parse.urlsplit(uri)
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, urllib.parse.quote(label, safe="")))


def test_candidate(item: dict[str, Any], sing_box: str) -> dict[str, Any] | None:
    if not is_public_host(item["host"]):
        return None
    try:
        outbound = to_singbox_outbound(item)
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            local_port = sock.getsockname()[1]
        config = {"log": {"level": "error"},
                  "inbounds": [{"type": "mixed", "tag": "in", "listen": "127.0.0.1", "listen_port": local_port}],
                  "outbounds": [outbound, {"type": "direct", "tag": "direct"}], "route": {"final": "proxy"}}
        with tempfile.TemporaryDirectory(prefix="proxy-check-") as temp:
            path = Path(temp) / "config.json"
            path.write_text(json.dumps(config), encoding="utf-8")
            process = subprocess.Popen([sing_box, "run", "-c", str(path)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            try:
                ready = False
                for _ in range(15):
                    if process.poll() is not None:
                        return None
                    try:
                        with socket.create_connection(("127.0.0.1", local_port), timeout=0.15):
                            ready = True
                            break
                    except OSError:
                        time.sleep(0.08)
                if not ready:
                    return None
                result = subprocess.run(["curl", "--silent", "--show-error", "--output", "/dev/null",
                    "--write-out", "%{http_code} %{time_total}", "--max-time", "6", "--connect-timeout", "3",
                    "--proxy", "socks5h://127.0.0.1:" + str(local_port), TEST_URL],
                    capture_output=True, text=True, timeout=8)
                parts = result.stdout.strip().split()
                if result.returncode != 0 or len(parts) != 2 or parts[0] != "204":
                    return None
                latency_ms = max(1, round(float(parts[1]) * 1000))
                speed = subprocess.run(["curl", "--silent", "--show-error", "--output", "/dev/null",
                    "--write-out", "%{http_code} %{speed_download}", "--max-time", "10", "--connect-timeout", "3",
                    "--proxy", "socks5h://127.0.0.1:" + str(local_port), SPEED_URL],
                    capture_output=True, text=True, timeout=12)
                speed_parts = speed.stdout.strip().split()
                speed_mbps = 0.0
                if speed.returncode == 0 and len(speed_parts) == 2 and speed_parts[0] == "200":
                    speed_mbps = round(float(speed_parts[1]) * 8 / 1_000_000, 2)
                resolved_geo = lookup_geo(item["host"])
                for field in ("country", "countryCode", "city"):
                    if resolved_geo.get(field):
                        item[field] = resolved_geo[field]
                item["name"] = location_label(item)
                item["uri"] = with_location_label(item["uri"], item["name"])
                item["latencyMs"] = latency_ms
                item["speedMbps"] = speed_mbps
                item["services"] = check_services(local_port)
                item["verified"] = True
                item["checkedAt"] = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
                item["test"] = "HTTPS 204 через туннель"
                return item
            finally:
                process.terminate()
                try: process.wait(timeout=1)
                except subprocess.TimeoutExpired: process.kill()
    except (OSError, ValueError, subprocess.SubprocessError, KeyError):
        return None


def subscription_uri(uri: str, label: str) -> str:
    parts = urllib.parse.urlsplit(uri)
    return urllib.parse.urlunsplit((parts.scheme, parts.netloc, parts.path, parts.query, urllib.parse.quote(label, safe="")))


def location_label(node: dict[str, Any]) -> str:
    country = str(node.get("country") or "").strip()
    country_code = str(node.get("countryCode") or "").strip()
    city = str(node.get("city") or "").strip()
    if not country:
        return "❗ Неизвестная геолокация"
    flag = country_flag(country_code)
    return flag + " " + country + ((" — " + city) if city else "")


def subscription_lines(nodes: list[dict[str, Any]]) -> list[str]:
    if not nodes:
        return []
    labels = [location_label(node) for node in nodes[1:]]
    totals: dict[str, int] = {}
    for label in labels:
        totals[label] = totals.get(label, 0) + 1
    seen: dict[str, int] = {}
    lines = [subscription_uri(nodes[0]["uri"], "⚡ Автоподбор")]
    for node, label in zip(nodes[1:], labels):
        if totals[label] > 1:
            seen[label] = seen.get(label, 0) + 1
            label += " · " + str(seen[label])
        lines.append(subscription_uri(node["uri"], label))
    return lines


def write_outputs(verified: list[dict[str, Any]], site_dir: Path = SITE, prefix: str = "") -> None:
    """Write one isolated catalog and its HAPP-compatible text subscriptions."""
    sub = site_dir / "sub"
    sub.mkdir(parents=True, exist_ok=True)
    nl = chr(10)
    prefix = re.sub(r"[^a-z0-9-]", "", prefix.lower()).strip("-")
    verified = sorted(
        verified,
        key=lambda x: (-float(x.get("speedMbps") or 0),
                       int(x.get("latencyMs") or 2147483647), x["host"])
    )
    groups = {
        "all": verified,
        "vless": [x for x in verified if x["protocol"] == "VLESS"],
        "socks5": [x for x in verified if x["protocol"] == "SOCKS5"],
    }
    all_name = prefix or "all"
    for key, nodes in groups.items():
        filename = all_name if key == "all" else (f"{prefix}-{key}" if prefix else key)
        lines = subscription_lines(nodes) if key == "all" else [x["uri"] for x in nodes]
        title = "Global Scanner" if prefix else "JSON Upload"
        body = nl.join([
            f"#profile-title: {title}",
            "#profile-update-interval: 1",
        ] + lines)
        if lines:
            body += nl
        encoded_body = base64.b64encode(body.encode("utf-8")).decode("ascii") + nl
        # HAPP standard subscriptions are plain UTF-8 text: metadata followed by one URI per line.
        # Keep the legacy Base64 representation only under the explicit .base64 extension.
        (sub / (filename + ".raw.txt")).write_text(body, encoding="utf-8")
        (sub / (filename + ".txt")).write_text(body, encoding="utf-8")
        (sub / (filename + ".base64")).write_text(encoded_body, encoding="ascii")
        (sub / (filename + "-happ.txt")).write_text(body, encoding="utf-8")

    # GitHub Pages is static: publish fixed-size variants with auto-pick always first.
    all_lines = subscription_lines(verified)
    for limit in (0, 10, 25, 50, 100, 200, 500):
        selected = all_lines[:1 + limit] if all_lines else []
        title = "Global Scanner" if prefix else "JSON Upload"
        body = nl.join([
            f"#profile-title: {title}",
            "#profile-update-interval: 1",
        ] + selected)
        if selected:
            body += nl
        filename = f"{all_name}-{limit}"
        encoded_body = base64.b64encode(body.encode("utf-8")).decode("ascii") + nl
        # All public .txt endpoints, including fixed-size variants, use the same plain-text format.
        (sub / (filename + ".raw.txt")).write_text(body, encoding="utf-8")
        (sub / (filename + ".txt")).write_text(body, encoding="utf-8")
        (sub / (filename + ".base64")).write_text(encoded_body, encoding="ascii")
        (sub / (all_name + "-happ-" + str(limit) + ".txt")).write_text(body, encoding="utf-8")

    payload = {
        "updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "total": len(verified),
        "protocols": {"VLESS": len(groups["vless"]), "SOCKS5": len(groups["socks5"])},
        "verification": "Каждая запись прошла HTTPS-запрос через sing-box; не прошедшие проверку исключены.",
        "nodes": [{
            k: x.get(k) for k in (
                "protocol", "host", "port", "name", "uri", "checkedAt", "test",
                "country", "countryCode", "city", "latencyMs", "speedMbps", "services", "source"
            )
        } for x in verified],
    }
    data_name = "data" + (("-" + prefix) if prefix else "") + ".json"
    (site_dir / data_name).write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + nl, encoding="utf-8"
    )


def verify_records(raw: list[tuple[str, str, dict[str, str]]], sing_box: str,
                   workers: int = 8, max_candidates: int = MAX_CANDIDATES,
                   per_source_limit: int = 200) -> tuple[int, list[dict[str, Any]]]:
    """Parse and verify one source family without mixing it with another."""
    candidates: dict[str, dict[str, Any]] = {}
    per_source: dict[str, int] = {}
    for source_name, line, geo_metadata in raw:
        if per_source.get(source_name, 0) >= per_source_limit:
            continue
        item = parse_link(line)
        if not item:
            continue
        item["source"] = source_name
        for field in ("country", "countryCode", "city"):
            if geo_metadata.get(field):
                item[field] = geo_metadata[field]
        candidates.setdefault(item["uri"], item)
        per_source[source_name] = per_source.get(source_name, 0) + 1
        if len(candidates) >= max_candidates:
            break

    items = list(candidates.values())
    if not items:
        return 0, []

    verified: list[dict[str, Any]] = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(test_candidate, item, sing_box) for item in items]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            if result:
                verified.append(result)
                if len(verified) >= MAX_PUBLISHED:
                    for pending in futures:
                        pending.cancel()
                    break
    return len(items), verified


def build(config_path: Path, sing_box: str, site_dir: Path = SITE, workers: int = 8) -> dict[str, int]:
    """Build independent uploaded-JSON and public-feed catalogs."""
    uploaded_raw = fetch_uploaded()
    public_raw = [(name, line, {}) for name, line in fetch_sources(config_path)]

    totals = {"candidates": 0, "verified": 0}
    catalogs = (
        ("", uploaded_raw, site_dir / "data.json", "uploaded JSON"),
        ("scanner", public_raw, site_dir / "data-scanner.json", "public scanner"),
    )
    for prefix, raw, existing_catalog, label in catalogs:
        candidate_count, verified = verify_records(
            raw, sing_box, workers,
            max_candidates=MAX_SCANNER_CANDIDATES if prefix else MAX_CANDIDATES,
            per_source_limit=MAX_SCANNER_PER_SOURCE if prefix else 200,
        )
        totals["candidates"] += candidate_count
        totals["verified"] += len(verified)
        if not candidate_count:
            if not existing_catalog.exists():
                write_outputs([], site_dir, prefix=prefix)
            print(f"No candidates from {label}; previous catalog preserved")
            continue
        if not verified:
            print(f"No {label} candidate passed the HTTPS tunnel check; previous catalog preserved")
            if not existing_catalog.exists():
                write_outputs([], site_dir, prefix=prefix)
            continue
        write_outputs(verified, site_dir, prefix=prefix)
        print(f"{label}: candidates {candidate_count}; verified {len(verified)}")

    print("Total candidates: " + str(totals["candidates"]) +
          "; total verified: " + str(totals["verified"]))
    return totals


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--sources", type=Path, default=ROOT / "sources.json")
    parser.add_argument("--sing-box", default=os.environ.get("SING_BOX_BIN", "sing-box"))
    parser.add_argument("--site", type=Path, default=SITE)
    parser.add_argument("--workers", type=int, default=8)
    args = parser.parse_args()
    build(args.sources, args.sing_box, args.site, max(1, min(args.workers, 12)))


if __name__ == "__main__":
    main()
