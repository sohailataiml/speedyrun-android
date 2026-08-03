/*
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.speedrun

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import anki.stats.ReadinessData
import anki.stats.ReadinessQueryResponse
import com.ichi2.anki.AnkiActivity
import com.ichi2.anki.R
import com.ichi2.anki.databinding.ActivityScoreDashboardBinding
import com.ichi2.anki.startup.ensureStorageIsReady
import com.ichi2.anki.utils.Destination
import dev.androidbroadcast.vbpd.viewBinding
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt

/* Must match rslib/src/stats/latency_monitor.rs's ROTE_PATTERN_CV_THRESHOLD.
 * Used only for highlighting rows; the *decision* to refuse is made in Rust
 * and arrives with its own threshold attached, so the two can't silently
 * disagree about the verdict. */
private const val ROTE_CV_THRESHOLD = 0.2f

/**
 * Speedrun addition: the three-score dashboard (PRD §5), Android side.
 * Mirrors qt/aqt/speedrun_dashboard.py's content and data flow - Memory is
 * always shown, Performance/Readiness are gated by the give-up rule. See
 * ARCHITECTURE.md §4/§6.
 */
class ScoreDashboard : AnkiActivity(R.layout.activity_score_dashboard) {
    private val binding by viewBinding(ActivityScoreDashboardBinding::bind)
    private val viewModel by viewModels<ScoreDashboardViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        if (showedActivityFailedScreen(savedInstanceState)) {
            return
        }
        super.onCreate(savedInstanceState)
        if (!ensureStorageIsReady()) {
            return
        }
        enableToolbar()

