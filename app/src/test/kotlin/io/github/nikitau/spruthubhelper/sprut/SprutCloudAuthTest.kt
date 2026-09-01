package io.github.nikitau.spruthubhelper.sprut

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SprutCloudAuthTest {
    @Test
    fun challengeAnswerMatchesArgon2idAndEd25519ReferenceVector() {
        val question = """{
            "rootSalt":"AQIDBAUGBwgJCgsMDQ4PEA==",
            "challenge":"ERITFBUWFxgZGhscHR4fIA==",
            "kdfParams":"m=32,t=2,p=1"
        }""".trimIndent()

        assertEquals(
            "IQjQymhaBFjrOXeaNYRjksnfXXhHALCgRJbbma6H2bunWkQ6gAdknAciGCCikcPPQAac3NTveqPA5ofb89ltDg==",
            SprutCloudAuth.answerChallenge("cloud-password", question),
        )
    }

    @Test
    fun enrollmentCreatesValidSaltAndPublicKey() {
        val answer = SprutCloudAuth.answerEnrollment(
            "cloud-password",
            """{"kdfParams":"m=32,t=2,p=1"}""",
        )
        val parsed = Json.parseToJsonElement(answer).jsonObject
        val salt = Base64.getDecoder().decode(parsed.getValue("rootSalt").jsonPrimitive.content)
        val publicKey = Base64.getDecoder().decode(parsed.getValue("authKey").jsonPrimitive.content)

        assertEquals(16, salt.size)
        assertEquals(32, publicKey.size)
        assertNotEquals(salt.toList(), publicKey.take(16))
    }
}
