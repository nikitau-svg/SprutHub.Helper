package io.github.nikitau.spruthubhelper.sprut

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import org.bouncycastle.math.ec.rfc8032.Ed25519

/** Implements the challenge-response flow used by the current SprutHub web cloud. */
internal object SprutCloudAuth {
    private val json = Json { ignoreUnknownKeys = true }
    private val base64Encoder = Base64.getEncoder()
    private val base64Decoder = Base64.getDecoder()
    private val secureRandom = SecureRandom()

    fun answerChallenge(password: String, questionData: String): String {
        require(password.isNotBlank()) { "SprutHub запросил облачный пароль" }
        val data = parseData(questionData)
        val salt = decode(data.requiredString("rootSalt"), "rootSalt")
        val challenge = decode(data.requiredString("challenge"), "challenge")
        val privateSeed = deriveSeed(password, salt, parseKdf(data.requiredString("kdfParams")))
        return try {
            val signature = ByteArray(Ed25519.SIGNATURE_SIZE)
            Ed25519.sign(privateSeed, 0, challenge, 0, challenge.size, signature, 0)
            base64Encoder.encodeToString(signature)
        } finally {
            privateSeed.fill(0)
        }
    }

    fun answerEnrollment(password: String, questionData: String): String {
        require(password.length in 8..128) {
            "Для облачного входа SprutHub нужен пароль длиной от 8 до 128 символов"
        }
        val data = parseData(questionData)
        val salt = ByteArray(16).also(secureRandom::nextBytes)
        val privateSeed = deriveSeed(password, salt, parseKdf(data.requiredString("kdfParams")))
        return try {
            val publicKey = ByteArray(Ed25519.PUBLIC_KEY_SIZE)
            Ed25519.generatePublicKey(privateSeed, 0, publicKey, 0)
            buildJsonObject {
                put("rootSalt", base64Encoder.encodeToString(salt))
                put("authKey", base64Encoder.encodeToString(publicKey))
            }.toString()
        } finally {
            privateSeed.fill(0)
        }
    }

    private fun parseData(raw: String): JsonObject = runCatching {
        json.parseToJsonElement(raw).jsonObject
    }.getOrElse {
        throw IllegalArgumentException("SprutHub прислал некорректный запрос авторизации", it)
    }

    private fun JsonObject.requiredString(key: String): String =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
            ?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("В запросе SprutHub отсутствует $key")

    private fun decode(value: String, field: String): ByteArray = runCatching {
        base64Decoder.decode(value)
    }.getOrElse {
        throw IllegalArgumentException("SprutHub прислал некорректное поле $field", it)
    }

    private fun parseKdf(raw: String): KdfParameters {
        val values = raw.split(',').associate { item ->
            val parts = item.trim().split('=', limit = 2)
            require(parts.size == 2) { "SprutHub прислал некорректные параметры защиты" }
            parts[0].trim() to (
                parts[1].trim().toIntOrNull()
                    ?: throw IllegalArgumentException("SprutHub прислал некорректные параметры защиты")
                )
        }
        val memoryKb = values["m"] ?: throw IllegalArgumentException("Не указан параметр защиты m")
        val iterations = values["t"] ?: throw IllegalArgumentException("Не указан параметр защиты t")
        val parallelism = values["p"] ?: throw IllegalArgumentException("Не указан параметр защиты p")
        require(parallelism in 1..16) { "Некорректный параметр защиты p" }
        require(iterations in 1..20) { "Некорректный параметр защиты t" }
        require(memoryKb in (8 * parallelism)..262_144) { "Некорректный параметр защиты m" }
        return KdfParameters(memoryKb, iterations, parallelism)
    }

    private fun deriveSeed(
        password: String,
        salt: ByteArray,
        kdf: KdfParameters,
    ): ByteArray {
        val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(kdf.memoryKb)
            .withIterations(kdf.iterations)
            .withParallelism(kdf.parallelism)
            .build()
        val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
        return ByteArray(Ed25519.SECRET_KEY_SIZE).also { seed ->
            try {
                Argon2BytesGenerator().apply { init(parameters) }.generateBytes(passwordBytes, seed)
            } finally {
                passwordBytes.fill(0)
            }
        }
    }

    private data class KdfParameters(
        val memoryKb: Int,
        val iterations: Int,
        val parallelism: Int,
    )
}
