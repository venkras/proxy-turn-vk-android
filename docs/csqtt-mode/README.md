# Режим CSQTT в qWDTT

Статус: **S0–S1 (документация и сборочный конвейер)**. Runtime-адаптер,
профили и UI появятся в следующих PR. Сейчас флаг `-PcsqttEngine=true`
только добавляет бинарник движка в APK; существующие режимы WDTT
(`vpn`, `rawtun`, `socks`) не меняются.

## Что это

CSQTT — отдельный клиентский движок на Rust, который подключается к
CSQTT-серверам через TURN-инфраструктуру звонков VK. В qWDTT он
рассматривается как **второй, независимый протокол подключения**, а не
замена существующего Go-движка `go_client`.

| | qWDTT (WDTT) | CSQTT |
|---|---|---|
| Движок | `go_client` | `csqtt-client` (Rust) |
| Имя бинарника в APK | `libclient.so` | `libcsqtt.so` |
| TUN fd | abstract UDS, движок слушает | abstract UDS, движок слушает + ACK-байт |
| Локальный UDP | `127.0.0.1:<port>` | в TUN-режиме не открывается |
| Пароль | аргумент `-password` | env `CSQTT_PASSWORD` |
| Wrap salt | `WDTT-WRAP-v1` | `CSQTT-WRAP-v1` |
| Порт сервера | 56000 (DTLS), 56001 (WG), 56003 (raw) | 46000 (UDP), 46002 (web) |
| Внутренний протокол | `GETCONF` + WireGuard / `RAWCONF` | `GETCONF` + `TUNCONF` + stream-v1/v2 |

Один движок не может обслуживать оба протокола, поэтому протокол
выбирается явно в профиле. Значение по умолчанию — `wdtt`, старые
профили и подписки продолжают работать без миграции.

## Схема процессов

```text
┌────────────────────────── qWDTT (Kotlin) ──────────────────────────┐
│                                                                    │
│  TunnelManager ──start──> RawTunVpnService (VpnService)            │
│       │                        │                                   │
│       │                        │ TUN fd                            │
│       │                        ▼                                   │
│       │                  TunFdBridge.sendOnce()                    │
│       │                        │ SCM_RIGHTS                         │
│       ▼                        ▼                                   │
│  ProcessBuilder ──────> libcsqtt.so (Rust, отдельный процесс)      │
│       ▲                        │                                   │
│       │ stdout: события        │ TURN/UDP 46000                    │
│       └────────────────────────┼──────────────> CSQTT-сервер       │
│                                │                                   │
│  stdin: PAUSE/RESUME/STOP      │                                   │
│         CAPTCHA_RESULT|...     │                                   │
└────────────────────────────────┼───────────────────────────────────┘
                                 ▼
                         интернет через VPS
```

Движок — сервер abstract UDS; Android подключается и передаёт файловый
дескриптор TUN через `SCM_RIGHTS`. Это тот же паттерн, что уже
используется в `RawTunEngine` + `TunFdBridge`, поэтому отдельный
`VpnService` не нужен.

## Поставка движка

Исходники CSQTT **не коммитятся** в qWDTT: лицензия апстрима
PolyForm-Noncommercial-1.0.0 несовместима с GPL-3.0 на уровне исходного
кода. Вместо этого:

- `scripts/csqtt-engine.lock.json` фиксирует тег, commit, версию Rust,
  NDK, API и SHA-256 архива;
- `scripts/build-csqtt-engine.sh` клонирует pinned-тег в
  `build/csqtt-engine/src` (gitignored), собирает выбранные ABI и кладёт
  `app/src/main/jniLibs/<abi>/libcsqtt.so`;
- `scripts/csqtt-engine-provenance.sh` пишет
  `app/src/main/assets/csqtt-engine.json` с версией, commit, размерами и
  SHA-256 каждого бинарника;
- `scripts/verify-csqtt-engine.sh` проверяет архитектуру, отсутствие
  `libc++_shared.so`, бюджет размера, выравнивание страниц и целостность
  provenance-файла;
- `scripts/verify-license.sh` не даёт собрать APK с движком без текста
  лицензии и уведомлений.

Локальная сборка:

```bash
# все ABI из lock-файла
bash scripts/build-csqtt-engine.sh

# только arm64
CSQTT_TARGET_ABIS=arm64-v8a bash scripts/build-csqtt-engine.sh

# локальные исходники CSQTT вместо клонирования
CSQTT_ENGINE_DIR=../csqtt-main bash scripts/build-csqtt-engine.sh

# APK с движком
./gradlew :app:assembleDebug -PcsqttEngine=true -PtargetAbis=arm64-v8a
```

Требования к окружению: Rust `1.97.1`, `cargo-ndk` `4.1.2`,
NDK `27.2.12479018`, CMake, Ninja, `jq`, Git. Подробности — в
`docs/csqtt-mode/DEVELOPMENT.md` (появится в S6).

## Ограничения v1

- Только клиент: развёртывание CSQTT-сервера и веб-панель вне scope.
- Только протокол CSQTT; старые WDTT-серверы в этом режиме не
  поддерживаются.
- `ROUTING:` от сервера в v1 логируется и сохраняется, но маршруты
  `VpnService` по ним не перестраиваются.
- SOCKS-режим для CSQTT не поддерживается.
- Мост капчи (`S3.5`) включается только если анонимный путь `vkcalls`
  требует капчу в целевом регионе.
- На устройстве может быть только один активный `VpnService`; режимы
  взаимоисключающие.

## Совместимость

| Профиль | Сервер | Режим qWDTT | Ожидание |
|---|---|---|---|
| Старый WDTT | 56000/56001 | WG | Работает без изменений |
| Старый WDTT | `-listen-raw` 56003 | Raw | Работает без изменений |
| Старый WDTT | любой | SOCKS | Работает без изменений |
| CSQTT | 46000 | CSQTT | Новый режим |
| CSQTT | 46000 | WG/Raw | Понятная ошибка «сервер не поддерживает WDTT» |
| CSQTT | 46000 | SOCKS | Вне scope (v1) |

## Документация

- [PROTOCOL.md](PROTOCOL.md) — контракт движка, события, `csqtt://`.
- [LICENSE-NOTES.md](LICENSE-NOTES.md) — лицензионная модель и
  уведомления.
- [../../THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md) — сводные
  уведомления сторонних компонентов.

## Лицензия

CSQTT распространяется по PolyForm Noncommercial 1.0.0, автор
`amurcanov`, апстрим: <https://github.com/amurcanov/csqtt>. Коммерческое
использование движка требует отдельного письменного разрешения автора.
Текст лицензии включён в APK: `assets/licenses/CSQTT-LICENSE`.
