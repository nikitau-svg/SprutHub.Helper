#!/usr/bin/env python3
"""Verify a published beta release and announce it through Telegram Bot API."""

from __future__ import annotations

import hashlib
import html
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from typing import Any


GITHUB_API = "https://api.github.com"
MAX_TELEGRAM_CAPTION = 1000
MAX_TELEGRAM_UPLOAD = 50 * 1024 * 1024
TELEGRAM_UPLOAD_TIMEOUT = 180


def github_request(url: str, token: str, accept: str = "application/vnd.github+json"):
    headers = {
        "Accept": accept,
        "User-Agent": "SprutHub-Helper-release-notifier",
        "X-GitHub-Api-Version": "2022-11-28",
    }
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60)


def load_release(repository: str, tag: str, token: str) -> dict[str, Any]:
    encoded_tag = urllib.parse.quote(tag, safe="")
    url = f"{GITHUB_API}/repos/{repository}/releases/tags/{encoded_tag}"
    with github_request(url, token) as response:
        return json.load(response)


def select_release_assets(release: dict[str, Any]) -> tuple[dict[str, Any], dict[str, Any]]:
    assets = [asset for asset in release.get("assets", []) if asset.get("state") == "uploaded"]
    apks = [asset for asset in assets if str(asset.get("name", "")).lower().endswith(".apk")]
    if len(apks) != 1:
        raise ValueError(f"Expected exactly one uploaded APK asset, found {len(apks)}")

    apk = apks[0]
    checksum_name = f"{apk['name']}.sha256"
    checksums = [asset for asset in assets if asset.get("name") == checksum_name]
    if len(checksums) != 1:
        raise ValueError(f"Release does not contain {checksum_name}")
    if int(apk.get("size", 0)) < 1_000_000:
        raise ValueError("APK asset is unexpectedly small")
    if int(apk.get("size", 0)) > MAX_TELEGRAM_UPLOAD:
        raise ValueError("APK asset exceeds Telegram Bot API's 50 MB upload limit")
    return apk, checksums[0]


def read_asset(asset: dict[str, Any], token: str, limit: int | None = None) -> bytes:
    with github_request(asset["url"], token, "application/octet-stream") as response:
        payload = response.read() if limit is None else response.read(limit + 1)
    if limit is not None and len(payload) > limit:
        raise ValueError(f"Asset {asset['name']} exceeds the allowed size")
    return payload


def verify_checksum(apk: dict[str, Any], checksum: dict[str, Any], token: str) -> tuple[str, bytes]:
    checksum_text = read_asset(checksum, token, limit=4096).decode("utf-8", errors="strict")
    match = re.search(r"(?i)\b([0-9a-f]{64})\b", checksum_text)
    if not match:
        raise ValueError("SHA-256 asset does not contain a valid digest")
    expected = match.group(1).lower()

    apk_payload = read_asset(apk, token, limit=MAX_TELEGRAM_UPLOAD)
    actual = hashlib.sha256(apk_payload).hexdigest()
    if actual != expected:
        raise ValueError(f"APK checksum mismatch: expected {expected}, got {actual}")
    return actual, apk_payload


