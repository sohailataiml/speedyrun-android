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
                binding.performanceHeadline.text = getString(R.string.speedrun_performance_headline_insufficient)
                binding.performanceDetails.text =
                    getString(
                        R.string.speedrun_give_up_status_refused,
                        insufficient.totalGradedReviews,
                        insufficient.reviewsRequired,
                        (insufficient.topicCoverage * 100).roundToInt(),
                        (insufficient.coverageRequired * 100).roundToInt(),
                    )
                binding.readinessHeadline.text = getString(R.string.speedrun_readiness_headline_insufficient)
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
