package com.nboard.ime.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted, atomic, device-local credentials; excluded from Android backup. */
class ChatGptSession(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "chatgpt-session.enc"))
    private val hostFile = AtomicFile(File(context.noBackupFilesDir, "chatgpt-host-id"))

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }

    internal fun read(): JSONObject? = synchronized(storageLock) {
        if (!file.baseFile.exists()) return@synchronized null
        runCatching {
            val bytes = file.readFully()
            val ivSize = bytes[0].toInt() and 255
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 1 + ivSize)))
            }
            JSONObject(String(cipher.doFinal(bytes.copyOfRange(1 + ivSize, bytes.size)), Charsets.UTF_8))
        }.getOrNull()
    }

    private fun save(data: JSONObject) = synchronized(storageLock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + cipher.doFinal(data.toString().toByteArray(Charsets.UTF_8))
        val output = file.startWrite()
        try { output.write(bytes); file.finishWrite(output) }
        catch (error: Exception) { file.failWrite(output); throw error }
    }

    fun hostId(): String = synchronized(storageLock) {
        if (hostFile.baseFile.exists()) return@synchronized String(hostFile.readFully(), Charsets.UTF_8)
        val id = "urn:uuid:${UUID.randomUUID()}"
        val output = hostFile.startWrite()
        try { output.write(id.toByteArray()); hostFile.finishWrite(output) }
        catch (error: Exception) { hostFile.failWrite(output); throw error }
        id
    }

    val isConnected: Boolean get() = read()?.let { hasPermission(it) && it.optString("refresh_token").isNotBlank() } == true
    val email: String get() = read()?.optString("email").orEmpty()
    val clientId: String? get() = read()?.optString("client_id")?.takeIf { it.isNotBlank() }
    val model: String get() = read()?.optString("model").orEmpty()

    internal fun accept(tokens: JSONObject, claims: JSONObject, clientId: String) = synchronized(storageLock) {
        require(hasPermission(tokens)) { "ChatGPT plan permission was not granted. Enable plan usage when signing in." }
        require(tokens.optString("access_token").isNotBlank() && tokens.optString("refresh_token").isNotBlank()) { "ChatGPT returned incomplete credentials" }
        val previous = read()
        require(previous == null || previous.optString("sub") == claims.getString("sub")) { "Sign out before connecting a different ChatGPT account" }
        tokens.put("client_id", clientId).put("sub", claims.getString("sub"))
            .put("email", claims.optString("email"))
            .put("expires_at", System.currentTimeMillis() + tokens.getLong("expires_in") * 1000)
            .put("model", previous?.optString("model").orEmpty())
        save(tokens)
    }

    internal fun accessToken(): String = synchronized(refreshLock) {
        val current = read() ?: error("Connect your ChatGPT account in Nboard settings")
        check(hasPermission(current)) { "ChatGPT plan permission is required" }
        if (current.optLong("expires_at") < System.currentTimeMillis() + 60_000) {
            val refreshed = ChatGptProtocol.tokenRequest(FormBody.Builder()
                .add("grant_type", "refresh_token").add("client_id", current.getString("client_id"))
                .add("refresh_token", current.getString("refresh_token"))
                .add("resource", ChatGptProtocol.RESOURCE).build())
            if (refreshed.has("scope")) check(hasPermission(refreshed)) { "ChatGPT plan permission has been revoked" }
            check(refreshed.optString("access_token").isNotBlank()) { "ChatGPT token refresh failed" }
            listOf("access_token", "refresh_token", "id_token", "scope", "token_type").forEach { field ->
                if (refreshed.has(field)) current.put(field, refreshed.get(field))
            }
            current.put("expires_at", System.currentTimeMillis() + refreshed.getLong("expires_in") * 1000)
            current.put("model", read()?.optString("model").orEmpty())
            save(current)
        }
        current.getString("access_token")
    }

    fun models(): List<Pair<String, String>> {
        val request = Request.Builder().url("${ChatGptProtocol.RESOURCE}/models")
            .header("Authorization", "Bearer ${accessToken()}").build()
        return ChatGptProtocol.http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Cannot load ChatGPT models (HTTP ${response.code})" }
            val models = JSONObject(response.body!!.string()).getJSONArray("models")
            (0 until models.length()).map { models.getJSONObject(it) }
                .filter { it.optString("visibility") == "list" }
                .map { it.getString("slug") to it.getString("display_name") }
        }
    }

    fun selectModel(model: String) = synchronized(storageLock) {
        val data = read() ?: error("Connect ChatGPT first")
        save(data.put("model", model))
    }

    /** Local credentials are cleared even if the remote session cannot be revoked. */
    fun disconnect(): Boolean = synchronized(refreshLock) {
        val data = read()
        val revoked = runCatching {
            if (data == null) return@runCatching true
            val discovery = ChatGptProtocol.http.newCall(Request.Builder()
                .url("${ChatGptProtocol.ISSUER}/.well-known/openid-configuration").build()).execute().use {
                check(it.isSuccessful)
                JSONObject(it.body!!.string())
            }
            val endpoint = discovery.getString("revocation_endpoint")
            require(endpoint.startsWith("${ChatGptProtocol.ISSUER}/"))
            ChatGptProtocol.http.newCall(Request.Builder().url(endpoint)
                .post(FormBody.Builder().add("token", data.getString("refresh_token"))
                    .add("token_type_hint", "refresh_token").add("client_id", data.getString("client_id")).build())
                .build()).execute().use { it.code == 200 }
        }.getOrDefault(false)
        file.delete()
        revoked
    }

    companion object {
        private val storageLock = Any()
        private val refreshLock = Any()
        private const val KEY_ALIAS = "nboard-chatgpt-session"
        private fun hasPermission(tokens: JSONObject): Boolean {
            val scopes = tokens.optString("scope").split(' ').toSet()
            return ChatGptProtocol.DIRECT_SCOPE in scopes && "resource.invoke" in scopes
        }
    }
}
