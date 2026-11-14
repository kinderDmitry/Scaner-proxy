import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]


class WebsiteScriptTests(unittest.TestCase):
    def test_upload_is_github_native_and_has_no_cloudflare_dependencies(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertNotIn("login/device/code", html)
        self.assertNotIn("login/oauth/access_token", html)
        self.assertIn('id="github-token"', html)
        self.assertIn("incoming/upload.json", html)
        self.assertIn("github_pat_", html)
        self.assertIn("Contents: Read and write", html)
        self.assertNotIn("localStorage", html)
        self.assertNotIn("sessionStorage", html)
        self.assertNotIn("workers.dev", html)
        self.assertNotIn("worker-url", html)
        self.assertNotIn("upload-token", html)

    def test_subscription_limit_selects_static_variant(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("all-happ-' + limit + '.txt", html)
        self.assertIn("for limit in (0, 10, 25, 50, 100, 200, 500)", (ROOT / "scripts" / "build_catalog.py").read_text(encoding="utf-8"))

    def test_upload_parser_reports_empty_and_truncated_files_clearly(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("parseUploadFile", html)
        self.assertIn("Браузер действительно прочитал 0 байт", html)
        self.assertIn("JSON повреждён или обрезан", html)
        self.assertNotIn("JSON.parse(await file.text())", html)

    def test_upload_accepts_android_files_even_when_size_metadata_is_zero(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("readUploadBytes(file)", html)
        self.assertIn("file.stream().getReader()", html)
        self.assertIn("new FileReader()", html)
        self.assertNotIn("if (!file || !file.size)", html)
        self.assertIn("upload-file').addEventListener('change'", html)

    def test_upload_parser_validates_config_array_and_count(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("В JSON не найден массив configs", html)
        self.assertIn("не более 1000 конфигураций", html)
        self.assertIn("file.size", html)

    def test_upload_supports_pasted_json_when_android_file_picker_returns_zero_bytes(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn('id="upload-text"', html)
        self.assertIn("function parseUploadText(raw)", html)
        self.assertIn("Вставь содержимое JSON в резервное поле ниже", html)
        self.assertIn("if(pasted.trim()) parsedResult=parseUploadText(pasted)", html)

    def test_catalog_fetch_has_absolute_github_pages_fallback(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("https://kinderdmitry.github.io/Scaner-proxy/data.json", html)
        self.assertIn("Каталог недоступен и по резервному адресу", html)

    def test_upload_accepts_plain_text_proxy_lists(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("accept=".replace("=", "=\".json,.txt,.list,application/json,text/plain\""), html)
        self.assertIn("TXT-файл должен содержать ссылки", html)
        self.assertIn("parsed = { configs: lines }", html)

    def test_node_cards_show_flag_country_and_city(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("function locationLabel(n)", html)
        self.assertIn("n.countryCode", html)
        self.assertIn("n.country", html)
        self.assertIn("n.city", html)
        self.assertIn("Геолокация не определена", html)

    def test_inline_javascript_is_valid(self):
        for page in ("index.html", "scanner.html"):
            html = (ROOT / "website" / page).read_text(encoding="utf-8")
            scripts = re.findall(r"<script(?:\\s[^>]*)?>(.*?)</script>", html, flags=re.I | re.S)
            self.assertTrue(scripts, page + " must contain its application script")
            for script in scripts:
                with tempfile.NamedTemporaryFile("w", suffix=".js", encoding="utf-8") as source:
                    source.write(script)
                    source.flush()
                    result = subprocess.run(["node", "--check", source.name], capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, page + ": " + result.stderr)


    def test_homepage_exposes_two_independent_happ_subscriptions(self):
        html = (ROOT / "website" / "index.html").read_text(encoding="utf-8")
        self.assertIn("Подписка из загруженного JSON", html)
        self.assertIn("sub/all-happ.txt", html)
        self.assertIn("./sub/scanner-happ.txt", html)
        self.assertIn("./scanner.html", html)

    def test_public_scanner_page_shows_geo_filters_and_own_subscription(self):
        html = (ROOT / "website" / "scanner.html").read_text(encoding="utf-8")
        self.assertIn("data-scanner.json", html)
        self.assertIn("sub/scanner-happ.txt", html)
        self.assertIn("sub/scanner.txt", html)
        self.assertIn("scanner-happ-'+value+'.txt", html)
        self.assertIn("countryCode", html)
        self.assertIn("country", html)
        self.assertIn("city", html)
        self.assertIn("Автоподбор", html)


if __name__ == "__main__":
    unittest.main()
