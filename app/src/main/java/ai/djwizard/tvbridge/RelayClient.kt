package ai.djwizard.tvbridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

// Small HTTP shim for the relay's unauthenticated endpoints. Today that's
// just /pair/init — the bridge never calls /pair/claim (that's the user +
// Claude's job). Once paired, everything else flows over the WebSocket.
class RelayClient(private val relayUrl: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class PairInitResult(val code: String, val token: String, val expiresAt: String)

    fun pairInit(): PairInitResult {
        val req = Request.Builder()
            .url("$relayUrl/pair/init")
            .post(EMPTY_JSON.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IOException("pair/init: HTTP ${resp.code} $body")
            }
            val j = JSONObject(body)
            return PairInitResult(
                code = j.getString("code"),
                token = j.getString("token"),
                expiresAt = j.optString("expires_at", ""),
            )
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val EMPTY_JSON = "{}"
    }
}
