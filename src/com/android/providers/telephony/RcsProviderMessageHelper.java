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

import static com.android.providers.telephony.RcsProvider.AUTHORITY;
import static com.android.providers.telephony.RcsProvider.TAG;
import static com.android.providers.telephony.RcsProviderParticipantHelper.ID_COLUMN;
import static com.android.providers.telephony.RcsProviderParticipantHelper.PARTICIPANT_TABLE;
import static com.android.providers.telephony.RcsProviderParticipantHelper.RCS_PARTICIPANT_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.RCS_THREAD_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.THREAD_TABLE;
import static com.android.providers.telephony.RcsProviderThreadHelper.getThreadIdFromUri;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Constants and helpers related to messages for {@link RcsProvider} to keep the code clean.
 *
 * @hide
 */
public class RcsProviderMessageHelper {
    static final String MESSAGE_TABLE = "rcs_message";
    static final String MESSAGE_ID_COLUMN = "rcs_message_row_id";
    static final String GLOBAL_ID_COLUMN = "rcs_message_global_id";
    static final String SUB_ID_COLUMN = "sub_id";
    static final String STATUS_COLUMN = "status";
    static final String ORIGINATION_TIMESTAMP_COLUMN = "origination_timestamp";

    static final String INCOMING_MESSAGE_TABLE = "rcs_incoming_message";
    static final String SENDER_PARTICIPANT_COLUMN = "sender_participant";
    static final String ARRIVAL_TIMESTAMP = "arrival_timestamp";
    static final String NOTIFIED_TIMESTAMP = "notified_timestamp";

    static final String OUTGOING_MESSAGE_TABLE = "rcs_outgoing_message";

    static final String MESSAGE_DELIVERY_TABLE = "rcs_message_delivery";
    static final String DELIVERED_TIMESTAMP = "delivered_timestamp";
    static final String SEEN_TIMESTAMP = "seen_timestamp";

    static final String UNIFIED_MESSAGE_VIEW = "unified_message_view";
    static final String UNIFIED_INCOMING_MESSAGE_VIEW = "unified_incoming_message_view";
    static final String UNIFIED_OUTGOING_MESSAGE_VIEW = "unified_outgoing_message_view";

    private static final int MESSAGE_ID_INDEX_IN_URI = 1;
    private static final int MESSAGE_ID_INDEX_IN_THREAD_URI = 3;

    private final SQLiteOpenHelper mSqLiteOpenHelper;

