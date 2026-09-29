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
    /** Cross-source aliases require a unique same-type neighbour within 20 m in both directions. */
    fun aliases(source: List<Camera>): List<Pair<String, String>> {
        val osm = source.filter { it.source == CameraSource.OSM }
        val imported = source.filter { it.source == CameraSource.LUFOP }
        val grid = imported.groupBy { (it.point.lat * 5000).toInt() to (it.point.lon * 5000).toInt() }
        val links = osm.mapNotNull { item ->
            val cell = (item.point.lat * 5000).toInt() to (item.point.lon * 5000).toInt()
            val nearby = (-1..1).flatMap { y -> (-2..2).flatMap { x -> grid[cell.first + y to cell.second + x].orEmpty() } }
                .filter { it.type == item.type && Geo.distance(it.point, item.point) <= 20 }
            nearby.singleOrNull()?.let { item.id to it.id }
        }
        val counts=links.groupingBy { it.second }.eachCount()
        return links.filter { counts[it.second]==1 }
    }
    fun merge(source: List<Camera>, owner: List<Camera>, corrections: List<CameraCorrection>,
        suppressedIds: Set<String>, savedAliases: List<Pair<String, String>> = emptyList()): List<Camera> {
        val parents = mutableMapOf<String, String>()
        fun root(id: String): String {
            val parent = parents[id] ?: return id
            return if (parent == id) id else root(parent).also { parents[id] = it }
        }
        (savedAliases + aliases(source)).forEach { (a, b) ->
            val ra = root(a); val rb = root(b)
            if (ra != rb) parents[ra] = rb
        }
        val overrides = corrections.associateBy { it.id }
        val groupedCorrections=corrections.groupBy { root(it.id) }
        val groupedAliases=savedAliases.flatMap { listOf(it.first,it.second) }.groupBy { root(it) }
        val hidden = suppressedIds.mapTo(hashSetOf(), ::root)
        val effective = source.distinctBy { it.id }.groupBy { root(it.id) }.flatMap { (group, records) ->
            if (group in hidden) emptyList() else {
                val correction = groupedCorrections[group].orEmpty().maxByOrNull { it.updatedAtMs }
                val original = correction?.let { c -> records.firstOrNull { it.id == c.id && it.source == c.source }
                    ?: c.sourcePoint?.let { Camera(c.id, it, c.type, c.source) } }
                    ?: records.maxBy { (if (it.direction != null) 2 else 0) + (if (it.enforcedMph != null) 1 else 0) }
                val result = correction?.apply(original) ?: overrides[original.id]?.apply(original) ?: original
                val ids = (records.map { it.id } + groupedAliases[group].orEmpty()).toSet()
                listOf(if (ids.size > 1) result.copy(aliasIds = ids) else result)
            }
        }
        return (effective + owner).distinctBy { it.id }
    }
}
