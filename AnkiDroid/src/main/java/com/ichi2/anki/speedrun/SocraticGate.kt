/*
 *  Copyright (c) 2026
 *
 *  This program is free software; you can redistribute it and/or modify it under
 *  the terms of the GNU General Public License as published by the Free Software
 *  Foundation; either version 3 of the License, or (at your option) any later
 *  version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY
 *  WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License along with
 *  this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.ichi2.anki.speedrun

/*
 * Speedrun addition: the Socratic Gatekeeper (Brainlift v2's primary thesis).
 * After a card is answered, decides whether to show a Socratic bridge question
 * before moving on, based on how fast the answer came and whether it was
 * correct. Port of the desktop implementation
 * (qt/aqt/speedrun_socratic_gate.py) and, ultimately, of the tested Rust
 * decision function (rslib/src/stats/socratic_gate.rs) - see
 * speedrun/docs/socratic-gate-mvp.md for the full design, MVP
 * simplifications, and the real n=90 ablation this mechanism was validated
 * against before being wired into either live app.
 *
 * Same deliberate choice as desktop: [socraticGateDecision] is a pure Kotlin
 * mirror of the Rust function, not an RPC call - it's a stateless two-input
 * threshold comparison with no collection access, so a new RPC would cost a
 * proto regen and a full NDK cross-compile rebuild of this fork's backend AAR
 * (see rust-change-note.md's ALL_ARCHS gotcha) for zero behavioral benefit
 * over duplicating ~10 lines of logic the Rust module's 6 unit tests already
 * pin down.
 */

import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import anki.scheduler.CardAnswer.Rating
import com.ichi2.anki.BuildConfig
import com.ichi2.anki.Reviewer
import com.ichi2.anki.libanki.Card
import com.ichi2.anki.libanki.Collection
import com.ichi2.utils.cancelable
import com.ichi2.utils.message
import com.ichi2.utils.negativeButton
import com.ichi2.utils.positiveButton
import com.ichi2.utils.show
import com.ichi2.utils.title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Must match rslib/src/stats/socratic_gate.rs's DEFAULT_FAST_THRESHOLD_MS. */
const val FAST_THRESHOLD_MS = 3_000

private const val MODEL = "claude-haiku-4-5-20251001"
private const val API_URL = "https://api.anthropic.com/v1/messages"

enum class GateDecision {
    AUTOMATED_MASTERY,
    DANGEROUS_ERROR,
    PRODUCTIVE_STRUGGLE,
    LUCKY_GUESS,
}

/** Pure mirror of rslib/src/stats/socratic_gate.rs::socratic_gate_decision. */
fun socraticGateDecision(
    takenMillis: Int,
    rating: Rating,
    fastThresholdMs: Int = FAST_THRESHOLD_MS,
): GateDecision {
    val correct = rating != Rating.AGAIN
    val fast = takenMillis <= fastThresholdMs
    return when {
        fast && correct -> GateDecision.AUTOMATED_MASTERY
        fast && !correct -> GateDecision.DANGEROUS_ERROR
        !fast && !correct -> GateDecision.PRODUCTIVE_STRUGGLE
        else -> GateDecision.LUCKY_GUESS
    }
}

fun requiresSocraticBridge(decision: GateDecision): Boolean =
    decision == GateDecision.DANGEROUS_ERROR || decision == GateDecision.PRODUCTIVE_STRUGGLE

private fun stripHtml(text: String): String = text.replace(Regex("<[^<]+?>"), " ").trim()

data class BridgeContent(
    val bridgeQuestion: String,
    val bridgeAnswer: String,
    val synthesis: String,
)

// Same prompt as speedrun/tools/socratic-gate/generate_bridges.py and the
// desktop port, minus the counterfactual-terminology instruction (real card
// content here, not the paraphrase-test's renamed-term fixtures).
private const val BRIDGE_SYSTEM_PROMPT = (
    "You write Socratic bridge questions for a study app. Given a single " +
        "flashcard (front/back), write ONE short bridging question that " +
        "would help a student who answered wrong re-derive the fact " +
        "themselves, rather than just being told the answer again. The " +
        "bridge should reference a related consequence, mechanism, or " +
        "contrast that forces the student to reason back to the card's fact " +
        "- not restate the fact directly. Then give the answer to your own " +
        "bridge question, and a one-sentence synthesis connecting it back to " +
        "the card's original fact.\n\n" +
        "Respond in exactly this three-line format, nothing else, no other " +
        "commentary before or after:\n" +
        "BRIDGE_QUESTION: <the bridging question>\n" +
        "BRIDGE_ANSWER: <the answer to the bridge question>\n" +
        "SYNTHESIS: <one sentence connecting it back to the original fact>"
)

