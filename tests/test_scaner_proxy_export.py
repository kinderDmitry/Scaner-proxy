import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

MODULE = Path(__file__).parents[1] / "scripts" / "build_catalog.py"
spec = importlib.util.spec_from_file_location("build_catalog_scaner_export", MODULE)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class ScanerProxyExportCompatibilityTests(unittest.TestCase):
    def test_reads_native_scaner_proxy_x_export_and_keeps_source_and_geo(self):
        exported = {
            "source": "Scaner Proxy X Android",
            "configs": [
                {"uri": "socks5://198.51.100.10:1080", "country": "Russia",
                 "countryCode": "RU", "city": "Moscow"},
                {"uri": "socks5://198.51.100.11:1080", "country": "",
                 "countryCode": "", "city": ""},
            ],
        }
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "upload.json"
            path.write_text(json.dumps(exported), encoding="utf-8")
            records = mod.fetch_uploaded(path)

        self.assertEqual(len(records), 2)
        self.assertEqual(records[0][0], "Scaner Proxy X Android")
        self.assertEqual(records[0][2], {
            "country": "Russia", "countryCode": "RU", "city": "Moscow"
        })
        self.assertEqual(records[1][2], {
            "country": "", "countryCode": "", "city": ""
        })

    def test_rejects_scaner_export_entries_without_supported_proxy_uri(self):
        exported = {"source": "Scaner Proxy X Android", "configs": [
            {"uri": "not-a-proxy", "country": "Russia", "countryCode": "RU", "city": "Moscow"}
        ]}
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "upload.json"
            path.write_text(json.dumps(exported), encoding="utf-8")
            records = mod.fetch_uploaded(path)
        self.assertEqual(records, [])


if __name__ == "__main__":
    unittest.main()