def markdown_to_plain(text: str) -> str:
    text = re.sub(r"```.*?```", "", text or "", flags=re.DOTALL)
    text = re.sub(r"!\[([^]]*)]\([^)]*\)", r"\1", text)
    text = re.sub(r"\[([^]]+)]\([^)]*\)", r"\1", text)
    text = re.sub(r"^[ \t]{0,3}#{1,6}[ \t]*", "", text, flags=re.MULTILINE)
    text = re.sub(r"^[ \t]*[-*+][ \t]+", "• ", text, flags=re.MULTILINE)
    text = re.sub(r"[*_~`]", "", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def truncate_notes(notes: str, limit: int = 2300) -> str:
    if len(notes) <= limit:
        return notes
    shortened = notes[: limit - 1].rsplit("\n", 1)[0].rstrip()
    if not shortened:
        shortened = notes[: limit - 1].rstrip()
    return f"{shortened}…"


def build_message(release: dict[str, Any], apk: dict[str, Any], digest: str) -> str:
    tag = str(release["tag_name"])
    version = tag.removeprefix("v")
    release_url = html.escape(str(release["html_url"]), quote=True)
    notes = truncate_notes(markdown_to_plain(str(release.get("body") or "")), limit=520)
    parts = [
        f"🧪 <b>SprutHub Helper {html.escape(version)}</b>",
        "Новая beta прошла проверку и готова к установке.",
    ]
    if notes:
        parts.extend(["<b>Коротко об обновлении</b>", html.escape(notes)])
    parts.extend(
        [
            f"📦 <code>{html.escape(str(apk['name']))}</code>",
            f"🔐 SHA-256: <code>{digest[:16]}…</code>",
            "Устанавливается поверх предыдущей beta без сброса настроек.",
            f'📝 <a href="{release_url}">Полное описание релиза</a>',
        ]
    )
    message = "\n\n".join(parts)
    if len(message) > MAX_TELEGRAM_CAPTION:
        raise ValueError(f"Telegram document caption is too long: {len(message)} characters")
    return message


def write_summary(text: str) -> None:
    summary_path = os.getenv("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(f"{text}\n")


def encode_multipart(
    fields: dict[str, str],
    file_field: str,
    file_name: str,
    file_payload: bytes,
) -> tuple[str, bytes]:
    boundary = f"SprutHubHelper{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            ]
        )
    safe_name = file_name.replace('"', "")
    chunks.extend(
        [
            f"--{boundary}\r\n".encode(),
            (
                f'Content-Disposition: form-data; name="{file_field}"; filename="{safe_name}"\r\n'
                "Content-Type: application/vnd.android.package-archive\r\n\r\n"
            ).encode(),
            file_payload,
            b"\r\n",
            f"--{boundary}--\r\n".encode(),
        ]
    )
    return boundary, b"".join(chunks)


def send_telegram_document(
    bot_token: str,
    chat_id: str,
    caption: str,
    apk_name: str,
    apk_payload: bytes,
) -> None:
    boundary, payload = encode_multipart(
        {
            "chat_id": chat_id,
            "caption": caption,
            "parse_mode": "HTML",
        },
        "document",
        apk_name,
        apk_payload,
    )
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{bot_token}/sendDocument",
        data=payload,
        headers={"Content-Type": f"multipart/form-data; boundary={boundary}"},
        method="POST",
    )
    try:
        # GitHub-hosted runners can need more than 30 seconds to upload an APK.
        # Keep this as one long attempt: retrying an ambiguous timed-out POST can
        # publish the same document twice if Telegram received the first request.
        with urllib.request.urlopen(request, timeout=TELEGRAM_UPLOAD_TIMEOUT) as response:
            result = json.load(response)
    except urllib.error.HTTPError as error:
        details = error.read(2048).decode("utf-8", errors="replace")
        raise RuntimeError(f"Telegram API returned HTTP {error.code}: {details}") from None
    if not result.get("ok"):
        raise RuntimeError(f"Telegram API rejected the message: {result.get('description', 'unknown error')}")


def is_true(value: str | None) -> bool:
    return str(value or "").lower() in {"1", "true", "yes", "on"}


def main() -> int:
    repository = os.getenv("GITHUB_REPOSITORY", "").strip()
    tag = os.getenv("RELEASE_TAG", "").strip()
    github_token = os.getenv("GH_TOKEN", "").strip()
    bot_token = os.getenv("TELEGRAM_BOT_TOKEN", "").strip()
    chat_id = os.getenv("TELEGRAM_CHAT_ID", "").strip()
    dry_run = is_true(os.getenv("TELEGRAM_DRY_RUN"))

    if not repository or not tag:
        raise ValueError("GITHUB_REPOSITORY and RELEASE_TAG are required")

    release = load_release(repository, tag, github_token)
    if release.get("draft"):
        raise ValueError("Telegram notification is allowed only for a published release")
    if not release.get("prerelease") or not re.search(r"-beta\.\d+$", str(release.get("tag_name", ""))):
        raise ValueError("Telegram notification is allowed only for a numbered beta prerelease")

    apk, checksum = select_release_assets(release)
    digest, apk_payload = verify_checksum(apk, checksum, github_token)
    message = build_message(release, apk, digest)

    if dry_run:
        print(message)
        print(f"\nAttachment: {apk['name']} ({len(apk_payload)} bytes)")
        write_summary("## Telegram dry run\n\n" + message + f"\n\nAttachment: `{apk['name']}`")
        return 0

    if not bot_token or not chat_id:
        warning = "Telegram notification skipped: configure TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID Actions secrets."
        print(f"::warning::{warning}")
        write_summary("## Telegram notification skipped\n\n" + warning)
        return 0

    send_telegram_document(
        bot_token,
        chat_id,
        message,
        str(apk["name"]),
        apk_payload,
    )
    write_summary(f"## Telegram notification sent\n\nAnnounced `{tag}` after verifying its APK checksum.")
    print(f"Telegram notification sent for {tag}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (ValueError, RuntimeError, urllib.error.URLError) as error:
        print(f"::error::{error}", file=sys.stderr)
        raise SystemExit(1)
