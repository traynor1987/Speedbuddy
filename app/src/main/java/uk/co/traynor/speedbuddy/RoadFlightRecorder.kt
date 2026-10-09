package uk.co.traynor.speedbuddy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal enum class FlightStage { GPS, REGIONAL, DECISION, PRESENTATION, PUBLISHED, REJECTED }

/** A whitelist, not a copy of DriveState: no points, names, tags, credentials or free text. */
internal data class FlightDecision(val mph: Int?,val status: String,val assumed: Boolean,
    val owner: Boolean,val changing: Boolean,val reason: String,val evidenceAgeMs: Long?,val evidenceDistanceM: Double?) {
    companion object {
        fun from(value: LimitDecision?,now: Long)=FlightDecision(value?.mph,
            when { value?.changing==true -> "changing";value?.mph==null -> "unknown";value.assumed -> "assumed";value.ownerApplied -> "owner confirmed";else -> "confirmed" },
            value?.assumed==true,value?.ownerApplied==true,value?.changing==true,FlightReasons.safe(value?.reason),
            value?.evidenceElapsedMs?.let { (now-it).coerceAtLeast(0) },value?.evidenceDistanceM?.takeIf { it.isFinite() })
    }
    fun json()=JSONObject().put("mph",mph ?: JSONObject.NULL).put("status",status).put("assumed",assumed)
        .put("ownerCorrection",owner).put("changing",changing).put("reason",reason)
        .put("evidenceAgeMs",evidenceAgeMs ?: JSONObject.NULL).put("evidenceDistanceM",evidenceDistanceM ?: JSONObject.NULL)
    fun label()=if(status=="not evaluated") "Not evaluated at this stage" else "${mph?.let { "$it mph" } ?: "Unknown"} · $status"
}
internal data class FlightState(val fixId: Long?,val fixSequence: Long?,val fixAgeMs: Long?,val speedMph: Double?,
    val accuracyM: Double?,val headingValid: Boolean,val bearing: Double?,val provider: String,val providerState: String,
    val roadId: String?,val matchConfidence: Double?,val sourceMph: Int?,val generation: String?,
    val decision: FlightDecision,val presentation: FlightDecision,val displayedMph: Int?,val currentFixDecision: Boolean,
    val fallback: String,val decisionRecorded: Boolean,val presentationRecorded: Boolean,val displayRecorded: Boolean,
    val filteredSpeedMph: Double?,val filteredSpeedRecorded: Boolean) {
    fun json()=JSONObject().apply {
        put("fixId",fixId ?: JSONObject.NULL);put("fixSequence",fixSequence ?: JSONObject.NULL);put("fixAgeMs",fixAgeMs ?: JSONObject.NULL)
        put("gpsSpeedMph",speedMph ?: JSONObject.NULL);put("accuracyM",accuracyM ?: JSONObject.NULL)
        put("filteredSpeedRecorded",filteredSpeedRecorded);put("filteredSpeedMph",filteredSpeedMph ?: JSONObject.NULL)
        put("headingValid",headingValid);put("bearing",bearing ?: JSONObject.NULL);put("provider",provider);put("providerState",providerState)
        put("roadId",roadId ?: JSONObject.NULL);put("matchConfidence",matchConfidence ?: JSONObject.NULL)
        put("rawSourceMph",sourceMph ?: JSONObject.NULL);put("generation",generation ?: JSONObject.NULL)
        put("decisionRecorded",decisionRecorded);put("presentationRecorded",presentationRecorded);put("displayRecorded",displayRecorded)
        put("decision",if(decisionRecorded) decision.json() else JSONObject.NULL)
        put("presentation",if(presentationRecorded) presentation.json() else JSONObject.NULL)
        put("displayedMph",if(displayRecorded) displayedMph ?: JSONObject.NULL else JSONObject.NULL)
        put("currentFixDecision",currentFixDecision);put("fallbackReason",fallback)
    }
    // Ages belong in the receipt, but passage of time alone is not a decision change.
    fun meaning()=copy(fixAgeMs=null,decision=decision.copy(evidenceAgeMs=null),presentation=presentation.copy(evidenceAgeMs=null))
    fun decisionMeaning()=Triple(decision.copy(evidenceAgeMs=null,evidenceDistanceM=null),
        presentation.copy(evidenceAgeMs=null,evidenceDistanceM=null),displayedMph)
}
internal data class FlightEvent(val sequence: Long,val atMs: Long,val stage: FlightStage,val previous: FlightState?,
    val new: FlightState,val cause: String,val decisionChanged: Boolean) {
    fun json()=JSONObject().put("sequence",sequence).put("monotonicMs",atMs).put("stage",stage.name)
        .put("previous",previous?.json() ?: JSONObject.NULL).put("new",new.json()).put("cause",cause).put("decisionChanged",decisionChanged)
}
internal data class FlightHistory(val capturedAtMs: Long,val events: List<FlightEvent>,val current: FlightState?,val previousDecision: FlightState?) {
    fun decisionChanges(now: Long=capturedAtMs)=events.count { it.decisionChanged && now-it.atMs in 0..30_000 }
    fun report(): String=JSONObject().put("schema","speedbuddy-road-flight-v1").put("capturedMonotonicMs",capturedAtMs)
        .put("retention","Process-local rolling 500 meaningful events; reset on process exit or clear")
        .put("privacy","No precise coordinates, road names, raw road IDs, credentials or personal identifiers; road IDs are session-salted")
        .put("current",current?.json() ?: JSONObject.NULL).put("events",JSONArray(events.map { it.json() })).toString(2)
}

