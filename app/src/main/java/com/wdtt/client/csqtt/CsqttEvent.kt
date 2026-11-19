package com.wdtt.client.csqtt

/**
 * Событие движка CSQTT, зеркало `rust-client/events.rs`.
 *
 * Движок пишет события в stdout как `__CSQTT_EVENT__|<TYPE>|<json>`. Набор
 * типов фиксирован контрактом; клиент обязан игнорировать неизвестные типы,
 * чтобы более новый движок не ломал старое приложение.
 */
sealed class CsqttEvent {

    /** Жизненный цикл: движок начал работу. */
    data class Started(val message: String = "") : CsqttEvent()

    /** Движок штатно завершился. */
    data class Stopped(val message: String = "") : CsqttEvent()

    /** PID дочернего процесса (нужен watchdog-у и guard-у). */
    data class Process(val pid: Int) : CsqttEvent()

    /** Воркер (TURN-поток) подключился и готов передавать данные. */
    data class Ready(val worker: Int, val message: String = "") : CsqttEvent()

    /** Диагностика DTLS-хендшейка конкретного воркера. */
    data class Dtls(val worker: Int, val message: String = "") : CsqttEvent()

    /** Ни одного активного воркера — соединение фактически мертво. */
    data class ActiveZero(val message: String = "") : CsqttEvent()

    /** VK-звонок недоступен; hash можно исключить из следующей попытки. */
    data class CallUnavailable(val hash: String, val code: Int) : CsqttEvent()

    /** Серия таймаутов TURN — похоже на проблему сети. */
    data class NetworkSuspect(val message: String = "") : CsqttEvent()

    /** Панель сервера перезапустилась; клиенту нужно переподключиться. */
    data class ServerRestart(val source: String = "panel") : CsqttEvent()

    /** Ответ сервера с конфигурацией TUN (обычно `TUNCONF:...`). */
    data class Config(val config: String) : CsqttEvent()

    /** Правила маршрутизации от сервера (JSON-строка). */
    data class Routing(val routing: String) : CsqttEvent()

    /** TUN-интерфейс потерян (exit code 3). */
    data object TunLost : CsqttEvent()

    /** Сервер сообщил лимит/количество воркеров. */
    data class Workers(val workers: Int) : CsqttEvent()

    /** Статистика трафика и активных воркеров. */
    data class Stats(val active: Int, val bytesUp: Long, val bytesDown: Long) : CsqttEvent()

    /** Ошибка движка; `fatal = true` — сессию нужно останавливать. */
    data class Error(val code: String, val message: String, val fatal: Boolean) : CsqttEvent()

    /** Нужна ручная капча (в v2.5.0 движок шлёт `CAPTCHA_SOLVE|`, событие — на будущее). */
    data class CaptchaRequest(
        val mode: String,
        val redirectUri: String,
        val sessionToken: String,
    ) : CsqttEvent()

    /** Капча завершена (событие на будущее, см. [CaptchaRequest]). */
    data class CaptchaDone(val success: Boolean, val error: String?) : CsqttEvent()

    /** Прогресс получения VK-креденшелов (`kind = credentials`). */
    data class Progress(val kind: String) : CsqttEvent()
}

/** Имена типов событий из `events.rs` (для сопоставления и логов). */
object CsqttEventType {
    const val STARTED = "STARTED"
    const val STOPPED = "STOPPED"
    const val PROCESS = "PROCESS"
    const val READY = "READY"
    const val DTLS = "DTLS"
    const val ACTIVE_ZERO = "ACTIVE_ZERO"
    const val CALL_UNAVAILABLE = "CALL_UNAVAILABLE"
    const val NETWORK_SUSPECT = "NETWORK_SUSPECT"
    const val SERVER_RESTART = "SERVER_RESTART"
    const val CONFIG = "CONFIG"
    const val ROUTING = "ROUTING"
    const val TUN_LOST = "TUN_LOST"
    const val WORKERS = "WORKERS"
    const val STATS = "STATS"
    const val ERROR = "ERROR"
    const val CAPTCHA_REQUEST = "CAPTCHA_REQUEST"
    const val CAPTCHA_DONE = "CAPTCHA_DONE"
    const val PROGRESS = "PROGRESS"
}
