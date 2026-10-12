package com.parla.libreta.data.ai

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/** A model and how hard it reasons (`reasoning.effort`): low, medium or high. */
data class AiModel(val name: String, val reasoningEffort: String)

/** What one model turn produced: its text and the output items to send back in the next turn. */
class TurnResult(val text: String, val output: JSONArray) {

    /** The tools the model asked to call (`function_call` items), in order; empty when it answered. */
    val calls: List<JSONObject>
        get() = items.filter { it.optString("type") == "function_call" }

    /** The web pages the answer cites (`url_citation` annotations, docs/17-busqueda-web.md): title and address, once each. */
    val citations: List<Pair<String, String>>
        get() = items.filter { it.optString("type") == "message" }.flatMap { message ->
            val content = message.optJSONArray("content") ?: JSONArray()
            (0 until content.length()).mapNotNull { content.optJSONObject(it)?.optJSONArray("annotations") }.flatMap { annotations ->
                (0 until annotations.length()).mapNotNull { annotations.optJSONObject(it) }
                    .filter { it.optString("type") == "url_citation" && it.optString("url").isNotEmpty() }
                    .map { it.optString("title").ifEmpty { it.optString("url") } to it.optString("url") }
            }
        }.distinctBy { it.second }

    private val items: List<JSONObject> get() = (0 until output.length()).mapNotNull { output.optJSONObject(it) }
}

/** Lets the UI stop a turn from another thread. */
class TurnHandle {
    @Volatile private var call: Call? = null

    @Volatile
    var isCancelled = false
        private set

    fun cancel() {
        isCancelled = true
        call?.cancel()
    }

    internal fun attach(call: Call) {
        this.call = call
        if (isCancelled) call.cancel()
    }
}

/**
 * One streamed turn of the Responses API with the user's ChatGPT plan (docs/07-ia.md): `store: false` and
 * `stream: true` always, so the caller sends the whole conversation every time. Only `response.completed` counts
 * as success.
 *
 * @param apiBase `https://api.openai.com/v1/` in the app; a mock server in tests.
 */
class ResponsesClient(
    private val client: OkHttpClient,
    private val auth: ChatGptAuth,
    private val apiBase: HttpUrl = DEFAULT_API_BASE,
) {

    /**
     * Runs a turn of [model] with [instructions] over [input], offering [tools] if any, calling [onText] with each piece of text as it arrives and
     * [onItem] with each output item as it is complete (on this thread). Blocks until the turn ends.
     * @throws IOException, including the ones in AiErrors.kt
     */
    fun respond(
        model: AiModel,
        instructions: String,
        input: JSONArray,
        handle: TurnHandle = TurnHandle(),
        tools: JSONArray? = null,
        onItem: (JSONObject) -> Unit = {},
        onText: (String) -> Unit = {},
    ): TurnResult {
        val body = JSONObject()
            .put("model", model.name)
            .put("instructions", instructions)
            .put("input", input)
            .put("reasoning", JSONObject().put("effort", model.reasoningEffort))
            .put("include", JSONArray().put("reasoning.encrypted_content"))
            .put("store", false)
            .put("stream", true)
            .also { if (tools != null && tools.length() > 0) it.put("tools", tools) }
            .toString()
        var response = send(body, auth.accessToken(), handle)
        if (response.code() == 401) {
            // The token may have been revoked or expired early: refresh once and retry.
            response.close()
            response = send(body, auth.accessToken(forceRefresh = true), handle)
        }
        return response.use { read(it, onItem, onText) }
    }

    private fun send(body: String, token: String, handle: TurnHandle): Response {
        val request = Request.Builder()
            .url(apiBase.resolve("responses")!!)
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(RequestBody.create(JSON, body))
            .build()
        val call = client.newCall(request)
        handle.attach(call)
        return call.execute()
    }

    private fun read(response: Response, onItem: (JSONObject) -> Unit, onText: (String) -> Unit): TurnResult {
        val responseBody = response.body() ?: throw IOException("OpenAI sent no body")
        if (!response.isSuccessful) throw OpenAiHttpException(response.code(), errorCode(responseBody.string()))
        val text = StringBuilder()
        val output = JSONArray()
        val source = responseBody.source()
        val data = StringBuilder()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.startsWith("data:")) {
                data.append(line.substring(5).trim())
                continue
            }
            // A blank line ends the event; "event:" lines repeat the type that the data already carries.
            if (line.isNotEmpty() || data.isEmpty()) continue
            val event = data.toString()
            data.setLength(0)
            if (event == "[DONE]") break
            val json = try {
                JSONObject(event)
            } catch (e: JSONException) {
                throw IOException("OpenAI sent an unreadable event", e)
            }
            when (json.optString("type")) {
                "response.output_text.delta" -> json.optString("delta").takeIf { it.isNotEmpty() }?.let {
                    text.append(it)
                    onText(it)
                }
                "response.output_item.done" -> json.optJSONObject("item")?.let {
                    output.put(it)
                    onItem(it)
                }
                "response.completed" -> return TurnResult(text.toString(), output)
                "response.failed" -> {
                    val error = json.optJSONObject("response")?.optJSONObject("error")
                    throw ResponseFailedException(error?.optString("code"), "Response failed: ${error?.optString("message")}")
                }
                "response.incomplete" -> {
                    val reason = json.optJSONObject("response")?.optJSONObject("incomplete_details")?.optString("reason")
                    throw ResponseFailedException(reason, "Response incomplete: $reason")
                }
                "error" -> throw ResponseFailedException(
                    json.optString("code").takeIf { it.isNotEmpty() },
                    "OpenAI error: ${json.optString("message")}",
                )
            }
        }
        throw IOException("The answer stopped before it was complete")
    }

    /** OpenAI's error code from an error body (`{"error":{"code":…}}`), if there is one. */
    private fun errorCode(body: String): String? = try {
        val error = JSONObject(body).opt("error")
        (if (error is JSONObject) error.optString("code").ifEmpty { error.optString("type") } else error?.toString())
            ?.takeIf { it.isNotEmpty() }
    } catch (e: JSONException) {
        null
    }

    companion object {
        val DEFAULT_API_BASE: HttpUrl = HttpUrl.get("https://api.openai.com/v1/")
        private val JSON = MediaType.get("application/json; charset=utf-8")
    }
}
