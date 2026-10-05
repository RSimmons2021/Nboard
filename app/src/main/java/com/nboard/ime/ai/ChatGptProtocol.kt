package com.nboard.ime.ai

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Official public-client OAuth contract. No ChatGPT session cookies or private endpoints. */
internal object ChatGptProtocol {
    const val RESOURCE = "https://api.openai.com/v1"
    const val ISSUER = "https://auth.openai.com"
    const val TOKEN_URL = "$ISSUER/api/accounts/oauth/token"
    const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
    val http = OkHttpClient.Builder().callTimeout(java.time.Duration.ofSeconds(45)).build()

    fun randomSecret(): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })

    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun authorizationUrl(hostId: String, clientId: String?, redirect: String, state: String, nonce: String, verifier: String): String {
        val builder = "$ISSUER/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId ?: "dynamic_agent_client")
            .addQueryParameter("ext_agent_host_id", hostId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirect)
            .addQueryParameter("scope", "openid profile email offline_access resource.invoke $DIRECT_SCOPE")
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", challenge(verifier))
        if (clientId == null) builder.addQueryParameter("agent_name_hint", "Nboard")
        return builder.build().toString()
    }

    fun callbackClient(callback: String, state: String, previousClient: String?): Pair<String, String> {
        val url = callback.toHttpUrl()
        require(url.encodedPath == "/auth/callback") { "Invalid sign-in callback" }
        require(MessageDigest.isEqual(state.toByteArray(), url.queryParameter("state").orEmpty().toByteArray())) {
            "Sign-in state did not match. Please try again."
        }
        require(url.queryParameter("error") == null) { "ChatGPT sign-in was declined" }
        val issued = url.queryParameter("client_id") ?: previousClient
        require(!issued.isNullOrBlank() && issued != "dynamic_agent_client") { "ChatGPT registration did not complete" }
        require(previousClient == null || issued == previousClient) { "ChatGPT registration changed unexpectedly" }
        val code = url.queryParameter("code")
        require(!code.isNullOrBlank()) { "No sign-in code was returned" }
        return issued to code
    }

    fun exchange(clientId: String, code: String, verifier: String, redirect: String): JSONObject = tokenRequest(
        FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId)
            .add("code", code).add("code_verifier", verifier).add("redirect_uri", redirect)
            .add("resource", RESOURCE).build()
    )

    fun tokenRequest(form: FormBody): JSONObject {
        http.newCall(Request.Builder().url(TOKEN_URL).post(form).build()).execute().use { response ->
            check(response.isSuccessful) { "ChatGPT authorization failed (HTTP ${response.code}). Sign in again." }
            return JSONObject(response.body?.string() ?: error("ChatGPT returned no credentials"))
        }
    }

    fun validateIdToken(token: String, clientId: String, nonce: String, jwks: JSONObject, nowSeconds: Long): JSONObject {
        val parts = token.split('.')
        require(parts.size == 3) { "Invalid ChatGPT identity token" }
        val decoder = Base64.getUrlDecoder()
        val header = JSONObject(String(decoder.decode(parts[0]), Charsets.UTF_8))
        require(header.optString("alg") == "RS256") { "Unsupported identity signature" }
        val keys = jwks.getJSONArray("keys")
        val key = (0 until keys.length()).map { keys.getJSONObject(it) }.firstOrNull {
            it.optString("kid") == header.optString("kid") && it.optString("kty") == "RSA"
        } ?: error("ChatGPT identity signing key was not found")
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(
            BigInteger(1, decoder.decode(key.getString("n"))), BigInteger(1, decoder.decode(key.getString("e")))
        ))
        val verifier = Signature.getInstance("SHA256withRSA").apply {
            initVerify(publicKey)
            update("${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII))
        }
        require(verifier.verify(decoder.decode(parts[2]))) { "ChatGPT identity signature did not verify" }
        val claims = JSONObject(String(decoder.decode(parts[1]), Charsets.UTF_8))
        require(claims.optString("iss") == ISSUER) { "Unexpected ChatGPT identity issuer" }
        val audience = claims.opt("aud")
        val validAudience = audience == clientId || audience is org.json.JSONArray &&
            (0 until audience.length()).any { audience.optString(it) == clientId }
        require(validAudience) { "ChatGPT identity audience did not match" }
        if (audience is org.json.JSONArray && audience.length() > 1) {
            require(claims.optString("azp") == clientId) { "ChatGPT authorized party did not match" }
        }
        require(claims.optLong("nbf", 0) <= nowSeconds + 60) { "ChatGPT identity is not valid yet" }
        require(claims.optLong("exp") > nowSeconds && claims.optLong("iat") <= nowSeconds + 60) { "ChatGPT identity has expired" }
        require(claims.optString("nonce") == nonce && claims.optString("sub").isNotBlank()) { "ChatGPT identity did not match this sign-in" }
        return claims
    }
}
