package com.wdtt.client.csqtt

import android.content.Context
import android.util.Log
import com.wdtt.client.CaptchaWebViewManager
import com.wdtt.client.ManlCaptchaWebViewManager
import kotlinx.coroutines.CancellationException

/**
 * Мост капчи CSQTT → существующие WebView-экраны qWDTT.
 *
 * Движок присылает `CAPTCHA_SOLVE|<mode>|<redirect_uri>|<session_token>` —
 * ровно тот же формат, что и Go-клиент WDTT, поэтому переиспользуются уже
 * проверенные на устройствах менеджеры:
 *
 *  - `auto`   — короткая скрытая попытка [CaptchaWebViewManager];
 *  - `manual` — видимый экран [ManlCaptchaWebViewManager];
 *  - неизвестный режим трактуется как ручной, чтобы пользователь точно мог
 *    довести подключение до конца.
 *
 * Возвращает токен для `CAPTCHA_RESULT|<token>`. Пустая строка означает
 * «капча не решена»: движок в этом случае нужно корректно остановить.
 */
object CsqttCaptchaBridge {
    private const val TAG = "CsqttCaptchaBridge"

    suspend fun solve(
        context: Context,
        mode: String,
        redirectUri: String,
        sessionToken: String,
    ): String {
        val normalized = mode.trim().lowercase()
        Log.i(TAG, "solve: mode=$normalized redirect=${safeRedirect(redirectUri)}")
        return when (normalized) {
            CsqttDefaults.CAPTCHA_MODE_AUTO -> solveAutoOrManual(context, redirectUri, sessionToken)
            CsqttDefaults.CAPTCHA_MODE_WV -> solveManual(context, redirectUri, sessionToken)
            else -> solveManual(context, redirectUri, sessionToken)
        }
    }

    /** Отмена текущего WebView-решения (stop/смена режима). */
    fun cancel() {
        runCatching { ManlCaptchaWebViewManager.cancelCaptcha() }
    }

    private suspend fun solveAutoOrManual(
        context: Context,
        redirectUri: String,
        sessionToken: String,
    ): String {
        return try {
            CaptchaWebViewManager.solveCaptchaAsync(redirectUri, sessionToken)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Слайдер/сложная капча — как и в WDTT, уходим в ручной WebView.
            Log.i(TAG, "auto captcha needs manual fallback: ${e.message}")
            solveManual(context, redirectUri, sessionToken)
        }
    }

    private suspend fun solveManual(
        context: Context,
        redirectUri: String,
        sessionToken: String,
    ): String = ManlCaptchaWebViewManager.solveCaptchaAsync(context, redirectUri, sessionToken)

    /**
     * redirect_uri — не секрет (это адрес страницы VK), но в лог попадает
     * только хост/путь без query: там может оказаться session_token.
     */
    private fun safeRedirect(uri: String): String {
        val trimmed = uri.trim()
        if (trimmed.isEmpty()) return "(empty)"
        val query = trimmed.indexOf('?')
        return if (query > 0) trimmed.substring(0, query) else trimmed
    }
}
