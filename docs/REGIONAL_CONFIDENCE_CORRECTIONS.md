# Regional confidence and false transitions — 9 October 2026

Starting source: `b7f2f7fa162fb3623b8c96af9366967af0aba3ba`. Starting push #296 and PR #297 passed. This focused correction remains on draft PR #7; main is unchanged. Physical acceptance remains failed/pending owner retest. No claim is made that the previously reported approximately 15-second Unknown boundary gap is fixed.

## Evidence traced

The installed-pack matcher reads canonical `way/<OSM ID>` geometry and directional tags, rejects corrupt/conflicting overlap and ambiguous road identity, then supplies provider facts to DrivingLimitPipeline. The pipeline uses covered regional facts ahead of legacy/live data, applies owner rules against reliable fresh geometry, and passes the numeric evidence through LimitDecisionEngine. Regional and legacy parsing use PackSpeedLimits; unsupported conditional/lane/variable limits remain unknown. RoadMatcher history and parallel-road thresholds are unchanged.

The display layer added its own confidence-recovery latch. DrivingService fed every pending GPS decision into that latch, resetting its timer. A completed confirmed 30 could therefore become presentation-assumed again indefinitely during normal one-second updates. This is a confirmed source of excessive ASSUMED indications independent of regional numeric evidence. A genuine regional gap remains a bounded assumption or Unknown; a covered uncertain/unknown match is not filled with convenient legacy/live numbers. Compatible legacy/live evidence remains supported outside regional authority, and is tested. No evidence establishes that stale/conflicting data should override the pack hierarchy, so that hierarchy is preserved.

LimitPresentation classified any upcoming entry as unresolved, even when its number equalled the current confirmed number. Equal numeric entries do not establish a legal-limit transition. A genuine unresolved turn still needs credible connected geometry/movement/heading, has a five-second presentation deadline, and resolves to fresh evidence or Unknown.

The Unknown helper text was gated by correction-button readiness. Pending GPS publication invalidates correction readiness; completed matching restores it even when the legal limit is unknown. The helper consequently appeared/disappeared with lookup completion. Supporting text should describe unresolved numeric evidence consistently; button availability continues to use fresh road identity independently. Confirmed numeric evidence is never delayed for text stability.

A separate regional transition regression checks that a strongly matched new road, clearly beyond a connected shared boundary and outside the GPS uncertainty margin, need not wait another fixed confirmation timer. Ambiguous, weak, before-boundary or disconnected evidence retains existing conservative stabilization. Owner boundary observations and overrides take precedence.

## Retest

Retain Lancashire and Merseyside 20261006-1 and existing owner data. On ordinary known roads, compare the posted number and Diagnostics source/decision/presentation reasons. Check 30→30 junctions, 20→30 at the actual sign, genuinely unknown turns, stationary updates, a directional road, nearby parallel roads and camera speech. A confidence-only 30 assumed→30 confirmed update must not repeat numeric limit speech. Camera encounters still use the newly matched road. Capture diagnostics while stopped for continued uncertainty, incorrect numbers or long gaps.

Deterministic fixtures establish regression behavior, not the correctness of every production-pack tag or the owner's route. Production-pack matching and the earlier long boundary gap still require physical evidence.

## Validation scope

New regressions exercise real installed RTree fixture packs as well as the shared JVM pipeline. The fixtures cover equal limits, immediate different limits, stationary confirmation, reverse one-way geometry, owner still-here and saved boundary priority, genuine gaps, conflicting/unknown provider authority, parallel ambiguity, live/legacy selection, numeric speech gating, camera encounter continuity, stale frames, and UI recreation/readiness changes. The previous tests that required presentation-only assumption after fresh confirmation now require confidence recovery immediately; numeric/genuine-gap assertions remain intact.
