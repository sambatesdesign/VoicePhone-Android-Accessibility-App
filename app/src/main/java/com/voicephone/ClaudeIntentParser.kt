package com.voicephone

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Sends unrecognised speech to Claude Haiku and maps the response back to a [VoiceIntent].
 *
 * Only called as a fallback when the keyword parser returns [VoiceIntent.Unknown].
 * Falls back gracefully — returns null if the API call fails or times out.
 */
class ClaudeIntentParser(private val apiKey: String) {

    companion object {
        private const val TAG = "ClaudeIntentParser"
        private const val MODEL = "claude-haiku-4-5"
        private const val MAX_TOKENS = 200
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    /**
     * Parse [rawSpeech] into a [VoiceIntent].
     * [contactNames] is passed in context so Claude can resolve names like "my son" or "Mum".
     * Returns null on network failure — caller should fall back to [VoiceIntent.Unknown].
     *
     * This is a blocking call — run on a background thread.
     */
    fun parse(rawSpeech: String, contactNames: List<String>): VoiceIntent? {
        if (apiKey.isEmpty()) return null

        val contactList = if (contactNames.isEmpty()) "none saved"
        else contactNames.joinToString(", ")

        val systemPrompt = """
You are the intent parser for VoicePhone, an accessibility phone app for blind and visually impaired users.
The user has spoken to their phone. Understand what they want and return a JSON response.

Saved contacts: $contactList

Available intents and their JSON format:
- Call someone:        {"intent":"Call","target":"<contact name or number>"}
- Answer a call:       {"intent":"Answer"}
- Hang up:             {"intent":"HangUp"}
- Reject a call:       {"intent":"Reject"}
- Read messages:       {"intent":"ReadSms"}
- Send a message:      {"intent":"SendSms","contactName":"<name>"}
- Ask the time:        {"intent":"Time"}
- Ask the date:        {"intent":"Date"}
- Missed calls:        {"intent":"MissedCalls"}
- Get help:            {"intent":"Help"}
- Open contacts app:   {"intent":"OpenContacts"}
- Open settings:       {"intent":"OpenSettings"}
- Answer a question:   {"intent":"DirectAnswer","response":"<your concise spoken answer>"}
- Not understood:      {"intent":"Unknown"}

Rules:
- Match contact names fuzzily (e.g. "ring mum" → Call with target "Mum" if in contacts)
- For DirectAnswer, keep responses short and conversational — it will be read aloud
- Respond with ONLY the JSON object, no other text
        """.trimIndent()

        val requestJson = JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", MAX_TOKENS)
            put("system", systemPrompt)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", rawSpeech)
                })
            })
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(requestJson.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "Claude API error ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            val raw = JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
            Log.d(TAG, "Claude raw response: $raw")
            // Strip markdown code fences if Claude wrapped the JSON
            val text = raw
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
            parseJsonToIntent(JSONObject(text))
        } catch (e: Exception) {
            Log.e(TAG, "Claude call failed: ${e.message}")
            null
        }
    }

    private fun parseJsonToIntent(json: JSONObject): VoiceIntent {
        return when (json.optString("intent")) {
            "Call"          -> VoiceIntent.Call(json.optString("target", ""))
            "Answer"        -> VoiceIntent.Answer
            "HangUp"        -> VoiceIntent.HangUp
            "Reject"        -> VoiceIntent.Reject
            "ReadSms"       -> VoiceIntent.ReadSms
            "SendSms"       -> VoiceIntent.SendSms(json.optString("contactName", ""))
            "Time"          -> VoiceIntent.Time
            "Date"          -> VoiceIntent.Date
            "MissedCalls"   -> VoiceIntent.MissedCalls
            "Help"          -> VoiceIntent.Help
            "OpenContacts"  -> VoiceIntent.OpenContacts
            "OpenSettings"  -> VoiceIntent.OpenSettings
            "DirectAnswer"  -> VoiceIntent.DirectAnswer(json.optString("response", ""))
            else            -> VoiceIntent.Unknown(json.optString("intent", ""))
        }
    }
}
