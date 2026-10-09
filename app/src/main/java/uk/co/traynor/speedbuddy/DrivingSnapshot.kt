package uk.co.traynor.speedbuddy

/** Reject a suspended owner-data read if an edit happened before publication. */
internal suspend fun <T> readCurrentOwnerSnapshot(revision: () -> Long, load: suspend () -> T): Pair<Long,T>? {
    val before=revision()
    val value=load()
    return if(before==revision()) before to value else null
}