/** Short memory-only critical sections. Export/file IO and rendering are always outside this lock. */
internal class RoadFlightRecorder(private val capacity: Int=500,private val clock: () -> Long={System.nanoTime()/1_000_000}) {
    init { require(capacity in 1..500) }
    private val salt=UUID.randomUUID().toString()
    private val events=ArrayDeque<FlightEvent>()
    private val last=mutableMapOf<FlightStage,FlightState>()
    private val fixIds=linkedMapOf<Long,Long>()
    private var fixSequence=0L
    private var sequence=0L
    private var lastAt=0L
    private var current: FlightState?=null
    private var previousDecision: FlightState?=null
    private val revision=MutableStateFlow(0L)
    val changes=revision.asStateFlow()
    private val opaqueIds=linkedMapOf<String,String>()
    private fun opaque(value: String?,prefix: String): String?=value?.let {
        val key=prefix+it
        val hashed=opaqueIds.getOrPut(key) {
            prefix+MessageDigest.getInstance("SHA-256").digest((salt+it).toByteArray()).take(12).joinToString("") { b -> "%02x".format(b) }
        }
        if(opaqueIds.size>500) opaqueIds.remove(opaqueIds.keys.first())
        hashed
    }
    private fun finite(value: Double?)=value?.takeIf { it.isFinite() }
    @Synchronized fun record(stage: FlightStage,state: DriveState,cause: String,now: Long,generation: String?=null) {
        val fix=state.fix
        val id=fix?.elapsedMs
        val fixNumber=id?.let { fixIds.getOrPut(it) { ++fixSequence } }
        if(fixIds.size>500) fixIds.remove(fixIds.keys.first())
        val decisions=stage in setOf(FlightStage.DECISION,FlightStage.PRESENTATION,FlightStage.PUBLISHED)
        val presents=stage in setOf(FlightStage.PRESENTATION,FlightStage.PUBLISHED)
        val unevaluated=FlightDecision(null,"not evaluated",false,false,false,"Not evaluated at this stage",null,null)
        val next=FlightState(id,fixNumber,fix?.let { now-it.elapsedMs },finite(fix?.speedMps?.times(MPS_TO_MPH)),
            finite(fix?.accuracyM),fix?.bearing?.let { it.isFinite() && it in 0.0..360.0 }==true,finite(fix?.bearing),
            if(stage in setOf(FlightStage.GPS,FlightStage.REJECTED)) "Not captured at this stage" else state.roadData.provider.takeIf { it in FlightReasons.providers } ?: "Other provider",
            state.roadData.providerState.takeIf { it in FlightReasons.providerStates } ?: "Not evaluated",
            opaque(state.road?.road?.id,"road-"),finite(state.road?.confidence),state.sourceLimitMph,opaque(generation,"generation-"),
            if(decisions) FlightDecision.from(state.limitDecision,now) else unevaluated,
            if(presents) FlightDecision.from(state.limitPresentation ?: state.limitDecision,now) else unevaluated,
            state.limitMph.takeIf { presents },id!=null && state.roadDecisionElapsedMs==id,
            FlightReasons.safe(state.roadData.fallbackReason ?: state.roadData.error),decisions,presents,presents,
            finite(state.speedMph),stage in setOf(FlightStage.GPS,FlightStage.PUBLISHED))
        val prior=last[stage]
        if(prior?.meaning()==next.meaning()) return
        val changed=stage==FlightStage.PUBLISHED && current!=null && current!!.decisionMeaning()!=next.decisionMeaning()
        if(stage==FlightStage.PUBLISHED) { if(changed) previousDecision=current;current=next }
        last[stage]=next
        val at=maxOf(clock(),lastAt);lastAt=at
        events.addLast(FlightEvent(++sequence,at,stage,prior,next,FlightReasons.cause(cause),changed))
        while(events.size>capacity) events.removeFirst()
        revision.value++
    }
    @Synchronized fun snapshot()=FlightHistory(maxOf(clock(),lastAt),events.toList(),current,previousDecision)
    @Synchronized fun clear() { events.clear();last.clear();current=null;previousDecision=null;fixIds.clear();opaqueIds.clear();revision.value++ }
}

