/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.providers.telephony;

import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProvider.TRANSACTION_FAILED;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.BaseColumns;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to threads for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderThreadHelper {
    static final String THREAD_TABLE = "rcs_thread";
    static final String OWNER_PARTICIPANT = "owner_participant";
    static final String THREAD_TYPE = "thread_type";

    static final String RCS_1_TO_1_THREAD_TABLE = "rcs_1_to_1_thread";
    static final String RCS_THREAD_ID_COLUMN = "rcs_thread_id";
    static final String FALLBACK_THREAD_ID_COLUMN = "rcs_fallback_thread_id";

    static final String RCS_GROUP_THREAD_TABLE = "rcs_group_thread";
    static final String GROUP_NAME_COLUMN = "group_name";
    static final String GROUP_ICON_COLUMN = "group_icon";
    static final String CONFERENCE_URI_COLUMN = "conference_uri";

    static final String UNIFIED_RCS_THREAD_VIEW = "unified_rcs_thread_view";

    static final int THREAD_ID_INDEX_IN_URI = 1;

    @VisibleForTesting
    public static void createThreadTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating thread tables");

        // Add the thread tables
        db.execSQL("CREATE TABLE " + THREAD_TABLE + " (" +
                BaseColumns._ID + " INTEGER PRIMARY KEY AUTOINCREMENT);");

        db.execSQL("CREATE TABLE " + RCS_1_TO_1_THREAD_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER PRIMARY KEY, " +
                FALLBACK_THREAD_ID_COLUMN + " INTEGER, " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + THREAD_TABLE + "(" + BaseColumns._ID + ")," +
                "FOREIGN KEY(" + FALLBACK_THREAD_ID_COLUMN
                + ") REFERENCES threads( " + BaseColumns._ID + "))");

        db.execSQL("CREATE TABLE " + RCS_GROUP_THREAD_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER PRIMARY KEY, " +
                OWNER_PARTICIPANT + " INTEGER, " +
                GROUP_NAME_COLUMN + " TEXT, " +
                GROUP_ICON_COLUMN + " TEXT, " +
                CONFERENCE_URI_COLUMN + " TEXT, " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + THREAD_TABLE + "(" + BaseColumns._ID + "))");

        // Add the views

        // The following is a unified thread view. Since SQLite does not support right or full
        // joins, we are using a union with null values for unused variables for each thread type.
        // The last column is an easy way to figure out whether the entry came from a 1 to 1 thread
        // or a group thread.
        //
        // SELECT
        //  rcs_thread_id,
        //  rcs_fallback_thread_id,
        //  null AS owner_participant,
        //  null AS group_name,
        //  null AS icon,
        //  null AS conference_uri,
        //  0 AS is_group
        // FROM
        //  rcs_1_to_1_thread
        // UNION
        // SELECT
        //  rcs_thread_id,
        //  null AS rcs_fallback_thread_id,
        //  owner_participant,
        //  group_name,
        //  group_icon,
        //  conference_uri,
        //  1 AS is_group
        // FROM
        //  rcs_group_thread
        db.execSQL("CREATE VIEW " + UNIFIED_RCS_THREAD_VIEW
                + " AS SELECT rcs_thread_id, rcs_fallback_thread_id, null AS owner_participant, "
                + "null AS group_name, null AS group_icon, null AS conference_uri, 0 AS thread_type"
                + " FROM rcs_1_to_1_thread UNION SELECT rcs_thread_id, null AS "
                + "rcs_fallback_thread_id, owner_participant, group_name, group_icon, "
                + "conference_uri, 1 AS thread_type FROM rcs_group_thread");

        // Add the triggers

        // Delete the corresponding rcs_thread row upon deleting a row in rcs_1_to_1_thread
        //
        // CREATE TRIGGER deleteRcsThreadBefore1to1
        //  AFTER DELETE ON rcs_1_to_1_thread
        // BEGIN
        //  DELETE FROM rcs_thread WHERE rcs_thread._id=OLD.rcs_thread_id;
        // END;
        db.execSQL(
                "CREATE TRIGGER deleteRcsThreadBefore1to1 AFTER DELETE ON rcs_1_to_1_thread BEGIN"
                        + " DELETE FROM rcs_thread WHERE rcs_thread._id=OLD.rcs_thread_id; END");

        // Delete the corresponding rcs_thread row upon deleting a row in rcs_group_thread
        //
        // CREATE TRIGGER deleteRcsThreadBefore1to1
        //  AFTER DELETE ON rcs_1_to_1_thread
        // BEGIN
        //  DELETE FROM rcs_thread WHERE rcs_thread._id=OLD.rcs_thread_id;
        // END;
        db.execSQL(
                "CREATE TRIGGER deleteRcsThreadBeforeGroup AFTER DELETE ON rcs_group_thread BEGIN"
                        + " DELETE FROM rcs_thread WHERE rcs_thread._id=OLD.rcs_thread_id; END");

        // Delete the junction table entries upon deleting a 1 to 1 thread
        //
        // CREATE TRIGGER delete1To1JunctionEntries
        // AFTER
        //  DELETE ON rcs_1_to_1_thread
        // BEGIN
        //  DELETE FROM
        //   rcs_thread_participant
        //  WHERE
        //   rcs_thread_participant.rcs_thread_id = OLD.rcs_thread_id;
        // END
        db.execSQL("CREATE TRIGGER delete1To1JunctionEntries AFTER DELETE ON rcs_1_to_1_thread "
                + "BEGIN DELETE FROM rcs_thread_participant WHERE rcs_thread_participant"
                + ".rcs_thread_id=OLD.rcs_thread_id; END");

        // Delete the junction table entries upon deleting a group thread
        //
        // CREATE TRIGGER delete1To1JunctionEntries
        // AFTER
        //  DELETE ON rcs_1_to_1_thread
        // BEGIN
        //  DELETE FROM
        //   rcs_thread_participant
        //  WHERE
        //   rcs_thread_participant.rcs_thread_id = OLD.rcs_thread_id;
        // END
        db.execSQL("CREATE TRIGGER deleteGroupJunctionEntries AFTER DELETE ON rcs_group_thread "
                + "BEGIN DELETE FROM rcs_thread_participant WHERE rcs_thread_participant"
                + ".rcs_thread_id=OLD.rcs_thread_id; END");

        // TODO - create indexes for faster querying
    }

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    RcsProviderThreadHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    Cursor queryUnifiedThread(String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_RCS_THREAD_VIEW, projection, selection, selectionArgs, null,
                null, sortOrder);
    }

    Cursor queryUnifiedThreadUsingId(Uri uri, String[] projection) {
        return queryUnifiedThread(projection, getThreadIdSelection(uri), null, null);
    }

    Cursor query1to1Thread(String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(RCS_1_TO_1_THREAD_TABLE, projection, selection, selectionArgs, null,
                null, sortOrder);
    }

    Cursor query1To1ThreadUsingId(Uri uri, String[] projection) {
        return query1to1Thread(projection, getThreadIdSelection(uri), null, null);
    }

    Cursor queryGroupThread(String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(RCS_GROUP_THREAD_TABLE, projection, selection, selectionArgs, null,
                null, sortOrder);
    }

    Cursor queryGroupThreadUsingId(Uri uri, String[] projection) {
        return queryGroupThread(projection, getThreadIdSelection(uri), null, null);
    }

    long insert1To1Thread(ContentValues contentValues) {
        long returnValue = TRANSACTION_FAILED;
        if (contentValues.containsKey(BaseColumns._ID) || contentValues.containsKey(
                RCS_THREAD_ID_COLUMN)) {
            Log.e(RcsProvider.TAG,
                    "RcsProviderThreadHelper: inserting threads with IDs is not supported");
            return returnValue;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        try {
            db.beginTransaction();

            // Insert into the common rcs_threads table
            long rowId = insertIntoCommonRcsThreads(db);
            if (rowId <= 0) {
                return returnValue;
            }

            // Add the rowId in rcs_threads table as a foreign key in rcs_1_to_1_table
            ContentValues insertionValues = new ContentValues(contentValues);
            insertionValues.put(RCS_THREAD_ID_COLUMN, rowId);
            db.insert(RCS_1_TO_1_THREAD_TABLE, RCS_THREAD_ID_COLUMN, insertionValues);

            db.setTransactionSuccessful();
            returnValue = rowId;
        } finally {
            db.endTransaction();
        }
        return returnValue;
    }

    long insertGroupThread(ContentValues contentValues) {
        long returnValue = TRANSACTION_FAILED;
        if (contentValues.containsKey(BaseColumns._ID) || contentValues.containsKey(
                RCS_THREAD_ID_COLUMN)) {
            Log.e(RcsProvider.TAG,
                    "RcsProviderThreadHelper: inserting threads with IDs is not supported");
            return returnValue;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        try {
            db.beginTransaction();

            // Insert into the common rcs_threads table
            long rowId = insertIntoCommonRcsThreads(db);
            if (rowId <= 0) {
                return returnValue;
            }

            // Add the rowId in rcs_threads table as a foreign key in rcs_group_table
            ContentValues insertionValues = new ContentValues(contentValues);
            insertionValues.put(RCS_THREAD_ID_COLUMN, rowId);
            db.insert(RCS_GROUP_THREAD_TABLE, RCS_THREAD_ID_COLUMN, insertionValues);

            db.setTransactionSuccessful();
            returnValue = rowId;
        } finally {
            db.endTransaction();
        }
        return returnValue;
    }

    private long insertIntoCommonRcsThreads(SQLiteDatabase db) {
        return db.insert(THREAD_TABLE, BaseColumns._ID, new ContentValues());
    }

    /**
     * Deletes the thread from either 1_to_1 or group table and depends on the triggers to delete
     * the rcs_thread entry
     */
    int deleteUnifiedThread(String selection, String[] selectionArgs) {
        int deletedCount;
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        Cursor cursor = queryUnifiedThread(new String[]{THREAD_TYPE},
                selection, selectionArgs, null);
        if (cursor == null || !cursor.moveToNext()) {
            return 0;
        }
        boolean isGroup = cursor.getInt(0) == 1;

        if (isGroup) {
            deletedCount = db.delete(RCS_GROUP_THREAD_TABLE, selection, selectionArgs);
        } else {
            deletedCount = db.delete(RCS_1_TO_1_THREAD_TABLE, selection, selectionArgs);
        }
        cursor.close();
        return deletedCount;
    }

    int deleteUnifiedThreadWithId(Uri uri) {
        return deleteUnifiedThread(getThreadIdSelection(uri), null);
    }

    int delete1To1Thread(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(RCS_1_TO_1_THREAD_TABLE, selection, selectionArgs);
    }

    int delete1To1ThreadWithId(Uri uri) {
        return delete1To1Thread(getThreadIdSelection(uri), null);
    }

    int deleteGroupThread(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(RCS_GROUP_THREAD_TABLE, selection, selectionArgs);
    }

    int deleteGroupThreadWithId(Uri uri) {
        return deleteGroupThread(getThreadIdSelection(uri), null);
    }

    int update1To1Thread(ContentValues values, String selection, String[] selectionArgs) {
        if (values.containsKey(RCS_THREAD_ID_COLUMN)) {
            Log.e(TAG,
                    "RcsProviderThreadHelper: updating thread id for 1 to 1 threads is not "
                            + "allowed");
            return 0;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(RCS_1_TO_1_THREAD_TABLE, values, selection, selectionArgs);
    }

    int update1To1ThreadWithId(ContentValues values, Uri uri) {
        return update1To1Thread(values, getThreadIdSelection(uri), null);
    }

    int updateGroupThread(ContentValues values, String selection, String[] selectionArgs) {
        if (values.containsKey(RCS_THREAD_ID_COLUMN)) {
            Log.e(TAG,
                    "RcsProviderThreadHelper: updating thread id for group threads is not "
                            + "allowed");
            return 0;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(RCS_GROUP_THREAD_TABLE, values, selection, selectionArgs);
    }

    int updateGroupThreadWithId(ContentValues values, Uri uri) {
        return updateGroupThread(values, getThreadIdSelection(uri), null);
    }

    private String getThreadIdSelection(Uri uri) {
        return "rcs_thread_id=" + getThreadIdFromUri(uri);
    }

    static String getThreadIdFromUri(Uri uri) {
        return uri.getPathSegments().get(THREAD_ID_INDEX_IN_URI);
    }
}
