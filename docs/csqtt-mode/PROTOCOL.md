# Контракт движка CSQTT (v1)

Документ описывает взаимодействие qWDTT с апстрим-движком CSQTT.
Зафиксированная версия — **2.5.0**, тег `v2.5.0`, commit
`fd936ef087c34cc46f26111dc2361f43b8b03c07` (см.
`scripts/csqtt-engine.lock.json`). qWDTT не меняет контракт апстрима:
адаптер только отображает параметры профиля в argv/env и разбирает
stdout.

## 1. Запуск процесса

```kotlin
val pb = ProcessBuilder(command.argv)
    .directory(context.filesDir)
    .redirectErrorStream(true)
pb.environment().putAll(command.env)
val process = pb.start()
```

Бинарник лежит в `nativeLibraryDir/libcsqtt.so` и запускается как
обычный исполняемый файл (это ELF, а не JNI-библиотека). Рабочая
директория — `filesDir`, чтобы движок не зависел от прав на `cacheDir`.

## 2. Аргументы командной строки

CSQTT 2.5.0 принимает long-флаги в GNU-стиле (`--peer`). Для
совместимости со старыми вызовами движок нормализует одиночный дефис
(`-peer` → `--peer`); в новом коде используйте `--`.

| Аргумент | По умолчанию | Назначение |
|---|---|---|
| `--peer <host:port>` | пусто | Адрес CSQTT-сервера (UDP 46000) |
| `--tun-uds <name>` | пусто | Имя abstract UDS для передачи TUN fd |
| `-n`, `--workers <N>` | `18` | Число воркеров, 1…100, округляется до группы |
| `--runtime-threads <N>` | `0` (авто) | Потоки tokio runtime |
| `--vk <hash[,hash...]>` | пусто | Хеши звонков VK, до 6 |
| `--vk-hash-mode manual\|auto_api\|auto_js` | `manual` | Откуда брать хеши |
| `--vk-auth-mode vkcalls\|legacy\|auto_js` | `vkcalls` | Способ авторизации в VK |
| `--captcha-mode auto\|wv\|rjs` | `auto` | Режим капчи |
| `--obfs unified\|audio\|video` | `unified` | Маскировка TURN-трафика |
| `--turn-transport udp\|tcp_tls` | `udp` | Транспорт до TURN |
| `--srtp-mode auto\|dtls-srtp\|wrap-srtp` | `auto` | Шифрование медиаканала |
| `--fingerprint chrome\|firefox\|safari\|edge\|opera` | `chrome` | TLS-фингерпринт |
| `--client-ids <id,id>` | пусто | Идентификаторы клиента |
| `--custom-turn-ips <ip[:port],...>` | пусто | Свой пул TURN-адресов |
| `--gen <u64>` | `0` | Поколение конфигурации |
| `--salt <string>` | пусто | Соль HKDF (`CSQTT-WRAP-v1`) |
| `--listen <addr:port>` | `127.0.0.1:9000` | Локальный UDP; в TUN-режиме не открывается |
| `--port <port>` | пусто | Порт локального прокси (legacy) |
| `--turn <...>` | пусто | Явный TURN-сервер (legacy/диагностика) |
| `--validate-vk-hashes` | выкл. | Проверка хешей перед стартом |
| `--domain-routing` | выкл. | Доменный роутинг |
| `--exclude-ru-zones` | выкл. | Исключать российские зоны |
| `--template-domains` | выкл. | Шаблонные домены |
| `--domain-whitelist` | выкл. | Режим белого списка доменов |
| `--direct-domains <a,b>` | пусто | Домены напрямую |
| `--domains-file <path>` | — | Файл доменов (можно повторять) |
| `--ip-routing` | выкл. | IP-роутинг |
| `--ip-whitelist` | выкл. | Режим белого списка IP |
| `--direct-ips <a,b>` | пусто | IP напрямую |
| `--exact-workers` | выкл. | Не округлять число воркеров |

Минимальный набор для TUN-режима:

```text
--peer 203.0.113.10:46000
--tun-uds wdtt_csqtt_12345_1730000000000
-n 18
--vk <hash1,hash2>
--vk-hash-mode manual
--vk-auth-mode vkcalls
--captcha-mode auto
--obfs unified
--turn-transport udp
--srtp-mode auto
--fingerprint chrome
--gen <generation>
--salt <salt>
```

## 3. Окружение

| Переменная | Обязательность | Назначение |
|---|---|---|
| `CSQTT_PASSWORD` | да | Пароль подключения. Только env, не argv |
| `CSQTT_EVENTS=1` | да для qWDTT | Включает `__CSQTT_EVENT__|...` в stdout |
| `LD_LIBRARY_PATH` | да | `nativeLibraryDir` (на случай динамических зависимостей) |
| `RAYON_NUM_THREADS=2` | рекомендуется | Ограничивает CPU-пул |
| `RUST_BACKTRACE` | нет | Диагностика паник |

Пароль и хеши **не должны** попадать в logcat, argv-логи и сообщения об
ошибках. В логах допустимы только факт наличия пароля и количество
хешей.