/** Dynamic reasons can contain road IDs or remote/server text. Export only known local classifications. */
internal object FlightReasons {
    val providers=setOf("None","Regional offline","Legacy saved OSM","Live server","Live server with legacy geometry")
    val providerStates=RoadProviderState.entries.map { it.name }.toSet()+setOf("LEGACY_MATCHED","NO_LOCAL_MATCH","Not evaluated")
    private val exact=setOf("Awaiting fresh road match","Owner road correction","Owner reported early; waiting for Changed now",
        "Saved owner boundary not yet crossed","Crossed saved owner boundary","Confirmed local road limit","Confirming road transition",
        "Brief match ambiguity on previous geometry","Resolving connected road transition","Unavailable: No recent confirmed limit",
        "Unavailable: no confident local road match","Unavailable: GPS accuracy exceeds 35 m",
        "Unavailable: insufficient GPS/road evidence while recording boundary","Unavailable: confirming a new road; prior geometry no longer fits",
        "Unavailable: road transition unresolved after presentation window","Unavailable: transition presentation expired",
        "Unavailable: stale presentation frame","Unavailable: stale GPS fix; decision history unchanged",
        "Regional packs unavailable","No regional coverage at this position",
        "Live request pending or unavailable; saved legacy geometry used when reliable",
        "Some regional pack files are corrupt, missing or unreadable","Regional geometry incomplete or conflicting",
        "Live road identity does not agree with fresh local geometry","Live road selection uncertain","Live road service unavailable")
    private val drops=listOf("Assumption ended: road match lost confidence or previous geometry no longer fits",
        "Assumption ended: GPS or heading uncertain","Assumption ended: travel direction changed","Assumption expired after 2 seconds",
        "Assumption expired after 30 metres","Assumption ended: road geometry disconnected","Assumption ended: conditional or directional source limit",
        "Assumption ended: road context changed")
    fun safe(raw: String?): String=when {
        raw==null -> "No decision reason"
        raw in exact -> raw
        raw.startsWith("Assumed ") -> "Assumed bounded continuity"
        raw.startsWith("Owner confirmed still ") -> "Owner confirmed prior limit; waiting for forward boundary observation"
        else -> drops.firstOrNull { raw=="Unavailable: $it" || raw.startsWith("Unavailable: $it; inherited ") } ?: "Other reason (untrusted details omitted)"
    }
    fun cause(raw: String)=raw.takeIf { it in setOf("gps accepted","gps rejected","regional result","regional superseded","pipeline evidence",
        "presentation resolved","state published","pending GPS matching","pending expired","transition expired","stale publication rejected","verified","pending","history cleared") } ?: "source changed"
}

internal object RoadDecisionFlight { val recorder=RoadFlightRecorder() }

/** Process-local frozen inspection survives Activity recreation, never writes to the driving bus. */
internal data class FrozenDiagnostics(val state: DriveState,val history: FlightHistory,val elapsedMs: Long,val wallMs: Long)
internal object DiagnosticsInspection {
    private val frozen=MutableStateFlow<FrozenDiagnostics?>(null)
    val state=frozen.asStateFlow()
    fun freeze(state: DriveState,history: FlightHistory,elapsedMs: Long,wallMs: Long) { frozen.value=FrozenDiagnostics(state,history,elapsedMs,wallMs) }
    fun unfreeze() { frozen.value=null }
}
