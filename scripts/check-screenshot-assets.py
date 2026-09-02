#!/usr/bin/env python3
"""Reject public screenshots that were not explicitly reviewed and cleaned."""

from __future__ import annotations

import argparse
import hashlib
import re
import struct
import sys
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
PRIVATE_METADATA_CHUNKS = {b"eXIf", b"iTXt", b"tEXt", b"tIME", b"zTXt"}
SAFE_FILE_NAME = re.compile(r"^[a-z0-9][a-z0-9._-]*\.png$")
CHECKSUM_LINE = re.compile(r"^([0-9a-f]{64})  ([a-z0-9][a-z0-9._-]*\.png)$")


class ScreenshotError(ValueError):
    pass


def png_chunks(payload: bytes) -> list[bytes]:
    if not payload.startswith(PNG_SIGNATURE):
        raise ScreenshotError("файл не является PNG")
    offset = len(PNG_SIGNATURE)
    chunks: list[bytes] = []
    saw_end = False
    while offset < len(payload):
        if offset + 12 > len(payload):
            raise ScreenshotError("повреждена таблица PNG-чанков")
        length = struct.unpack(">I", payload[offset : offset + 4])[0]
        chunk_type = payload[offset + 4 : offset + 8]
        end = offset + 12 + length
        if end > len(payload):
            raise ScreenshotError("повреждена длина PNG-чанка")
        chunks.append(chunk_type)
        offset = end
        if chunk_type == b"IEND":
            saw_end = True
            break
    if not saw_end or offset != len(payload):
        raise ScreenshotError("PNG содержит данные после IEND или не завершён")
    return chunks


def load_manifest(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ScreenshotError(
            f"нет {path.name}: изображения нельзя публиковать до ручного одобрения владельца"
        )
    approved: dict[str, str] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        match = CHECKSUM_LINE.fullmatch(line)
        if not match:
            raise ScreenshotError(f"некорректная строка {number} в {path.name}")
        digest, name = match.groups()
        if name in approved:
            raise ScreenshotError(f"дубликат {name} в {path.name}")
        approved[name] = digest
    return approved


def validate(directory: Path, manifest: Path) -> None:
    if not directory.exists():
        return
    files = sorted(path for path in directory.iterdir() if path.is_file() and path.name != manifest.name)
    if not files:
        if manifest.exists() and load_manifest(manifest):
            raise ScreenshotError("манифест одобрения содержит отсутствующие изображения")
        return

    invalid_names = [path.name for path in files if not SAFE_FILE_NAME.fullmatch(path.name)]
    if invalid_names:
        raise ScreenshotError(
            "разрешены только PNG с короткими английскими именами: " + ", ".join(invalid_names)
        )

    approved = load_manifest(manifest)
    actual_names = {path.name for path in files}
    approved_names = set(approved)
    if actual_names != approved_names:
        missing = sorted(actual_names - approved_names)
        stale = sorted(approved_names - actual_names)
        details = []
        if missing:
            details.append("не одобрены: " + ", ".join(missing))
        if stale:
            details.append("нет файлов: " + ", ".join(stale))
        raise ScreenshotError("; ".join(details))

    for path in files:
        payload = path.read_bytes()
        chunks = set(png_chunks(payload))
        private_chunks = sorted(chunk.decode("ascii") for chunk in chunks & PRIVATE_METADATA_CHUNKS)
        if private_chunks:
            raise ScreenshotError(
                f"{path.name} содержит метаданные {', '.join(private_chunks)}; экспортируйте чистый PNG"
            )
        digest = hashlib.sha256(payload).hexdigest()
        if digest != approved[path.name]:
            raise ScreenshotError(
                f"{path.name} изменён после одобрения: обновлять checksum можно только после нового просмотра"
            )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, default=Path("docs/screenshots"))
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    manifest = args.manifest or args.directory / "APPROVED.sha256"
    try:
        validate(args.directory, manifest)
    except ScreenshotError as error:
        print(f"Проверка публичных скриншотов не пройдена: {error}", file=sys.stderr)
        return 1
    print("Публичные скриншоты: состав, одобрение и PNG-метаданные проверены")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
