package ai.retrobot.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProviderAccountEntity::class,
        ModelEntity::class,
        VoteEntity::class,
        RatingEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class ArenaDatabase : RoomDatabase() {

    abstract fun providerAccountDao(): ProviderAccountDao
    abstract fun modelDao(): ModelDao
    abstract fun voteDao(): VoteDao
    abstract fun ratingDao(): RatingDao

    companion object {
        @Volatile
        private var instance: ArenaDatabase? = null

        fun get(context: Context): ArenaDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context).also { instance = it }
            }

        private fun build(context: Context): ArenaDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ArenaDatabase::class.java,
                "retrobot.db",
            )
                .fallbackToDestructiveMigration()
                .build()
    }
}