        binding.refreshButton.setOnClickListener { viewModel.refresh() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> bindState(state) }
            }
        }
    }

    private fun bindState(state: ScoreDashboardState) {
        binding.progressBar.isVisible = state.isLoading
        binding.content.isVisible = !state.isLoading && state.topics.isNotEmpty()
        binding.emptyState.isVisible = !state.isLoading && state.topics.isEmpty()
        if (state.isLoading || state.topics.isEmpty()) {
            return
        }

        bindMemory(state.mastery)
        bindLatency(state.mastery)
        bindPerformanceAndReadiness(state.readiness)

        binding.lastUpdated.text =
            state.lastUpdatedMillis?.let {
                getString(R.string.speedrun_last_updated, DateFormat.getTimeInstance().format(Date(it)))
            }
    }

    private fun bindMemory(mastery: List<anki.stats.TopicMastery>) {
        val withReviews = mastery.filter { it.cardsWithReviews > 0 }
        binding.memoryHeadline.text =
            if (withReviews.isNotEmpty()) {
                val overall = withReviews.sumOf { it.mastery.toDouble() } / withReviews.size
                getString(R.string.speedrun_memory_headline, (overall * 100).roundToInt())
            } else {
                getString(R.string.speedrun_memory_headline_empty)
            }

        binding.memoryTable.text =
            mastery.joinToString("\n") { topic ->
                val masteryText = if (topic.cardsWithReviews > 0) "${(topic.mastery * 100).roundToInt()}%" else "—"
                val recallText = if (topic.cardsWithReviews > 0) "${(topic.averageRecall * 100).roundToInt()}%" else "—"
                "${topic.topic}: mastery $masteryText, avg recall $recallText, " +
                    "${topic.cardsWithReviews}/${topic.cardsTotal} reviewed"
            }
    }

    /**
     * Brainlift v3 POV 1. Mirrors the desktop `_latency_group`.
     *
     * Only topics with a measured volatility are listed. `hasLatencyVolatility()`
     * is checked rather than reading the value, because proto3 defaults an
     * absent float to 0.0 - and 0.0 sits *below* the rote threshold, so a
     * naive read would present every unstudied topic as a confirmed
     * spacebar reflex.
     */
    private fun bindLatency(mastery: List<anki.stats.TopicMastery>) {
        val measurable = mastery.filter { it.hasLatencyVolatility() }
        if (measurable.isEmpty()) {
            binding.latencyHeadline.text = getString(R.string.speedrun_latency_headline_empty)
            binding.latencyTable.text = ""
            binding.latencyMethod.text = ""
            return
        }

        val rote = measurable.filter { it.latencyVolatility < ROTE_CV_THRESHOLD }
        binding.latencyHeadline.text =
            getString(R.string.speedrun_latency_headline, rote.size, measurable.size)

        val rows =
            measurable.sortedBy { it.latencyVolatility }.joinToString("\n") { topic ->
                val flag = if (topic.latencyVolatility < ROTE_CV_THRESHOLD) "  ⚠ rote pattern" else ""
                val reflex =
                    if (topic.belowMinReadingTimeCount > 0) {
                        " · ${topic.belowMinReadingTimeCount} faster than the card can be read"
                    } else {
                        ""
                    }
                "${topic.topic}: volatility ${"%.2f".format(topic.latencyVolatility)} · " +
                    "${topic.system1ReviewCount} fast / ${topic.system2ReviewCount} considered$reflex$flag"
            }
        val hidden = mastery.size - measurable.size
        binding.latencyTable.text =
            if (hidden > 0) {
                rows + "\n\n" + getString(R.string.speedrun_latency_hidden_topics, hidden)
            } else {
                rows
            }
        binding.latencyMethod.text =
            getString(R.string.speedrun_latency_method, "%.2f".format(ROTE_CV_THRESHOLD))
    }

    /**
     * One line per rule that actually failed, not just the first.
     *
     * The backend returns every failing reason; showing one would send the
     * student off to grind review count while a second blocker still
     * stands - and in the rote case it would hide the only reason that
     * says something about *how* they studied rather than how much.
     */
    private fun refusalReasons(insufficient: anki.stats.InsufficientData): String {
        val lines =
            insufficient.reasonsList.mapNotNull { reason ->
                when (reason) {
                    anki.stats.InsufficientData.Reason.NOT_ENOUGH_REVIEWS ->
                        getString(
                            R.string.speedrun_reason_not_enough_reviews,
                            insufficient.totalGradedReviews,
                            insufficient.reviewsRequired,
                        )
                    anki.stats.InsufficientData.Reason.NOT_ENOUGH_COVERAGE ->
                        getString(
                            R.string.speedrun_reason_not_enough_coverage,
                            (insufficient.topicCoverage * 100).roundToInt(),
                            (insufficient.coverageRequired * 100).roundToInt(),
                        )
                    anki.stats.InsufficientData.Reason.ROTE_PATTERN_DETECTED ->
                        getString(
                            R.string.speedrun_reason_rote_pattern,
                            (insufficient.rotePatternTopicFraction * 100).roundToInt(),
                            "%.2f".format(insufficient.rotePatternCvThreshold),
                            (insufficient.rotePatternFractionAllowed * 100).roundToInt(),
                        )
                    else -> null
                }
            }
        return if (lines.isEmpty()) getString(R.string.speedrun_reason_unknown) else lines.joinToString("\n\n")
    }

    /** "Not enough data" is the wrong headline for a rote refusal - there is
     * plenty of data, and it is the data that is the problem. */
    private fun isRote(insufficient: anki.stats.InsufficientData) =
        insufficient.reasonsList.contains(anki.stats.InsufficientData.Reason.ROTE_PATTERN_DETECTED)

    private fun bindPerformanceAndReadiness(readiness: ReadinessQueryResponse?) {
        when (readiness?.resultCase) {
            ReadinessQueryResponse.ResultCase.DATA -> {
                val data = readiness.data
                val performance = data.inputs

                binding.performanceHeadline.text =
                    getString(
                        R.string.speedrun_performance_headline,
                        (performance.predictedAccuracy * 100).roundToInt(),
                    )
                binding.performanceDetails.text =
                    getString(
                        R.string.speedrun_give_up_status_passed,
                        performance.inputs.totalGradedReviews,
                        (performance.inputs.topicCoverage * 100).roundToInt(),
                    )

                binding.readinessHeadline.text =
                    getString(R.string.speedrun_readiness_headline, data.projectedScore)
                binding.readinessDetails.text =
                    getString(
                        R.string.speedrun_readiness_details,
                        data.rangeLow,
                        data.rangeHigh,
                        confidenceLabel(data.confidence),
                    )
            }
            ReadinessQueryResponse.ResultCase.INSUFFICIENT -> {
                val insufficient = readiness.insufficient
                val rote = isRote(insufficient)
                binding.performanceHeadline.text =
                    getString(
                        if (rote) {
                            R.string.speedrun_performance_headline_rote
                        } else {
                            R.string.speedrun_performance_headline_insufficient
                        },
                    )
                binding.performanceDetails.text = refusalReasons(insufficient)
                binding.readinessHeadline.text =
                    getString(
                        if (rote) {
                            R.string.speedrun_readiness_headline_rote
                        } else {
                            R.string.speedrun_readiness_headline_insufficient
                        },
                    )
                binding.readinessDetails.text = ""
            }
            else -> {
                binding.performanceHeadline.text = getString(R.string.speedrun_performance_headline_unavailable)
                binding.readinessHeadline.text = ""
                binding.readinessDetails.text = ""
            }
        }
    }

    private fun confidenceLabel(confidence: ReadinessData.Confidence): String =
        when (confidence) {
            ReadinessData.Confidence.LOW -> getString(R.string.speedrun_confidence_low)
            ReadinessData.Confidence.MEDIUM -> getString(R.string.speedrun_confidence_medium)
            ReadinessData.Confidence.HIGH -> getString(R.string.speedrun_confidence_high)
            else -> ""
        }
}

class ScoreDashboardDestination : Destination {
    override fun toIntent(context: Context) = Intent(context, ScoreDashboard::class.java)
}
