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
 * Speedrun addition: curriculum grounding and answer-leak checking - the
 * Kotlin counterpart of qt/aqt/speedrun_grounding.py.
 *
 * Written for the v2 Socratic bridge, kept when v3 retired that feature,
 * because the checks outlived what motivated them. Under the v3
 * Latency-Volatility thesis the AI is a *proctor* rather than a tutor: it
 * generates context-shifted "jitter" variants to test far transfer instead
 * of generating hints. A jitter variant has the same two ways of being
 * worthless as a bridge did - inventing facts, or handing over the answer -
 * so it needs the same two checks. Nothing here knows what a bridge is.
 *
 * Answers PRD §3's non-negotiable ("every AI output traces to a named
 * source, passes an eval") for AI output generated live during review.
 *
 * The corpus ships as app assets driven by assets/speedrun/sources.json,
 * mirroring the desktop repo's speedrun/ai/ directory, because Android
 * can't reach the desktop repo's working tree. Grounding stays a *soft*
 * signal, never a gate: the corpus covers six topics, so making it a hard
 * requirement would silently kill output on every other topic.
 *
 * Retrieval is deliberately NOT cosine similarity. The desktop version
 * started that way and got it backwards on real cards - cosine rewards
 * generic vocabulary overlap and penalises short queries against long
 * chunks, ranking an out-of-corpus ribosome card above a genuine
 * citric-acid-cycle one. This uses IDF-weighted concept coverage scored
 * separately over the card's front and its answer, taking the minimum,
 * because the *answer* is the fact generated material is grounded in.
 */

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.math.ln
import kotlin.math.min

/** Matches qt/aqt/speedrun_grounding.py's GROUNDING_COVERAGE_THRESHOLD.
 *
 * Chosen against a 9-chunk Krebs-only corpus where in-corpus cards scored
 * 0.37-1.00 and uncovered topics scored exactly 0.00. That gap has since
 * narrowed and the threshold has NOT been re-tuned: the corpus is now 54
 * chunks across six topics, and an out-of-corpus card can pick up partial
 * credit from shared vocabulary (a ribosome/SRP card now scores 0.216,
 * only 0.034 below the line). Ordering is still correct but the margin is
 * thin. See the desktop module's comment for why it was left rather than
 * nudged to fit one example. */
const val GROUNDING_COVERAGE_THRESHOLD = 0.25

/** The manifest, not a single filename. Both platforms drive the corpus
 * off speedrun/ai/sources.json so that adding curriculum material
 * extends retrieval on desktop and mobile at once. Loading only the
 * Krebs file meant every chem/phys card retrieved nothing and grounding
 * reported "not checked" - a silent abstention that looks exactly like a
 * passing card unless you inspect it. */
// Previously `internal` in SocraticGate.kt, which v3 deletes. Owned here
// now; must stay in step with the desktop module's copies in
// qt/aqt/speedrun_grounding.py.
const val MODEL = "claude-haiku-4-5-20251001"
const val API_URL = "https://api.anthropic.com/v1/messages"

private const val SOURCES_MANIFEST_PATH = "speedrun/sources.json"
private const val ASSET_DIR = "speedrun"

/** Must match the desktop STOPWORDS set. Terms carrying no information
 * about what a card is *about* - counting them would let generic phrasing
 * masquerade as topical relevance. */
private val STOPWORDS =
    (
        "a an and are as at be by for from has have how in into is it its of on or " +
            "that the to was were what when where which who why will with you your this " +
            "these those there their they them then than some such only other more most " +
            "can could would should may might must do does did done not no nor but if " +
            "while during each both few all any own same so too very just now also about " +
            "above below between through before after under over again further once one " +
            "two three four five called sometimes generally considered major primary"
    ).split(" ").toSet()

private fun chunkRegex(prefix: String) =
    Regex(
        "^## (${Regex.escape(prefix)}-\\d+): .+?\\n(.*?)(?=^## |\\z)",
        setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL),
    )

private val TOKEN_RE = Regex("[a-z0-9]+")

data class CurriculumChunk(
    val chunkId: String,
    val text: String,
    val terms: Set<String>,
)

data class GroundingResult(
    val grounded: Boolean,
    val reasoning: String,
)

private var cachedChunks: List<CurriculumChunk>? = null

/** Parses the bundled corpus into chunks, cached after first load.
 * Returns an empty list if the asset is missing - the grounding check
 * then degrades to "skipped", the same give-up-gate discipline used
 * everywhere else in this project rather than guessing. */
fun loadCurriculumChunks(context: Context): List<CurriculumChunk> {
    cachedChunks?.let { return it }
    val parsed =
        runCatching {
            val manifest =
                context.assets
                    .open(SOURCES_MANIFEST_PATH)
                    .bufferedReader(StandardCharsets.UTF_8)
                    .use { it.readText() }
            val sources = JSONObject(manifest).getJSONArray("sources")
            val chunks = mutableListOf<CurriculumChunk>()
            for (i in 0 until sources.length()) {
                val source = sources.getJSONObject(i)
                val file = source.getString("file")
                val prefix = source.getString("chunk_prefix")
                // One unreadable source document must not cost us the
                // whole corpus - degrade by that document, not globally.
                val raw =
                    runCatching {
                        context.assets
                            .open("$ASSET_DIR/$file")
                            .bufferedReader(StandardCharsets.UTF_8)
                            .use { it.readText() }
                    }.onFailure { Timber.w(it, "Speedrun: source document %s unavailable", file) }
                        .getOrNull() ?: continue
                chunkRegex(prefix).findAll(raw).forEach { m ->
                    val text = m.groupValues[2].trim()
                    chunks.add(CurriculumChunk(m.groupValues[1], text, contentTerms(text)))
                }
            }
            chunks.toList()
        }.onFailure { Timber.w(it, "Speedrun: curriculum manifest unavailable") }
            .getOrDefault(emptyList())
    cachedChunks = parsed
    return parsed
}

