package uk.co.traynor.speedbuddy

/** Local inventory remains the authority for installed state when catalogue/authentication fails. */
internal object OfflinePackRows {
    data class Row(val pack: RegionalPackDescriptor,val installed: RegionalPackDescriptor?)
    fun merge(catalogue: List<RegionalPackDescriptor>,installed: List<RegionalPackDescriptor>): List<Row> =
        (installed+catalogue).map { it.id }.distinct().sorted().map { id ->
            val local=installed.firstOrNull { it.id==id }
            Row(catalogue.firstOrNull { it.id==id } ?: local!!,local)
        }
}
