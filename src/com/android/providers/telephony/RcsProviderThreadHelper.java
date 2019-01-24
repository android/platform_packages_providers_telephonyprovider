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

import static android.provider.Telephony.RcsColumns.Rcs1To1ThreadColumns.FALLBACK_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.CONFERENCE_URI_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_ICON_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.GROUP_NAME_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsGroupThreadColumns.OWNER_PARTICIPANT_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsMessageColumns.ORIGINATION_TIMESTAMP_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsThreadColumns.RCS_THREAD_ID_COLUMN;
import static android.provider.Telephony.RcsColumns.RcsUnifiedThreadColumns.THREAD_TYPE_COLUMN;
import static android.provider.Telephony.RcsColumns.TRANSACTION_FAILED;
import static android.telephony.ims.RcsThreadQueryContinuationToken.THREAD_QUERY_CONTINUATION_TOKEN;
import static android.telephony.ims.RcsThreadQueryParameters.THREAD_QUERY_PARAMETERS_KEY;

import static com.android.providers.telephony.RcsProvider.RCS_1_TO_1_THREAD_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_GROUP_THREAD_TABLE;
import static com.android.providers.telephony.RcsProvider.RCS_THREAD_TABLE;
import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProvider.UNIFIED_RCS_THREAD_VIEW;
import static com.android.providers.telephony.RcsProviderUtil.INSERTION_FAILED;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Bundle;
import android.provider.BaseColumns;
import android.telephony.ims.RcsThreadQueryContinuationToken;
import android.telephony.ims.RcsThreadQueryParameters;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to threads for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderThreadHelper {
    private static final int THREAD_ID_INDEX_IN_URI = 1;

    @VisibleForTesting
    public static void createThreadTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating thread tables");

        // Add the thread tables
        db.execSQL("CREATE TABLE " + RCS_THREAD_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT);");

        db.execSQL("CREATE TABLE " + RCS_1_TO_1_THREAD_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER PRIMARY KEY, " +
                FALLBACK_THREAD_ID_COLUMN + " INTEGER, " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + RCS_THREAD_TABLE + "(" + RCS_THREAD_ID_COLUMN + ")," +
                "FOREIGN KEY(" + FALLBACK_THREAD_ID_COLUMN
                + ") REFERENCES threads( " + BaseColumns._ID + "))");

        db.execSQL("CREATE TABLE " + RCS_GROUP_THREAD_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER PRIMARY KEY, " +
                OWNER_PARTICIPANT_COLUMN + " INTEGER, " +
                GROUP_NAME_COLUMN + " TEXT, " +
                GROUP_ICON_COLUMN + " TEXT, " +
                CONFERENCE_URI_COLUMN + " TEXT, " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + RCS_THREAD_TABLE + "(" + RCS_THREAD_ID_COLUMN + "))");

        // Add the views

        // The following is a unified thread view. Since SQLite does not support right or full
        // joins, we are using a union with null values for unused variables for each thread type.
        // The isGroup column is an easy way to figure out whether the entry came from a 1 to 1
        // thread or a group thread. The last message is added to figure out the latest threads and
        // snippet text. We use COALESCE so that MAX() can take null values into account in order to
        // have threads with no messages still represented here
        //
        // SELECT <1 to 1 thread and first message>
        // FROM (
        //     SELECT *
        //     FROM rcs_1_to_1_thread LEFT JOIN rcs_message
        //         ON rcs_1_to_1_thread.rcs_thread_id=rcs_message.rcs_thread_id)
        // GROUP BY rcs_thread_id
        // HAVING MAX(COALESCE(origination_timestamp,1))
        //
        // UNION
        // SELECT <group thread and first message>
        // FROM (
        //     SELECT *
        //     FROM rcs_group_thread LEFT JOIN rcs_message
        //         ON rcs_group_thread.rcs_thread_id=rcs_message.rcs_thread_id)
        // GROUP BY rcs_thread_id
        // HAVING MAX(COALESCE(origination_timestamp,1))

        db.execSQL("CREATE VIEW " + UNIFIED_RCS_THREAD_VIEW + " AS "
                + "SELECT rcs_thread_id, rcs_fallback_thread_id, null AS owner_participant, null "
                + "AS group_name, null AS group_icon, null AS conference_uri, 0 AS thread_type, "
                + "origination_timestamp, rcs_text, status FROM (SELECT * FROM rcs_1_to_1_thread "
                + "LEFT JOIN rcs_message ON rcs_1_to_1_thread.rcs_thread_id=rcs_message"
                + ".rcs_thread_id) GROUP BY rcs_thread_id HAVING MAX(COALESCE"
                + "(origination_timestamp,1)) UNION SELECT rcs_thread_id, null AS "
                + "rcs_fallback_thread_id, owner_participant, group_name, group_icon, "
                + "conference_uri, 1 AS thread_type, origination_timestamp, rcs_text, status FROM "
                + "(SELECT * FROM rcs_group_thread LEFT JOIN rcs_message ON rcs_group_thread"
                + ".rcs_thread_id=rcs_message.rcs_thread_id) GROUP BY rcs_thread_id HAVING MAX"
                + "(COALESCE(origination_timestamp,1))");

        // Add the triggers

        // Delete the corresponding rcs_thread row upon deleting a row in rcs_1_to_1_thread
        //
        // CREATE TRIGGER deleteRcsThreadBefore1to1
        //  AFTER DELETE ON rcs_1_to_1_thread
        // BEGIN
        //	DELETE FROM rcs_thread WHERE rcs_thread._id=OLD.rcs_thread_id;
        // END
        db.execSQL(
                "CREATE TRIGGER deleteRcsThreadBefore1to1 AFTER DELETE ON rcs_1_to_1_thread BEGIN"
                        + " DELETE FROM rcs_thread WHERE "
                        + "rcs_thread.rcs_thread_id=OLD.rcs_thread_id; END");

        // Delete the corresponding rcs_thread row upon deleting a row in rcs_group_thread
        //
        // CREATE TRIGGER deleteRcsThreadBefore1to1
        //  AFTER DELETE ON rcs_1_to_1_thread
        // BEGIN
        //	DELETE FROM rcs_thread WHERE rcs_thread._id=OLD.rcs_thread_id;
        // END
        db.execSQL(
                "CREATE TRIGGER deleteRcsThreadBeforeGroup AFTER DELETE ON rcs_group_thread BEGIN"
                        + " DELETE FROM rcs_thread WHERE "
                        + "rcs_thread.rcs_thread_id=OLD.rcs_thread_id; END");

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

    Cursor queryUnifiedThread(Bundle bundle) {
        RcsThreadQueryParameters queryParameters = null;
        RcsThreadQueryContinuationToken continuationToken = null;
        if (bundle != null) {
            queryParameters = bundle.getParcelable(
                    THREAD_QUERY_PARAMETERS_KEY);
            continuationToken = bundle.getParcelable(THREAD_QUERY_CONTINUATION_TOKEN);
        }

        if (continuationToken != null) {
            return RcsProviderUtil.performContinuationQuery(mSqLiteOpenHelper.getReadableDatabase(),
                    continuationToken);
        }

        if (queryParameters == null) {
            queryParameters = RcsThreadQueryParameters.builder().build();
        }

        return performInitialQuery(queryParameters);
    }

    private Cursor performInitialQuery(RcsThreadQueryParameters queryParameters) {
        if (queryParameters == null) {
            // return everything for test purposes
            queryParameters = RcsThreadQueryParameters.builder().build();
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        StringBuilder rawQuery = new StringBuilder("SELECT * FROM ").append(
                UNIFIED_RCS_THREAD_VIEW);

        if (queryParameters.getThreadType() == RcsThreadQueryParameters.ONLY_1_TO_1_THREADS) {
            rawQuery.append(" WHERE ").append(THREAD_TYPE_COLUMN).append("=0");
        } else if (queryParameters.getThreadType() == RcsThreadQueryParameters.ONLY_GROUP_THREADS) {
            rawQuery.append(" WHERE ").append(THREAD_TYPE_COLUMN).append("=1");
        }

        rawQuery.append(" ORDER BY ");

        if (queryParameters.getSortingProperty() == RcsThreadQueryParameters.TIMESTAMP) {
            rawQuery.append(ORIGINATION_TIMESTAMP_COLUMN);
        } else {
            rawQuery.append(RCS_THREAD_ID_COLUMN);
        }

        rawQuery.append(queryParameters.isAscending() ? " ASC " : " DESC ");
        RcsProviderUtil.appendLimit(rawQuery, queryParameters.getLimit());

        String rawQueryAsString = rawQuery.toString();
        Cursor cursor = db.rawQuery(rawQueryAsString, null);

        // If this is a paginated query, build the next query and return as a Cursor extra. Only do
        // this if the current query returned a result.
        int limit = queryParameters.getLimit();
        if (limit > 0) {
            RcsProviderUtil.createContinuationTokenBundle(cursor,
                    new RcsThreadQueryContinuationToken(rawQueryAsString, limit, limit),
                    THREAD_QUERY_CONTINUATION_TOKEN);
        }

        return cursor;
    }

    Cursor queryUnifiedThreadUsingId(Uri uri, String[] projection) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        String threadId = getThreadIdFromUri(uri);

        return db.query(UNIFIED_RCS_THREAD_VIEW, projection, RCS_THREAD_ID_COLUMN + "=?",
                new String[]{threadId},
                null, null, null);
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
        if (contentValues.containsKey(RCS_THREAD_ID_COLUMN)) {
            Log.e(RcsProvider.TAG,
                    "RcsProviderThreadHelper: inserting threads with IDs is not supported");
            return returnValue;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        try {
            db.beginTransaction();

            // Insert into the common rcs_threads table
            long rowId = insertIntoCommonRcsThreads(db);

            // Add the rowId in rcs_threads table as a foreign key in rcs_1_to_1_table
            contentValues.put(RCS_THREAD_ID_COLUMN, rowId);
            db.insert(RCS_1_TO_1_THREAD_TABLE, RCS_THREAD_ID_COLUMN, contentValues);
            contentValues.remove(RCS_THREAD_ID_COLUMN);

            db.setTransactionSuccessful();
            returnValue = rowId;
        } finally {
            db.endTransaction();
        }
        return returnValue;
    }

    long insertGroupThread(ContentValues contentValues) {
        long returnValue = TRANSACTION_FAILED;
        if (contentValues.containsKey(RCS_THREAD_ID_COLUMN)) {
            Log.e(RcsProvider.TAG,
                    "RcsProviderThreadHelper: inserting threads with IDs is not supported");
            return returnValue;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        try {
            db.beginTransaction();

            // Insert into the common rcs_threads table
            long rowId = insertIntoCommonRcsThreads(db);
            if (rowId == INSERTION_FAILED) {
                return returnValue;
            }

            // Add the rowId in rcs_threads table as a foreign key in rcs_group_table
            contentValues.put(RCS_THREAD_ID_COLUMN, rowId);
            db.insert(RCS_GROUP_THREAD_TABLE, RCS_THREAD_ID_COLUMN, contentValues);
            contentValues.remove(RCS_THREAD_ID_COLUMN);

            db.setTransactionSuccessful();
            returnValue = rowId;
        } finally {
            db.endTransaction();
        }
        return returnValue;
    }

    private long insertIntoCommonRcsThreads(SQLiteDatabase db) {
        return db.insert(RCS_THREAD_TABLE, RCS_THREAD_ID_COLUMN, new ContentValues());
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
        return RCS_THREAD_ID_COLUMN + "=" + getThreadIdFromUri(uri);
    }

    static String getThreadIdFromUri(Uri uri) {
        return uri.getPathSegments().get(THREAD_ID_INDEX_IN_URI);
    }
}
