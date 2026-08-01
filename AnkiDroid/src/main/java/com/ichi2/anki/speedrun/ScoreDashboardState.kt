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

import com.ichi2.anki.exception.ReportableException

/**
 * Speedrun addition: state for the three-score dashboard (PRD §5). Mirrors
 * qt/aqt/speedrun_dashboard.py's data shape - Memory is always available,
 * Performance/Readiness are gated by the give-up rule.
 */
data class ScoreDashboardState(
    val isLoading: Boolean = true,
    val error: ReportableException? = null,
    val topics: List<String> = emptyList(),
    val mastery: List<anki.stats.TopicMastery> = emptyList(),
    val readiness: anki.stats.ReadinessQueryResponse? = null,
    val lastUpdatedMillis: Long? = null,
)
