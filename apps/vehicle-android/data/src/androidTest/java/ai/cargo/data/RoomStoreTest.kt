package ai.cargo.data

import ai.cargo.contracts.DimensionsMm
import ai.cargo.contracts.Vec3Mm
import ai.cargo.data.Seed.INST_A
import ai.cargo.data.Seed.INST_B
import ai.cargo.data.Seed.ITEM_A
import ai.cargo.data.Seed.ITEM_B
import ai.cargo.data.Seed.SCENE
import ai.cargo.data.Seed.T
import ai.cargo.data.Seed.apply
import ai.cargo.data.db.CargoDatabase
import ai.cargo.scene.ItemRef
import ai.cargo.scene.JudgementIssue
import ai.cargo.scene.IssueKind
import ai.cargo.scene.LockedChange
import ai.cargo.scene.PreparedSave
import ai.cargo.scene.SaveDecision
import ai.cargo.scene.SavePreparation
import ai.cargo.scene.SceneChecksum
import ai.cargo.scene.SceneCommand
import ai.cargo.scene.SceneDraftState
import ai.cargo.scene.SceneJson
import ai.cargo.scene.StaleAnalysis
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteReadOnlyDatabaseException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
class RoomStoreTest : StoreTestBase() {
    @After fun cleanup() = removeCreated()

    private fun seeded(prefix: String): Pair<String, CargoStore> {
        val name = databaseName(prefix)
        val store = Seed.open(context, name)
        Seed.firstRevision(store)
        return name to store
    }

