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
MAX_PUBLISHED = 1000
ALLOWED_SOURCE_HOSTS = {"raw.githubusercontent.com"}
SUPPORTED = {"vless", "socks", "socks5"}
TEST_URL = "https://www.gstatic.com/generate_204"
SPEED_URL = "https://speed.cloudflare.com/__down?bytes=262144"
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
        if not validate_source_url(url):
            print("Skipping non-whitelisted source: " + name)
            continue
        request = urllib.request.Request(url, headers={"User-Agent": "ScanerProxyCatalog/1.0"})
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                body = response.read(MAX_SOURCE_BYTES + 1)
            if len(body) > MAX_SOURCE_BYTES:
                continue
            collected.extend((name, line) for line in decode_feed(body).splitlines())
        except (OSError, urllib.error.URLError, TimeoutError) as exc:
            print("Source unavailable: " + name + ": " + str(exc))
    return collected



def fetch_uploaded(config_path: Path = ROOT / "incoming" / "upload.json") -> list[tuple[str, str]]:
    """Read configs explicitly uploaded by the app/site; never execute imported data."""
    if not config_path.exists():
        return []
    payload = json.loads(config_path.read_text(encoding="utf-8"))
    values = payload.get("configs", []) if isinstance(payload, dict) else payload
    if not isinstance(values, list):
        raise ValueError("Uploaded JSON must contain a configs array")
    collected: list[tuple[str, str]] = []
    for value in values[:MAX_CANDIDATES]:
        if isinstance(value, str):
            line = value
        elif isinstance(value, dict):
            line = str(value.get("uri") or value.get("rawConfig") or value.get("config") or "")
        else:
            continue
        if line.strip():
            collected.append(("App upload", line.strip()))
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
                item.update(lookup_geo(item["host"]))
                item["latencyMs"] = latency_ms
                item["speedMbps"] = speed_mbps
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


def write_outputs(verified: list[dict[str, Any]], site_dir: Path = SITE) -> None:
    sub = site_dir / "sub"
    sub.mkdir(parents=True, exist_ok=True)
    verified = sorted(verified, key=lambda x: (-float(x.get("speedMbps") or 0), int(x.get("latencyMs") or 2147483647), x["host"]))
    groups = {"all": verified, "vless": [x for x in verified if x["protocol"] == "VLESS"],
              "socks5": [x for x in verified if x["protocol"] == "SOCKS5"]}
    for key, nodes in groups.items():
        lines = [x["uri"] for x in nodes]
        body = "#profile-title: Scaner Proxy X\n#profile-update-interval: 6\n# Scaner Proxy X — проверено через HTTPS-туннель\n" + "\n".join(lines)
        if lines: body += "\n"
        (sub / (key + ".txt")).write_text(body, encoding="utf-8")
        (sub / (key + ".base64")).write_text(base64.b64encode(body.encode()).decode() + "\n", encoding="ascii")
    payload = {"updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds"),
        "total": len(verified), "protocols": {"VLESS": len(groups["vless"]), "SOCKS5": len(groups["socks5"])},
        "verification": "Каждая запись прошла HTTPS-запрос через sing-box; не прошедшие проверку исключены.",
        "nodes": [{k: x.get(k) for k in ("protocol", "host", "port", "name", "uri", "checkedAt", "test", "country", "countryCode", "city", "latencyMs", "speedMbps")} for x in verified]}
    (site_dir / "data.json").write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build(config_path: Path, sing_box: str, site_dir: Path = SITE, workers: int = 8) -> dict[str, int]:
    raw = fetch_uploaded()
    candidates, per_source = {}, {}
    for source_name, line in raw:
        if per_source.get(source_name, 0) >= 200:
            continue
        item = parse_link(line)
        if item:
            item["source"] = source_name
            candidates.setdefault(item["uri"], item)
            per_source[source_name] = per_source.get(source_name, 0) + 1
        if len(candidates) >= MAX_CANDIDATES:
            break
    items = list(candidates.values())
    if not items:
        raise RuntimeError("No supported candidates; previous published catalog was preserved")
    verified = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        futures = [pool.submit(test_candidate, item, sing_box) for item in items]
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            if result:
                verified.append(result)
                if len(verified) >= MAX_PUBLISHED:
                    for pending in futures: pending.cancel()
                    break
    if not verified:
        raise RuntimeError("No proxy passed real HTTPS tunnel check; previous catalog was preserved")
    verified.sort(key=lambda x: (x["protocol"], x["host"], x["port"]))
    write_outputs(verified, site_dir)
    print("Candidates: " + str(len(items)) + "; verified: " + str(len(verified)))
    return {"candidates": len(items), "verified": len(verified)}


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
