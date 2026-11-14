package ai.cargo.feature.editor

import ai.cargo.contracts.HandlingStatus
import ai.cargo.contracts.JudgementSnapshot
import ai.cargo.contracts.OpeningStatus
import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.SearchStatus
import ai.cargo.contracts.UncertifiedReason
import ai.cargo.designsystem.CargoStatusKind
import ai.cargo.geometry.PathVerdict
import ai.cargo.geometry.RemovalVerdict
import ai.cargo.geometry.SceneGeometry
import ai.cargo.scene.IssueKind
import ai.cargo.scene.SceneContext
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.SearchInterruptCause
import ai.cargo.scene.StaleAnalysis
import ai.cargo.scene.StaleReason

/** One independent axis. [id] is stable and doubles as the node tag on screen. */
data class AxisRow(val id: String, val kind: CargoStatusKind, val text: String, val code: String)

data class InstanceDetail(val instanceId: String, val name: String, val lines: List<String>)

data class JudgementBoard(
    val rows: List<AxisRow>,
    val staleReasons: List<String>,
    val restoredRecord: Boolean,
    val fitsConfirmed: Boolean,
    val clearanceText: String?,
    val conflictLocations: List<String>,
    val issues: List<String>,
    val instances: List<InstanceDetail>,
)

/**
 * The single mapping from engine results to words and status meanings. There is no overall verdict field: axes stay
 * separate rows, nothing else may combine them, and a row only reads as clear when its own axis is current and clear.
 */
object JudgementPresenter {
    const val NO_CANDIDATE_TEXT = "검사한 후보에서 배치를 찾지 못했습니다. 적재 불가로 증명되지는 않았습니다."
    const val NOT_ASSESSED_TEXT = "안전·고정·중량: 판정하지 않음"
    const val DEMO_LABEL = "합성 시연용·실제 차량 치수 아님"

    fun board(state: EditorUiState): JudgementBoard {
        val draft = state.draft
        val context = state.context
        if (draft == null || context == null) return empty()
        val stale = StaleAnalysis.forDraft(draft, context)
        val validation = draft.validation
        // Only reasons a user can act on are listed. "Not validated yet" alone is its own axis wording.
        val staleReasons = stale.filterNot { it == StaleReason.NOT_VALIDATED }
        val snapshot = validation?.snapshot?.takeIf { stale.isEmpty() }
        val issues = validation?.takeIf { stale.isEmpty() }?.issues.orEmpty()
        val names = draft.instances.associate { i -> i.instanceId to (context.item(ai.cargo.scene.ItemRef(i.itemId, i.itemRevision))?.name ?: "물품") }
        return JudgementBoard(
            rows = axes(snapshot, staleReasons.isNotEmpty(), state.notAvailable) + search(draft) + notAssessed(),
            staleReasons = staleReasons,
            restoredRecord = validation?.restoredFromRevision == true && stale.isEmpty(),
            fitsConfirmed = StaleAnalysis.fitsConfirmed(draft, context),
            clearanceText = snapshot?.let { "최소 여유 ${it.minimumClearanceMm} mm · 판정 오차 합계 ±${it.errorMm} mm" },
            conflictLocations = snapshot?.conflictLocations.orEmpty().map { "x ${it.x} · y ${it.y} · z ${it.z}" },
            issues = issues.map { "${names[it.instanceId] ?: "물품"}: ${issueText(it.kind)}" },
            instances = if (stale.isEmpty()) instanceDetails(state.geometry, names) else emptyList(),
        )
    }

    private fun empty() = JudgementBoard(emptyList(), emptyList(), false, false, null, emptyList(), emptyList(), emptyList())

    private fun axes(s: JudgementSnapshot?, stale: Boolean, notAvailable: NotAvailableNote?): List<AxisRow> {
        if (s == null) return pendingAxes(stale, notAvailable)
        return listOf(placement(s.placement), opening(s.opening), path(s.path.status, s.path.pathClass), handling(s.handling))
    }

    /** No current judgement: no axis may look clear, and the old values stay out of sight. */
    private fun pendingAxes(stale: Boolean, notAvailable: NotAvailableNote?): List<AxisRow> {
        val kind = if (stale) CargoStatusKind.STALE else CargoStatusKind.UNVERIFIED
        val tail = when {
            notAvailable != null -> "판정 불가(${notAvailable.code})"
            stale -> "이전 판정은 현재 입력과 달라 숨겼습니다. 재검증 필요"
            else -> "아직 판정하지 않음"
        }
        val code = when {
            notAvailable != null -> "NOT_AVAILABLE_${notAvailable.code}"
            stale -> "STALE"
            else -> "NOT_JUDGED"
        }
        return listOf("placement" to "내부 배치", "opening" to "입구 치수", "path" to "연속 반입경로", "handling" to "취급 조건")
            .map { (id, name) -> AxisRow(id, kind, "$name: $tail", code) }
    }