private fun contentTerms(text: String): Set<String> =
    TOKEN_RE
        .findAll(text.lowercase())
        .map { it.value }
        .filter { it !in STOPWORDS && it.length > 2 }
        .toSet()

/** IDF over the chunks, plus the weight charged to terms in no chunk at
 * all. A term in every chunk carries no discriminative information (IDF
 * 0); a term the corpus has never seen is maximally uncovered, so it is
 * charged the rarest-possible in-corpus weight. */
private fun corpusIdf(chunks: List<CurriculumChunk>): Pair<Map<String, Double>, Double> {
    if (chunks.isEmpty()) return emptyMap<String, Double>() to 0.0
    val docFreq = mutableMapOf<String, Int>()
    for (chunk in chunks) {
        for (term in chunk.terms) docFreq[term] = (docFreq[term] ?: 0) + 1
    }
    val n = chunks.size.toDouble()
    return docFreq.mapValues { (_, df) -> ln(n / df) } to ln(n)
}

/** What fraction of a card's information content this chunk covers,
 * weighted by term distinctiveness. Asymmetric on purpose: a long chunk
 * isn't penalised for extra material, a two-word card isn't penalised for
 * being short, and terms absent from the whole corpus count fully against
 * the score - which is what drives out-of-corpus cards to 0. */
private fun coverage(
    query: String,
    chunkTerms: Set<String>,
    idf: Map<String, Double>,
    oovWeight: Double,
): Double {
    val terms = contentTerms(query)
    if (terms.isEmpty()) return 0.0
    var covered = 0.0
    var total = 0.0
    for (term in terms) {
        val weight = idf[term] ?: oovWeight
        total += weight
        if (term in chunkTerms) covered += weight
    }
    return if (total > 0) covered / total else 0.0
}

/** Returns the top chunks to show the judge, plus the gate score.
 * Gate score is min(front coverage, back coverage) because both must
 * hold - a corpus that has never heard of "phosphofructokinase" cannot
 * vouch for a bridge about it however much the question's framing
 * ("rate-limiting step", "enzyme") overlaps covered material. Cards with
 * an empty back fall back to front coverage alone. */
fun retrieveForGrounding(
    front: String,
    back: String,
    chunks: List<CurriculumChunk>,
    topK: Int = 2,
): Pair<List<CurriculumChunk>, Double> {
    if (chunks.isEmpty()) return emptyList<CurriculumChunk>() to 0.0
    val (idf, oovWeight) = corpusIdf(chunks)

    val combined = "$front $back".trim()
    val ranked =
        chunks.sortedByDescending { coverage(combined, it.terms, idf, oovWeight) }.take(topK)

    val bestFront = chunks.maxOfOrNull { coverage(front, it.terms, idf, oovWeight) } ?: 0.0
    val gate =
        if (contentTerms(back).isNotEmpty()) {
            val bestBack = chunks.maxOfOrNull { coverage(back, it.terms, idf, oovWeight) } ?: 0.0
            min(bestFront, bestBack)
        } else {
            bestFront
        }
    return ranked to gate
}

// Same prompt as the desktop port's GROUNDEDNESS_SYSTEM_PROMPT.
private const val GROUNDEDNESS_SYSTEM_PROMPT =
    "You are a fact-checker for an MCAT study app. You will be given a " +
        "generated bridge question, its answer, and a synthesis sentence, " +
        "plus one or more source passages. Your job: determine whether the " +
        "factual claims in the bridge answer and synthesis are actually " +
        "supported by the source passages - not whether they're true in " +
        "general biochemistry, specifically whether THESE passages support " +
        "them. If the bridge introduces a specific fact, number, enzyme " +
        "name, or mechanism that isn't in the provided passages, that's not " +
        "grounded, even if it happens to be correct.\n\n" +
        "Respond in exactly this format, nothing else:\n" +
        "GROUNDED: <yes or no>\n" +
        "REASONING: <one or two sentences citing what is or isn't supported>"

private val GROUNDEDNESS_RE =
    Regex("GROUNDED:\\s*(yes|no)\\s*\\nREASONING:\\s*(.+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

/** Blocking HTTP call - callers must run this off the main thread.
 *
 * Takes the generated material as a plain string rather than a
 * bridge-shaped struct: v3's jitter variants have a different shape from
 * v2's bridges, and this check never cared about the shape - only about
 * whether the claims are supported by the passages.
 */
fun checkGrounded(
    apiKey: String,
    generatedText: String,
    passages: List<CurriculumChunk>,
): GroundingResult {
    val passageText = passages.joinToString("\n\n") { "[${it.chunkId}] ${it.text}" }
    val userPrompt = "Generated material:\n$generatedText\n\nSource passages:\n$passageText"

    val payload =
        JSONObject().apply {
            put("model", MODEL)
            put("max_tokens", 150)
            put("system", GROUNDEDNESS_SYSTEM_PROMPT)
            put("temperature", 0.3)
            put(
                "messages",
                JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("content", userPrompt)
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
        OutputStreamWriter(connection.outputStream, StandardCharsets.UTF_8).use { it.write(payload.toString()) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("API error $code: $body")
        val text =
            JSONObject(body)
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
        val match = GROUNDEDNESS_RE.find(text) ?: throw IllegalStateException("no GROUNDED/REASONING in response: $text")
        return GroundingResult(
            grounded = match.groupValues[1].trim().lowercase() == "yes",
            reasoning = match.groupValues[2].trim(),
        )
    } finally {
        connection.disconnect()
    }
}