    /** Test-only damage: the trigger that protects the row is removed in this throwaway database. */
    private fun damageRevision(name: String, revision: Long) {
        val sql = SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READWRITE)
        sql.use { db ->
            db.execSQL("DROP TRIGGER immutable_scene_revision_update")
            db.execSQL("UPDATE scene_revision SET json = replace(json, '\"x\":0', '\"x\":7') WHERE revision = $revision")
        }
    }

    @Test fun CE12_save_and_reopen_returns_the_identical_revision() {
        val (name, store) = seeded("reopen")
        val prepared = Seed.secondRevision(store)
        assertEquals(SaveResult.Saved(2), store.scenes.save(prepared))
        store.close()
        val reopened = Seed.open(context, name)
        val loaded = assertIs<SceneLoad.Healthy>(reopened.scenes.load(SCENE, T))
        assertEquals(prepared.revision, loaded.revision)
        assertTrue(reopened.integrityOk())
        val first = assertNotNull(reopened.scenes.revision(SCENE, 1))
        assertEquals(first.instances.single(), loaded.revision.instances.first { it.instanceId == INST_A }, "first item coordinates survive")
        reopened.close()
    }

    @Test fun CE12_head_compare_and_set_refuses_a_second_writer() {
        val (_, store) = seeded("cas")
        val a = Seed.secondRevision(store)
        val b = Seed.secondRevision(store)
        assertEquals(SaveResult.Saved(2), store.scenes.save(a))
        assertEquals(SaveResult.HeadMoved(1, 2), store.scenes.save(b))
        assertEquals(2, store.scenes.head(SCENE))
        store.close()
    }

    @Test fun CE12_exception_inside_save_rolls_back_everything() {
        val (_, store) = seeded("rollback")
        assertFailsWith<IllegalStateException> {
            store.scenes.save(Seed.secondRevision(store)) { point -> if (point == SavePoint.HEAD_UPDATED) error("simulated failure") }
        }
        assertEquals(1, store.scenes.head(SCENE))
        assertEquals(null, store.scenes.revision(SCENE, 2))
        store.close()
    }

    @Test fun CE08_store_repeats_the_locked_diff_guard() {
        val (_, store) = seeded("locked")
        val honest = Seed.secondRevision(store)
        val moved = honest.revision.instances.map { if (it.instanceId == INST_A) it.copy(poseMm = Vec3Mm(9, 0, 0)) else it }
        val unsigned = honest.revision.copy(instances = moved)
        val forged = honest.copy(revision = unsigned.copy(checksum = SceneChecksum.of(unsigned)))
        assertEquals(SaveResult.LockedDiffRefused(listOf(INST_A)), store.scenes.save(forged))
        assertEquals(1, store.scenes.head(SCENE))
        store.close()
    }

    /** Grants for a move must not let the same save swap the item; the store repeats this check on its own. */
    @Test fun CE08_store_refuses_a_locked_item_swap_hidden_behind_a_granted_move() {
        val (_, store) = seeded("lockedswap")
        val honest = Seed.secondRevision(store)
        val swapped = honest.revision.instances.map {
            if (it.instanceId == INST_A) it.copy(itemId = ITEM_B, poseMm = Vec3Mm(1000, 0, 1000), locked = false) else it
        }
        val unsigned = honest.revision.copy(instances = swapped)
        val forged = honest.copy(
            revision = unsigned.copy(checksum = SceneChecksum.of(unsigned)),
            lockedChanges = listOf(LockedChange(INST_A, ai.cargo.scene.LockedChangeKind.POSE)),
            unlockedIds = listOf(INST_A),
        )
        assertEquals(SaveResult.LockedDiffRefused(listOf(INST_A)), store.scenes.save(forged))
        assertEquals(1, store.scenes.head(SCENE))
        assertEquals(null, store.scenes.revision(SCENE, 2))
        store.close()
    }

    @Test fun CE08_store_refuses_bad_checksum_and_unknown_references() {
        val (_, store) = seeded("guard")
        val honest = Seed.secondRevision(store)
        assertIs<SaveResult.Invalid>(store.scenes.save(honest.copy(revision = honest.revision.copy(updatedAt = "2030-01-01T00:00:00Z"))))
        val unknown = honest.revision.copy(instances = honest.revision.instances.map { if (it.instanceId == INST_B) it.copy(itemRevision = 99) else it })
        assertIs<SaveResult.Invalid>(store.scenes.save(honest.copy(revision = unknown.copy(checksum = SceneChecksum.of(unknown)))))
        store.close()
    }

    @Test fun immutable_rows_refuse_update_and_delete_at_sqlite_level() {
        val (name, store) = seeded("immutable")
        store.close()
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            for (statement in listOf(
                "UPDATE scene_revision SET json = 'x'", "DELETE FROM scene_revision",
                "UPDATE item_version SET json = 'x'", "DELETE FROM item_version",
                "UPDATE profile_version SET json = 'x'", "DELETE FROM profile_version",
            )) {
                val error = assertFailsWith<android.database.SQLException>(statement) { db.execSQL(statement) }
                assertTrue(error.message!!.contains("immutable row"), error.message)
            }
        }
    }

    @Test fun CE13_damaged_head_is_quarantined_with_read_only_recovery_and_original_kept() {
        val (name, store) = seeded("damage")
        assertEquals(SaveResult.Saved(2), store.scenes.save(Seed.secondRevision(store)))
        store.close()
        damageRevision(name, 2)
        val damagedJson = rawRevisionJson(name, 2)

        val reopened = Seed.open(context, name)
        val load = assertIs<SceneLoad.RecoveredReadOnly>(reopened.scenes.load(SCENE, T))
        assertEquals(1, load.revision.revision)
        assertEquals(2, load.damagedRevision)
        val entry = reopened.quarantine.entries().single()
        assertEquals("$SCENE/2", entry.rowKey)
        assertEquals(damagedJson, entry.storedJson)

        // Reading again does not duplicate the record, and nothing can be saved on a damaged head.
        reopened.scenes.load(SCENE, T)
        assertEquals(1, reopened.quarantine.entries().size)
        assertEquals(damagedJson, rawRevisionJson(name, 2), "original row is unchanged")
        val context = reopened.library.sceneContext(T)
        val attempted = SceneDraftState.fromRevision(load.revision).apply(SceneCommand.Add(INST_B, ItemRef(ITEM_B, 1), Seed.POSE_B, 0), context)
        val prepared = Seed.prepare(attempted, 1, context)
        assertEquals(SaveResult.HeadMoved(1, 2), reopened.scenes.save(prepared))
        val onDamagedHead = prepared.revision.copy(revision = 3, parentRevision = 2)
        val forced = prepared.copy(revision = onDamagedHead.copy(checksum = SceneChecksum.of(onDamagedHead)), expectedHead = 2)
        assertEquals(SaveResult.HeadDamaged(2), reopened.scenes.save(forced))

        // Only an explicit user action moves on, into a new revision; the damaged row remains.
        assertEquals(SaveResult.Saved(3), reopened.scenes.adoptRecovery(SCENE, 1, T))
        val adopted = assertIs<SceneLoad.Healthy>(reopened.scenes.load(SCENE, T))
        assertEquals(3, adopted.revision.revision)
        assertEquals(1, adopted.revision.parentRevision)
        assertEquals(damagedJson, rawRevisionJson(name, 2))
        reopened.close()
    }

    private fun rawRevisionJson(name: String, revision: Long): String =
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT json FROM scene_revision WHERE revision = $revision", null).use { c -> c.moveToFirst(); c.getString(0) }
        }

    @Test fun CE13_all_revisions_damaged_reports_no_valid_revision() {
        val (name, store) = seeded("alldamaged")
        store.close()
        damageRevision(name, 1)
        val reopened = Seed.open(context, name)
        assertEquals(SceneLoad.NoValidRevision(1), reopened.scenes.load(SCENE, T))
        reopened.close()
    }

    @Test fun CE13_future_schema_refuses_writes_and_keeps_bytes() {
        val (name, store) = seeded("future")
        store.close()
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.version = 99
        }
        val before = hashes(name)
        val opened = assertIs<StoreOpen.FutureSchema>(StoreOpener.open(context, name, 5))
        assertEquals(99, opened.view.version)
        assertNotNull(opened.view.headRevisionJson(SCENE), "read-only view can still show the head")
        // A raw read-only connection is what the app holds; it physically cannot write.
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertFailsWith<SQLiteReadOnlyDatabaseException> { db.execSQL("DELETE FROM scene_head") }
        }
        assertEquals(before[databaseFile(name).name], hashes(name)[databaseFile(name).name], "main file bytes unchanged")
        assertEquals(99, SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READONLY).use { it.version })
    }

    @Test fun CE13_unreadable_file_is_typed_failure_and_bytes_stay() {
        val name = databaseName("garbage")
        val file = databaseFile(name)
        file.parentFile!!.mkdirs()
        val bytes = ByteArray(8192) { (it % 251).toByte() }
        file.writeBytes(bytes)
        assertIs<StoreOpen.OpenFailed>(StoreOpener.open(context, name, 5))
        assertTrue(file.readBytes().contentEquals(bytes), "corruption handler must not delete or rewrite")
    }

    @Test fun interrupted_first_creation_leaves_an_empty_file_that_still_opens() {
        val name = databaseName("emptyfile")
        val file = databaseFile(name)
        file.parentFile!!.mkdirs()
        file.writeBytes(ByteArray(0))
        val store = assertIs<StoreOpen.Ready>(StoreOpener.open(context, name, 5)).store
        Seed.firstRevision(store)
        assertEquals(1, store.scenes.head(SCENE))
        store.close()
    }

    @Test fun CE13_unversioned_file_with_tables_is_not_recreated() {
        val name = databaseName("unversioned")
        val file = databaseFile(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { it.execSQL("CREATE TABLE somebody_elses (a INTEGER)") }
        val before = hashes(name)
        assertIs<StoreOpen.OpenFailed>(StoreOpener.open(context, name, 5))
        assertEquals(before, hashes(name))
    }

    @Test fun CE15_draft_undo_redo_and_camera_values_round_trip_across_reopen() {
        val (name, store) = seeded("draft")
        val edited = WriterService.editedDraft(store)
        store.drafts.commit(edited, T)
        store.close()
        val reopened = Seed.open(context, name)
        val restored = assertIs<DraftLoad.Restored>(reopened.drafts.load(SCENE, T))
        assertEquals(edited, restored.state)
        assertEquals(false, restored.recoveredInterruptedSearch)
        assertEquals(Vec3Mm(500, 0, 0), restored.state.instances.first { it.instanceId == INST_B }.poseMm)
        assertEquals(1, restored.state.redo.size)
        assertEquals(2, restored.state.undo.size)
        assertEquals(37, restored.state.camera.yawDegrees)
        assertEquals(edited.toWire(), restored.state.toWire())
        val redone = restored.state.apply(SceneCommand.Redo, reopened.library.sceneContext(T))
        assertEquals(Vec3Mm(600, 0, 0), redone.instances.first { it.instanceId == INST_B }.poseMm)
        reopened.close()
    }

    @Test fun save_replaces_the_draft_with_a_fresh_boundary_in_the_same_transaction() {
        val (_, store) = seeded("boundary")
        store.drafts.commit(WriterService.editedDraft(store), T)
        assertEquals(SaveResult.Saved(2), store.scenes.save(Seed.secondRevision(store)))
        val draft = assertIs<DraftLoad.Restored>(store.drafts.load(SCENE, T)).state
        assertEquals(2, draft.baseRevision)
        assertTrue(draft.undo.isEmpty() && draft.redo.isEmpty())
        assertEquals(store.scenes.revision(SCENE, 1)!!.instances.single(), draft.instances.first { it.instanceId == INST_A })
        store.close()
    }

    @Test fun damaged_draft_is_quarantined_and_head_stays_usable() {
        val (name, store) = seeded("draftdamage")
        store.drafts.commit(WriterService.editedDraft(store), T)
        store.close()
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { it.execSQL("UPDATE edit_draft SET stateJson = stateJson || ' '") }
        val reopened = Seed.open(context, name)
        assertEquals(DraftLoad.Damaged, reopened.drafts.load(SCENE, T))
        assertEquals(1, reopened.quarantine.entries().size)
        assertIs<SceneLoad.Healthy>(reopened.scenes.load(SCENE, T))
        reopened.close()
    }

    @Test fun CE10_library_version_keeps_scene_reference_until_applied_and_saved() {
        val (_, store) = seeded("version")
        assertEquals(VersionWrite.Saved(2), store.library.saveItemVersion(Seed.item(ITEM_A, revision = 2, x = 300)))
        val head = assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision
        assertEquals(1, head.instances.single().itemRevision)
        val context = store.library.sceneContext(T)
        assertEquals(emptyList(), StaleAnalysis.forRevision(head, context).staleReasons)
        assertEquals(listOf(ItemRef(ITEM_A, 2)), StaleAnalysis.forRevision(head, context).newerItems)

        val applied = SceneDraftState.fromRevision(head).apply(SceneCommand.ApplyItemVersion(INST_A, 2), context)
        assertIs<SaveDecision.Rejected>(SavePreparation.prepare(applied, 1, context, T))
        val saved = Seed.prepare(applied, 1, context)
        assertEquals(SaveResult.Saved(2), store.scenes.save(saved))
        assertEquals(1, store.scenes.revision(SCENE, 1)!!.instances.single().itemRevision)
        assertEquals(2, store.scenes.revision(SCENE, 2)!!.instances.single().itemRevision)
        assertEquals(Seed.ORIGIN, store.scenes.revision(SCENE, 2)!!.instances.single().poseMm)
        store.close()
    }

    @Test fun item_versions_are_monotonic_and_corrupt_versions_drop_out() {
        val (name, store) = seeded("versions")
        assertEquals(VersionWrite.Conflict(2, 5), store.library.saveItemVersion(Seed.item(ITEM_A, revision = 5)))
        store.close()
        SQLiteDatabase.openDatabase(databaseFile(name).path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("DROP TRIGGER immutable_item_version_update")
            db.execSQL("UPDATE item_version SET json = replace(json, '200', '201') WHERE itemId = '$ITEM_A'")
        }
        val reopened = Seed.open(context, name)
        assertEquals(null, reopened.library.item(ITEM_A, 1, T))
        assertEquals("item_version", reopened.quarantine.entries().single().tableName)
        reopened.close()
    }

    @Test fun acknowledged_issues_are_stored_with_the_revision() {
        val (_, store) = seeded("ack")
        val context = store.library.sceneContext(T)
        val head = assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T)).revision
        val draft = SceneDraftState.fromRevision(head).apply(SceneCommand.Add(INST_B, ItemRef(ITEM_B, 1), Vec3Mm(100, 0, 0), 0), context)
        val record = (ai.cargo.scene.SceneValidator.validate(draft, context) as ai.cargo.scene.ValidationOutcome.Ready).record
        val validated = draft.apply(SceneCommand.ApplyValidation(record), context)
        val acknowledged = validated.apply(SceneCommand.ConfirmPlacement(record.inputHash, record.issues), context)
        val saved: PreparedSave = (SavePreparation.prepare(acknowledged, 1, context, T) as SaveDecision.Approved).save
        assertEquals(SaveResult.Saved(2), store.scenes.save(saved))
        val load = assertIs<SceneLoad.Healthy>(store.scenes.load(SCENE, T))
        assertTrue(load.acknowledged.contains(JudgementIssue(IssueKind.PLACEMENT_CONFLICT, INST_A)))
        assertEquals(LockedChange::class, LockedChange(INST_A, ai.cargo.scene.LockedChangeKind.POSE)::class)
        store.close()
    }

    @Test fun schema_constant_and_contract_dimensions_are_as_expected() {
        assertEquals(2, CargoDatabase.CURRENT_VERSION)
        assertEquals(DimensionsMm(2000, 1000, 2000), Seed.profile().bounds)
        assertTrue(SceneJson.encodeRevision(Seed.run {
            val store = open(context, databaseName("encode"))
            firstRevision(store)
            (store.scenes.load(SCENE, T) as SceneLoad.Healthy).revision.also { store.close() }
        }).contains("NOT_ASSESSED"))
    }
}
