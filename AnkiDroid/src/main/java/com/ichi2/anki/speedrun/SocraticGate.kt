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

import android.content.Context
import android.view.Gravity
import androidx.appcompat.app.AlertDialog
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
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume

/** Must match rslib/src/stats/socratic_gate.rs's DEFAULT_FAST_THRESHOLD_MS. */
const val FAST_THRESHOLD_MS = 3_000

// Internal rather than private: CurriculumGrounding.kt's groundedness
// call shares the same model and endpoint, and two copies would be a
// silent drift risk.
internal const val MODEL = "claude-haiku-4-5-20251001"
internal const val API_URL = "https://api.anthropic.com/v1/messages"

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

/**
 * Card text as a human would read it. Drops `<style>`/`<script>` blocks
 * *including their contents* before stripping the remaining tags - same
 * fix as the desktop port's `_strip_html`, and for the same real bug
 * found by instrumenting the live desktop gate: `card.question(col)`
 * returns the fully rendered card, which begins with the notetype's CSS
 * block, and a tags-only strip leaves the raw CSS rules behind as card
 * "text" (`.card { font-family: arial; font-size: 20p...`). That
 * silently polluted the leak check's notion of the gold answer with
 * tokens like "card"/"color"/"arial" and wasted prompt tokens on styling
 * noise. Whitespace is collapsed so the model and the n-gram check see
 * clean prose.
 */
