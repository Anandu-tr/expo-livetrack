package expo.modules.livetrack.buffer

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Room database backing the offline location/event buffer.
 *
 * Single-table schema (`points`). Process-wide singleton via [getInstance] so the
 * capture service and the module bridge share one connection.
 */
@Database(entities = [PointEntity::class], version = 1, exportSchema = false)
abstract class BufferDb : RoomDatabase() {
  abstract fun pointDao(): PointDao

  companion object {
    private const val DB_NAME = "livetrack_buffer.db"

    @Volatile
    private var instance: BufferDb? = null

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
        // Buffer is disposable telemetry; a destructive reset on schema change is
        // acceptable and far safer than crashing the capture path on migration.
        .fallbackToDestructiveMigration()
        .build()
  }
}
