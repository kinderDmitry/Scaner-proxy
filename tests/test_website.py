import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]


class WebsiteScriptTests(unittest.TestCase):
    def test_upload_is_github_native_and_has_no_cloudflare_dependencies(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("login/device/code", html)
        self.assertIn("incoming/upload.json", html)
        self.assertNotIn("workers.dev", html)
        self.assertNotIn("worker-url", html)
        self.assertNotIn("upload-token", html)

    def test_subscription_limit_selects_static_variant(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("all-' + limit + '.txt", html)
        self.assertIn("for limit in (0, 10, 25, 50, 100, 200, 500)", (ROOT / "scripts" / "build_catalog.py").read_text(encoding="utf-8"))

    def test_upload_parser_reports_empty_and_truncated_files_clearly(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("parseUploadFile", html)
        self.assertIn("Файл пустой", html)
        self.assertIn("JSON повреждён или обрезан", html)
        self.assertNotIn("JSON.parse(await file.text())", html)

    def test_upload_parser_validates_config_array_and_count(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("В JSON не найден массив configs", html)
        self.assertIn("не более 1000 конфигураций", html)
        self.assertIn("file.size", html)

    def test_inline_javascript_is_valid(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        scripts = re.findall(r"<script(?:\\s[^>]*)?>(.*?)</script>", html, flags=re.I | re.S)
        self.assertTrue(scripts, "website must contain its application script")
        for script in scripts:
            with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8") as source:
                source.write(script)
                source.flush()
                result = subprocess.run(["node", "--check", source.name], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
