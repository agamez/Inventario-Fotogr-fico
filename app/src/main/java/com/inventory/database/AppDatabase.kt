package com.inventory.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ContainerEntity::class,
        PhotoEntity::class,
    ],
    version = 3,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun containerDao(): ContainerDao

    companion object {
        /**
         * Adds the nullable `coverImage` BLOB column to boxes and locations.
         * Existing rows keep NULL, so user data survives untouched.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE boxes ADD COLUMN coverImage BLOB")
                db.execSQL("ALTER TABLE locations ADD COLUMN coverImage BLOB")
            }
        }

        /**
         * Collapses the two-level locations/boxes model into one unlimited
         * `containers` tree. Locations become root containers keeping their
         * ids; boxes become children of their location, re-keyed by the
         * location id offset so the two id spaces never collide. Photos are
         * rebuilt against the new table with `boxId` renamed to `nodeId`
         * (detached photos stay detached: NULL + x is NULL).
         *
         * FKs are disabled by Room while a migration runs, so the order of
         * statements is collision-free; ids are only ever rewritten, names
         * are untouched, and uniqueness lives in the repository (no unique
         * index exists to conflict with during the copy).
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS containers (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "parentId INTEGER, " +
                        "coverImage BLOB, " +
                        "createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(parentId) REFERENCES containers(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_containers_parentId " +
                        "ON containers(parentId)",
                )
                db.execSQL(
                    "INSERT INTO containers (id, name, parentId, coverImage, createdAt) " +
                        "SELECT id, name, NULL, coverImage, createdAt FROM locations",
                )
                db.execSQL(
                    "INSERT INTO containers (id, name, parentId, coverImage, createdAt) " +
                        "SELECT b.id + (SELECT COALESCE(MAX(id), 0) FROM locations), b.name, " +
                        "b.locationId, " +
                        "b.coverImage, b.createdAt FROM boxes b",
                )
                db.execSQL(
                    "CREATE TABLE photos_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "nodeId INTEGER, " +
                        "image BLOB NOT NULL, " +
                        "description TEXT NOT NULL, " +
                        "note TEXT, " +
                        "createdAt INTEGER NOT NULL, " +
                        "FOREIGN KEY(nodeId) REFERENCES containers(id) " +
                        "ON UPDATE NO ACTION ON DELETE CASCADE" +
                        ")",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_photos_nodeId " +
                        "ON photos_new(nodeId)",
                )
                db.execSQL(
                    "INSERT INTO photos_new (id, nodeId, image, description, note, createdAt) " +
                        "SELECT id, boxId + (SELECT COALESCE(MAX(id), 0) FROM locations), " +
                        "image, description, note, createdAt FROM photos",
                )
                db.execSQL("DROP TABLE photos")
                db.execSQL("ALTER TABLE photos_new RENAME TO photos")
                db.execSQL("DROP TABLE boxes")
                db.execSQL("DROP TABLE locations")
            }
        }
    }
}
