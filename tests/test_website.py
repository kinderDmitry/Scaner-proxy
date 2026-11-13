import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).parents[1]


class WebsiteScriptTests(unittest.TestCase):
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
