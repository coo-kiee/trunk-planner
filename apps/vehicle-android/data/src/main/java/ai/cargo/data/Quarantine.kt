package ai.cargo.data

import ai.cargo.data.db.CargoDao
import ai.cargo.data.db.QuarantineEntity

/** Records damage once per row. It copies; the damaged original is left in place for analysis. */
class Quarantine internal constructor(private val dao: CargoDao) {
    fun record(table: String, key: String, json: String, checksum: String, reason: String, at: String) {
        if (dao.quarantineCount(table, key) == 0) {
            dao.insertQuarantine(QuarantineEntity(0, table, key, json, checksum, reason, at))
        }
    }

    fun entries(): List<QuarantineEntity> = dao.quarantined()
}
