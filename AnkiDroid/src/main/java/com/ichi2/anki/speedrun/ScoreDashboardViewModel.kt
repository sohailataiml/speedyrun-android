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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.CollectionManager.withCol
import com.ichi2.anki.exception.ReportableException
import com.ichi2.anki.libanki.Collection
import com.ichi2.anki.libanki.masteryQueryTopics
import com.ichi2.anki.libanki.readinessQuery
import com.ichi2.anki.libanki.speedrunTopics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

// Placeholder inputs until a real exam-style question flow exists to
// measure these per attempt - matches qt/aqt/speedrun_dashboard.py.
private const val ASSUMED_DIFFICULTY = 0.5f
private const val ASSUMED_TIMING_SECONDS = 70.0f

private data class DashboardData(
    val topics: List<String>,
    val mastery: List<anki.stats.TopicMastery>,
    val readiness: anki.stats.ReadinessQueryResponse?,
)

class ScoreDashboardViewModel : ViewModel() {
    val state: StateFlow<ScoreDashboardState>
        field = MutableStateFlow(ScoreDashboardState())

    init {
        refresh()
    }

    fun refresh() {
        Timber.i("Refreshing Speedrun score dashboard")
        state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            withCol { safeFetchDashboardData() }
                .onFailure { exception ->
                    state.update { it.copy(isLoading = false, error = ReportableException(exception)) }
                }.onSuccess { data ->
                    state.update {
                        it.copy(
                            isLoading = false,
                            topics = data.topics,
                            mastery = data.mastery,
                            readiness = data.readiness,
                            lastUpdatedMillis = System.currentTimeMillis(),
                        )
                    }
                }
        }
    }
}

private fun Collection.safeFetchDashboardData(): Result<DashboardData> =
    try {
        val topics = speedrunTopics()
        if (topics.isEmpty()) {
            Result.success(DashboardData(topics = emptyList(), mastery = emptyList(), readiness = null))
        } else {
            val mastery = masteryQueryTopics(topics)
            val readiness = readinessQuery(topics, ASSUMED_DIFFICULTY, ASSUMED_TIMING_SECONDS)
            Result.success(DashboardData(topics, mastery, readiness))
        }
    } catch (exception: Exception) {
        Result.failure(exception)
    }
