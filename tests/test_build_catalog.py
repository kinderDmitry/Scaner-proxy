import base64
import importlib.util
import json
import os
import tempfile
import unittest
import urllib.parse
from pathlib import Path
from unittest.mock import patch

MODULE = Path(__file__).parents[1] / "scripts" / "build_catalog.py"
spec = importlib.util.spec_from_file_location("build_catalog", MODULE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class FeedParsingTests(unittest.TestCase):
    def test_parses_vless_reality_websocket(self):
        link = "vless://11111111-1111-4111-8111-111111111111@example.com:443?security=reality&sni=cdn.example.com&pbk=public&sid=abcd&type=ws&path=%2Fedge#DE-node"
        item = mod.parse_link(link)
        self.assertEqual(item["protocol"], "VLESS")
        self.assertEqual(item["query"]["path"], "/edge")
        self.assertEqual(item["query"]["security"], "reality")

    def test_parses_and_normalizes_socks5(self):
        item = mod.parse_link("socks5://user:p%40ss@example.com:1080#Frankfurt")
        self.assertEqual(item["protocol"], "SOCKS5")
        self.assertTrue(item["uri"].startswith("socks://"))
        self.assertEqual(item["password"], "p@ss")

    def test_rejects_unsupported_or_malformed_links(self):
        self.assertIsNone(mod.parse_link("vmess://abc"))
        self.assertIsNone(mod.parse_link("vless://uuid@example.com"))
        self.assertIsNone(mod.parse_link("not a proxy"))

    def test_decodes_base64_subscription(self):
        body = "vless://11111111-1111-4111-8111-111111111111@example.com:443#node\n"
        self.assertIn("vless://", mod.decode_feed(base64.b64encode(body.encode())))


    def test_reads_uploaded_json_config_strings_and_objects(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "upload.json"
            path.write_text(json.dumps({"configs": [
                "vless://11111111-1111-4111-8111-111111111111@example.com:443#node",
                {"uri": "socks5://proxy.example:1080", "country": "Германия", "countryCode": "DE", "city": "Франкфурт"}
            ]}), encoding="utf-8")
            result = mod.fetch_uploaded(path)
            self.assertEqual(len(result), 2)
            self.assertEqual(result[0][0], "App upload")
            self.assertTrue(result[1][1].startswith("socks5://"))
            self.assertEqual(result[1][2], {"country": "Германия", "countryCode": "DE", "city": "Франкфурт"})


    def test_decrypts_hybrid_rsa_aes_uploaded_json(self):
        from cryptography.hazmat.primitives import hashes, serialization
        from cryptography.hazmat.primitives.asymmetric import padding, rsa
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
        private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        private_pem = private_key.private_bytes(
            serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption()).decode()
        public_key = private_key.public_key()
        key = bytes(range(32))
        iv = bytes(range(12))
        clear = json.dumps({"configs": ["socks5://proxy.example:1080"]}).encode()
        encrypted = AESGCM(key).encrypt(iv, clear, None)
        wrapped = public_key.encrypt(key, padding.OAEP(
            mgf=padding.MGF1(algorithm=hashes.SHA256()),
            algorithm=hashes.SHA256(), label=None))
        wrapper = {
            "encrypted": True,
            "algorithm": "AES-256-GCM+RSA-OAEP-256",
            "iv": base64.b64encode(iv).decode(),
            "ciphertext": base64.b64encode(encrypted).decode(),
            "wrappedKey": base64.b64encode(wrapped).decode()
        }
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "upload.json"
            path.write_text(json.dumps(wrapper), encoding="utf-8")
            with patch.dict(os.environ, {"UPLOAD_PRIVATE_KEY_PEM": private_pem}):
                result = mod.fetch_uploaded(path)
        self.assertEqual(result[0][1], "socks5://proxy.example:1080")


    def test_rejects_invalid_uploaded_json_shape(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "upload.json"
            path.write_text('{"configs":"not-an-array"}', encoding="utf-8")
            with self.assertRaises(ValueError):
                mod.fetch_uploaded(path)

    def test_rejects_untrusted_feed_hosts(self):
        self.assertTrue(mod.validate_source_url("https://raw.githubusercontent.com/u/r/main/list.txt"))
        self.assertFalse(mod.validate_source_url("https://example.com/list.txt"))
        self.assertFalse(mod.validate_source_url("http://raw.githubusercontent.com/u/r/main/list.txt"))


class GeoTests(unittest.TestCase):
    def test_subscription_name_replaces_feed_label_with_location_only(self):
        uri = "vless://uuid@example.com:443?security=tls#old-feed-name"
        renamed = mod.with_location_label(uri, "🇩🇪 Германия · Франкфурт")
        self.assertEqual(urllib.parse.unquote(renamed.rsplit("#", 1)[1]), "🇩🇪 Германия · Франкфурт")

    def test_country_flag(self):
        self.assertEqual(mod.country_flag("DE"), "🇩🇪")
        self.assertEqual(mod.country_flag(""), "❗")


class VerificationTests(unittest.TestCase):
    def test_builds_vless_reality_outbound(self):
        item = mod.parse_link("vless://11111111-1111-4111-8111-111111111111@example.com:443?security=reality&sni=cdn.example.com&pbk=pub&sid=short&type=ws&path=%2Fws")
        outbound = mod.to_singbox_outbound(item)
        self.assertEqual(outbound["type"], "vless")
        self.assertTrue(outbound["tls"]["reality"]["enabled"])
        self.assertEqual(outbound["transport"]["path"], "/ws")

    def test_builds_authenticated_socks_outbound(self):
        item = mod.parse_link("socks5://user:pass@example.com:1080")
        outbound = mod.to_singbox_outbound(item)
        self.assertEqual(outbound["type"], "socks")
        self.assertEqual(outbound["username"], "user")

    def test_service_checks_report_http_status_and_timeouts(self):
        from types import SimpleNamespace
        responses = [
            SimpleNamespace(returncode=0, stdout="204"),
            SimpleNamespace(returncode=0, stdout="403"),
            SimpleNamespace(returncode=28, stdout="")
        ]
        with patch.object(mod.subprocess, "run", side_effect=responses):
            results = mod.check_services(10808)
        self.assertTrue(results["YouTube"]["ok"])
        self.assertFalse(results["Instagram"]["ok"])
        self.assertEqual(results["Instagram"]["status"], 403)
        self.assertFalse(results["ChatGPT"]["ok"])
        self.assertIsNone(results["ChatGPT"]["status"])

    def test_rejects_private_and_unresolvable_hosts(self):
        with patch.object(mod.socket, "getaddrinfo", return_value=[(2, 1, 6, "", ("127.0.0.1", 0))]):
            self.assertFalse(mod.is_public_host("localhost"))
        with patch.object(mod.socket, "getaddrinfo", side_effect=OSError("DNS failure")):
            self.assertFalse(mod.is_public_host("bad.invalid"))


class OutputTests(unittest.TestCase):
    def test_only_verified_nodes_are_written(self):
        with tempfile.TemporaryDirectory() as temp:
            site = Path(temp)
            good = {"uri": "socks://proxy.example:1080", "protocol": "SOCKS5", "host": "proxy.example",
                    "port": 1080, "name": "good", "checkedAt": "2026-01-01T00:00:00+00:00",
                    "test": "HTTPS 204 через туннель", "verified": True,
                    "services": {"YouTube": {"ok": True, "status": 204}, "Instagram": {"ok": False, "status": 403}}}
            mod.write_outputs([good], site)
            text = (site / "sub" / "all.txt").read_text()
            self.assertIn(good["uri"], text)
            self.assertIn("#profile-update-interval: 6", text)
            data = json.loads((site / "data.json").read_text())
            self.assertEqual(data["total"], 1)
            self.assertEqual(data["protocols"]["SOCKS5"], 1)
            self.assertEqual(data["nodes"][0]["services"]["YouTube"]["ok"], True)
            self.assertIn("Instagram", data["nodes"][0]["services"])

    def test_no_uploaded_json_keeps_previous_catalog_without_failing(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sources = root / "sources.json"
            sources.write_text('{"sources":[]}', encoding="utf-8")
            site = root / "site"
            site.mkdir()
            current = site / "data.json"
            current.write_text("previous-catalog", encoding="utf-8")
            with patch.object(mod, "fetch_uploaded", return_value=[]):
                result = mod.build(sources, "missing-sing-box", site)
            self.assertEqual(result, {"candidates": 0, "verified": 0})
            self.assertEqual(current.read_text(encoding="utf-8"), "previous-catalog")

    def test_subscription_starts_with_fastest_auto_pick_then_geo_named_configs(self):
        with tempfile.TemporaryDirectory() as temp:
            site = Path(temp)
            nodes = [
                {"uri": "socks://slow.example:1080", "protocol": "SOCKS5", "host": "slow.example", "port": 1080,
                 "speedMbps": 3.0, "latencyMs": 30, "country": "Germany", "countryCode": "DE", "city": "Berlin"},
                {"uri": "socks://fast.example:1080", "protocol": "SOCKS5", "host": "fast.example", "port": 1080,
                 "speedMbps": 30.0, "latencyMs": 80, "country": "France", "countryCode": "FR", "city": "Paris"},
                {"uri": "socks://mid.example:1080", "protocol": "SOCKS5", "host": "mid.example", "port": 1080,
                 "speedMbps": 12.0, "latencyMs": 50, "country": "Netherlands", "countryCode": "NL", "city": "Amsterdam"},
            ]
            mod.write_outputs(nodes, site)
            lines = [line for line in (site / "sub" / "all.txt").read_text().splitlines() if line.startswith("socks://")]
            self.assertTrue(lines[0].startswith("socks://fast.example:1080"))
            self.assertIn("Автоподбор", urllib.parse.unquote(lines[0]))
            self.assertTrue(lines[1].startswith("socks://mid.example:1080"))
            self.assertIn("🇳🇱 Netherlands — Amsterdam", urllib.parse.unquote(lines[1]))
            self.assertTrue(lines[2].startswith("socks://slow.example:1080"))
            self.assertIn("🇩🇪 Germany — Berlin", urllib.parse.unquote(lines[2]))

    def test_static_subscription_limits_always_keep_auto_pick_first(self):
        with tempfile.TemporaryDirectory() as temp:
            site = Path(temp)
            nodes = [
                {"uri": f"socks://node{i}.example:1080", "protocol": "SOCKS5", "host": f"node{i}.example",
                 "port": 1080, "speedMbps": float(100-i), "latencyMs": 20+i,
                 "country": "Germany", "countryCode": "DE", "city": f"City{i}"}
                for i in range(30)
            ]
            mod.write_outputs(nodes, site)
            for limit in (0, 10, 25, 50, 100, 200, 500):
                lines = [line for line in (site / "sub" / f"all-{limit}.txt").read_text().splitlines()
                         if line.startswith("socks://")]
                self.assertEqual(len(lines), min(30, limit + 1))
                self.assertIn("Автоподбор", urllib.parse.unquote(lines[0]))
                self.assertTrue(lines[0].startswith("socks://node0.example:1080"))

    def test_duplicate_geo_labels_receive_unique_suffixes(self):
        nodes = [
            {"uri": "socks://first.example:1080", "country": "Germany", "countryCode": "DE", "city": "Berlin"},
            {"uri": "socks://second.example:1080", "country": "Germany", "countryCode": "DE", "city": "Berlin"},
            {"uri": "socks://third.example:1080", "country": "Germany", "countryCode": "DE", "city": "Berlin"},
        ]
        labels = [urllib.parse.unquote(line.rsplit("#", 1)[1]) for line in mod.subscription_lines(nodes)]
        self.assertEqual(labels[1:], ["🇩🇪 Germany — Berlin · 1", "🇩🇪 Germany — Berlin · 2"])

    def test_unknown_geo_is_explicit(self):
        node = {"uri": "socks://unknown.example:1080", "country": "", "countryCode": "", "city": ""}
        label = urllib.parse.unquote(mod.subscription_lines([{"uri": "socks://auto.example:1080"}, node])[1].rsplit("#", 1)[1])
        self.assertEqual(label, "❗ Неизвестная геолокация")

    def test_outputs_are_sorted_by_measured_speed(self):
        with tempfile.TemporaryDirectory() as temp:
            site = Path(temp)
            slower = {"uri": "socks://slow.example:1080", "protocol": "SOCKS5", "host": "slow.example",
                      "port": 1080, "name": "slow", "checkedAt": "2026-01-01", "test": "HTTPS",
                      "speedMbps": 4.0, "latencyMs": 90}
            faster = {"uri": "socks://fast.example:1080", "protocol": "SOCKS5", "host": "fast.example",
                      "port": 1080, "name": "fast", "checkedAt": "2026-01-01", "test": "HTTPS",
                      "speedMbps": 40.0, "latencyMs": 120}
            mod.write_outputs([slower, faster], site)
            lines = [line for line in (site / "sub" / "all.txt").read_text().splitlines() if line.startswith("socks://")]
            self.assertTrue(lines[0].startswith(faster["uri"] + "#"))
            self.assertTrue(lines[1].startswith(slower["uri"] + "#"))
            self.assertIn("Автоподбор", urllib.parse.unquote(lines[0]))

    def test_first_empty_run_creates_valid_subscription_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sources = root / "sources.json"
            sources.write_text('{"sources":[]}', encoding="utf-8")
            site = root / "site"
            with patch.object(mod, "fetch_uploaded", return_value=[]):
                result = mod.build(sources, "missing-sing-box", site)
            self.assertEqual(result, {"candidates": 0, "verified": 0})
            self.assertEqual(json.loads((site / "data.json").read_text())["total"], 0)
            self.assertTrue((site / "sub" / "all.txt").exists())

    def test_empty_run_preserves_existing_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sources = root / "sources.json"
            sources.write_text('{"sources":[]}')
            site = root / "site"
            site.mkdir()
            current = site / "data.json"
            current.write_text("previous-good-catalog")
            with patch.object(mod, "fetch_uploaded", return_value=[]):
                result = mod.build(sources, "missing-sing-box", site)
            self.assertEqual(result, {"candidates": 0, "verified": 0})
            self.assertEqual(current.read_text(), "previous-good-catalog")


    def test_scanner_catalog_uses_independent_subscription_and_data_files(self):
        with tempfile.TemporaryDirectory() as temp:
            site = Path(temp)
            nodes = [
                {"uri": "socks://fast.example:1080", "protocol": "SOCKS5", "host": "fast.example",
                 "port": 1080, "speedMbps": 25.0, "latencyMs": 40,
                 "country": "Germany", "countryCode": "DE", "city": "Frankfurt am Main"},
                {"uri": "socks://slow.example:1080", "protocol": "SOCKS5", "host": "slow.example",
                 "port": 1080, "speedMbps": 4.0, "latencyMs": 120,
                 "country": "Japan", "countryCode": "JP", "city": "Tokyo"},
            ]
            mod.write_outputs(nodes, site, prefix="scanner")
            lines = [line for line in (site / "sub" / "scanner.txt").read_text().splitlines()
                     if line.startswith("socks://")]
            self.assertEqual(len(lines), 2)
            self.assertIn("Автоподбор", urllib.parse.unquote(lines[0]))
            self.assertIn("🇯🇵 Japan — Tokyo", urllib.parse.unquote(lines[1]))
            self.assertEqual(json.loads((site / "data-scanner.json").read_text())["total"], 2)
            self.assertTrue((site / "sub" / "scanner-10.txt").exists())

    def test_build_collects_public_feeds_into_separate_scanner_subscription(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sources = root / "sources.json"
            sources.write_text('{"sources":[]}', encoding="utf-8")
            site = root / "site"
            def verify(item, _sing_box):
                item.update({"country": "Germany", "countryCode": "DE", "city": "Berlin",
                             "latencyMs": 35, "speedMbps": 12.5, "checkedAt": "2026-11-14T00:00:00+00:00",
                             "services": {}, "verified": True})
                return item
            with (
                patch.object(mod, "fetch_uploaded", return_value=[]),
                patch.object(mod, "fetch_sources", return_value=[("Public feed", "socks5://scanner.example:1080")]),
                patch.object(mod, "test_candidate", side_effect=verify),
            ):
                result = mod.build(sources, "unused-sing-box", site)
            self.assertEqual(result, {"candidates": 1, "verified": 1})
            self.assertEqual(json.loads((site / "data.json").read_text())["total"], 0)
            self.assertEqual(json.loads((site / "data-scanner.json").read_text())["total"], 1)
            scanner_lines = [line for line in (site / "sub" / "scanner.txt").read_text().splitlines()
                             if line.startswith("socks://")]
            self.assertEqual(len(scanner_lines), 1)
            self.assertIn("Автоподбор", urllib.parse.unquote(scanner_lines[0]))
            self.assertTrue((site / "sub" / "all.txt").exists())


if __name__ == "__main__":
    unittest.main()
