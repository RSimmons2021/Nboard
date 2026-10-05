package com.nboard.ime

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.nboard.ime.ai.ChatGptProtocol
import com.nboard.ime.ai.ChatGptSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

/** Browser OAuth with a PKCE-protected callback bound exclusively to loopback. */
class ChatGptSignInActivity : AppCompatActivity() {
    private var listener: ServerSocket? = null
    private lateinit var status: TextView
    private lateinit var continueButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(themeStyleFor(KeyboardModeSettings.loadThemeMode(this)))
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val inset = (24 * resources.displayMetrics.density).toInt()
            setPadding(inset, inset * 3, inset, inset)
        }
        content.addView(TextView(this).apply { text = "Use your ChatGPT plan"; textSize = 26f })
        status = TextView(this).apply {
            text = "Connect an eligible ChatGPT account using your browser. AI requests share your plan's usage limits. Word predictions and learned words stay on your phone. Only text you submit to AI assistance is sent."
            textSize = 15f
            setPadding(0, 32, 0, 32)
        }
        content.addView(status)
        continueButton = MaterialButton(this).apply {
            text = "Continue with ChatGPT"
            setOnClickListener { beginSignIn() }
        }
        content.addView(continueButton)
        content.addView(MaterialButton(this).apply {
            text = "View and manage ChatGPT usage"
            setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com/settings/usage"))) }
        })
        setContentView(content)
    }

    private fun beginSignIn() {
        continueButton.isEnabled = false
        status.text = "Finish sign-in in your browser, then return to Nboard."
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val session = ChatGptSession(applicationContext)
                    val previousClient = session.clientId
                    val state = ChatGptProtocol.randomSecret()
                    val nonce = ChatGptProtocol.randomSecret()
                    val verifier = ChatGptProtocol.randomSecret()
                    val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
                    listener = server
                    server.soTimeout = 1000
                    val redirect = "http://127.0.0.1:${server.localPort}/auth/callback"
                    val authorizationUrl = ChatGptProtocol.authorizationUrl(session.hostId(), previousClient, redirect, state, nonce, verifier)
                    withContext(Dispatchers.Main) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl))) }
                    val deadline = System.currentTimeMillis() + 180_000
                    var callback: Pair<String, String>? = null
                    server.use {
                        while (callback == null && System.currentTimeMillis() < deadline) {
                            val socket = try { server.accept() } catch (_: SocketTimeoutException) { continue }
                            socket.use { client ->
                                client.soTimeout = 3000
                                val input = client.getInputStream()
                                // Read only the request line, with a hard bound on untrusted input.
                                val bytes = ArrayList<Byte>()
                                while (bytes.size < 8192) {
                                    val next = input.read()
                                    if (next < 0 || next == 10) break
                                    bytes.add(next.toByte())
                                }
                                val line = String(bytes.toByteArray(), Charsets.US_ASCII).trim()
                                val target = line.split(' ').getOrNull(1).orEmpty()
                                val parsed = if (line.startsWith("GET /auth/callback?")) runCatching {
                                    ChatGptProtocol.callbackClient("http://127.0.0.1:${server.localPort}$target", state, previousClient)
                                } else null
                                val success = parsed?.isSuccess == true
                                val body = if (success) "Authorization received. Return to Nboard to finish connecting." else "This sign-in callback was not accepted. Return to Nboard and try again."
                                client.getOutputStream().write(("HTTP/1.1 ${if (success) "200 OK" else "400 Bad Request"}\r\n" +
                                    "Content-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nConnection: close\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body").toByteArray())
                                if (parsed != null) callback = parsed.getOrThrow()
                            }
                        }
                    }
                    val (clientId, code) = callback ?: error("Sign-in timed out. Please try again.")
                    val tokens = ChatGptProtocol.exchange(clientId, code, verifier, redirect)
                    val jwks = ChatGptProtocol.http.newCall(Request.Builder()
                        .url("${ChatGptProtocol.ISSUER}/.well-known/jwks.json").build()).execute().use {
                        check(it.isSuccessful) { "Cannot verify ChatGPT identity right now" }
                        JSONObject(it.body!!.string())
                    }
                    val claims = ChatGptProtocol.validateIdToken(tokens.getString("id_token"), clientId, nonce, jwks, System.currentTimeMillis() / 1000)
                    session.accept(tokens, claims, clientId)
                    val models = session.models()
                    check(models.isNotEmpty()) { "Your account has no available models. Check ChatGPT plan access." }
                    if (models.none { it.first == session.model }) session.selectModel(models.first().first)
                }
                KeyboardModeSettings.saveAiProvider(this@ChatGptSignInActivity, AiProvider.CHATGPT)
                status.text = "Connected. Return to the keyboard to use your ChatGPT plan."
                continueButton.text = "Done"
                continueButton.isEnabled = true
                continueButton.setOnClickListener { finish() }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                status.text = error.message ?: "Sign-in could not complete. Try again."
                continueButton.isEnabled = true
            } finally {
                listener?.close()
                listener = null
            }
        }
    }

    override fun onDestroy() {
        listener?.close()
        super.onDestroy()
    }
}