    private fun placement(p: PlacementStatus) = when (p) {
        PlacementStatus.CLEAR -> AxisRow("placement", CargoStatusKind.CLEAR, "내부 배치: 충돌·경계 초과 없음(오차 포함, 모델 기준)", p.name)
        PlacementStatus.CONFLICT -> AxisRow("placement", CargoStatusKind.CONFLICT, "내부 배치: 충돌 또는 경계 초과", p.name)
        PlacementStatus.UNCERTAIN -> AxisRow("placement", CargoStatusKind.UNCERTAIN, "내부 배치: 오차에 따라 달라짐(불확실)", p.name)
    }

    private fun opening(o: OpeningStatus) = when (o) {
        OpeningStatus.FITS -> AxisRow("opening", CargoStatusKind.CLEAR, "입구 치수: 단면이 입구 치수 안에 듦(경로 통과와 별개)", o.name)
        OpeningStatus.DOES_NOT_FIT -> AxisRow("opening", CargoStatusKind.CONFLICT, "입구 치수: 허용 자세 어느 것도 입구에 들지 않음", o.name)
        OpeningStatus.UNCERTAIN -> AxisRow("opening", CargoStatusKind.UNCERTAIN, "입구 치수: 오차에 따라 달라짐(불확실)", o.name)
        OpeningStatus.OPENING_UNMEASURED -> AxisRow("opening", CargoStatusKind.UNVERIFIED, "입구 치수: 입구 미측정. 입구 폭·높이를 재서 공간 설정에 입력하세요", o.name)
    }

    private fun path(status: String, pathClass: String?) = when (status) {
        PathVerdict.VERIFIED_IN_MODEL.name -> AxisRow("path", CargoStatusKind.CLEAR, "연속 반입경로: 검사한 직선 삽입 경로에서 통과(모델 기준, $pathClass)", status)
        PathVerdict.NOT_FOUND_IN_CHECKED_CLASS.name -> AxisRow("path", CargoStatusKind.UNCERTAIN, "연속 반입경로: 검사한 경로에서 찾지 못함(불가 판정 아님)", status)
        else -> AxisRow("path", CargoStatusKind.UNVERIFIED, "연속 반입경로: 미검증", status)
    }

    private fun handling(h: HandlingStatus) = when (h) {
        HandlingStatus.OK -> AxisRow("handling", CargoStatusKind.CLEAR, "취급 조건: 회전·지지·적층 조건 위반 없음", h.name)
        HandlingStatus.VIOLATION -> AxisRow("handling", CargoStatusKind.CONFLICT, "취급 조건: 위반 있음(아래 목록)", h.name)
    }

    /** Search has its own lifetime in the draft, so an interruption stays visible with no judgement present. */
    private fun search(draft: SceneDraftState): List<AxisRow> {
        val last = draft.lastSearch
        if (draft.pendingSearch != null) return listOf(AxisRow("search", CargoStatusKind.UNVERIFIED, "후보 탐색: 진행 중", "RUNNING"))
        if (last == null) return listOf(AxisRow("search", CargoStatusKind.UNVERIFIED, "후보 탐색: 실행하지 않음", SearchStatus.NOT_RUN.name))
        return listOf(
            when (last.status) {
                SearchStatus.FOUND_PLACEMENT -> AxisRow("search", CargoStatusKind.CLEAR, "후보 탐색: 후보를 찾음", last.status.name)
                SearchStatus.CERTIFIED_INFEASIBLE_IN_MODEL -> AxisRow("search", CargoStatusKind.CONFLICT, "후보 탐색: 한 상자·정적 장애물 모델 안에서는 놓을 자리가 없음(이 모델 한정)", last.status.name)
                SearchStatus.NO_CANDIDATE_UNCERTIFIED -> AxisRow("search", CargoStatusKind.UNCERTAIN, "$NO_CANDIDATE_TEXT (${reason(last.reason)})", last.status.name)
                SearchStatus.SEARCH_INTERRUPTED -> AxisRow("search", CargoStatusKind.UNCERTAIN, "후보 탐색: 탐색 중단(${cause(last.interruptCause)}). 불가 판정이 아니며 이어할 수 있습니다", last.status.name)
                SearchStatus.NOT_RUN -> AxisRow("search", CargoStatusKind.UNVERIFIED, "후보 탐색: 실행하지 않음", last.status.name)
            },
        )
    }

