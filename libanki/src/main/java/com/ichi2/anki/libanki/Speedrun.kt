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
package com.ichi2.anki.libanki

/**
 * Speedrun addition: thin Kotlin wrappers over the give-up-gate/Performance/
 * Readiness RPCs, matching the pattern already used for [masteryQuery] and
 * the pylib wrappers in collection.py. See ARCHITECTURE.md §6.
 */

fun Collection.masteryQueryTopics(topics: List<String>): List<anki.stats.TopicMastery> = backend.masteryQuery(topics)

fun Collection.giveUpGate(topics: List<String>): anki.stats.GiveUpGateResponse = backend.giveUpGate(topics)

fun Collection.performanceQuery(
    topics: List<String>,
    averageDifficulty: Float,
    averageTimingSeconds: Float,
): anki.stats.PerformanceQueryResponse = backend.performanceQuery(topics, averageDifficulty, averageTimingSeconds)

fun Collection.readinessQuery(
    topics: List<String>,
    averageDifficulty: Float,
    averageTimingSeconds: Float,
): anki.stats.ReadinessQueryResponse = backend.readinessQuery(topics, averageDifficulty, averageTimingSeconds)

/** All `topic::<name>` tags in the collection, with the prefix stripped. */
fun Collection.speedrunTopics(): List<String> =
    tags
        .all()
        .filter { it.startsWith("topic::") }
        .map { it.removePrefix("topic::") }
        .distinct()
        .sorted()
