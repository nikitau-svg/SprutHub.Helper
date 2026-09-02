#!/usr/bin/env python3

from __future__ import annotations

import base64
import hashlib
import importlib.util
import struct
import tempfile
import unittest
import zlib
from pathlib import Path


MODULE_PATH = Path(__file__).with_name("check-screenshot-assets.py")
SPEC = importlib.util.spec_from_file_location("check_screenshot_assets", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ScreenshotError = MODULE.ScreenshotError
validate = MODULE.validate


CLEAN_PNG = base64.b64decode(
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
)


def with_text_chunk(payload: bytes) -> bytes:
    chunk_type = b"tEXt"
    data = b"owner\x00private"
    chunk = struct.pack(">I", len(data)) + chunk_type + data
    chunk += struct.pack(">I", zlib.crc32(chunk_type + data) & 0xFFFFFFFF)
    return payload[:8] + chunk + payload[8:]


class ScreenshotAssetCheckTest(unittest.TestCase):
    def test_accepts_exactly_approved_metadata_free_png(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            image = directory / "home-ready.png"
            image.write_bytes(CLEAN_PNG)
            (directory / "APPROVED.sha256").write_text(
                f"{hashlib.sha256(CLEAN_PNG).hexdigest()}  {image.name}\n",
                encoding="utf-8",
            )
            validate(directory, directory / "APPROVED.sha256")

    def test_rejects_unapproved_png(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            (directory / "home-ready.png").write_bytes(CLEAN_PNG)
            with self.assertRaisesRegex(ScreenshotError, "нет APPROVED.sha256"):
                validate(directory, directory / "APPROVED.sha256")

    def test_rejects_private_metadata_even_when_checksum_matches(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            payload = with_text_chunk(CLEAN_PNG)
            image = directory / "home-ready.png"
            image.write_bytes(payload)
            (directory / "APPROVED.sha256").write_text(
                f"{hashlib.sha256(payload).hexdigest()}  {image.name}\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ScreenshotError, "метаданные tEXt"):
                validate(directory, directory / "APPROVED.sha256")

    def test_rejects_changed_image_after_approval(self) -> None:
        with tempfile.TemporaryDirectory() as raw:
            directory = Path(raw)
            image = directory / "home-ready.png"
            image.write_bytes(CLEAN_PNG)
            (directory / "APPROVED.sha256").write_text(
                f"{'0' * 64}  {image.name}\n",
                encoding="utf-8",
            )
            with self.assertRaisesRegex(ScreenshotError, "изменён после одобрения"):
                validate(directory, directory / "APPROVED.sha256")


if __name__ == "__main__":
    unittest.main()
