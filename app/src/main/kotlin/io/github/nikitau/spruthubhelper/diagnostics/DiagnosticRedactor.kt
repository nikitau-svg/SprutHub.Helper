package io.github.nikitau.spruthubhelper.diagnostics

/**
 * Defence-in-depth redaction for the persistent journal and every export.
 *
 * Structured values with sensitive keys are removed by construction. The text
 * patterns additionally protect reason strings and legacy diagnostic messages.
 */
object DiagnosticRedactor {
    const val REDACTED = "[скрыто]"

    private const val MAX_EVENT_LENGTH = 160
    private const val MAX_REASON_LENGTH = 480
    private const val MAX_DETAIL_KEY_LENGTH = 80
    private const val MAX_DETAIL_VALUE_LENGTH = 320
    private const val MAX_DETAILS = 24

    private val emailPattern = Regex(
        "(?i)\\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}\\b",
    )
    private val urlPattern = Regex(
        "(?i)\\b(https?|wss?)://([^\\s/?#]+)([/\\?#][^\\s]*)?",
    )
    private val labelledSensitiveValuePattern = Regex(
        "(?i)\\b(password|passwd|pwd|passphrase|token|secret|authorization|auth|cookie|api[_ -]?key|" +
            "парол[ья]|токен|секрет|авторизаци[яи]|" +
            "id|(?:a|s|c|hub|room|accessory|service|characteristic|control|device|user)[_ -]?id|hubid|" +
            "serial(?:[_ -]?(?:number|no))?|" +
            "серийн(?:ый|ого)[_ -]?(?:номер|номера)?|идентификатор[_ -]?хаба|" +
            "latitude|longitude|lat|lon|lng|coordinates?|широта|долгота|координат[аы]?|" +
            "heart[_ -]?rate|pulse|steps?|sleep|weight|oxygen(?:[_ -]?saturation)?|" +
            "blood[_ -]?pressure|active[_ -]?calories|body[_ -]?temperature|" +
            "respiratory[_ -]?rate|hrv|health[_ -]?(?:value|metric)|" +
            "пульс|шаги|сон|вес|кислород|давление|калории|температура[_ -]?тела|" +
            "частота[_ -]?дыхания|вариабельность[_ -]?пульса)" +
            "(\\s*[\"']?\\s*[:=]\\s*[\"']?)([^\\s,;}&\"']+)",
    )
    private val bearerPattern = Regex(
        "(?i)\\b(Bearer|Basic)(\\s+)[A-Z0-9._~+/=-]+",
    )
    private val uuidPattern = Regex(
        "(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b",
    )
    private val jwtPattern = Regex(
        "\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}(?:\\.[A-Za-z0-9_-]{8,})?\\b",
    )
    private val longIdentifierPattern = Regex(
        "\\b(?=[A-Za-z0-9_+/=-]{24,}\\b)(?=[A-Za-z0-9_+/=-]*[A-Za-z])(?=[A-Za-z0-9_+/=-]*[0-9])[A-Za-z0-9_+/=-]+\\b",
    )
    private val coordinatePairPattern = Regex(
        "(?<![\\d.])[+-]?\\d{1,3}\\.\\d{4,}\\s*[,;/ ]\\s*[+-]?\\d{1,3}\\.\\d{4,}(?![\\d.])",
    )
    private val geoUriPattern = Regex(
        "(?i)\\bgeo:[+-]?\\d{1,3}(?:\\.\\d+)?[,;][+-]?\\d{1,3}(?:\\.\\d+)?(?:\\?[^\\s]*)?",
    )
    private val hostLabelPattern = Regex(
        "(?i)\\b(failed to connect to|unable to resolve(?: host)?|endpoint|hostname|host|адрес сервера)" +
            "(\\s*[:=]?\\s*[\"']?)([A-Z0-9._-]+(?::\\d+)?)",
    )
    private val ipAddressPattern = Regex(
        "(?<![\\d.])(?:\\d{1,3}\\.){3}\\d{1,3}(?::\\d{1,5})?(?![\\d.])|" +
            "(?i)\\[[0-9a-f:]+](?::\\d{1,5})?",
    )
    private val localHostnamePattern = Regex(
        "(?i)\\b(?:[A-Z0-9-]+\\.)+(?:local|lan|home|internal)\\b(?::\\d{1,5})?",
    )
    private val controlCharactersPattern = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F]")

    fun redact(event: DiagnosticEvent): DiagnosticEvent = event.copy(
        event = redactText(event.event, MAX_EVENT_LENGTH),
        reason = event.reason?.let { redactText(it, MAX_REASON_LENGTH) },
        details = event.details.entries
            .take(MAX_DETAILS)
            .associate { (key, value) ->
                val safeKey = redactText(key, MAX_DETAIL_KEY_LENGTH)
                safeKey to if (isSensitiveKey(key)) {
                    REDACTED
                } else {
                    redactText(value, MAX_DETAIL_VALUE_LENGTH)
                }
            },
    )

    fun redactText(value: String, maxLength: Int = Int.MAX_VALUE): String {
        var result = controlCharactersPattern.replace(value, " ")
        result = urlPattern.replace(result) { match ->
            val scheme = match.groupValues[1].lowercase()
            val hiddenPath = if (match.groupValues[3].isBlank()) "" else "/…"
            "$scheme://$REDACTED$hiddenPath"
        }
        result = emailPattern.replace(result, REDACTED)
        result = labelledSensitiveValuePattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + REDACTED
        }
        result = bearerPattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + REDACTED
        }
        result = hostLabelPattern.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2] + REDACTED
        }
        result = ipAddressPattern.replace(result, REDACTED)
        result = localHostnamePattern.replace(result, REDACTED)
        result = geoUriPattern.replace(result, REDACTED)
        result = coordinatePairPattern.replace(result, REDACTED)
        result = uuidPattern.replace(result, REDACTED)
        result = jwtPattern.replace(result, REDACTED)
        result = longIdentifierPattern.replace(result, REDACTED)
        return if (result.length <= maxLength) result else result.take(maxLength - 1).trimEnd() + "…"
    }

    fun isSensitiveKey(key: String): Boolean {
        val compact = key.lowercase().filter(Char::isLetterOrDigit)
        if (compact.endsWith("id") || compact.endsWith("identifier")) return true
        return compact in EXACT_SENSITIVE_KEYS || HEALTH_KEY_PARTS.any(compact::contains)
    }

    private val EXACT_SENSITIVE_KEYS = setOf(
        "password",
        "passwd",
        "pwd",
        "passphrase",
        "token",
        "accesstoken",
        "refreshtoken",
        "secret",
        "authorization",
        "auth",
        "cookie",
        "apikey",
        "credential",
        "credentials",
        "hub",
        "hubid",
        "serial",
        "serialnumber",
        "email",
        "mail",
        "latitude",
        "longitude",
        "lat",
        "lon",
        "lng",
        "coordinate",
        "coordinates",
        "geolocation",
        "geofencecenter",
        "пароль",
        "токен",
        "секрет",
        "авторизация",
        "серийныйномер",
        "идентификаторхаба",
        "почта",
        "широта",
        "долгота",
        "координата",
        "координаты",
    )

    private val HEALTH_KEY_PARTS = setOf(
        "healthvalue",
        "healthmetric",
        "heartrate",
        "restingheartrate",
        "pulse",
        "steps",
        "sleep",
        "weight",
        "oxygensaturation",
        "bloodpressure",
        "activecalories",
        "bodytemperature",
        "respiratoryrate",
        "hrv",
        "пульс",
        "шаги",
        "сон",
        "вес",
        "кислород",
        "давление",
        "калории",
        "температуратела",
        "частотадыхания",
        "вариабельностьпульса",
    )
}
