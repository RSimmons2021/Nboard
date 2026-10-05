package com.nboard.ime

import com.nboard.ime.ai.ChatGptClient
import com.nboard.ime.ai.ChatGptProtocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class ChatGptProtocolTest {
    @Test fun `PKCE matches RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            ChatGptProtocol.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun `registration requests plan permission and a persistent host`() {
        val url = ChatGptProtocol.authorizationUrl("urn:uuid:host", null, "http://127.0.0.1:8080/auth/callback", "state", "nonce", "verifier").toHttpUrl()
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("Nboard", url.queryParameter("agent_name_hint"))
        assertTrue(url.queryParameter("scope")!!.contains("chatgpt.tokens.use.direct"))
        assertEquals("urn:uuid:host", url.queryParameter("ext_agent_host_id"))
    }

    @Test fun `callbacks reject changed state missing issued client and changed registration`() {
        val callback = "http://127.0.0.1:8080/auth/callback?code=code&state=expected&client_id=issued"
        assertEquals("issued" to "code", ChatGptProtocol.callbackClient(callback, "expected", null))
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callbackClient(callback, "wrong", null) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callbackClient(callback, "expected", "different") }
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.callbackClient(callback.substringBefore("&client_id"), "expected", null) }
    }

    @Test fun `signed identity must match issuer audience expiry and nonce`() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val public = pair.public as RSAPublicKey
        val encoder = Base64.getUrlEncoder().withoutPadding()
        fun encode(data: ByteArray) = encoder.encodeToString(data)
        val jwks = JSONObject().put("keys", JSONArray().put(JSONObject().put("kid", "test").put("kty", "RSA")
            .put("n", encode(public.modulus.toByteArray())).put("e", encode(public.publicExponent.toByteArray()))))
        fun signed(claims: JSONObject): String {
            val body = encode("{\"alg\":\"RS256\",\"kid\":\"test\"}".toByteArray()) + "." + encode(claims.toString().toByteArray())
            val signature = Signature.getInstance("SHA256withRSA").apply { initSign(pair.private); update(body.toByteArray()) }.sign()
            return "$body.${encode(signature)}"
        }
        val claims = JSONObject().put("iss", ChatGptProtocol.ISSUER).put("aud", "client").put("sub", "account")
            .put("iat", 10).put("exp", 1000).put("nonce", "nonce")
        assertEquals("account", ChatGptProtocol.validateIdToken(signed(claims), "client", "nonce", jwks, 100).getString("sub"))
        for ((field, value) in listOf("iss" to "evil", "aud" to "other", "nonce" to "other", "exp" to 1)) {
            val invalid = JSONObject(claims.toString()).put(field, value)
            assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.validateIdToken(signed(invalid), "client", "nonce", jwks, 100) }
        }
        val forged = signed(claims).let { it.substringBeforeLast('.') + "." + encode(ByteArray(256)) }
        assertThrows(IllegalArgumentException::class.java) { ChatGptProtocol.validateIdToken(forged, "client", "nonce", jwks, 100) }
    }

    @Test fun `subscription request uses only supported Responses fields`() {
        val request = ChatGptClient.requestBody("account-model", "rewrite", "Be concise")
        assertFalse(request.getBoolean("store"))
        assertTrue(request.getBoolean("stream"))
        assertEquals("Be concise", request.getString("instructions"))
        assertFalse(request.has("max_output_tokens"))
        assertFalse(request.has("temperature"))
        assertEquals("user", request.getJSONArray("input").getJSONObject(0).getString("role"))
    }

    @Test fun `stream waits for completion including failures after text`() {
        val delta = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"hello world\"}\n\n"
        val complete = "data: {\"type\":\"response.completed\"}\n\n"
        assertEquals("hello…", ChatGptClient.readStream(StringReader(delta + complete).buffered(), 5))
        assertThrows(IllegalStateException::class.java) { ChatGptClient.readStream(StringReader(delta).buffered(), 0) }
        val failure = "data: {\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"subscription_sharing_usage_limit_exceeded\"}}}\n\n"
        assertThrows(java.io.IOException::class.java) { ChatGptClient.readStream(StringReader(delta + failure).buffered(), 5) }
    }
}