    @VisibleForTesting
    public static void createRcsMessageTables(SQLiteDatabase db) {
        Log.d(TAG, "Creating message tables");

        // Add the message tables
        db.execSQL("CREATE TABLE " + MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER PRIMARY KEY AUTOINCREMENT, " + RCS_THREAD_ID_COLUMN + " INTEGER, "
                + GLOBAL_ID_COLUMN + " TEXT, " + SUB_ID_COLUMN + " INTEGER, " + STATUS_COLUMN
                + " INTEGER, " + ORIGINATION_TIMESTAMP_COLUMN + " INTEGER, FOREIGN KEY("
                + RCS_THREAD_ID_COLUMN + ") REFERENCES " + THREAD_TABLE + "(" + ID_COLUMN + "))");

        db.execSQL("CREATE TABLE " + INCOMING_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER PRIMARY KEY, " + SENDER_PARTICIPANT_COLUMN + " INTEGER, "
                + ARRIVAL_TIMESTAMP + " INTEGER, " + NOTIFIED_TIMESTAMP + " INTEGER, FOREIGN KEY ("
                + MESSAGE_ID_COLUMN + ") REFERENCES " + MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + "))");

        db.execSQL("CREATE TABLE " + OUTGOING_MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER PRIMARY KEY, FOREIGN KEY (" + MESSAGE_ID_COLUMN + ") REFERENCES "
                + MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN + "))");

        db.execSQL("CREATE TABLE " + MESSAGE_DELIVERY_TABLE + "(" + MESSAGE_ID_COLUMN
                + " INTEGER, " + RCS_PARTICIPANT_ID_COLUMN + " INTEGER, " + DELIVERED_TIMESTAMP
                + " INTEGER, " + SEEN_TIMESTAMP + " INTEGER, "
                + "CONSTRAINT message_delivery PRIMARY KEY (" + MESSAGE_ID_COLUMN + ", "
                + RCS_PARTICIPANT_ID_COLUMN + "), FOREIGN KEY (" + MESSAGE_ID_COLUMN
                + ") REFERENCES " + MESSAGE_TABLE + "(" + MESSAGE_ID_COLUMN + "), FOREIGN KEY ("
                + RCS_PARTICIPANT_ID_COLUMN + ") REFERENCES " + PARTICIPANT_TABLE + "(" + ID_COLUMN
                + "))");

        // Add the views
        //
        // The following view inner joins incoming messages with all messages, inner joins outgoing
        // messages with all messages, and unions them together, while also adding an is_incoming
        // column for easily telling where the record came from. This may have been achieved with
        // an outer join but SQLite doesn't support them.
        //
        // CREATE VIEW unified_message_view AS
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp,
        //        0 AS sender_participant,
        //        0 AS arrival_timestamp,
        //        0 AS notification_timestamp,
        //        0 AS is_incoming
        //
        // FROM rcs_message INNER JOIN rcs_outgoing_message
        //          ON rcs_message.rcs_message_row_id=rcs_outgoing_message.rcs_message_row_id
        //
        // UNION
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp,
        //        rcs_incoming_message.sender_participant,
        //        rcs_incoming_message.arrival_timestamp,
        //        rcs_incoming_message.notification_timestamp,
        //        1 AS is_incoming
        //
        // FROM rcs_message INNER JOIN rcs_incoming_message
        //          ON rcs_message.rcs_message_row_id=rcs_incoming_message.rcs_message_row_id
        //
        db.execSQL(
                "CREATE VIEW unified_message_view AS SELECT rcs_message.rcs_message_row_id, "
                        + "rcs_message.rcs_thread_id, rcs_message.rcs_message_global_id, "
                        + "rcs_message.sub_id, rcs_message.status, rcs_message"
                        + ".origination_timestamp, 0 AS sender_participant, 0 AS "
                        + "arrival_timestamp, 0 AS notified_timestamp, 0 AS is_incoming FROM "
                        + "rcs_message INNER JOIN rcs_outgoing_message ON rcs_message."
                        + "rcs_message_row_id=rcs_outgoing_message.rcs_message_row_id UNION SELECT "
                        + "rcs_message.rcs_message_row_id, rcs_message.rcs_thread_id, rcs_message"
                        + ".rcs_message_global_id, rcs_message.sub_id, rcs_message.status, "
                        + "rcs_message.origination_timestamp, rcs_incoming_message"
                        + ".sender_participant, rcs_incoming_message.arrival_timestamp, "
                        + "rcs_incoming_message.notified_timestamp, 1 AS is_incoming FROM "
                        + "rcs_message INNER JOIN rcs_incoming_message ON rcs_message."
                        + "rcs_message_row_id=rcs_incoming_message.rcs_message_row_id");

        // The following view inner joins incoming messages with all messages
        //
        // CREATE VIEW unified_incoming_message_view AS
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp,
        //        rcs_incoming_message.sender_participant,
        //        rcs_incoming_message.arrival_timestamp,
        //        rcs_incoming_message.notification_timestamp,
        //
        // FROM rcs_message INNER JOIN rcs_incoming_message
        //          ON rcs_message.rcs_message_row_id=rcs_incoming_message.rcs_message_row_id

        db.execSQL(
                "CREATE VIEW unified_incoming_message_view AS SELECT rcs_message"
                        + ".rcs_message_row_id, rcs_message.rcs_thread_id, rcs_message"
                        + ".rcs_message_global_id, rcs_message.sub_id, rcs_message.status, "
                        + "rcs_message.origination_timestamp, rcs_incoming_message"
                        + ".sender_participant, rcs_incoming_message.arrival_timestamp, "
                        + "rcs_incoming_message.notified_timestamp FROM "
                        + "rcs_message INNER JOIN rcs_incoming_message ON rcs_message."
                        + "rcs_message_row_id=rcs_incoming_message.rcs_message_row_id");

        // The following view inner joins incoming messages with all messages.
        //
        // CREATE VIEW unified_outgoing_message AS
        //
        // SELECT rcs_message.rcs_message_row_id,
        //        rcs_message.rcs_thread_id,
        //        rcs_message.rcs_message_global_id,
        //        rcs_message.sub_id,
        //        rcs_message.status,
        //        rcs_message.origination_timestamp
        //
        // FROM rcs_message INNER JOIN rcs_outgoing_message
        //          ON rcs_message.rcs_message_row_id=rcs_outgoing_message.rcs_message_row_id

        db.execSQL(
                "CREATE VIEW unified_outgoing_message_view AS SELECT rcs_message"
                        + ".rcs_message_row_id, rcs_message.rcs_thread_id, rcs_message"
                        + ".rcs_message_global_id, rcs_message.sub_id, rcs_message.status, "
                        + "rcs_message.origination_timestamp FROM rcs_message INNER JOIN "
                        + "rcs_outgoing_message ON rcs_message"
                        + ".rcs_message_row_id=rcs_outgoing_message.rcs_message_row_id");


        // Add triggers

        // Delete the corresponding rcs_message row upon deleting a row in rcs_incoming_message
        //
        // CREATE TRIGGER delete_common_message_after_incoming
        //  AFTER DELETE ON rcs_incoming_message
        // BEGIN
        //  DELETE FROM rcs_message WHERE rcs_message.rcs_message_row_id=OLD.rcs_message_row_id;
        // END
        db.execSQL(
                "CREATE TRIGGER delete_common_message_after_incoming AFTER DELETE ON "
                        + "rcs_incoming_message BEGIN DELETE FROM rcs_message WHERE rcs_message"
                        + ".rcs_message_row_id=OLD.rcs_message_row_id; END");

        // Delete the corresponding rcs_message row upon deleting a row in rcs_outgoing_message
        //
        // CREATE TRIGGER delete_common_message_after_outgoing
        //  AFTER DELETE ON rcs_outgoing_message
        // BEGIN
        //  DELETE FROM rcs_message WHERE rcs_message.rcs_message_row_id=OLD.rcs_message_row_id;
        // END
        db.execSQL(
                "CREATE TRIGGER delete_common_message_after_outgoing AFTER DELETE ON "
                        + "rcs_outgoing_message BEGIN DELETE FROM rcs_message WHERE rcs_message"
                        + ".rcs_message_row_id=OLD.rcs_message_row_id; END");
    }

