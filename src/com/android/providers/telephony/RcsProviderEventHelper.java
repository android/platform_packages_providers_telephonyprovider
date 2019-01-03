/*
 * Copyright (C) 2019 The Android Open Source Project
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
import static com.android.providers.telephony.RcsProviderParticipantHelper.PARTICIPANT_TABLE;
import static com.android.providers.telephony.RcsProviderParticipantHelper.RCS_PARTICIPANT_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.RCS_THREAD_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.THREAD_TABLE;
import static com.android.providers.telephony.RcsProviderThreadHelper.getThreadIdFromUri;

import android.content.ContentValues;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to events for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
class RcsProviderEventHelper {
    static final String THREAD_EVENT_TABLE = "rcs_thread_event";
    static final String EVENT_ID = "event_id";
    static final String EVENT_TYPE = "event_type";
    static final String TIMESTAMP = "timestamp";
    static final String ORIGINATING_PARTICIPANT = "originating_participant";
    static final String DESTINATION_PARTICIPANT = "destination_participant";
    static final String OLD_ICON_URI = "old_icon_uri";
    static final String NEW_ICON_URI = "new_icon_uri";
    static final String OLD_NAME = "old_name";
    static final String NEW_NAME = "new_name";

    static final String PARTICIPANT_EVENT_TABLE = "rcs_participant_event";
    static final String OLD_ALIAS = "old_alias";
    static final String NEW_ALIAS = "new_alias";

    private static final int PARTICIPANT_JOINED_EVENT_TYPE = 2;
    private static final int PARTICIPANT_LEFT_EVENT_TYPE = 4;
    private static final int ICON_CHANGED_EVENT_TYPE = 8;
    private static final int NAME_CHANGED_EVENT_TYPE = 16;

    private static final int PARTICIPANT_INDEX_IN_EVENT_URI = 1;
    private static final int EVENT_INDEX_IN_EVENT_URI = 3;

    @VisibleForTesting
    public static void createRcsEventTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating event tables");

        // Add the event tables
        db.execSQL("CREATE TABLE " + THREAD_EVENT_TABLE + "(" + EVENT_ID
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + RCS_THREAD_ID_COLUMN + " INTEGER, "
                + ORIGINATING_PARTICIPANT + " INTEGER, " + EVENT_TYPE + " INTEGER, " + TIMESTAMP
                + " INTEGER, " + DESTINATION_PARTICIPANT + " INTEGER, " + OLD_ICON_URI + " TEXT, "
                + NEW_ICON_URI + " TEXT, " + OLD_NAME + " TEXT, " + NEW_NAME + " TEXT, "
                + " FOREIGN KEY (" + RCS_THREAD_ID_COLUMN + ") REFERENCES " + THREAD_TABLE + " ("
                + RCS_THREAD_ID_COLUMN + "), FOREIGN KEY (" + ORIGINATING_PARTICIPANT
                + ") REFERENCES " + PARTICIPANT_TABLE + " (" + RCS_PARTICIPANT_ID_COLUMN + "))");

        db.execSQL("CREATE TABLE " + PARTICIPANT_EVENT_TABLE + "(" + EVENT_ID
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + RCS_PARTICIPANT_ID_COLUMN + " INTEGER, "
                + TIMESTAMP + " INTEGER, " + OLD_ALIAS + " TEXT, " + NEW_ALIAS + " TEXT,"
                + " FOREIGN KEY (" + RCS_PARTICIPANT_ID_COLUMN + ") REFERENCES " + PARTICIPANT_TABLE
                + " (" + RCS_PARTICIPANT_ID_COLUMN + "))");
    }

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    RcsProviderEventHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    long insertParticipantEvent(Uri uri, ContentValues values) {
        String participantId = getParticipantIdFromUri(uri);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        values.put(RCS_PARTICIPANT_ID_COLUMN, participantId);
        long rowId = db.insert(PARTICIPANT_EVENT_TABLE, RCS_PARTICIPANT_ID_COLUMN, values);
        values.remove(RCS_PARTICIPANT_ID_COLUMN);

        return rowId;
    }

    long insertParticipantJoinedEvent(Uri uri, ContentValues values) {
        return insertParticipantChangedEvent(uri, values, PARTICIPANT_JOINED_EVENT_TYPE);
    }

    long insertParticipantLeftEvent(Uri uri, ContentValues values) {
        return insertParticipantChangedEvent(uri, values, PARTICIPANT_LEFT_EVENT_TYPE);
    }

    long insertThreadNameChangeEvent(Uri uri, ContentValues values) {
        return insertParticipantChangedEvent(uri, values, NAME_CHANGED_EVENT_TYPE);
    }

    long insertThreadIconChangeEvent(Uri uri, ContentValues values) {
        return insertParticipantChangedEvent(uri, values, ICON_CHANGED_EVENT_TYPE);
    }

    private long insertParticipantChangedEvent(Uri uri, ContentValues values, int eventType) {
        String threadId = getThreadIdFromUri(uri);

        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        values.put(EVENT_TYPE, eventType);
        values.put(RCS_THREAD_ID_COLUMN, threadId);
        long rowId = db.insert(THREAD_EVENT_TABLE, EVENT_ID, values);
        values.remove(EVENT_TYPE);
        values.remove(RCS_THREAD_ID_COLUMN);

        return rowId;
    }

    int deleteParticipantEvent(Uri uri) {
        String eventId = getEventIdFromEventUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        return db.delete(PARTICIPANT_EVENT_TABLE, "event_id=" + eventId, null);
    }

    int deleteGroupThreadEvent(Uri uri) {
        String eventId = getEventIdFromEventUri(uri);
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        return db.delete(THREAD_EVENT_TABLE, "event_id=" + eventId, null);
    }

    private String getEventIdFromEventUri(Uri uri) {
        return uri.getPathSegments().get(EVENT_INDEX_IN_EVENT_URI);
    }

    private String getParticipantIdFromUri(Uri uri) {
        return uri.getPathSegments().get(PARTICIPANT_INDEX_IN_EVENT_URI);
    }
}