private fun stripHtml(text: String): String =
    text
        .replace(Regex("<(style|script)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        .replace(Regex("<[^<]+?>"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

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

/** A bridge that has passed the leak check and, where the curriculum
 * corpus covers the topic, been judged for groundedness.
 * [grounded] is deliberately nullable: null means the check did not run
 * because the corpus can't speak to this card's topic, which is a
 * different statement from "checked and failed". */
data class VerifiedBridge(
    val content: BridgeContent,
    val grounded: Boolean?,
    val groundedReasoning: String,
)

/** Raised when a bridge question still leaks the gold answer after one
 * regeneration. Callers skip showing a bridge entirely rather than
 * surfacing an error - the student did nothing wrong. */
class BridgeLeakedException : Exception("bridge question leaked the gold answer after one retry")

/**
 * generate -> leak-check (hard gate, one retry) -> grounding-check (soft
 * signal, only when the corpus covers this topic). Mirrors desktop's
 * `_generate_and_verify_bridge`. Blocking; callers run it on IO.
 *
 * The asymmetry is deliberate. The leak check is topic-independent (pure
 * n-gram overlap against this card's own answer), so it is always
 * meaningful and is enforced. Grounding depends on a corpus that only
 * covers the Krebs cycle, so it is reported, never enforced - making it a
 * gate would silently kill bridges on every other topic.
 */
private fun generateAndVerifyBridge(
    context: Context,
    apiKey: String,
    front: String,
    back: String,
): VerifiedBridge {
    var content = generateBridge(apiKey, front, back)
    if (leaksGoldAnswer(content.bridgeQuestion, back)) {
        content = generateBridge(apiKey, front, back)
        if (leaksGoldAnswer(content.bridgeQuestion, back)) throw BridgeLeakedException()
    }

    var grounded: Boolean? = null
    var reasoning = ""
    val chunks = loadCurriculumChunks(context)
    val (passages, gateScore) = retrieveForGrounding(front, back, chunks)
    if (passages.isNotEmpty() && gateScore >= GROUNDING_COVERAGE_THRESHOLD) {
        val result = checkGrounded(apiKey, content, passages)
        grounded = result.grounded
        reasoning = result.reasoning
    }
    return VerifiedBridge(content, grounded, reasoning)
}

/**
 * Called from [Reviewer.displayCardAnswer], before the back is revealed.
 * Suspends until any gating dialogs this shows are dismissed; the caller
 * always reveals the answer immediately after this returns, whether or
 * not gating did anything.
 *
 * A genuine "fast + confident + wrong" Dangerous Error can only be caught
 * after grading - there's no way to know the answer is wrong before it's
 * shown. That case still goes through [prepareSocraticBridge] /
 * [awaitSocraticBridge] below, unchanged, once the reveal proceeds
 * normally here.
 */
suspend fun maybeGateBeforeAnswer(
    reviewer: Reviewer,
    col: Collection,
    card: Card,
) {
    val apiKey = BuildConfig.ANTHROPIC_API_KEY
    if (apiKey.isBlank()) return

    val takenMillis = card.timeTaken(col)
    val fast = takenMillis <= FAST_THRESHOLD_MS

    val confident = showConfidenceDialogAndAwaitChoice(reviewer)
    if (fast || confident == null) {
        // Fast (Automated Mastery / Lucky Guess row), or the dialog was
        // dismissed without a choice - fail open, reveal normally rather
        // than getting the student stuck on an unanswered prompt.
        return
    }

    // Slow, regardless of confidence (brainlift.md §4's "Slow + any
    // confidence" row -> Productive Struggle): withhold the back, show
    // the bridge first.
    val front = stripHtml(card.question(col))
    val back = stripHtml(card.answer(col))
    val result =
        runCatching {
            withContext(Dispatchers.IO) { generateAndVerifyBridge(reviewer, apiKey, front, back) }
        }
    result
        .onSuccess { verified -> showBridgeQuestionDialogAndAwaitDismissal(reviewer, "before revealing", verified) }
        .onFailure { e ->
            // A leak that survived a retry means no usable bridge exists
            // for this card right now; skip silently rather than show the
            // student an error they can't act on.
            if (e !is BridgeLeakedException) Timber.w(e, "Speedrun Socratic bridge generation failed")
        }

    // Suppress the post-grade Dangerous Error/Productive Struggle check
    // for this same card - it already got its bridge, pre-reveal.
    reviewer.speedrunBridgeShownForCardId = card.id
}

/** Shown in place of the normal answer reveal. Captures a self-reported
 * confidence tap before the back is shown - see brainlift.md §4's
 * decision table, which conditions the withhold-vs-reveal choice on
 * latency and this confidence signal, not just correctness (which isn't
 * knowable pre-reveal anyway). Returns true/false for the tapped
 * choice, or null if dismissed without one. */
private suspend fun showConfidenceDialogAndAwaitChoice(reviewer: Reviewer): Boolean? =
    suspendCancellableCoroutine { continuation ->
        fun resumeOnce(value: Boolean?) {
            if (continuation.isActive) continuation.resume(value)
        }
        val dialog =
            AlertDialog.Builder(reviewer).show {
                title(text = "Speedrun — before we reveal")
                message(text = "How confident are you in your answer?")
                positiveButton(text = "I've got it") { resumeOnce(true) }
                negativeButton(text = "Not sure") { resumeOnce(false) }
                cancelable(true)
            }
        dialog.anchorUnderQuestion()
        dialog.setOnCancelListener { resumeOnce(null) }
        continuation.invokeOnCancellation { dialog.dismiss() }
    }

/**
 * Does the bridge *question* give away the gold answer before the
 * student has had a chance to reason? Checks only the question - not the
 * answer/synthesis, which are shown after Reveal and are explicitly
 * supposed to name the fact ("a synthesis connecting it back to the
 * card's original fact"). Flagging those would just measure the
 * synthesis doing its job.
 *
 * Two real bugs are baked into this shape, both caught by the standalone
 * agent's adversarial tests (speedrun/tools/socratic-agent/): a fixed
 * 6-word n-gram can't form at all against short gold answers like
 * "Citrate synthase", so short answers fall back to whole-phrase
 * containment; and an earlier version checking answer+synthesis flagged
 * 6 of 10 real cards for correct behaviour.
 */
private fun leaksGoldAnswer(
    bridgeQuestion: String,
    goldBack: String,
): Boolean {
    val gold = TOKENS_RE.findAll(goldBack.lowercase()).map { it.value }.toList()
    val bridge = TOKENS_RE.findAll(bridgeQuestion.lowercase()).map { it.value }.toList()
    if (gold.isEmpty()) return false
    if (gold.size < LEAK_NGRAM_SIZE) {
        return (0..bridge.size - gold.size).any { i -> bridge.subList(i, i + gold.size) == gold }
    }
    val goldNgrams = (0..gold.size - LEAK_NGRAM_SIZE).map { gold.subList(it, it + LEAK_NGRAM_SIZE) }.toSet()
    val bridgeNgrams = (0..bridge.size - LEAK_NGRAM_SIZE).map { bridge.subList(it, it + LEAK_NGRAM_SIZE) }.toSet()
    return goldNgrams.intersect(bridgeNgrams).isNotEmpty()
}

private const val LEAK_NGRAM_SIZE = 6
private val TOKENS_RE = Regex("[a-z0-9]+")

/** Everything the bridge needs, captured synchronously at the correct
 * moment - before grading mutates [card]/[col] state and before
 * [Card.timeTaken] could be skewed by any later delay. */
data class PendingSocraticBridge(
    val apiKey: String,
    val front: String,
    val back: String,
    val label: String,
)

/**
 * Called from [Reviewer.answerCardInner] right after a card is answered,
 * before grading mutates any state. Returns null (no bridge) if no API key
 * is configured or the gate doesn't call for an intervention. Cheap and
 * synchronous - the actual API call happens later, in [awaitSocraticBridge].
 */
fun prepareSocraticBridge(
    card: Card,
    col: Collection,
    rating: Rating,
    reviewer: Reviewer,
): PendingSocraticBridge? {
    val apiKey = BuildConfig.ANTHROPIC_API_KEY
    if (apiKey.isBlank()) return null
    if (reviewer.speedrunBridgeShownForCardId == card.id) return null

    val takenMillis = card.timeTaken(col)
    val decision = socraticGateDecision(takenMillis, rating)
    if (!requiresSocraticBridge(decision)) return null

    val front = stripHtml(card.question(col))
    val back = stripHtml(card.answer(col))
    val label = if (decision == GateDecision.DANGEROUS_ERROR) "Dangerous error" else "Worth a closer look"
    return PendingSocraticBridge(apiKey, front, back, label)
}

/**
 * Generates the bridge content and blocks (suspends) until the user
 * dismisses it. Must be awaited, not launched fire-and-forget: a
 * fire-and-forget coroutine lets [Reviewer.answerCardInner] return and
 * the reviewer advance to the *next* card before the async API call
 * resolves, so the bridge dialog would pop up over a question it isn't
 * about, looking like a stale "hint for the previous question." Calling
 * this suspend function from answerCardInner - after grading has already
 * been submitted, same as the desktop hook's placement after a
 * successful `answer_card` RPC - holds the current card on screen until
 * the student has actually engaged with the bridge.
 */
suspend fun awaitSocraticBridge(
    reviewer: Reviewer,
    pending: PendingSocraticBridge,
) {
    val result =
        runCatching {
            withContext(Dispatchers.IO) {
                generateAndVerifyBridge(reviewer, pending.apiKey, pending.front, pending.back)
            }
        }
    result
        .onSuccess { verified -> showBridgeQuestionDialogAndAwaitDismissal(reviewer, pending.label, verified) }
        .onFailure { e ->
            if (e !is BridgeLeakedException) Timber.w(e, "Speedrun Socratic bridge generation failed")
        }
}

/** Two-stage reveal via two sequential AlertDialogs - same interaction shape
 * as the card flip itself: the bridge question first, then (on demand) the
 * bridge answer and synthesis. Never the plain card answer restated, which is
 * the whole point of the mechanism (see socratic-gate-mvp.md's n=90 result).
 * Suspends until the student closes the answer stage (or cancels earlier),
 * so the caller can hold card advancement until then.
 */
private suspend fun showBridgeQuestionDialogAndAwaitDismissal(
    reviewer: Reviewer,
    label: String,
    verified: VerifiedBridge,
) {
    suspendCancellableCoroutine<Unit> { continuation ->
        fun resumeOnce() {
            if (continuation.isActive) continuation.resume(Unit)
        }
        // Three distinct states, shown distinctly. Silence must not be
        // ambiguous between "checked and passed" and "never checked" -
        // the corpus only covers the Krebs cycle, so "never checked" is
        // the common case and a student has no other way to tell them
        // apart.
        val badge =
            when (verified.grounded) {
                true -> "\n\n✓ Verified against the curriculum source."
                false -> "\n\n⚠ Not verified against the curriculum source for this topic."
                null -> ""
            }
        val dialog =
            AlertDialog.Builder(reviewer).show {
                title(text = "Speedrun — Socratic bridge ($label)")
                message(text = verified.content.bridgeQuestion + badge)
                positiveButton(text = "Reveal") {
                    showBridgeAnswerDialog(reviewer, label, verified.content, onClose = ::resumeOnce)
                }
                negativeButton(text = "Close") { resumeOnce() }
                cancelable(true)
            }
        dialog.anchorUnderQuestion()
        dialog.setOnCancelListener { resumeOnce() }
        continuation.invokeOnCancellation { dialog.dismiss() }
    }
}

private fun showBridgeAnswerDialog(
    reviewer: Reviewer,
    label: String,
    content: BridgeContent,
    onClose: () -> Unit,
) {
    val dialog =
        AlertDialog.Builder(reviewer).show {
            title(text = "Speedrun — Socratic bridge ($label)")
            message(text = "${content.bridgeAnswer}\n\n${content.synthesis}")
            positiveButton(text = "Close") { onClose() }
            cancelable(true)
        }
    dialog.anchorUnderQuestion()
    dialog.setOnCancelListener { onClose() }
}

/** Anchored near the top of the screen, roughly where the card's answer
 * renders below its question, with the default background dim kept. For
 * the pre-reveal path (maybeGateBeforeAnswer) there's nothing to hide yet
 * - the answer hasn't been shown at all. For the post-grade path
 * (prepareSocraticBridge/awaitSocraticBridge - the "fast + confident +
 * wrong" Dangerous Error case), the answer *is* already on screen
 * (Android's review flow requires seeing it to grade), and the dim
 * obscures it rather than leaving it readable beside the bridge. */
private fun AlertDialog.anchorUnderQuestion() {
    window?.apply {
        setGravity(Gravity.TOP)
        val yOffsetPx = (280 * context.resources.displayMetrics.density).toInt()
        attributes = attributes.apply { y = yOffsetPx }
    }
}
