package uk.co.traynor.speedbuddy

enum class LimitSpeechCategory { CURRENT, UPCOMING, CAMERA, OVERSPEED }
enum class LimitSpeechSource { REGIONAL, LEGACY, LIVE, OWNER_CORRECTION, OWNER_BOUNDARY, CAMERA_TAG, MATCHED_CAMERA_ROAD, UPCOMING_GEOMETRY, OTHER }
internal enum class SpeechOutcome { SCHEDULED, DEFERRED, SUBMITTED, PLAYBACK_STARTED, COMPLETED,
    CANCELLED_INVALID, SUPERSEDED, EXPIRED, MUTED, FAILED, FOCUS_DENIED, STOPPED, CLOSED, LATE_START_REJECTED }

/** Selection uses the final current-road presentation; a preview or camera tag is never promoted. */
internal object CurrentRoadSpeech {
    fun candidate(state: DriveState,now: Long): Int?=state.limitMph?.takeIf { mph ->
        PendingDrivingEvidence.limitSpeechRelevant(state,mph,now) && !state.pendingConfirmedLimit &&
            state.limitDecision?.assumed==false && (state.limitPresentation ?: state.limitDecision)?.assumed==false
    }
    fun source(state: DriveState)=when {
        state.limitDecision?.ownerApplied==true -> LimitSpeechSource.OWNER_CORRECTION
        state.limitDecision?.boundaryApplied==true -> LimitSpeechSource.OWNER_BOUNDARY
        state.roadData.provider=="Regional offline" -> LimitSpeechSource.REGIONAL
        state.roadData.provider.contains("Live") -> LimitSpeechSource.LIVE
        state.roadData.provider=="Legacy saved OSM" -> LimitSpeechSource.LEGACY
        else -> LimitSpeechSource.OTHER
    }
}

/** Keeps the existing numeric-change gate, but observes invalidation even between completed fixes. */
internal class CurrentRoadVoice(private val recorder: RoadFlightRecorder=RoadDecisionFlight.recorder) {
    private val gate=DeferredLimitVoice()
    private var deferred: FlightSpeechTicket?=null
    @Synchronized fun onPublication(state: DriveState,now: Long) {
        val ticket=deferred ?: return
        if(!PendingDrivingEvidence.limitSpeechRelevant(state,ticket.mph,now)) {
            recorder.speech(ticket,SpeechOutcome.CANCELLED_INVALID,state,now)
            deferred=null;gate.discardPending()
        }
    }
    @Synchronized fun update(state: DriveState,now: Long,busy: Boolean,enabled: Boolean): FlightSpeechTicket? {
        onPublication(state,now)
        val candidate=CurrentRoadSpeech.candidate(state,now)
        // An already verified deferred cue can wait through bounded same-road processing;
        // new current-road speech is never scheduled from an assumed state.
        if(candidate==null && enabled && deferred?.let { PendingDrivingEvidence.limitSpeechRelevant(state,it.mph,now) }==true) return null
        val ready=gate.update(candidate,busy,enabled)
        val number=ready ?: gate.deferredLimit
        val prior=deferred
        if(prior!=null && prior.mph!=number) {
            recorder.speech(prior,if(enabled) SpeechOutcome.SUPERSEDED else SpeechOutcome.MUTED,state,now);deferred=null
        }
        if(number!=null && deferred==null) {
            deferred=recorder.scheduleSpeech(LimitSpeechCategory.CURRENT,number,CurrentRoadSpeech.source(state),state,now)
            if(ready==null) recorder.speech(deferred!!,SpeechOutcome.DEFERRED,state,now)
        }
        return if(ready!=null) deferred.also { deferred=null } else null
    }
    @Synchronized fun close(state: DriveState,now: Long) { deferred?.let { recorder.speech(it,SpeechOutcome.CLOSED,state,now) };deferred=null;gate.discardPending() }
}

/** Only privacy-safe immutable state enters a ticket; no speech text or GPS geometry. */
internal class FlightSpeechTicket(val id: Long,val category: LimitSpeechCategory,val mph: Int,val source: LimitSpeechSource,
    val origin: FlightState,val scheduledElapsedMs: Long,val evidenceElapsedMs: Long?,val cameraId: String?) {
    internal var lastState=origin
    internal var lastOutcome: SpeechOutcome?=null
    internal var playback: FlightState?=null
}
internal data class FlightSpeech(val ticketId: Long,val category: LimitSpeechCategory,val mph: Int,val source: LimitSpeechSource,
    val outcome: SpeechOutcome,val scheduledElapsedMs: Long,val evidenceElapsedMs: Long?,val evidenceAgeMs: Long?,
    val origin: FlightState,val playback: FlightState?,val cameraId: String?) {
    fun json()=org.json.JSONObject().put("ticketId",ticketId).put("category",category.name).put("spokenMph",mph)
        .put("evidenceSource",source.name).put("result",outcome.name).put("scheduledElapsedMs",scheduledElapsedMs)
        .put("evidenceElapsedMs",evidenceElapsedMs ?: org.json.JSONObject.NULL).put("evidenceAgeMs",evidenceAgeMs ?: org.json.JSONObject.NULL)
        .put("evidenceTimestampKind",if(source==LimitSpeechSource.CAMERA_TAG) "GPS applicability verification" else "Road decision fix")
        .put("evidenceProvider",origin.provider).put("roadMatchConfidence",origin.matchConfidence ?: org.json.JSONObject.NULL)
        .put("evidenceConfidence",if(source==LimitSpeechSource.CAMERA_TAG) "Camera-specific tag; applicability checked separately" else origin.decision.status)
        .put("originFixId",origin.fixId ?: org.json.JSONObject.NULL).put("originFixSequence",origin.fixSequence ?: org.json.JSONObject.NULL)
        .put("originRoadId",origin.roadId ?: org.json.JSONObject.NULL).put("cameraId",cameraId ?: org.json.JSONObject.NULL)
        .put("displayedAtScheduling",origin.displayedMph ?: org.json.JSONObject.NULL)
        .put("playbackObserved",playback!=null).put("displayedAtPlayback",playback?.displayedMph ?: org.json.JSONObject.NULL)
        .put("playback",playback?.json() ?: org.json.JSONObject.NULL)
}