    RcsProviderMessageHelper(SQLiteOpenHelper sqLiteOpenHelper) {
        mSqLiteOpenHelper = sqLiteOpenHelper;
    }

    Cursor queryUnifiedMessageWithId(Uri uri) {
        return queryUnifiedMessageWithSelection(getMessageIdSelection(uri), null);
    }

    Cursor queryUnifiedMessageWithIdInThread(Uri uri) {
        return queryUnifiedMessageWithSelection(getMessageIdSelectionInThreadUri(uri), null);
    }

    Cursor queryUnifiedMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_MESSAGE_VIEW, null, selection, selectionArgs, null, null, null,
                null);
    }

    Cursor queryIncomingMessageWithId(Uri uri) {
        return queryIncomingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    Cursor queryIncomingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_INCOMING_MESSAGE_VIEW, null, selection, selectionArgs, null, null,
                null, null);
    }

    Cursor queryOutgoingMessageWithId(Uri uri) {
        return queryOutgoingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    Cursor queryOutgoingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();
        return db.query(UNIFIED_OUTGOING_MESSAGE_VIEW, null, selection, selectionArgs, null, null,
                null, null);
    }

    Cursor queryAllMessagesOnThread(Uri uri, String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getReadableDatabase();

        String appendedSelection = appendThreadIdToSelection(uri, selection);
        return db.query(UNIFIED_MESSAGE_VIEW, null, appendedSelection, null, null, null, null);
    }

    Uri insertMessageOnThread(Uri uri, ContentValues values, boolean isIncoming, boolean is1To1) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        String threadId = RcsProviderThreadHelper.getThreadIdFromUri(uri);
        values.put(RCS_THREAD_ID_COLUMN, Integer.parseInt(threadId));

        db.beginTransaction();

        ContentValues subMessageTableValues = new ContentValues();
        if (isIncoming) {
            subMessageTableValues = getIncomingMessageValues(values);
        }

        long rowId;
        try {
            rowId = db.insert(MESSAGE_TABLE, MESSAGE_ID_COLUMN, values);
            if (rowId <= 0) {
                return null;
            }

            subMessageTableValues.put(MESSAGE_ID_COLUMN, rowId);
            long tempId = db.insert(isIncoming ? INCOMING_MESSAGE_TABLE : OUTGOING_MESSAGE_TABLE,
                    MESSAGE_ID_COLUMN, subMessageTableValues);
            subMessageTableValues.remove(MESSAGE_ID_COLUMN);
            if (tempId <= 0) {
                return null;
            }

            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        return Uri.parse(
                "content://" + AUTHORITY + "/" + (is1To1 ? "p2p_thread" : "group_thread") + "/"
                        + threadId + "/" + (isIncoming ? "incoming_message" : "outgoing_message")
                        + "/" + rowId);
    }

    int deleteIncomingMessageWithId(Uri uri) {
        return deleteIncomingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    int deleteIncomingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(INCOMING_MESSAGE_TABLE, selection, selectionArgs);
    }

    int deleteOutgoingMessageWithId(Uri uri) {
        return deleteOutgoingMessageWithSelection(getMessageIdSelection(uri), null);
    }

    int deleteOutgoingMessageWithSelection(String selection, String[] selectionArgs) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.delete(OUTGOING_MESSAGE_TABLE, selection, selectionArgs);
    }

    int updateIncomingMessage(Uri uri, ContentValues values) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();

        ContentValues incomingMessageValues = getIncomingMessageValues(values);

        int updateCountInIncoming = 0;
        int updateCountInCommon = 0;
        db.beginTransaction();
        if (!incomingMessageValues.isEmpty()) {
            updateCountInIncoming = db.update(INCOMING_MESSAGE_TABLE, incomingMessageValues,
                    getMessageIdSelection(uri), null);
        }
        if (!values.isEmpty()) {
            updateCountInCommon = db.update(MESSAGE_TABLE, values, getMessageIdSelection(uri), null);
        }
        db.setTransactionSuccessful();
        db.endTransaction();

        return Math.max(updateCountInIncoming, updateCountInCommon);
    }

    int updateOutgoingMessage(Uri uri, ContentValues values) {
        SQLiteDatabase db = mSqLiteOpenHelper.getWritableDatabase();
        return db.update(MESSAGE_TABLE, values, getMessageIdSelection(uri), null);
    }

    /**
     * Removes the incoming message values out of all values and returns as a separate content
     * values object.
     */
    private ContentValues getIncomingMessageValues(ContentValues allValues) {
        ContentValues incomingMessageValues = new ContentValues();

        if (allValues.containsKey(SENDER_PARTICIPANT_COLUMN)) {
            incomingMessageValues.put(SENDER_PARTICIPANT_COLUMN,
                    allValues.getAsInteger(SENDER_PARTICIPANT_COLUMN));
            allValues.remove(SENDER_PARTICIPANT_COLUMN);
        }

        if (allValues.containsKey(ARRIVAL_TIMESTAMP)) {
            incomingMessageValues.put(ARRIVAL_TIMESTAMP, allValues.getAsLong(ARRIVAL_TIMESTAMP));
            allValues.remove(ARRIVAL_TIMESTAMP);
        }

        if (allValues.containsKey(NOTIFIED_TIMESTAMP)) {
            incomingMessageValues.put(NOTIFIED_TIMESTAMP, allValues.getAsLong(NOTIFIED_TIMESTAMP));
            allValues.remove(NOTIFIED_TIMESTAMP);
        }

        return incomingMessageValues;
    }

    private String appendThreadIdToSelection(Uri uri, String selection) {
        String threadIdSelection = "rcs_thread_id=" + getThreadIdFromUri(uri);

        if (TextUtils.isEmpty(selection)) {
            return threadIdSelection;
        }

        return "(" + selection + ") AND " + threadIdSelection;
    }

    private String getMessageIdSelection(Uri uri) {
        return "rcs_message_row_id=" + getMessageIdFromUri(uri);
    }

    private String getMessageIdSelectionInThreadUri(Uri uri) {
        return "rcs_message_row_id=" + getMessageIdFromThreadUri(uri);
    }

    private String getMessageIdFromUri(Uri uri) {
        return uri.getPathSegments().get(MESSAGE_ID_INDEX_IN_URI);
    }

    private String getMessageIdFromThreadUri(Uri uri) {
        return uri.getPathSegments().get(MESSAGE_ID_INDEX_IN_THREAD_URI);
    }
}
