package ai.cargo.feature.profile

import ai.cargo.contracts.BoxProxy
import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.ProfileGrade
import ai.cargo.contracts.ProfileVersion
import ai.cargo.contracts.Vec3Mm
import kotlin.math.abs

enum class SpaceField(val label: String) {
    X("공간 폭 X (mm)"), Y("공간 높이 Y (mm)"), Z("공간 깊이 Z (mm)"),
    OPENING_X("입구 폭 (mm, 미측정이면 비움)"), OPENING_Y("입구 높이 (mm, 미측정이면 비움)"),
    ERROR("측정 오차 ±(mm)"),
    REPEAT_X("2차 측정 폭 (mm, 선택)"), REPEAT_Y("2차 측정 높이 (mm, 선택)"), REPEAT_Z("2차 측정 깊이 (mm, 선택)"),
    OBSTACLE_X("휠아치 등 장애물 폭 (mm, 선택)"), OBSTACLE_Y("장애물 높이 (mm, 선택)"), OBSTACLE_Z("장애물 깊이 (mm, 선택)"),
    OBSTACLE_OX("장애물 시작 X (mm)"), OBSTACLE_OZ("장애물 시작 Z (mm)"),
}

data class ProfileForm(
    val numbers: Map<SpaceField, String> = mapOf(SpaceField.ERROR to "10"),
    val focused: SpaceField = SpaceField.X,
) {
    fun text(field: SpaceField) = numbers[field].orEmpty()
}

data class ProfileFormResult(val errors: Map<SpaceField, String>, val grade: ProfileGrade?)

/** Grades come from what was really measured here. Nothing fills a missing dimension. */
object ProfileRules {
    private const val MAX = 10_000L
    private const val MAX_ERROR = 1_000L
    private val REQUIRED = listOf(SpaceField.X, SpaceField.Y, SpaceField.Z)
    private val REPEAT = listOf(SpaceField.REPEAT_X, SpaceField.REPEAT_Y, SpaceField.REPEAT_Z)
    private val OBSTACLE_SIZE = listOf(SpaceField.OBSTACLE_X, SpaceField.OBSTACLE_Y, SpaceField.OBSTACLE_Z)

    fun check(form: ProfileForm): ProfileFormResult {
        val errors = HashMap<SpaceField, String>()
        REQUIRED.forEach { if (!inRange(form.text(it))) errors[it] = "1~$MAX mm로 입력하세요" }
        if (form.text(SpaceField.ERROR).toLongOrNull()?.let { it in 0..MAX_ERROR } != true) errors[SpaceField.ERROR] = "0~$MAX_ERROR mm로 입력하세요"
        pairing(form, listOf(SpaceField.OPENING_X, SpaceField.OPENING_Y), errors)
        pairing(form, REPEAT, errors)
        pairing(form, OBSTACLE_SIZE, errors)
        spreadProblems(form, errors)
        obstacleOriginProblems(form, errors)
        val repeated = REPEAT.all { form.text(it).isNotEmpty() }
        return ProfileFormResult(errors, if (errors.isEmpty()) (if (repeated) ProfileGrade.VERIFIED_MEASURED else ProfileGrade.USER_MEASURED) else null)
    }

    private fun pairing(form: ProfileForm, fields: List<SpaceField>, errors: MutableMap<SpaceField, String>) {
        val filled = fields.filter { form.text(it).isNotEmpty() }
        if (filled.isEmpty()) return
        fields.forEach { if (!inRange(form.text(it))) errors[it] = "함께 입력하세요 (1~$MAX mm)" }
    }

    /** The stored error must cover the spread and has a limit, so two far-apart readings ask for a new measurement. */
    private fun spreadProblems(form: ProfileForm, errors: MutableMap<SpaceField, String>) {
        REQUIRED.zip(REPEAT).forEach { (first, second) ->
            val a = form.text(first).toLongOrNull() ?: return@forEach
            val b = form.text(second).toLongOrNull() ?: return@forEach
            if (abs(a - b) > MAX_ERROR) errors[second] = "두 측정값 차이가 $MAX_ERROR mm를 넘습니다. 다시 재주세요"
        }
    }

