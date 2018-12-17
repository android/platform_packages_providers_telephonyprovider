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
import static com.android.providers.telephony.RcsProviderThreadHelper.RCS_THREAD_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.THREAD_TABLE;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.BaseColumns;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to participants for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
public class RcsProviderParticipantHelper {
    static final String ID_COLUMN = "_id";
    static final String PARTICIPANT_TABLE = "rcs_participant";
    static final String CANONICAL_ADDRESS_ID_COLUMN = "canonical_address_id";
    static final String RCS_ALIAS_COLUMN = "rcs_alias";

    static final String PARTICIPANT_THREAD_JUNCTION_TABLE = "rcs_thread_participant";
    static final String RCS_PARTICIPANT_ID_COLUMN = "rcs_participant_id";

    private static final int PARTICIPANT_ID_INDEX_IN_URI = 1;

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

    Cursor queryParticipantWithId(Uri uriWithId, String[] projection) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(PARTICIPANT_TABLE, projection, getParticipantIdSelection(uriWithId), null,
                null, null, null);
    }

    long insertParticipant(ContentValues contentValues) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        if (!contentValues.containsKey(CANONICAL_ADDRESS_ID_COLUMN) || TextUtils.isEmpty(
                contentValues.getAsString(CANONICAL_ADDRESS_ID_COLUMN))) {
            Log.e(TAG,
                    "RcsProviderParticipantHelper: Inserting participants without canonical "
                            + "address is not supported");
            return Long.MIN_VALUE;
        }
        return db.insert(PARTICIPANT_TABLE, RCS_PARTICIPANT_ID_COLUMN, contentValues);
    }

    int deleteParticipant(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        // TODO - delete entries from junction table
        return db.delete(PARTICIPANT_TABLE, selection, selectionArgs);
    }

    int deleteParticipantWithId(Uri uri) {
        return deleteParticipant(getParticipantIdSelection(uri), null);
    }

    int updateParticipant(ContentValues contentValues, String selection, String[] selectionArgs) {
        if (contentValues.containsKey(ID_COLUMN)) {
            Log.e(TAG, "RcsProviderParticipantHelper: Updating participant id is not supported");
            return 0;
        }

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(PARTICIPANT_TABLE, contentValues, selection, selectionArgs);
    }

    int updateParticipantWithId(ContentValues contentValues, Uri uriWithId) {
        return updateParticipant(contentValues, getParticipantIdSelection(uriWithId), null);
    }

    private String getParticipantIdSelection(Uri uri) {
        return BaseColumns._ID + "=" + uri.getPathSegments().get(PARTICIPANT_ID_INDEX_IN_URI);
    }
}