## 4. stdout: события

При `CSQTT_EVENTS=1` движок печатает строки вида:

```text
__CSQTT_EVENT__|<TYPE>|<json>
```

Парсер обязан игнорировать обычные строки лога, неизвестные типы и
битый JSON. Префикс может быть внутри строки, если движок добавил
timestamp; адаптер ищет `__CSQTT_EVENT__|` и разбирает остаток.

| Тип | Payload | Комментарий |
|---|---|---|
| `PROCESS` | `{"pid":1234}` | PID запущенного движка |
| `READY` | `{"worker":N}` | Воркер готов |
| `DTLS` | `{"worker":N,"message":"..."}` | Статус DTLS (успех/fallback) |
| `WORKERS` | `{"workers":N}` | Серверный лимит воркеров, `0` — нет активных |
| `STATS` | `{"active":N,"bytes_up":N,"bytes_down":N}` | Периодическая статистика |
| `CONFIG` | `{"config":"TUNCONF:..."}` | Конфигурация TUN |
| `ROUTING` | `{"routing":"<json>"}` | Правила маршрутизации сервера |
| `ERROR` | `{"code":"...","message":"...","fatal":true}` | Ошибка; `fatal` — остановка |
| `ACTIVE_ZERO` | `{}` | Все воркеры потеряли активность |
| `TUN_LOST` | `{}` | TUN-интерфейс потерян, процесс завершится с кодом 3 |
| `STOPPED` | `{}` | Штатная остановка |
| `SERVER_RESTART` | `{"source":"panel"}` | Перезапуск со стороны панели |
| `NETWORK_SUSPECT` | `{}` | Серия сетевых таймаутов |
| `CALL_UNAVAILABLE` | `{"hash":"...","code":951}` | Хеш звонка недоступен |
| `PROGRESS` | `{"kind":"credentials"}` | Прогресс авторизации |

`STARTED` в CSQTT 2.5.0 не эмитится: признаком старта считайте
`PROCESS`, а готовности — первый `READY`/`CONFIG`.

Капча в 2.5.0 передаётся **не** JSON-событием, а отдельной строкой:

```text
CAPTCHA_SOLVE|<mode>|<redirect_uri>|<session_token>
```

`mode` — `wv` или `rjs`. После решения адаптер отправляет в stdin
`CAPTCHA_RESULT|<result>`; при отмене — `CAPTCHA_RESULT|` с пустым
значением.

## 5. stdin: управление

| Команда | Действие |
|---|---|
| `PAUSE` | Приостановить воркеры (экран выключен, экономия батареи) |
| `RESUME` | Возобновить воркеры |
| `STOP` | Штатная остановка |
| `FINISH_VK_CALLS` | Завершить JS-сессию VK |
| `CAPTCHA_RESULT\|<token>` | Результат капчи; секрет не логируется |
| `UPDATE_DOMAINS\|<payload>` | Обновить доменный роутинг |
| `VK_JS_BOOTSTRAP:<base64>` | Передать bootstrap только для `auto_js` |

`SCREEN_OFF`/`SCREEN_ON` в CSQTT 2.5.0 не поддерживаются: адаптер
обязан отображать состояние экрана в `PAUSE`/`RESUME`. Неизвестные
команды движок пишет в лог как `[STDIN] ...` и продолжает работу.

## 6. Передача TUN fd

1. Android генерирует имя `wdtt_csqtt_<pid>_<nano>` (без `@`).
2. Запускает движок с `--tun-uds <name>`.
3. Движок делает `bind` на abstract UDS и слушает.
4. Android открывает `LocalSocket` в namespace `ABSTRACT`, отправляет
   `ParcelFileDescriptor` через `SCM_RIGHTS`.
5. Движок переводит fd в nonblocking и отправляет один байт ACK `1`.
6. Android может не читать ACK: `TunFdBridge.sendOnce()` уже совместим.

В TUN-режиме локальный UDP-порт (`--listen`) не открывается. Если
абстрактный адрес занят, движок завершается с ошибкой
`Failed to bind abstract UDS ...: address in use`.

Имя сокета должно быть уникальным на процесс: при повторном
подключении старый сокет закрывается, а имя генерируется заново.

## 7. Внутренний протокол

### 7.1 Запрос конфигурации

```text
GETCONF:<local_port>|<password>|<generation>|<salt>|<worker_id>|<desired_count>
```

`desired_count` может быть пустым. Ответ — один из вариантов:

```text
TUNCONF:<client_ip>:<dns>[:<field>...]
NOCONF
DENIED:<reason>
```

`client_ip` (поле 0) и `dns` (поле 1) — единственные поля, на которые
опирается апстрим-клиент:

- `client_ip` — IPv4-адрес внутри туннеля (например `10.66.67.2`);
- `dns` — IPv4 DNS-сервер; допускается список через запятую
  (`TunVpnService` разбирает его как несколько адресов).

Остальные поля необязательны, и их позиции не гарантированы. В 2.5.0
встречаются `stream-v1`/`stream-v2`, порт клиента, `workers=N`,
`pad=...` и Base64-правила маршрутизации. Апстрим разбирает хвост как
`split(":", limit = 5)` и:

- сканирует **все** поля на `workers=N`;
- считает полем маршрутизации только индекс 4, если оно не начинается
  с `workers=` или `pad=`;
- декодирует Base64 (при необходимости gzip, `1f 8b`) и парсит JSON.

Поэтому адаптер обязан:

- принимать короткую форму `TUNCONF:<ip>:<dns>`;
- не привязывать `workers`/routing к фиксированному индексу;
- игнорировать неизвестные поля.

Значения по умолчанию: MTU `1500`, stream-режим считается `v1`,
`workers` — из профиля. Примеры, которые обязаны разбираться:
`TUNCONF:10.66.66.2:1.1.1.1` и
`TUNCONF:10.66.67.2:1.1.1.1:stream-v2`.

Причины `DENIED`:

| Причина | Сообщение для пользователя |
|---|---|
| `wrong_password` | Неверный пароль подключения |
| `expired` | Срок действия пароля истёк |
| `deactivated` | Аккаунт деактивирован |
| `protocol_mismatch` | Версия движка CSQTT не совпадает с сервером; обновите приложение |
| `invalid_worker_count` | Превышен лимит потоков |
| другое | Доступ отклонён сервером (`<reason>`) |

### 7.2 Служебные кадры

Внутри потока встречаются управляющие префиксы:

| Префикс | Назначение |
|---|---|
| `\xffCSQTT_STREAM_REPAIR_V1` | запрос восстановления потоков |
| `\xffCSQTT_STREAM_ALIVE_V1` | keepalive |
| `\xffCSQTT_PATH_ECHO_V1` | эхо пути |
| `ROUTING:` | правила маршрутизации (`START:`, `FINISH:`, чанки) |
| `WORKERS:` / `TUNWORKERS:` | лимиты воркеров |
| `READY_OK`, `OK:disconnected` | подтверждения управления |

У `STREAM_REPAIR`/`STREAM_ALIVE` после префикса идут 8 байт
big-endian `sequence`, 2 байта `desired_count`, 1 байт `count` и
`count × 2` байт идентификаторов воркеров.

Фрагментация: magic `0xCF`, 8-байтный заголовок
(`magic`, `frag_idx`, `total_frags`, `0`, `packet_id:u16`,
`total_len:u16`), payload ≤ 1272, пакет ≤ 2048 байт, до 32 фрагментов.
Шифрование: `dtls-srtp` (DTLS) или `wrap-srtp` (AEAD без DTLS). Эти
детали не нужны Kotlin-слою, но полезны при диагностике pcap.

## 8. Формат ссылки `csqtt://`

Ссылка — зашифрованный Base64URL-блок без padding:

```text
csqtt://<base64url(iv || ciphertext || tag)>
```

Параметры шифрования (совместимость с апстримом):

- ключ: `SHA-256("csqtt-chiper-key")` (опечатка `chiper` — часть
  формата), AES-256;
- режим: `AES/GCM/NoPadding`, tag 128 бит;
- IV: первые 12 байт блока;
- ciphertext: остальные байты;
- кодировка: Base64URL без `=`.

Расшифрованный текст:

```text
connect?v=2&host=<ip>&peer=<port>&password=<password>[&hashes=<h1>+<h2>]
```

или, для `auto_js`:

```text
connect?v=2&host=<ip>&peer=<port>&password=<password>&token=<vk_access_token>
```

Правила валидации (повторяют апстрим):

- `v` обязателен и равен `2`;
- `host` обязателен, не пустой, не `0.0.0.0`, не `127.0.0.1`, не
  `localhost`, не `::1`; пробелы запрещены;
- `peer` — целое `1..65535` (это **порт**, не `host:port`);
- `password` может быть пустым, но без пробелов;
- `hashes` — от 1 до 6 значений через `+`, каждое ≥ 16 символов, без
  пробелов и дублей; из значений вырезается префикс
  `https://vk.com/call/join/` и аналогичные;
- `token` — непустой, ≤ 4096 символов, без пробелов;
- `hashes` и `token` взаимоисключающие.

Итоговый peer для движка: `"$host:$port"`, для IPv6 — `"[$host]:$port"`
(в текущем формате host — IPv4 или имя, но валидатор должен быть готов
к IPv6).

## 9. Коды завершения

| Код | Значение | Действие qWDTT |
|---|---|---|
| `0` | Штатная остановка | Показать «Отключено» |
| `1` | Фатальная ошибка | Показать `ERROR.message`, остановить сессию |
| `3` | Потерян TUN | Пересоздать TUN и переподключиться по watchdog |

## 10. Ограничения текущего контракта

- `ROUTING` в v1 только логируется и сохраняется; применение маршрутов
  к `VpnService` — отдельный этап.
- Капча `wv`/`rjs` требует UI; без моста режим считается непригодным,
  если сервер требует капчу.
- Локальный SOCKS/UDP-прокси для CSQTT не используется.
- Формат `csqtt://` зашифрован статическим ключом; это не секрет и не
  замена TLS, а способ не портить ссылку спецсимволами.