    private fun reason(r: UncertifiedReason?) = when (r) {
        UncertifiedReason.PROOF_CLASS_UNSUPPORTED -> "복합 형상은 증명 범위 밖"
        UncertifiedReason.SUPPORT_CONSTRAINT_UNPROVEN -> "지지 조건은 증명 범위 밖"
        UncertifiedReason.MULTI_ITEM_PROOF_UNSUPPORTED -> "기존 짐이 있으면 증명 범위 밖"
        null -> "사유 미기록"
    }

    private fun cause(c: SearchInterruptCause?) = when (c) {
        SearchInterruptCause.BUDGET -> "검사 한도 도달"
        SearchInterruptCause.CANCELLED -> "사용자 취소"
        SearchInterruptCause.PROCESS_DEATH -> "앱 종료"
        null -> "사유 미기록"
    }

    private fun notAssessed() = listOf(AxisRow("safety", CargoStatusKind.UNVERIFIED, NOT_ASSESSED_TEXT, "NOT_ASSESSED"))

    fun issueText(kind: IssueKind) = when (kind) {
        IssueKind.PLACEMENT_CONFLICT -> "충돌 또는 경계 초과"
        IssueKind.ROTATION_NOT_ALLOWED -> "허용되지 않은 회전"
        IssueKind.AIRBORNE -> "공중 배치(아래 지지 없음)"
        IssueKind.INSUFFICIENT_SUPPORT -> "지지 부족"
        IssueKind.ON_NON_STACKABLE -> "적층 금지 물품 위에 놓임"
    }

    fun staleText(reason: String) = when (reason) {
        StaleReason.ITEM_VERSION_APPLIED -> "물품 새 버전을 적용함"
        StaleReason.PROFILE_VERSION_APPLIED -> "공간 새 버전을 적용함"
        StaleReason.ENGINE_VERSION_CHANGED -> "판정 엔진 버전이 달라짐"
        StaleReason.CONFIGURATION_CHANGED -> "판정 설정이 달라짐"
        StaleReason.INPUT_CHANGED -> "저장 뒤 입력이 달라짐"
        StaleReason.REFERENCED_VERSION_MISSING -> "참조한 버전을 읽을 수 없음"
        StaleReason.LEGACY_AXIS_UPCAST -> "이전 좌표계에서 변환됨"
        else -> reason
    }

    private fun instanceDetails(geometry: SceneGeometry?, names: Map<String, String>): List<InstanceDetail> {
        if (geometry == null) return emptyList()
        return names.map { (id, name) ->
            InstanceDetail(id, name, buildList {
                geometry.openingDetails.firstOrNull { it.instanceId == id }?.let { add("입구: ${it.status.name}") }
                add("경로: " + (geometry.pathDetails.firstOrNull { it.instanceId == id }?.verdict?.name ?: "이번 편집 대상 아님(미검증)"))
                geometry.support.firstOrNull { it.instanceId == id }?.let { add("지지 면적 ${it.supportedAreaMm2} mm²") }
                geometry.removal.firstOrNull { it.instanceId == id }?.let {
                    add(
                        when (it.verdict) {
                            RemovalVerdict.CLEAR_IN_CHECKED_CLASS -> "꺼냄: 직선 경로에서 가리지 않음"
                            RemovalVerdict.BLOCKED_IN_CHECKED_CLASS -> "꺼냄 경고: 나중 짐이 가림"
                            RemovalVerdict.ERROR_DECIDES -> "꺼냄 경고: 오차에 따라 가릴 수 있음"
                        },
                    )
                }
            })
        }
    }

    /** Provenance reads the same on every screen and stays after restore. */
    fun provenance(profile: ProfileVersion?): AxisRow? = when (profile?.grade) {
        null -> null
        ProfileGrade.DEMO_SYNTHETIC -> AxisRow("provenance", CargoStatusKind.UNVERIFIED, DEMO_LABEL, profile.grade.name)
        ProfileGrade.UNSUPPORTED -> AxisRow("provenance", CargoStatusKind.UNVERIFIED, "미지원 차량: 판정을 제공하지 않습니다. 직접 측정하세요", profile.grade.name)
        ProfileGrade.USER_MEASURED -> AxisRow("provenance", CargoStatusKind.CLEAR, "사용자 직접측정 공간(±${profile.errorMm} mm)", profile.grade.name)
        ProfileGrade.VERIFIED_MEASURED -> AxisRow("provenance", CargoStatusKind.CLEAR, "반복 실측 공간(±${profile.errorMm} mm)", profile.grade.name)
        ProfileGrade.PUBLISHED_PARTIAL -> AxisRow("provenance", CargoStatusKind.UNCERTAIN, "공개 부분치수 공간: 누락 치수 있음", profile.grade.name)
    }
}
