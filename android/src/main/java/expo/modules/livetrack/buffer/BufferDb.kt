package expo.modules.livetrack.buffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database backing the offline location/event buffer.
 *
 * Single-table schema (`points`). Process-wide singleton via [getInstance] so the
 * capture service and the module bridge share one connection.
 */
@Database(entities = [PointEntity::class], version = 2, exportSchema = false)
abstract class BufferDb : RoomDatabase() {
  abstract fun pointDao(): PointDao

  companion object {
    private const val DB_NAME = "livetrack_buffer.db"

    @Volatile
    private var instance: BufferDb? = null

    /**
     * v1 -> v2: adds `attempts`, the retry counter that bounds re-upload loops.
     *
     * ADDITIVE ONLY — no table rebuild, no data copy, so every un-uploaded row
     * survives the app update. `NOT NULL` is legal in `ALTER TABLE ADD COLUMN`
     * precisely because a DEFAULT is supplied; the matching
     * `@ColumnInfo(defaultValue = "0")` on the entity keeps the fresh-install
     * CREATE TABLE identical, so Room's post-migration validation passes on both
     * install paths.
     */
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `points` ADD COLUMN `attempts` INTEGER NOT NULL DEFAULT 0")
      }
    }

    fun getInstance(context: Context): BufferDb =
      instance ?: synchronized(this) {
        instance ?: build(context).also { instance = it }
      }

    private fun build(context: Context): BufferDb =
      Room.databaseBuilder(
        context.applicationContext,
        BufferDb::class.java,
        DB_NAME,
      )
        // NO destructive fallback: the buffer holds un-uploaded user data that must
        // survive app updates. Every schema bump MUST register an explicit Migration
        // here, so existing data is preserved across updates.
        .addMigrations(MIGRATION_1_2)
        .build()
  }
}
