# Процесс веток и выпусков

## Модель веток

`main` содержит только проверенный код, который можно выпускать. `beta` принимает обычные изменения и автоматически создаёт тестовый APK. Рабочие ветки открывают pull request в `beta`; после совместного тестирования `beta` сливается в `main` отдельным pull request.

После принятия этого патча и при чистом рабочем дереве постоянная beta-ветка создаётся от актуальной `main` один раз:

```bash
git switch main
git pull --ff-only origin main
git switch -c beta
git push -u origin beta
```

Для обеих постоянных веток рекомендуется включить ruleset:

- pull request обязателен;
- проверка `build` из workflow **Android CI** обязательна;
- ветка должна быть актуальна перед слиянием;
- force-push и удаление запрещены;
- хотя бы одно одобрение для `main`;
- теги `v*` нельзя перемещать или удалять без отдельного административного решения.

## Beta

Каждый push в `beta` запускает lint, unit-тесты и `assembleDebug`, затем сохраняет переименованный APK и его SHA-256 как GitHub Actions artifact на 14 дней. В workflow нет пользовательских секретов и signing-keystore.

Debug APK подписывается стандартным временным ключом CI. Подпись может меняться между запусками, поэтому обновление поверх предыдущей beta может не установиться. Это тестовый артефакт, а не стабильный релиз.

## Подготовка стабильной версии

Перед первым выпуском по новой схеме удалите устаревший Release `v0.1.0` и связанные с ним assets: его исходный commit содержит персональные значения по умолчанию. Удаление Release не очищает Git-историю — вопрос переписывания истории решается отдельно перед изменением видимости репозитория.

1. Убедиться, что beta проверена на реальном устройстве и совместимом SprutHub.
2. Обновить `versionCode` и `versionName` в отдельном pull request. `versionCode` всегда увеличивается.
3. Перенести пункты из `Unreleased` в новый раздел CHANGELOG с датой.
4. Слить pull request `beta` → `main` после зелёного CI.
5. Создать аннотированный тег `vX.Y.Z`, точно совпадающий с `versionName`.

Для уже проверенной версии `0.2.0` команды выглядели бы так (выполнять только после фактического слияния beta в `main`):

```bash
git switch main
git pull --ff-only origin main
git tag -a v0.2.0 -m "SprutHub Helper 0.2.0"
git push origin v0.2.0
```

Push тега запускает workflow **Release candidate**. Он повторяет проверки, собирает неподписанный release APK и создаёт **draft**, а не публичный релиз. Workflow не читает signing-секреты. Неподписанный APK нельзя публиковать как готовый файл.

Workflow принимает тег только в формате `vX.Y.Z`, проверяет совпадение с `versionName` и отклоняет тег, если его коммит не входит в историю `main`.

## Локальная подпись

Постоянный release-keystore хранится вне репозитория и вне синхронизируемой папки. Нужны как минимум две зашифрованные резервные копии с раздельным доступом. Потеря ключа не позволит устанавливать обновления поверх уже выпущенного APK.

Если ключ создаётся впервые, не передавайте пароль в аргументах команды — `keytool` запросит его интерактивно:

```bash
keytool -genkeypair -v \
  -keystore /absolute/private/path/spruthub-helper-release.p12 \
  -storetype PKCS12 \
  -alias spruthub-helper \
  -keyalg RSA -keysize 4096 -validity 10000
```

Существующий ключ стабильного приложения нельзя заменять. Для локальной сборки Gradle уже поддерживает переменные окружения:

```bash
export ANDROID_SIGNING_STORE_FILE="/absolute/private/path/spruthub-helper-release.p12"
export ANDROID_SIGNING_KEY_ALIAS="spruthub-helper"
read -r -s ANDROID_SIGNING_STORE_PASSWORD
export ANDROID_SIGNING_STORE_PASSWORD
read -r -s ANDROID_SIGNING_KEY_PASSWORD
export ANDROID_SIGNING_KEY_PASSWORD

gradle --no-daemon :app:clean :app:lintRelease :app:testDebugUnitTest :app:assembleRelease

unset ANDROID_SIGNING_STORE_PASSWORD ANDROID_SIGNING_KEY_PASSWORD
```

Перед вычислением контрольной суммы переименуйте копию APK в `SprutHub.Helper-vX.Y.Z.apk`, затем проверьте подпись и запишите SHA-256:

```bash
apksigner verify --verbose --print-certs SprutHub.Helper-vX.Y.Z.apk
shasum -a 256 SprutHub.Helper-vX.Y.Z.apk > SprutHub.Helper-vX.Y.Z.apk.sha256
```

Сравните сертификат с предыдущим стабильным релизом.

## Публикация draft-релиза

1. Скачать или просмотреть неподписанный candidate только для сверки версии; не публиковать его.
2. Локально собрать release из того же тега с постоянным ключом.
3. Проверить подпись, установку и базовые сценарии: local, cloud fallback, Device Controls, плитка и ручная Health-синхронизация.
4. Удалить неподписанный asset из draft.
5. Прикрепить подписанный APK и его `.sha256`.
6. Скопировать проверенный раздел CHANGELOG в release notes и только затем опубликовать release.

Ни keystore, ни его base64-копия, ни пароли не должны попадать в GitHub Secrets, Actions artifacts, логи, release assets или git. Если позже потребуется полностью автоматическая подпись, это отдельное изменение с аппаратным или специализированным хранилищем ключей и обязательным security review.
