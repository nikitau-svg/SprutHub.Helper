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
from typing import Any


GITHUB_API = "https://api.github.com"
MAX_TELEGRAM_TEXT = 4000


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
    return apk, checksums[0]


def read_asset(asset: dict[str, Any], token: str, limit: int | None = None) -> bytes:
    with github_request(asset["url"], token, "application/octet-stream") as response:
        payload = response.read() if limit is None else response.read(limit + 1)
    if limit is not None and len(payload) > limit:
        raise ValueError(f"Asset {asset['name']} exceeds the allowed size")
    return payload


def verify_checksum(apk: dict[str, Any], checksum: dict[str, Any], token: str) -> str:
    checksum_text = read_asset(checksum, token, limit=4096).decode("utf-8", errors="strict")
    match = re.search(r"(?i)\b([0-9a-f]{64})\b", checksum_text)
    if not match:
        raise ValueError("SHA-256 asset does not contain a valid digest")
    expected = match.group(1).lower()

    digest = hashlib.sha256()
    with github_request(apk["url"], token, "application/octet-stream") as response:
        while chunk := response.read(1024 * 1024):
            digest.update(chunk)
    actual = digest.hexdigest()
    if actual != expected:
        raise ValueError(f"APK checksum mismatch: expected {expected}, got {actual}")
    return actual


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
    notes = truncate_notes(markdown_to_plain(str(release.get("body") or "")))
    parts = [
        f"🧪 <b>SprutHub Helper {html.escape(version)}</b>",
        "Новая beta прошла сборку и проверку и уже опубликована в GitHub.",
    ]
    if notes:
        parts.extend(["<b>Что изменилось</b>", html.escape(notes)])
    parts.extend(
        [
            f"📦 <code>{html.escape(str(apk['name']))}</code>",
            f"🔐 SHA-256: <code>{digest[:16]}…</code>",
            "Можно устанавливать поверх предыдущей beta — настройки приложения сохранятся.",
        ]
    )
    message = "\n\n".join(parts)
    if len(message) > MAX_TELEGRAM_TEXT:
        raise ValueError(f"Telegram message is too long: {len(message)} characters")
    return message


def write_summary(text: str) -> None:
    summary_path = os.getenv("GITHUB_STEP_SUMMARY")
    if summary_path:
        with open(summary_path, "a", encoding="utf-8") as summary:
            summary.write(f"{text}\n")


def send_telegram(bot_token: str, chat_id: str, text: str, apk_url: str, release_url: str) -> None:
    payload = {
        "chat_id": chat_id,
        "text": text,
        "parse_mode": "HTML",
        "disable_web_page_preview": True,
        "reply_markup": {
            "inline_keyboard": [
                [
                    {"text": "📥 Скачать APK", "url": apk_url},
                    {"text": "📝 Описание", "url": release_url},
                ]
            ]
        },
    }
    request = urllib.request.Request(
        f"https://api.telegram.org/bot{bot_token}/sendMessage",
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
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
    digest = verify_checksum(apk, checksum, github_token)
    message = build_message(release, apk, digest)

    if dry_run:
        print(message)
        write_summary("## Telegram dry run\n\n" + message)
        return 0

    if not bot_token or not chat_id:
        warning = "Telegram notification skipped: configure TELEGRAM_BOT_TOKEN and TELEGRAM_CHAT_ID Actions secrets."
        print(f"::warning::{warning}")
        write_summary("## Telegram notification skipped\n\n" + warning)
        return 0

    send_telegram(
        bot_token,
        chat_id,
        message,
        str(apk["browser_download_url"]),
        str(release["html_url"]),
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
