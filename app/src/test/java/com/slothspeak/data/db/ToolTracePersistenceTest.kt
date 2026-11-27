package com.slothspeak.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.slothspeak.data.db.entities.Conversation
import com.slothspeak.data.db.entities.QAPair
import com.slothspeak.tools.ToolTraceEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * Schema v6: the persisted tool trace (`QAPair.toolCallsJson`) round-trips through Room, and a
 * real v5 database is upgraded in place by [SlothSpeakDatabase.MIGRATION_5_6] without data loss.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ToolTracePersistenceTest {

    private lateinit var context: Context
    private lateinit var db: SlothSpeakDatabase

    private val trace = listOf(
        ToolTraceEntry("brave_web_search", """{"query": "sloths"}""", "1. Sloths …", isError = false, durationMs = 120L),
        ToolTraceEntry("http_request", """{"method": "GET", "url": "https://example.com"}""", "HTTP 200", isError = false, durationMs = 45L)
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SlothSpeakDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndRead_toolCallsJson_roundTrip() = runBlocking {
        val dao = db.conversationDao()
        val convId = dao.insertConversation(Conversation())
        val pairId = dao.insertQAPair(
            QAPair(
                conversationId = convId,
                questionText = "q",
                answerText = "a",
                responseId = "r1",
                model = "glm-5.3",
                toolCallsJson = ToolTraceEntry.toJson(trace)
            )
        )

        val loaded = dao.getQAPairsForConversationOnce(convId).single()
        assertEquals(pairId, loaded.id)
        assertEquals(trace, ToolTraceEntry.fromJson(loaded.toolCallsJson))
    }

    @Test
    fun toolCallsJson_defaultsToNull_forLegacyRows() = runBlocking {
        val dao = db.conversationDao()
        val convId = dao.insertConversation(Conversation())
        val pairId = dao.insertQAPair(QAPair(conversationId = convId, questionText = "q", answerText = "", responseId = "", model = "m"))

        val loaded = dao.getQAPairsForConversationOnce(convId).single()
        assertNull(loaded.toolCallsJson)
        assertEquals(emptyList<ToolTraceEntry>(), ToolTraceEntry.fromJson(loaded.toolCallsJson))

        // Early-save placeholder → final result: the trace is persisted with the answer.
        dao.updateQAPairResult(
            qaPairId = pairId,
            answerText = "final answer",
            audioFilePaths = null,
            responseId = "r2",
            model = "glm-5.3",
            reasoningEffort = "high",
            llmResponseSeconds = 4,
            answerTextRich = null,
            reasoningText = "thinking…",
            toolCallsJson = ToolTraceEntry.toJson(trace)
        )
        val updated = dao.getQAPairsForConversationOnce(convId).single()
        assertEquals("final answer", updated.answerText)
        assertEquals(trace, ToolTraceEntry.fromJson(updated.toolCallsJson))
    }

    @Test
    fun updateQAPairResult_withoutToolCallsJson_storesNull() = runBlocking {
        val dao = db.conversationDao()
        val convId = dao.insertConversation(Conversation())
        val pairId = dao.insertQAPair(
            QAPair(conversationId = convId, questionText = "q", answerText = "", responseId = "", model = "m",
                toolCallsJson = ToolTraceEntry.toJson(trace))
        )
        dao.updateQAPairResult(
            qaPairId = pairId, answerText = "a", audioFilePaths = null, responseId = "r",
            model = "m", reasoningEffort = null, llmResponseSeconds = 1, answerTextRich = null
        )
        val updated = dao.getQAPairsForConversationOnce(convId).single()
        assertNull("an answer produced without tools must not inherit an older trace", updated.toolCallsJson)
    }

    // ============================================================
    // Migration 5 → 6
    // ============================================================

    @Test
    fun migration_5_6_addsToolCallsJsonAndKeepsData() {
        val name = "migration-test.db"
        context.getDatabasePath(name).let { file ->
            file.parentFile?.mkdirs()
            file.delete()
        }

        // A v5 database as Room created it before this change (no toolCallsJson column).
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name).path, null).use { sqlite ->
            sqlite.execSQL(
                "CREATE TABLE conversations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "createdAt INTEGER NOT NULL, isFavorite INTEGER NOT NULL)"
            )
            sqlite.execSQL(
                "CREATE TABLE qa_pairs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "conversationId INTEGER NOT NULL, questionText TEXT NOT NULL, answerText TEXT NOT NULL, " +
                    "audioFilePaths TEXT, responseId TEXT NOT NULL, model TEXT NOT NULL, " +
                    "reasoningEffort TEXT, llmResponseSeconds INTEGER, answerTextRich TEXT, " +
                    "reasoningText TEXT, createdAt INTEGER NOT NULL, " +
                    "FOREIGN KEY(conversationId) REFERENCES conversations(id) " +
                    "ON UPDATE NO ACTION ON DELETE CASCADE)"
            )
            sqlite.execSQL("CREATE INDEX index_qa_pairs_conversationId ON qa_pairs (conversationId)")
            sqlite.execSQL("INSERT INTO conversations (createdAt, isFavorite) VALUES (1700000000000, 1)")
            sqlite.execSQL(
                "INSERT INTO qa_pairs (conversationId, questionText, answerText, responseId, model, createdAt) " +
                    "VALUES (1, 'old question', 'old answer', 'resp-1', 'glm-5.3', 1700000000000)"
            )
            sqlite.version = 5
        }

        // Opening at version 6 runs MIGRATION_5_6 and validates the resulting schema.
        val migrated = Room.databaseBuilder(context, SlothSpeakDatabase::class.java, name)
            .addMigrations(SlothSpeakDatabase.MIGRATION_5_6)
            .allowMainThreadQueries()
            .build()
        try {
            val columns = mutableMapOf<String, Pair<String, Int>>()
            migrated.openHelper.readableDatabase.query("PRAGMA table_info(qa_pairs)").use { cursor ->
                while (cursor.moveToNext()) {
                    columns[cursor.getString(cursor.getColumnIndexOrThrow("name"))] =
                        cursor.getString(cursor.getColumnIndexOrThrow("type")) to cursor.getInt(cursor.getColumnIndexOrThrow("notnull"))
                }
            }
            assertNotNull("toolCallsJson column missing after migration", columns["toolCallsJson"])
            assertEquals("TEXT" to 0, columns["toolCallsJson"])
            assertEquals("legacy columns are untouched", "TEXT" to 0, columns["reasoningText"])

            val dao = migrated.conversationDao()
            val legacy = runBlocking { dao.getQAPairsForConversationOnce(1L).single() }
            assertEquals("old question", legacy.questionText)
            assertEquals("old answer", legacy.answerText)
            assertNull(legacy.toolCallsJson)
            assertTrue(ToolTraceEntry.fromJson(legacy.toolCallsJson).isEmpty())

            // New rows can persist a trace alongside the migrated ones.
            val convId = 1L
            runBlocking {
                dao.insertQAPair(
                    QAPair(conversationId = convId, questionText = "new", answerText = "with tools",
                        responseId = "resp-2", model = "glm-5.3", toolCallsJson = ToolTraceEntry.toJson(trace))
                )
            }
            val pairs = runBlocking { dao.getQAPairsForConversationOnce(convId) }
            assertEquals(2, pairs.size)
            assertEquals(trace, ToolTraceEntry.fromJson(pairs.last().toolCallsJson))
        } finally {
            migrated.close()
        }
    }
}
