#!/usr/bin/env python3

import importlib.util
from pathlib import Path
import unittest
from unittest import mock


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
            "html_url": "https://github.com/example/SprutHub.Helper/releases/tag/v0.3.0-beta.7",
        }
        apk = {"name": "SprutHub.Helper.apk"}
        message = notifier.build_message(release, apk, "a" * 64)
        self.assertIn("SprutHub Helper 0.3.0-beta.7", message)
        self.assertIn("• Исправлена &lt;панель&gt;", message)
        self.assertIn(
            '<a href="https://github.com/example/SprutHub.Helper/releases/tag/v0.3.0-beta.7">'
            "Полное описание релиза</a>",
            message,
        )
        self.assertLessEqual(len(message), notifier.MAX_TELEGRAM_CAPTION)

    def test_encodes_apk_as_multipart_document(self):
        boundary, payload = notifier.encode_multipart(
            {"chat_id": "@sprut_test", "caption": "Готово"},
            "document",
            "SprutHub.Helper.apk",
            b"APK-CONTENT",
        )
        self.assertIn(f"--{boundary}".encode(), payload)
        self.assertIn(b'name="document"; filename="SprutHub.Helper.apk"', payload)
        self.assertIn(b"application/vnd.android.package-archive", payload)
        self.assertIn(b"APK-CONTENT", payload)

    def test_sends_release_without_inline_keyboard(self):
        class SuccessfulResponse:
            def __enter__(self):
                return self

            def __exit__(self, exc_type, exc_value, traceback):
                return False

            def read(self):
                return b'{"ok": true}'

        with mock.patch.object(
            notifier.urllib.request,
            "urlopen",
            return_value=SuccessfulResponse(),
        ) as urlopen:
            notifier.send_telegram_document(
                "test-token",
                "@sprut_test",
                '📝 <a href="https://example.test/release">Полное описание релиза</a>',
                "SprutHub.Helper.apk",
                b"APK-CONTENT",
            )

        request = urlopen.call_args.args[0]
        self.assertEqual(
            urlopen.call_args.kwargs["timeout"],
            notifier.TELEGRAM_UPLOAD_TIMEOUT,
        )
        self.assertGreaterEqual(notifier.TELEGRAM_UPLOAD_TIMEOUT, 120)
        self.assertIn(b'name="caption"', request.data)
        self.assertIn(b"https://example.test/release", request.data)
        self.assertNotIn(b'name="reply_markup"', request.data)


if __name__ == "__main__":
    unittest.main()
