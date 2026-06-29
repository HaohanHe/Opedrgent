package top.hsyscn.opedrgent.note

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * 知识图谱 SQLite 数据库（原生实现）。
 *
 * 为知识图谱提供持久化存储：节点、边、embedding、实体及关联关系。
 * 不依赖 Room/kapt，避免注解处理器兼容性问题。
 */
class KnowledgeGraphDatabase(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {

    companion object {
        private const val DATABASE_NAME = "knowledge_graph.db"
        private const val DATABASE_VERSION = 1

        const val TABLE_NODES = "kg_nodes"
        const val TABLE_EDGES = "kg_edges"
        const val TABLE_EMBEDDINGS = "kg_embeddings"
        const val TABLE_ENTITIES = "kg_entities"
        const val TABLE_NODE_ENTITIES = "kg_node_entities"
        const val TABLE_MIGRATION_LOG = "kg_migration_log"

        // kg_nodes
        const val COL_NODE_ID = "id"
        const val COL_NODE_TITLE = "title"
        const val COL_NODE_SUMMARY = "summary"
        const val COL_NODE_KEYWORDS = "keywords"
        const val COL_NODE_UPDATED_AT = "updated_at"
        const val COL_NODE_CONTENT_HASH = "content_hash"

        // kg_edges
        const val COL_EDGE_ID = "id"
        const val COL_EDGE_SOURCE_ID = "source_id"
        const val COL_EDGE_TARGET_ID = "target_id"
        const val COL_EDGE_RELATION_TYPE = "relation_type"
        const val COL_EDGE_WEIGHT = "weight"
        const val COL_EDGE_REASON = "reason"
        const val COL_EDGE_CREATED_AT = "created_at"

        // kg_embeddings
        const val COL_EMB_NODE_ID = "node_id"
        const val COL_EMB_PROVIDER = "provider"
        const val COL_EMB_MODEL = "model"
        const val COL_EMB_DIMENSION = "dimension"
        const val COL_EMB_VECTOR = "vector"
        const val COL_EMB_UPDATED_AT = "updated_at"

        // kg_entities
        const val COL_ENTITY_ID = "id"
        const val COL_ENTITY_NAME = "name"
        const val COL_ENTITY_TYPE = "entity_type"
        const val COL_ENTITY_FREQUENCY = "frequency"

        // kg_node_entities
        const val COL_NE_NODE_ID = "node_id"
        const val COL_NE_ENTITY_ID = "entity_id"
        const val COL_NE_WEIGHT = "weight"

        // kg_migration_log
        const val COL_MIG_ID = "id"
        const val COL_MIG_VERSION = "version"
        const val COL_MIG_MIGRATED_AT = "migrated_at"
        const val COL_MIG_SOURCE = "source"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NODES (
                $COL_NODE_ID TEXT PRIMARY KEY,
                $COL_NODE_TITLE TEXT DEFAULT '',
                $COL_NODE_SUMMARY TEXT DEFAULT '',
                $COL_NODE_KEYWORDS TEXT DEFAULT '',
                $COL_NODE_UPDATED_AT INTEGER NOT NULL DEFAULT 0,
                $COL_NODE_CONTENT_HASH TEXT DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EDGES (
                $COL_EDGE_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_EDGE_SOURCE_ID TEXT NOT NULL,
                $COL_EDGE_TARGET_ID TEXT NOT NULL,
                $COL_EDGE_RELATION_TYPE TEXT NOT NULL,
                $COL_EDGE_WEIGHT REAL NOT NULL DEFAULT 0,
                $COL_EDGE_REASON TEXT DEFAULT '',
                $COL_EDGE_CREATED_AT INTEGER NOT NULL DEFAULT 0,
                UNIQUE($COL_EDGE_SOURCE_ID, $COL_EDGE_TARGET_ID, $COL_EDGE_RELATION_TYPE)
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_EMBEDDINGS (
                $COL_EMB_NODE_ID TEXT PRIMARY KEY,
                $COL_EMB_PROVIDER TEXT NOT NULL DEFAULT '',
                $COL_EMB_MODEL TEXT DEFAULT '',
                $COL_EMB_DIMENSION INTEGER NOT NULL DEFAULT 0,
                $COL_EMB_VECTOR BLOB,
                $COL_EMB_UPDATED_AT INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_ENTITIES (
                $COL_ENTITY_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_ENTITY_NAME TEXT UNIQUE NOT NULL,
                $COL_ENTITY_TYPE TEXT DEFAULT '',
                $COL_ENTITY_FREQUENCY INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NODE_ENTITIES (
                $COL_NE_NODE_ID TEXT NOT NULL,
                $COL_NE_ENTITY_ID INTEGER NOT NULL,
                $COL_NE_WEIGHT REAL NOT NULL DEFAULT 0,
                PRIMARY KEY($COL_NE_NODE_ID, $COL_NE_ENTITY_ID),
                FOREIGN KEY($COL_NE_NODE_ID) REFERENCES $TABLE_NODES($COL_NODE_ID) ON DELETE CASCADE,
                FOREIGN KEY($COL_NE_ENTITY_ID) REFERENCES $TABLE_ENTITIES($COL_ENTITY_ID) ON DELETE CASCADE
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MIGRATION_LOG (
                $COL_MIG_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_MIG_VERSION INTEGER NOT NULL,
                $COL_MIG_MIGRATED_AT INTEGER NOT NULL,
                $COL_MIG_SOURCE TEXT DEFAULT ''
            )
            """.trimIndent()
        )

        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_source ON $TABLE_EDGES($COL_EDGE_SOURCE_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_target ON $TABLE_EDGES($COL_EDGE_TARGET_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_type ON $TABLE_EDGES($COL_EDGE_RELATION_TYPE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_node_entities_entity ON $TABLE_NODE_ENTITIES($COL_NE_ENTITY_ID)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_embeddings_provider ON $TABLE_EMBEDDINGS($COL_EMB_PROVIDER)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // 当前版本为 1，后续升级在此处理。
    }
}
