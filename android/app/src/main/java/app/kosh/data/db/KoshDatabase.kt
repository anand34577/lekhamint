package app.kosh.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val list = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(value: List<String>): String = Json.encodeToString(list, value)

    @TypeConverter
    fun toList(value: String?): List<String> =
        if (value.isNullOrEmpty()) emptyList() else runCatching { Json.decodeFromString(list, value) }.getOrDefault(emptyList())
}

/**
 * Version history:
 *  1. Initial schema.
 *  2. Typed tables for transaction_splits and rules. Splits stored as raw
 *     records by version 1 are moved over by [app.kosh.data.Repository.promoteRawRecords].
 *  3. Loans and deposits, and transactions.loan_id / deposit_id / part. Loans
 *     and deposits kept as raw records, and those fields kept in transaction
 *     extras by version 2, are promoted on start.
 */
@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TagEntity::class, TransactionEntity::class,
        BudgetEntity::class, RecurringRuleEntity::class, DebtEntity::class, RawRecordEntity::class,
        SplitEntity::class, RuleEntity::class, LoanEntity::class, DepositEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class KoshDatabase : RoomDatabase() {
    abstract fun accounts(): AccountDao
    abstract fun categories(): CategoryDao
    abstract fun tags(): TagDao
    abstract fun transactions(): TransactionDao
    abstract fun budgets(): BudgetDao
    abstract fun recurring(): RecurringDao
    abstract fun debts(): DebtDao
    abstract fun splits(): SplitDao
    abstract fun rules(): RuleDao
    abstract fun loans(): LoanDao
    abstract fun deposits(): DepositDao
    abstract fun raw(): RawRecordDao
    abstract fun stats(): StatsDao

    companion object {
        const val NAME = "kosh.db"

        fun open(context: Context): KoshDatabase =
            Room.databaseBuilder(context, KoshDatabase::class.java, NAME)
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .build()
    }
}
