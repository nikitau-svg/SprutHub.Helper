#!/usr/bin/env python3

import importlib.util
from pathlib import Path
import unittest


SCRIPT = Path(__file__).with_name("notify_telegram_release.py")
SPEC = importlib.util.spec_from_file_location("notify_telegram_release", SCRIPT)
notifier = importlib.util.module_from_spec(SPEC)
assert SPEC and SPEC.loader
SPEC.loader.exec_module(notifier)


class ReleaseNotifierTest(unittest.TestCase):
    def test_selects_matching_apk_and_checksum(self):
        release = {
            "assets": [
                {"name": "SprutHub.Helper.apk", "state": "uploaded", "size": 2_000_000},
                {"name": "SprutHub.Helper.apk.sha256", "state": "uploaded", "size": 100},
            ]
        }
        apk, checksum = notifier.select_release_assets(release)
        self.assertEqual(apk["name"], "SprutHub.Helper.apk")
        self.assertEqual(checksum["name"], "SprutHub.Helper.apk.sha256")

    def test_rejects_release_without_checksum(self):
        with self.assertRaisesRegex(ValueError, "does not contain"):
            notifier.select_release_assets(
                {"assets": [{"name": "SprutHub.Helper.apk", "state": "uploaded", "size": 2_000_000}]}
            )

    def test_converts_release_markdown_to_readable_text(self):
        source = "## Исправлено\n\n- **Панель** больше не закрывается.\n- [APK](https://example.test) готов."
        self.assertEqual(
            notifier.markdown_to_plain(source),
            "Исправлено\n\n• Панель больше не закрывается.\n• APK готов.",
        )

    def test_builds_safe_human_readable_message(self):
        release = {
            "tag_name": "v0.3.0-beta.7",
            "body": "- Исправлена <панель>",
        }
        apk = {"name": "SprutHub.Helper.apk"}
        message = notifier.build_message(release, apk, "a" * 64)
        self.assertIn("SprutHub Helper 0.3.0-beta.7", message)
        self.assertIn("• Исправлена &lt;панель&gt;", message)
        self.assertLessEqual(len(message), notifier.MAX_TELEGRAM_TEXT)


if __name__ == "__main__":
    unittest.main()
