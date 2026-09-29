package uk.co.traynor.speedbuddy

/** Both the map and the alert service consume this effective camera view. */
object CameraLayers {
    /** Lufop has coordinate-derived IDs. Only an unambiguous same-type move may carry owner edits forward. */
    fun reconcileId(old: Camera, replacement: List<Camera>): String? {
        val nearby = replacement.asSequence().filter { it.type == old.type }
            .map { it to Geo.distance(old.point, it.point) }.filter { it.second <= 35.0 }
            .sortedBy { it.second }.take(2).toList()
        if (nearby.isEmpty() || nearby.size > 1 && nearby[1].second - nearby[0].second < 15.0) return null
        return nearby[0].first.id
    }
    fun merge(source: List<Camera>, owner: List<Camera>, corrections: List<CameraCorrection>,
        suppressedIds: Set<String>): List<Camera> {
        val overrides = corrections.associateBy { it.id }
        val effectiveSource = source.asSequence().filterNot { it.id in suppressedIds }.map { original ->
            overrides[original.id]?.apply(original) ?: original
        }
        return (effectiveSource + owner.asSequence()).distinctBy { it.id }.toList()
    }
}