private val RESPONSE_RE =
    Regex(
        "BRIDGE_QUESTION:\\s*(.+?)\\s*\nBRIDGE_ANSWER:\\s*(.+?)\\s*\nSYNTHESIS:\\s*(.+)",
        RegexOption.DOT_MATCHES_ALL,
    )

private fun generateBridge(
    apiKey: String,
    front: String,
    back: String,
): BridgeContent {
    val payload =
        JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 300)
            put("system", BRIDGE_SYSTEM_PROMPT)
            put("temperature", 0.7)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", "Card front: $front\nCard back: $back")
                    },
                ),
            )
        }

    val connection = URL(API_URL).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.setRequestProperty("x-api-key", apiKey)
    connection.setRequestProperty("anthropic-version", "2023-06-01")
    connection.setRequestProperty("content-type", "application/json")
    connection.doOutput = true
    connection.connectTimeout = 30_000
    connection.readTimeout = 30_000
    try {
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use {
            it.write(payload.toString())
        }
        val responseCode = connection.responseCode
        val stream = if (responseCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (responseCode !in 200..299) {
            throw IllegalStateException("API error $responseCode: $body")
        }
        val text =
            JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
        val match =
            RESPONSE_RE.find(text)
                ?: throw IllegalStateException("no BRIDGE_QUESTION/ANSWER/SYNTHESIS in response: $text")
        return BridgeContent(
            bridgeQuestion = match.groupValues[1].trim(),
            bridgeAnswer = match.groupValues[2].trim(),
            synthesis = match.groupValues[3].trim(),
        )
    } finally {
        connection.disconnect()
    }
}

/**
 * Called from [Reviewer.answerCardInner] right after a card is answered.
 * Silently does nothing if no API key is configured or the gate doesn't call
 * for an intervention - never blocks or delays the normal review flow, since
 * the API call and dialog run in their own coroutine rather than being
 * awaited inline.
 */
fun maybeShowSocraticBridge(
    reviewer: Reviewer,
    col: Collection,
    card: Card,
    rating: Rating,
) {
    val apiKey = BuildConfig.ANTHROPIC_API_KEY
    if (apiKey.isBlank()) return

    val takenMillis = card.timeTaken(col)
    val decision = socraticGateDecision(takenMillis, rating)
    if (!requiresSocraticBridge(decision)) return

    val front = stripHtml(card.question(col))
    val back = stripHtml(card.answer(col))
    val label = if (decision == GateDecision.DANGEROUS_ERROR) "Dangerous error" else "Worth a closer look"

    reviewer.lifecycleScope.launch {
        val result =
            runCatching {
                withContext(Dispatchers.IO) { generateBridge(apiKey, front, back) }
            }
        result
            .onSuccess { content -> showBridgeQuestionDialog(reviewer, label, content) }
            .onFailure { e ->
                Timber.w(e, "Speedrun Socratic bridge generation failed")
            }
    }
}

/** Two-stage reveal via two sequential AlertDialogs - same interaction shape
 * as the card flip itself: the bridge question first, then (on demand) the
 * bridge answer and synthesis. Never the plain card answer restated, which is
 * the whole point of the mechanism (see socratic-gate-mvp.md's n=90 result).
 */
private fun showBridgeQuestionDialog(
    reviewer: Reviewer,
    label: String,
    content: BridgeContent,
) {
    AlertDialog.Builder(reviewer).show {
        title(text = "Speedrun — Socratic bridge ($label)")
        message(text = content.bridgeQuestion)
        positiveButton(text = "Reveal") {
            showBridgeAnswerDialog(reviewer, label, content)
        }
        negativeButton(text = "Close")
        cancelable(true)
    }
}

private fun showBridgeAnswerDialog(
    reviewer: Reviewer,
    label: String,
    content: BridgeContent,
) {
    AlertDialog.Builder(reviewer).show {
        title(text = "Speedrun — Socratic bridge ($label)")
        message(text = "${content.bridgeAnswer}\n\n${content.synthesis}")
        positiveButton(text = "Close")
        cancelable(true)
    }
}