    private fun obstacleOriginProblems(form: ProfileForm, errors: MutableMap<SpaceField, String>) {
        val hasObstacle = form.text(SpaceField.OBSTACLE_X).isNotEmpty()
        listOf(SpaceField.OBSTACLE_OX, SpaceField.OBSTACLE_OZ).forEach { field ->
            val text = form.text(field)
            if (text.isEmpty()) return@forEach
            when {
                !hasObstacle -> errors[field] = "장애물 크기를 함께 입력하세요"
                text.toLongOrNull()?.let { it in 0..MAX } != true -> errors[field] = "0~$MAX mm로 입력하세요"
            }
        }
    }

    private fun inRange(text: String) = text.toLongOrNull()?.let { it in 1..MAX } == true

    fun measured(form: ProfileForm, id: String, revision: Long, at: String): ProfileVersion? {
        val grade = check(form).grade ?: return null
        fun n(field: SpaceField) = form.text(field).toLong()
        val first = DimensionsMm(n(SpaceField.X), n(SpaceField.Y), n(SpaceField.Z))
        val repeats = if (grade == ProfileGrade.VERIFIED_MEASURED) {
            listOf(first, DimensionsMm(n(SpaceField.REPEAT_X), n(SpaceField.REPEAT_Y), n(SpaceField.REPEAT_Z)))
        } else {
            emptyList()
        }
        val spread = repeats.takeIf { it.size == 2 }?.let { (a, b) -> maxOf(abs(a.x - b.x), abs(a.y - b.y), abs(a.z - b.z)) } ?: 0
        val obstacle = if (form.text(SpaceField.OBSTACLE_X).isEmpty()) {
            emptyList()
        } else {
            listOf(
                BoxProxy(
                    Vec3Mm(form.text(SpaceField.OBSTACLE_OX).toLongOrNull() ?: 0, 0, form.text(SpaceField.OBSTACLE_OZ).toLongOrNull() ?: 0),
                    DimensionsMm(n(SpaceField.OBSTACLE_X), n(SpaceField.OBSTACLE_Y), n(SpaceField.OBSTACLE_Z)),
                ),
            )
        }
        val opening = if (form.text(SpaceField.OPENING_X).isEmpty()) null else DimensionsMm(n(SpaceField.OPENING_X), n(SpaceField.OPENING_Y), 1)
        return ProfileVersion(
            schemaVersion = 1, id = id, revision = revision, createdAt = at, updatedAt = at, grade = grade,
            bounds = first, obstacles = obstacle, opening = opening,
            // Two measurements that disagree widen the error: the bound is never narrower than the observed spread.
            errorMm = maxOf(n(SpaceField.ERROR), spread),
            provenance = if (grade == ProfileGrade.VERIFIED_MEASURED) "사용자 반복 직접측정" else "사용자 직접측정",
            measuredAt = at,
            method = "tape-measure wizard",
            repeatMeasurements = repeats,
        )
    }

    /** Clearly marked demo space. Its numbers describe no real vehicle. */
    fun demoSynthetic(id: String, revision: Long, at: String) = ProfileVersion(
        schemaVersion = 1, id = id, revision = revision, createdAt = at, updatedAt = at,
        grade = ProfileGrade.DEMO_SYNTHETIC,
        bounds = DimensionsMm(1200, 700, 1000),
        obstacles = listOf(BoxProxy(Vec3Mm(0, 0, 400), DimensionsMm(150, 250, 350))),
        opening = DimensionsMm(1100, 650, 1),
        errorMm = 5,
        provenance = ProfileLabels.DEMO_LABEL,
        method = "synthetic demo values",
        repeatMeasurements = emptyList(),
    )

    /** A vehicle with no usable data: stored so the screen can say so, with no bounds and so no judgement. */
    fun unsupported(id: String, revision: Long, at: String) = ProfileVersion(
        schemaVersion = 1, id = id, revision = revision, createdAt = at, updatedAt = at,
        grade = ProfileGrade.UNSUPPORTED,
        bounds = null, obstacles = emptyList(), opening = null, errorMm = 0,
        provenance = "지원 데이터 없음", method = "none", repeatMeasurements = emptyList(),
    )
}

object ProfileLabels {
    const val DEMO_LABEL = "합성 시연용·실제 차량 치수 아님"

    fun grade(grade: ProfileGrade) = when (grade) {
        ProfileGrade.VERIFIED_MEASURED -> "반복 실측"
        ProfileGrade.USER_MEASURED -> "사용자 직접측정"
        ProfileGrade.PUBLISHED_PARTIAL -> "공개 부분치수"
        ProfileGrade.DEMO_SYNTHETIC -> DEMO_LABEL
        ProfileGrade.UNSUPPORTED -> "미지원 차량"
    }
}
