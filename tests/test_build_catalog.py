import base64
import importlib.util
import json
import tempfile
import unittest
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

    def test_rejects_untrusted_feed_hosts(self):
        self.assertTrue(mod.validate_source_url("https://raw.githubusercontent.com/u/r/main/list.txt"))
        self.assertFalse(mod.validate_source_url("https://example.com/list.txt"))
        self.assertFalse(mod.validate_source_url("http://raw.githubusercontent.com/u/r/main/list.txt"))


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
                    "test": "HTTPS 204 через туннель", "verified": True}
            mod.write_outputs([good], site)
            text = (site / "sub" / "all.txt").read_text()
            self.assertIn(good["uri"], text)
            self.assertIn("#profile-update-interval: 6", text)
            data = json.loads((site / "data.json").read_text())
            self.assertEqual(data["total"], 1)
            self.assertEqual(data["protocols"]["SOCKS5"], 1)

    def test_empty_run_preserves_existing_catalog(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            sources = root / "sources.json"
            sources.write_text('{"sources":[]}')
            site = root / "site"
            site.mkdir()
            current = site / "data.json"
            current.write_text("previous-good-catalog")
            with self.assertRaises(RuntimeError):
                mod.build(sources, "missing-sing-box", site)
            self.assertEqual(current.read_text(), "previous-good-catalog")


if __name__ == "__main__":
    unittest.main()
