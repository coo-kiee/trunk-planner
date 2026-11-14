package ai.cargo.scene

import ai.cargo.contracts.PlacementStatus
import ai.cargo.contracts.SearchStatus
import ai.cargo.contracts.Vec3Mm
import ai.cargo.geometry.SearchBudget
import ai.cargo.scene.SceneCommand.*
import ai.cargo.scene.SceneFx.INST_A
import ai.cargo.scene.SceneFx.INST_B
import ai.cargo.scene.SceneFx.ITEM_A
import ai.cargo.scene.SceneFx.ITEM_B
import ai.cargo.scene.SceneFx.assertRejected
import ai.cargo.scene.SceneFx.confirmed
import ai.cargo.scene.SceneFx.library
import ai.cargo.scene.SceneFx.lockedRevision
import ai.cargo.scene.SceneFx.newDraft
import ai.cargo.scene.SceneFx.ok
import ai.cargo.scene.SceneFx.rejected
import ai.cargo.scene.SceneFx.saved
import ai.cargo.scene.SceneFx.validated
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SceneReducerTest {
    private val origin = Vec3Mm(0, 0, 0)
    private val beside = Vec3Mm(400, 0, 0)

    @Test fun CE08_locked_instance_cannot_move_rotate_or_vanish_without_unlock() {
        val draft = SceneDraftState.fromRevision(lockedRevision())
        assertEquals(RejectReason.LOCKED_REQUIRES_UNLOCK, draft.rejected(Move(INST_A, beside)).reason)
        assertEquals(RejectReason.LOCKED_REQUIRES_UNLOCK, draft.rejected(Rotate(INST_A, 1, origin)).reason)
        assertEquals(RejectReason.LOCKED_REQUIRES_UNLOCK, draft.rejected(Remove(INST_A)).reason)
        val moved = draft.ok(Unlock(INST_A)).ok(Move(INST_A, beside))
        assertEquals(beside, moved.instances.single().poseMm)
    }

    @Test fun CE08_mutated_locked_pose_is_refused_at_save() {
        val draft = SceneDraftState.fromRevision(lockedRevision())
        // A bug or corrupted draft moves the pose with no command behind it.
        val forged = draft.copy(instances = draft.instances.map { it.copy(poseMm = beside) }).confirmed()
        val decision = SavePreparation.prepare(forged, 1, library, SceneFx.T2)
        assertRejected(SaveRejection.LOCKED_DIFF, decision)
        assertEquals(listOf(INST_A), (decision as SaveDecision.Rejected).instanceIds)
    }

    @Test fun CE08_silently_cleared_locked_flag_and_removal_are_refused() {
        val draft = SceneDraftState.fromRevision(lockedRevision())
        val cleared = draft.copy(instances = draft.instances.map { it.copy(locked = false) })
        assertEquals(RejectReason.LOCKED_REQUIRES_UNLOCK, cleared.rejected(Move(INST_A, beside)).reason)
        assertRejected(SaveRejection.LOCKED_DIFF, SavePreparation.prepare(cleared.confirmed(), 1, library, SceneFx.T2))
        val gone = draft.copy(instances = emptyList()).confirmed()
        assertRejected(SaveRejection.LOCKED_DIFF, SavePreparation.prepare(gone, 1, library, SceneFx.T2))
    }

    @Test fun CE08_authority_for_one_field_does_not_cover_a_simultaneous_change_of_another() {
        val before = ai.cargo.contracts.Instance(INST_A, ITEM_A, 1, origin, 0, true)
        val pose = listOf(LockedChange(INST_A, LockedChangeKind.POSE))
        val itemRevision = listOf(LockedChange(INST_A, LockedChangeKind.ITEM_REVISION))
        fun check(after: ai.cargo.contracts.Instance, changes: List<LockedChange>, unlocked: List<String>) =
            LockedDiff.violations(listOf(before), listOf(after), changes, unlocked)

        // The independent review's counterexample: item swapped along with an authorized move.
        val swapped = before.copy(itemId = ITEM_B, itemRevision = 2, poseMm = beside, locked = false)
        assertEquals(listOf(INST_A), check(swapped, pose, listOf(INST_A)))
        // An item swap has no authority at all, not even with every grant present.
        val allGrants = pose + itemRevision + LockedChange(INST_A, LockedChangeKind.REMOVAL)
        assertEquals(listOf(INST_A), check(before.copy(itemId = ITEM_B), allGrants, listOf(INST_A)))
        assertEquals(listOf(INST_A), check(before.copy(itemRevision = 2, poseMm = beside), pose, listOf(INST_A)), "revision without its grant")
        assertEquals(listOf(INST_A), check(before.copy(itemRevision = 2, poseMm = beside), itemRevision, listOf(INST_A)), "pose without its grant")
        assertEquals(listOf(INST_A), check(before.copy(orientationIndex = 1, itemRevision = 2), itemRevision, listOf(INST_A)), "rotation without its grant")
        assertEquals(listOf(INST_A), check(before.copy(poseMm = beside, locked = false), pose, emptyList()), "lock cleared without unlock")

        // Everything explicitly authorized still passes, so the guard does not block the real flow.
        assertEquals(emptyList(), check(before.copy(itemRevision = 2, poseMm = beside, locked = false), pose + itemRevision, listOf(INST_A)))
        assertEquals(emptyList(), check(before.copy(poseMm = beside), pose, emptyList()), "moved then locked again")
        assertEquals(emptyList(), check(before, emptyList(), emptyList()))
    }

    @Test fun CE08_real_commands_combining_move_and_version_apply_can_be_saved() {
        val saved = SceneDraftState.fromRevision(lockedRevision())
            .ok(Unlock(INST_A)).ok(Move(INST_A, beside)).ok(ApplyItemVersion(INST_A, 2)).ok(Lock(INST_A))
            .confirmed().saved(1).revision
        assertEquals(beside, saved.instances.single().poseMm)
        assertEquals(2, saved.instances.single().itemRevision)
    }

    @Test fun undo_redo_restores_authority_so_provenance_cannot_be_forged() {
        val base = SceneDraftState.fromRevision(lockedRevision())
        val moved = base.ok(Unlock(INST_A)).ok(Move(INST_A, beside))
        assertEquals(1, moved.lockedChanges.size)
        val undoneMove = moved.ok(Undo)
        assertEquals(origin, undoneMove.instances.single().poseMm)
        assertTrue(undoneMove.lockedChanges.isEmpty(), "authority for the undone move is gone")
        // Pose copied back onto the undone state has no command behind it.
        val forged = undoneMove.copy(instances = undoneMove.instances.map { it.copy(poseMm = beside) }).confirmed()
        assertRejected(SaveRejection.LOCKED_DIFF, SavePreparation.prepare(forged, 1, library, SceneFx.T2))
        // Redo brings the move and its authority back together.
        val redone = undoneMove.ok(Redo)
        assertEquals(beside, redone.instances.single().poseMm)
        assertEquals(1, redone.lockedChanges.size)
        assertNotNull(redone.confirmed().saved(1).revision)
    }

    @Test fun new_edit_clears_redo_and_history_is_snapshot_based() {
        val a = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0))
        val moved = a.ok(Move(INST_A, beside))
        val undone = moved.ok(Undo)
        assertEquals(1, undone.redo.size)
        val edited = undone.ok(Move(INST_A, Vec3Mm(500, 0, 0)))
        assertTrue(edited.redo.isEmpty())
        assertEquals(RejectReason.NOTHING_TO_REDO, edited.rejected(Redo).reason)
        assertEquals(emptyList(), moved.ok(Undo).ok(Undo).instances)
        assertEquals(RejectReason.NOTHING_TO_UNDO, newDraft().rejected(Undo).reason)
    }

    @Test fun history_is_bounded_to_wire_limit() {
        var s = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0))
        repeat(150) { s = s.ok(Move(INST_A, Vec3Mm(it.toLong(), 0, 0))) }
        assertEquals(100, s.undo.size)
    }

    @Test fun change_invalidates_validation_and_confirmation_and_camera_does_not() {
        val confirmed = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0)).confirmed()
        assertTrue(StaleAnalysis.fitsConfirmed(confirmed, library))
        val moved = confirmed.ok(Move(INST_A, beside))
        assertNull(moved.validation)
        assertNull(moved.confirmation)
        assertFalse(StaleAnalysis.fitsConfirmed(moved, library))
        val camera = confirmed.ok(SetCamera(SceneDraftState.DEFAULT_CAMERA.copy(yawDegrees = 42)))
        assertTrue(StaleAnalysis.fitsConfirmed(camera, library))
    }

    @Test fun locking_keeps_judgement_but_enters_history() {
        val confirmed = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0)).confirmed()
        val locked = confirmed.ok(Lock(INST_A))
        assertTrue(StaleAnalysis.fitsConfirmed(locked, library))
        assertEquals(false, locked.ok(Undo).instances.single().locked)
        assertEquals(RejectReason.NOT_LOCKED, confirmed.rejected(Unlock(INST_A)).reason)
    }

    @Test fun restored_judgement_cannot_be_confirmed_or_saved_without_revalidation() {
        val draft = SceneDraftState.fromRevision(lockedRevision())
        assertEquals(RejectReason.NOT_VALIDATED, draft.rejected(ConfirmPlacement(draft.validation!!.inputHash)).reason)
        assertRejected(SaveRejection.NOT_VALIDATED, SavePreparation.prepare(draft, 1, library, SceneFx.T2))
    }

    @Test fun late_validation_result_is_rejected() {
        val added = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0))
        val early = (SceneValidator.validate(added, library) as ValidationOutcome.Ready).record
        val moved = added.ok(Move(INST_A, beside))
        assertEquals(RejectReason.STALE_VALIDATION_RESULT, moved.rejected(ApplyValidation(early)).reason)
        val v = added.validated()
        assertEquals(RejectReason.STALE_VALIDATION_RESULT, v.rejected(ConfirmPlacement("f".repeat(64))).reason)
    }

    @Test fun CE10_new_item_version_leaves_saved_reference_and_is_not_stale_until_applied() {
        val revision = lockedRevision()
        val before = revision.copy()
        val status = StaleAnalysis.forRevision(revision, library)
        assertEquals(emptyList(), status.staleReasons, "version 2 exists but is not applied")
        assertEquals(listOf(ItemRef(ITEM_A, 2)), status.newerItems)
        assertEquals(before, revision)
        assertEquals(1, revision.instances.single().itemRevision)

        val draft = SceneDraftState.fromRevision(revision)
        assertEquals(emptyList(), StaleAnalysis.forDraft(draft, library))
        val applied = draft.ok(ApplyItemVersion(INST_A, 2))
        assertEquals(2, applied.instances.single().itemRevision)
        assertEquals(origin, applied.instances.single().poseMm, "applying a version never moves the item")
        assertTrue(StaleReason.ITEM_VERSION_APPLIED in StaleAnalysis.forDraft(applied, library))
        assertFalse(StaleAnalysis.fitsConfirmed(applied, library))
        assertRejected(SaveRejection.NOT_VALIDATED, SavePreparation.prepare(applied, 1, library, SceneFx.T2))

        val revalidated = applied.validated()
        assertEquals(emptyList(), StaleAnalysis.forDraft(revalidated, library))
        assertRejected(SaveRejection.NOT_CONFIRMED, SavePreparation.prepare(revalidated, 1, library, SceneFx.T2))
        val saved = revalidated.confirmed().saved(1).revision
        assertEquals(2, saved.revision)
        assertEquals(1, saved.parentRevision)
        assertEquals(2, saved.instances.single().itemRevision)
        assertNotEquals(revision.canonicalInputHash, saved.canonicalInputHash)
        assertEquals(1, revision.instances.single().itemRevision, "older revision value is unchanged")
    }

    @Test fun CE10_version_apply_cannot_be_skipped_by_reusing_the_old_judgement() {
        val draft = SceneDraftState.fromRevision(lockedRevision()).validated()
        val applied = draft.ok(ApplyItemVersion(INST_A, 2))
        val reused = applied.copy(validation = draft.validation, confirmation = Confirmation(draft.validation!!.inputHash, emptyList(), SceneFx.T2))
        assertRejected(SaveRejection.STALE, SavePreparation.prepare(reused, 1, library, SceneFx.T2))
        assertFalse(StaleAnalysis.fitsConfirmed(reused, library))
    }

    @Test fun CE11_profile_config_and_engine_changes_are_stale_and_hide_fits() {
        val revision = lockedRevision()
        val draft = SceneDraftState.fromRevision(revision).confirmed()
        assertTrue(StaleAnalysis.fitsConfirmed(draft, library))
        val profile = draft.ok(ApplyProfileVersion(2))
        assertTrue(StaleReason.PROFILE_VERSION_APPLIED in StaleAnalysis.forDraft(profile, library))
        assertFalse(StaleAnalysis.fitsConfirmed(profile, library))

        val items = listOf(SceneFx.item(ITEM_A), SceneFx.item(ITEM_B))
        val engine = SceneContext(items, listOf(SceneFx.profile()), library.config.copy(engineVersion = "aabb-mm-v2"))
        assertEquals(listOf(StaleReason.ENGINE_VERSION_CHANGED), StaleAnalysis.forRevision(revision, engine).staleReasons)
        assertEquals(listOf(StaleReason.ENGINE_VERSION_CHANGED), StaleAnalysis.forDraft(draft, engine))
        assertFalse(StaleAnalysis.fitsConfirmed(draft, engine))

        val config = SceneContext(items, listOf(SceneFx.profile()), library.config.copy(revision = 2))
        assertEquals(listOf(StaleReason.CONFIGURATION_CHANGED), StaleAnalysis.forRevision(revision, config).staleReasons)
        assertFalse(StaleAnalysis.fitsConfirmed(draft, config))

        val margin = SceneContext(items, listOf(SceneFx.profile()), library.config.copy(marginMm = 5))
        assertEquals(listOf(StaleReason.INPUT_CHANGED), StaleAnalysis.forRevision(revision, margin).staleReasons)
    }

    @Test fun unsupported_profile_gives_no_snapshot_and_no_revision() {
        val ctx = SceneContext(listOf(SceneFx.item(ITEM_A)), listOf(SceneFx.profile(grade = ai.cargo.contracts.ProfileGrade.UNSUPPORTED)))
        val draft = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0), ctx)
        assertTrue(SceneValidator.validate(draft, ctx) is ValidationOutcome.NotAvailable)
        assertRejected(SaveRejection.NOT_VALIDATED, SavePreparation.prepare(draft, null, ctx, SceneFx.T2))
    }

    @Test fun new_placement_air_or_bad_support_cannot_be_confirmed_even_with_acknowledgement() {
        val v = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), Vec3Mm(0, 300, 0), 0)).validated()
        val issues = v.validation!!.issues
        assertEquals(listOf(IssueKind.AIRBORNE), issues.map { it.kind })
        assertEquals(RejectReason.NEW_PLACEMENT_INVALID, v.rejected(ConfirmPlacement(v.validation!!.inputHash, issues)).reason)
    }

    @Test fun conflict_placement_needs_explicit_ack_and_is_saved_exactly_as_judged() {
        val v = newDraft()
            .ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0))
            .ok(Add(INST_B, ItemRef(ITEM_B, 1), Vec3Mm(100, 0, 0), 0))
            .validated()
        assertEquals(PlacementStatus.CONFLICT, v.validation!!.snapshot.placement)
        val issues = v.validation!!.issues
        assertEquals(RejectReason.ACKNOWLEDGEMENT_REQUIRED, v.rejected(ConfirmPlacement(v.validation!!.inputHash)).reason)
        val confirmed = v.ok(ConfirmPlacement(v.validation!!.inputHash, issues))
        assertFalse(StaleAnalysis.fitsConfirmed(confirmed, library))
        val saved = confirmed.saved(null)
        assertEquals(PlacementStatus.CONFLICT, saved.revision.judgementSnapshot.placement)
        assertEquals(issues, saved.acknowledged)
    }

    @Test fun existing_locked_item_made_invalid_by_a_version_can_be_saved_only_with_acknowledgement() {
        // Item below shrinks in version 2: the unchanged item above loses full support.
        val below = SceneFx.item(ITEM_A)
        val ctx = SceneContext(listOf(below, below.copy(revision = 2, measuredDimensionsMm = ai.cargo.contracts.DimensionsMm(100, 200, 200), shapeProxy = listOf(ai.cargo.contracts.BoxProxy(origin, ai.cargo.contracts.DimensionsMm(100, 200, 200)))), SceneFx.item(ITEM_B)), listOf(SceneFx.profile()))
        val rev = newDraft()
            .ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0, locked = true), ctx)
            .ok(Add(INST_B, ItemRef(ITEM_B, 1), Vec3Mm(0, 200, 0), 0, locked = true), ctx)
            .confirmed(ctx).saved(null, ctx).revision
        val v = SceneDraftState.fromRevision(rev).ok(ApplyItemVersion(INST_A, 2), ctx).validated(ctx)
        val issues = v.validation!!.issues
        assertEquals(listOf(JudgementIssue(IssueKind.INSUFFICIENT_SUPPORT, INST_B)), issues)
        assertEquals(RejectReason.ACKNOWLEDGEMENT_REQUIRED, v.rejected(ConfirmPlacement(v.validation!!.inputHash), ctx).reason)
        val saved = v.ok(ConfirmPlacement(v.validation!!.inputHash, issues), ctx).saved(1, ctx)
        assertEquals(ai.cargo.contracts.HandlingStatus.VIOLATION, saved.revision.judgementSnapshot.handling)
        assertEquals(issues, saved.acknowledged)
    }

    @Test fun head_cas_and_running_search_refuse_save() {
        val confirmed = SceneDraftState.fromRevision(lockedRevision()).confirmed()
        assertRejected(SaveRejection.HEAD_MOVED, SavePreparation.prepare(confirmed, 2, library, SceneFx.T2))
        val searching = confirmed.ok(BeginSearch("t1", ItemRef(ITEM_B, 1)))
        assertRejected(SaveRejection.SEARCH_RUNNING, SavePreparation.prepare(searching, 1, library, SceneFx.T2))
    }

    @Test fun save_starts_a_new_draft_boundary_and_never_touches_older_revisions() {
        val first = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0, locked = true)).confirmed().saved(null)
        val next = first.nextDraft
        assertEquals(1, next.baseRevision)
        assertTrue(next.undo.isEmpty() && next.redo.isEmpty())
        assertEquals(first.revision.instances, next.instances)
        assertEquals(RejectReason.NOTHING_TO_UNDO, next.rejected(Undo).reason)
        assertEquals(SceneChecksum.of(first.revision), first.revision.checksum)
        assertTrue(SceneChecksum.verify(SceneJson.encodeRevision(first.revision)))
    }

    @Test fun CE08_full_product_flow_keeps_first_item_fixed_through_search_add_and_save() {
        val first = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0, locked = true)).confirmed().saved(null).revision
        var draft = SceneDraftState.fromRevision(first)
        val itemB = ItemRef(ITEM_B, 1)
        draft = draft.ok(BeginSearch("search-1", itemB))
        val run = SceneSearch.run(draft, itemB, library) as SearchRun.Done
        draft = draft.ok(FinishSearch("search-1", run.record))
        val pose = run.outcome.pose!!
        draft = draft.ok(Add(INST_B, itemB, pose.poseMm, pose.orientationIndex, fromSearchToken = "search-1"))
        val v = draft.validated()
        assertEquals(SearchStatus.FOUND_PLACEMENT, v.validation!!.snapshot.search.status)
        assertEquals(PlacementStatus.CLEAR, v.validation!!.snapshot.placement)
        val second = v.confirmed().saved(1).revision
        assertEquals(first.instances.single(), second.instances.first { it.instanceId == INST_A }, "first item is unchanged")
        assertEquals(2, second.instances.size)
        assertEquals(1, second.parentRevision)
    }

    @Test fun search_result_arriving_late_or_after_edits_is_not_attached() {
        val base = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0))
        val itemB = ItemRef(ITEM_B, 1)
        val running = base.ok(BeginSearch("t", itemB))
        assertEquals(RejectReason.SEARCH_ALREADY_RUNNING, running.rejected(BeginSearch("u", itemB)).reason)
        val run = SceneSearch.run(running, itemB, library) as SearchRun.Done
        assertEquals(RejectReason.STALE_SEARCH_RESULT, running.rejected(FinishSearch("other", run.record)).reason)
        val moved = running.ok(Move(INST_A, beside))
        val after = moved.ok(FinishSearch("t", run.record))
        assertNull(after.lastSearch)
        assertNull(after.pendingSearch)
    }

    @Test fun CE06_process_death_marker_reads_as_interrupted_with_no_pose() {
        val running = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0)).ok(BeginSearch("t", ItemRef(ITEM_B, 1)))
        val restored = SceneReducer.recoverAfterRestore(running)
        assertNull(restored.pendingSearch)
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, restored.lastSearch!!.status)
        assertEquals(SearchInterruptCause.PROCESS_DEATH, restored.lastSearch!!.interruptCause)
        assertNull(restored.lastSearch!!.reason)
        val snapshot = restored.validated().validation!!.snapshot
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, snapshot.search.status)
        assertNull(snapshot.search.reason)
    }

    @Test fun CE06_budget_interrupt_flows_into_snapshot_and_edit_drops_it() {
        val base = newDraft().ok(Add(INST_A, ItemRef(ITEM_A, 1), origin, 0))
        val itemB = ItemRef(ITEM_B, 1)
        val running = base.ok(BeginSearch("t", itemB))
        val run = SceneSearch.run(running, itemB, library, SearchBudget(0)) as SearchRun.Done
        assertEquals(SearchInterruptCause.BUDGET, run.record.interruptCause)
        assertNull(run.outcome.pose)
        val finished = running.ok(FinishSearch("t", run.record))
        assertEquals(SearchStatus.SEARCH_INTERRUPTED, finished.validated().validation!!.snapshot.search.status)
        assertEquals(SearchStatus.NOT_RUN, finished.ok(Move(INST_A, beside)).validated().validation!!.snapshot.search.status)
    }

    @Test fun wire_projection_matches_schema_and_requires_a_base() {
        assertNull(newDraft().toWire())
        val draft = SceneDraftState.fromRevision(lockedRevision()).ok(SetCamera(SceneDraftState.DEFAULT_CAMERA.copy(yawDegrees = 9)))
        val wire = draft.toWire()!!
        assertEquals(1, wire.baseRevision)
        assertEquals(9, wire.camera.yawDegrees)
        val json = SceneJson.json.encodeToString(ai.cargo.contracts.EditDraft.serializer(), wire)
        ai.cargo.contracts.ContractCodec.validateDefinition("EditDraft", json)
    }

    @Test fun saved_revision_is_valid_wire_and_round_trips_through_codec() {
        val revision = lockedRevision()
        val json = SceneJson.encodeRevision(revision)
        assertEquals(revision, ai.cargo.contracts.ContractCodec.decodeScene(json))
    }

    @Test fun state_round_trips_including_provenance() {
        val state = SceneDraftState.fromRevision(lockedRevision()).ok(Unlock(INST_A)).ok(Move(INST_A, beside)).ok(Undo).ok(BeginSearch("t", ItemRef(ITEM_B, 1)))
        val json = SceneJson.json.encodeToString(SceneDraftState.serializer(), state)
        assertEquals(state, SceneJson.json.decodeFromString(SceneDraftState.serializer(), json))
    }

    @Test fun checksum_ignores_only_itself_and_key_order() {
        val revision = lockedRevision()
        val json = SceneJson.encodeRevision(revision)
        val tampered = json.replace("\"revision\":1", "\"revision\":9")
        assertFalse(SceneChecksum.verify(tampered))
        assertFalse(SceneChecksum.verify("not json"))
        val fields = SceneJson.json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject
        val reordered = kotlinx.serialization.json.JsonObject(fields.entries.reversed().associate { it.key to it.value }).toString()
        assertTrue(SceneChecksum.verify(reordered))
    }

    @Test fun input_hash_covers_handling_and_ignores_ordering_and_lock() {
        val a = listOf(ai.cargo.contracts.Instance(INST_A, ITEM_A, 1, origin, 0, false), ai.cargo.contracts.Instance(INST_B, ITEM_B, 1, beside, 0, false))
        val p = SceneFx.profile()
        val h = InputHash.of(a, p, library)
        assertEquals(h, InputHash.of(a.reversed(), p, library))
        assertEquals(h, InputHash.of(a.map { it.copy(locked = true) }, p, library))
        assertNotEquals(h, InputHash.of(a.map { it.copy(orientationIndex = 1) }, p, library))
        val other = SceneContext(listOf(SceneFx.item(ITEM_A, stackable = false), SceneFx.item(ITEM_B)), listOf(p))
        assertNotEquals(h, InputHash.of(a, p, other))
        assertNull(InputHash.of(a.map { it.copy(itemRevision = 99) }, p, library))
    }

    @Test fun legacy_scene_upcasts_only_with_provenance_and_stays_stale() {
        val fixture = """{"schemaVersion":0,"instances":[{"instanceId":"a","poseMm":{"x":12,"y":56,"z":34}}]}"""
        assertEquals(LegacyUpcast.Result.ReadOnly("FRAME_PROVENANCE_MISSING"), LegacyUpcast.upcast(fixture, null))
        val r = LegacyUpcast.upcast(fixture, LegacyUpcast.FRAME_OPENING_RIGHT_V0) as LegacyUpcast.Result.Upcast
        assertEquals(LegacyUpcast.LegacyPose("a", 12, 34, 56), r.poses.single())
        assertEquals(listOf(StaleReason.LEGACY_AXIS_UPCAST), r.staleReasons)
        assertEquals(LegacyUpcast.Result.ReadOnly("LEGACY_JSON_UNREADABLE"), LegacyUpcast.upcast("nope", LegacyUpcast.FRAME_OPENING_RIGHT_V0))
    }
}
