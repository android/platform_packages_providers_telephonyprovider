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

import static com.android.providers.telephony.RcsProvider.GROUP_THREAD_URI_PREFIX;
import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProvider.TRANSACTION_FAILED;
import static com.android.providers.telephony.RcsProviderThreadHelper.RCS_THREAD_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.THREAD_TABLE;
import static com.android.providers.telephony.RcsProviderThreadHelper.getThreadIdFromUri;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to participants for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderParticipantHelper {
    static final String ID_COLUMN = "_id";
    static final String PARTICIPANT_TABLE = "rcs_participant";
    static final String CANONICAL_ADDRESS_ID_COLUMN = "canonical_address_id";
    static final String RCS_ALIAS_COLUMN = "rcs_alias";

    static final String PARTICIPANT_THREAD_JUNCTION_TABLE = "rcs_thread_participant";
    static final String RCS_PARTICIPANT_ID_COLUMN = "rcs_participant_id";

    private static final int PARTICIPANT_ID_INDEX_IN_URI = 1;
    private static final int PARTICIPANT_ID_INDEX_IN_THREAD_URI = 3;

    @VisibleForTesting
    public static void createParticipantTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating participant tables");

        db.execSQL("CREATE TABLE " + PARTICIPANT_TABLE + " (" +
                ID_COLUMN + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                CANONICAL_ADDRESS_ID_COLUMN + " INTEGER ," +
                RCS_ALIAS_COLUMN + " TEXT, " +
                "FOREIGN KEY(" + CANONICAL_ADDRESS_ID_COLUMN + ") "
                + "REFERENCES canonical_addresses(address)" +
                ");");

        db.execSQL("CREATE TABLE " + PARTICIPANT_THREAD_JUNCTION_TABLE + " (" +
                RCS_THREAD_ID_COLUMN + " INTEGER, " +
                RCS_PARTICIPANT_ID_COLUMN + " INTEGER, " +
                "CONSTRAINT thread_participant PRIMARY KEY("
                + RCS_THREAD_ID_COLUMN + ", " + RCS_PARTICIPANT_ID_COLUMN + "), " +
                "FOREIGN KEY(" + RCS_THREAD_ID_COLUMN
                + ") REFERENCES " + THREAD_TABLE + "(" + ID_COLUMN + "), " +
                "FOREIGN KEY(" + RCS_PARTICIPANT_ID_COLUMN
                + ") REFERENCES " + PARTICIPANT_TABLE + "(" + ID_COLUMN + "))");

        // TODO - create indexes for faster querying
    }

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    RcsProviderParticipantHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    Cursor queryParticipant(String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(PARTICIPANT_TABLE, projection, selection, selectionArgs, null, null,
                sortOrder);
    }

    Cursor queryParticipantWithId(Uri uri, String[] projection) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(PARTICIPANT_TABLE, projection, getParticipantIdSelection(uri), null, null,
                null,
                null);
    }

    Cursor queryParticipantIn1To1Thread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        return db.rawQuery(
                "  SELECT * "
                        + "FROM rcs_participant "
                        + "WHERE rcs_participant._id = ("
                        + "  SELECT rcs_thread_participant.rcs_participant_id "
                        + "  FROM rcs_thread_participant "
                        + "  WHERE rcs_thread_participant.rcs_thread_id=" + threadId + ")", null);
    }

    Cursor queryParticipantsInGroupThread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        return db.rawQuery("  SELECT * "
                + "FROM rcs_participant "
                + "WHERE rcs_participant._id = ("
                + "  SELECT rcs_participant_id "
                + "  FROM rcs_thread_participant "
                + "  WHERE rcs_thread_id= " + threadId + ")", null);
    }

    Cursor queryParticipantInGroupThreadWithId(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        return db.rawQuery("  SELECT * "
                        + "FROM rcs_participant "
                        + "WHERE rcs_participant._id = ("
                        + "  SELECT rcs_participant_id "
                        + "  FROM rcs_thread_participant "
                        + "  WHERE rcs_thread_id=? AND rcs_participant_id=?)",
                new String[]{threadId, participantId});
    }

    long insertParticipant(ContentValues contentValues) {
        if (!contentValues.containsKey(CANONICAL_ADDRESS_ID_COLUMN) || TextUtils.isEmpty(
                contentValues.getAsString(CANONICAL_ADDRESS_ID_COLUMN))) {
            Log.e(TAG,
                    "RcsProviderParticipantHelper: Inserting participants without canonical "
                            + "address is not supported");
            return TRANSACTION_FAILED;
        }
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.insert(PARTICIPANT_TABLE, RCS_PARTICIPANT_ID_COLUMN, contentValues);
    }

    long insertParticipantIntoP2pThread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        Cursor cursor = db.query(PARTICIPANT_THREAD_JUNCTION_TABLE, null,
                "rcs_thread_id=? AND rcs_participant_id=?", new String[]{threadId, participantId},
                null, null, null);

        int existingEntryCount = 0;
        if (cursor != null) {
            existingEntryCount = cursor.getCount();
            cursor.close();
        }

        // if this 1 to 1 thread already has a participant, fail the transaction.
        if (existingEntryCount > 0) {
            return TRANSACTION_FAILED;
        }

        ContentValues contentValues = new ContentValues(2);
        contentValues.put(RCS_THREAD_ID_COLUMN, threadId);
        contentValues.put(RCS_PARTICIPANT_ID_COLUMN, participantId);
        long rowId = db.insert(PARTICIPANT_THREAD_JUNCTION_TABLE, RCS_PARTICIPANT_ID_COLUMN,
                contentValues);

        if (rowId <= 0) {
            return TRANSACTION_FAILED;
        }
        return Long.parseLong(participantId);
    }

    /**
     * Inserts a participant into group thread. This function returns the participant ID instead of
     * the row id in the junction table
     */
    long insertParticipantIntoGroupThread(ContentValues values) {
        if (!values.containsKey(RCS_THREAD_ID_COLUMN) || !values.containsKey(
                RCS_PARTICIPANT_ID_COLUMN)) {
            Log.e(TAG, "RcsProviderParticipantHelper: Cannot insert participant into group.");
            return TRANSACTION_FAILED;
        }
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        long insertedRowId = db.insert(PARTICIPANT_THREAD_JUNCTION_TABLE, RCS_PARTICIPANT_ID_COLUMN,
                values);

        if (insertedRowId <= 0) {
            return TRANSACTION_FAILED;
        }

        return values.getAsLong(RCS_PARTICIPANT_ID_COLUMN);
    }

    /**
     * Inserts a participant into group thread. This function returns the participant ID instead of
     * the row id in the junction table
     */
    long insertParticipantIntoGroupThreadWithId(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        ContentValues contentValues = new ContentValues(2);
        contentValues.put(RCS_THREAD_ID_COLUMN, threadId);
        contentValues.put(RCS_PARTICIPANT_ID_COLUMN, participantId);

        long insertedRowId = db.insert(
                PARTICIPANT_THREAD_JUNCTION_TABLE, RCS_PARTICIPANT_ID_COLUMN, contentValues);

        if (insertedRowId <= 0) {
            return TRANSACTION_FAILED;
        }

        return Long.parseLong(participantId);
    }

    int deleteParticipantWithId(Uri uri) {
        String participantId = uri.getPathSegments().get(PARTICIPANT_ID_INDEX_IN_URI);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        // See if this participant is involved in any threads
        Cursor cursor = db.query(PARTICIPANT_THREAD_JUNCTION_TABLE, null,
                "rcs_participant_id=" + participantId, null, null, null, null);

        int participatingThreadCount = 0;
        if (cursor != null) {
            participatingThreadCount = cursor.getCount();
            cursor.close();
        }

        if (participatingThreadCount > 0) {
            Log.e(TAG,
                    "RcsProviderParticipantHelper: Can't delete participant while it is still in "
                            + "RCS threads, uri:"
                            + uri);
            return 0;
        }

        return db.delete(PARTICIPANT_TABLE, "_id=" + participantId, null);
    }

    int deleteParticipantFromGroupThread(Uri uri) {
        String threadId = getThreadIdFromUri(uri);
        String participantId = getParticipantIdFromUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(PARTICIPANT_THREAD_JUNCTION_TABLE,
                "rcs_thread_id=? AND rcs_participant_id=?",
                new String[]{threadId, participantId});
    }

    int updateParticipant(ContentValues contentValues, String selection, String[] selectionArgs) {
        if (contentValues.containsKey(ID_COLUMN)) {
            Log.e(TAG, "RcsProviderParticipantHelper: Updating participant id is not supported");
            return 0;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(PARTICIPANT_TABLE, contentValues, selection, selectionArgs);
    }

    int updateParticipantWithId(ContentValues contentValues, Uri uri) {
        return updateParticipant(contentValues, getParticipantIdSelection(uri), null);
    }

    private String getParticipantIdSelection(Uri uri) {
        return "_id=" + uri.getPathSegments().get(PARTICIPANT_ID_INDEX_IN_URI);
    }

    Uri getParticipantInThreadUri(ContentValues values, long rowId) {
        if (values == null) {
            return null;
        }
        Integer threadId = values.getAsInteger(RCS_THREAD_ID_COLUMN);
        if (threadId == null) {
            return null;
        }

        return Uri.parse(GROUP_THREAD_URI_PREFIX + "/" + threadId + "/participant/" + rowId);
    }

    private String getParticipantIdFromUri(Uri uri) {
        return uri.getPathSegments().get(PARTICIPANT_ID_INDEX_IN_THREAD_URI);
    }
}
