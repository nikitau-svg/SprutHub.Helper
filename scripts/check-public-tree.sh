#!/usr/bin/env bash
set -euo pipefail

failed=0

while IFS= read -r tracked_file; do
  lower_file="$(printf '%s' "$tracked_file" | tr '[:upper:]' '[:lower:]')"
  case "$lower_file" in
    *.apk|*.aab|*.apks|*.jks|*.keystore|*.p12|*.pfx|*.hprof|*.log|*.db|*.sqlite|*.sqlite3|*.env|*.env.*|.env|.env.*|key.properties|keystore.properties)
      echo "Запрещённый публичный файл: $tracked_file" >&2
      failed=1
      ;;
  esac
done < <(git ls-files --cached --others --exclude-standard)

scan_files() {
  local title="$1"
  local pattern="$2"
  local matches
  matches="$(
    git grep -IlE -e "$pattern" -- \
      ':!scripts/check-public-tree.sh' \
      ':!docs/REPOSITORY_AUDIT.md' || true
  )"
  if [[ -n "$matches" ]]; then
    echo "$title:" >&2
    while IFS= read -r matched_file; do
      echo "  $matched_file" >&2
    done <<< "$matches"
    failed=1
  fi
}

scan_files \
  "Найден возможный закрытый ключ или токен" \
  '-----BEGIN ([A-Z0-9 ]+ )?PRIVATE KEY-----|github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{20,}|[0-9]{8,}:[A-Za-z0-9_-]{30,}'

scan_files \
  "Найден абсолютный путь с рабочей машины" \
  '/Users/[^/[:space:]]+/|/var/folders/|[A-Za-z]:\\Users\\'

scan_files \
  "Найден похожий на реальный hub ID в URL" \
  '/hubs/[A-Fa-f0-9]{16}([^A-Fa-f0-9]|$)'

scan_files \
  "Найден жёстко заданный идентификатор хаба" \
  '(DEFAULT_SERIAL|DEFAULT_HUB_ID)[[:space:]]*=[[:space:]]*"[A-Za-z0-9-]{8,}"'

if (( failed != 0 )); then
  echo "Публичный аудит не пройден. Удалите значение из Git и при необходимости отзовите секрет." >&2
  exit 1
fi

python3 scripts/test_check_screenshot_assets.py
python3 scripts/check-screenshot-assets.py
python3 scripts/check-markdown-links.py
echo "Публичное дерево и локальные ссылки проверены"
