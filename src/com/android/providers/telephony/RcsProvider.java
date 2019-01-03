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

import android.app.AppOpsManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Content provider to handle RCS messages. The functionality here is similar to SmsProvider,
 * MmsProvider etc.
 *
 * The provider has constraints around inserting, updating and deleting - the user needs to know
 * whether they are inserting a message that is incoming/outgoing, or the thread they are inserting
 * is a group or p2p etc. This is in order to keep the implementation simple and avoid complex
 * queries.
 *
 * @hide
 */
public class RcsProvider extends ContentProvider {
    static final String TAG = "RcsProvider";
    static final String AUTHORITY = "rcs";
    private static final String CONTENT_AUTHORITY = "content://" + AUTHORITY;
    static final long TRANSACTION_FAILED = Long.MIN_VALUE;

    private static final String THREAD_URI_PREFIX = CONTENT_AUTHORITY + "/thread/";
    private static final String PARTICIPANT_URI_PREFIX = CONTENT_AUTHORITY + "/participant/";
    private static final String P2P_THREAD_URI_PREFIX = CONTENT_AUTHORITY + "/p2p_thread/";
    private static final String FILE_TRANSFER_PREFIX = CONTENT_AUTHORITY + "/file_transfer/";
    static final String GROUP_THREAD_URI_PREFIX =
            "content://" + AUTHORITY + "/group_thread/";

    private static final UriMatcher URL_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int UNIFIED_RCS_THREAD = 1;
    private static final int UNIFIED_RCS_THREAD_WITH_ID = 2;
    private static final int PARTICIPANT = 3;
    private static final int PARTICIPANT_WITH_ID = 4;
    private static final int PARTICIPANT_ALIAS_CHANGE_EVENT = 5;
    private static final int PARTICIPANT_ALIAS_CHANGE_EVENT_WITH_ID = 6;
    private static final int P2P_THREAD = 7;
    private static final int P2P_THREAD_WITH_ID = 8;
    private static final int P2P_THREAD_PARTICIPANT = 9;
    private static final int P2P_THREAD_PARTICIPANT_WITH_ID = 10;
    private static final int GROUP_THREAD = 11;
    private static final int GROUP_THREAD_WITH_ID = 12;
    private static final int GROUP_THREAD_PARTICIPANT = 13;
    private static final int GROUP_THREAD_PARTICIPANT_WITH_ID = 14;
    private static final int GROUP_THREAD_PARTICIPANT_JOINED_EVENT = 15;
    private static final int GROUP_THREAD_PARTICIPANT_JOINED_EVENT_WITH_ID = 16;
    private static final int GROUP_THREAD_PARTICIPANT_LEFT_EVENT = 17;
    private static final int GROUP_THREAD_PARTICIPANT_LEFT_EVENT_WITH_ID = 18;
    private static final int GROUP_THREAD_NAME_CHANGE_EVENT = 19;
    private static final int GROUP_THREAD_NAME_CHANGE_EVENT_WITH_ID = 20;
    private static final int GROUP_THREAD_ICON_CHANGE_EVENT = 21;
    private static final int GROUP_THREAD_ICON_CHANGE_EVENT_WITH_ID = 22;
    private static final int UNIFIED_MESSAGE = 23;
    private static final int UNIFIED_MESSAGE_WITH_ID = 24;
    private static final int UNIFIED_MESSAGE_WITH_FILE_TRANSFER = 25;
    private static final int INCOMING_MESSAGE = 26;
    private static final int INCOMING_MESSAGE_WITH_ID = 27;
    private static final int OUTGOING_MESSAGE = 28;
    private static final int OUTGOING_MESSAGE_WITH_ID = 29;
    private static final int OUTGOING_MESSAGE_DELIVERY = 30;
    private static final int OUTGOING_MESSAGE_DELIVERY_WITH_ID = 31;
    private static final int UNIFIED_MESSAGE_ON_THREAD = 32;
    private static final int UNIFIED_MESSAGE_ON_THREAD_WITH_ID = 33;
    private static final int INCOMING_MESSAGE_ON_P2P_THREAD = 34;
    private static final int INCOMING_MESSAGE_ON_P2P_THREAD_WITH_ID = 35;
    private static final int OUTGOING_MESSAGE_ON_P2P_THREAD = 36;
    private static final int OUTGOING_MESSAGE_ON_P2P_THREAD_WITH_ID = 37;
    private static final int INCOMING_MESSAGE_ON_GROUP_THREAD = 38;
    private static final int INCOMING_MESSAGE_ON_GROUP_THREAD_WITH_ID = 39;
    private static final int OUTGOING_MESSAGE_ON_GROUP_THREAD = 40;
    private static final int OUTGOING_MESSAGE_ON_GROUP_THREAD_WITH_ID = 41;
    private static final int FILE_TRANSFER_WITH_ID = 42;

    SQLiteOpenHelper mDbOpenHelper;

    @VisibleForTesting
    RcsProviderThreadHelper mThreadHelper;
    @VisibleForTesting
    RcsProviderParticipantHelper mParticipantHelper;
    @VisibleForTesting
    RcsProviderMessageHelper mMessageHelper;
    @VisibleForTesting
    RcsProviderEventHelper mEventHelper;

    static {
        // example query: content://rcs/thread?owner_participant=3
        URL_MATCHER.addURI(AUTHORITY, "thread", UNIFIED_RCS_THREAD);

        // example query: content://rcs/thread/4
        URL_MATCHER.addURI(AUTHORITY, "thread/#", UNIFIED_RCS_THREAD_WITH_ID);

        // example query: content://rcs/participant?alias="alice"
        URL_MATCHER.addURI(AUTHORITY, "participant", PARTICIPANT);

        // example query: content://rcs/participant/12
        URL_MATCHER.addURI(AUTHORITY, "participant/#", PARTICIPANT_WITH_ID);

        // example query: content://rcs/participant/12/alias_change_event
        URL_MATCHER.addURI(AUTHORITY, "participant/#/alias_change_event",
                PARTICIPANT_ALIAS_CHANGE_EVENT);

        // example query: content://rcs/participant/12/alias_change_event/4, only supports deletes
        URL_MATCHER.addURI(AUTHORITY, "participant/#/alias_change_event/#",
                PARTICIPANT_ALIAS_CHANGE_EVENT_WITH_ID);

        // example query: content://rcs/p2p_thread?rcs_fallback_thread_id=6
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread", P2P_THREAD);

        // example query: content://rcs/p2p_thread/4 , where 4 is the _id in rcs_threads table.
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#", P2P_THREAD_WITH_ID);

        // example query: content://rcs/p2p_thread/7/participant"
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/participant", P2P_THREAD_PARTICIPANT);

        // example query: content://rcs/p2p_thread/9/participant/3", only supports a 1 time insert
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/participant/#", P2P_THREAD_PARTICIPANT_WITH_ID);

        // example query: content://rcs/group_thread?group_name="best friends"
        URL_MATCHER.addURI(AUTHORITY, "group_thread", GROUP_THREAD);

        // example query: content://rcs/group_thread/13, where 13 is the _id in rcs_threads table.
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#", GROUP_THREAD_WITH_ID);

        // example query: content://rcs/group_thread/13/participant_joined_event. Supports
        // queries and inserts
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant_joined_event",
                GROUP_THREAD_PARTICIPANT_JOINED_EVENT);

        // example query: content://rcs/group_thread/13/participant_joined_event/3. Supports deletes
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant_joined_event/#",
                GROUP_THREAD_PARTICIPANT_JOINED_EVENT_WITH_ID);

        // example query: content://rcs/group_thread/13/participant_left_event. Supports queries
        // and inserts
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant_left_event",
                GROUP_THREAD_PARTICIPANT_LEFT_EVENT);

        // example query: content://rcs/group_thread/13/participant_left_event/5. Supports deletes
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant_left_event/#",
                GROUP_THREAD_PARTICIPANT_LEFT_EVENT_WITH_ID);

        // example query: content://rcs/group_thread/13/name_changed_event. Supports queries and
        // inserts
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/name_changed_event",
                GROUP_THREAD_NAME_CHANGE_EVENT);

        // example query: content://rcs/group_thread/13/name_changed_event/7. Supports deletes
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/name_changed_event/#",
                GROUP_THREAD_NAME_CHANGE_EVENT_WITH_ID);

        // example query: content://rcs/group_thread/13/icon_changed_event. Supports queries and
        // inserts
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/icon_changed_event",
                GROUP_THREAD_ICON_CHANGE_EVENT);

        // example query: content://rcs/group_thread/13/icon_changed_event/9. Supports deletes
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/icon_changed_event/#",
                GROUP_THREAD_ICON_CHANGE_EVENT_WITH_ID);

        // example query: content://rcs/group_thread/18/participant?alias="charlie"
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant",
                GROUP_THREAD_PARTICIPANT);

        // example query: content://rcs/group_thread/21/participant/4, only supports inserts and
        // deletes
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant/#",
                GROUP_THREAD_PARTICIPANT_WITH_ID);

        // example query: content://rcs/message?sub_id=5
        URL_MATCHER.addURI(AUTHORITY, "message", UNIFIED_MESSAGE);

        // example query: content://rcs/message/4
        URL_MATCHER.addURI(AUTHORITY, "message/#", UNIFIED_MESSAGE_WITH_ID);

        // example query: content://rcs/message/4/file_transfer, only supports inserts
        URL_MATCHER.addURI(AUTHORITY, "message/#/file_transfer",
                UNIFIED_MESSAGE_WITH_FILE_TRANSFER);

        // example query: content://rcs/incoming_message?sub_id=4
        URL_MATCHER.addURI(AUTHORITY, "incoming_message", INCOMING_MESSAGE);

        // example query: content://rcs/incoming_message/45
        URL_MATCHER.addURI(AUTHORITY, "incoming_message/#", INCOMING_MESSAGE_WITH_ID);

        // example query: content://rcs/outgoing_message?sub_id=9
        URL_MATCHER.addURI(AUTHORITY, "outgoing_message", OUTGOING_MESSAGE);

        // example query: content://rcs/outgoing_message/54
        URL_MATCHER.addURI(AUTHORITY, "outgoing_message/#", OUTGOING_MESSAGE_WITH_ID);

        // example query: content://rcs/outgoing_message/54/delivery. Only supports queries
        URL_MATCHER.addURI(AUTHORITY, "outgoing_message/#/delivery", OUTGOING_MESSAGE_DELIVERY);

        // example query: content://rcs/outgoing_message/9/delivery/4. Does not support queries
        URL_MATCHER.addURI(AUTHORITY, "outgoing_message/#/delivery/#",
                OUTGOING_MESSAGE_DELIVERY_WITH_ID);

        // example query: content://rcs/thread/5/message?recipient=11. Only supports querying.
        URL_MATCHER.addURI(AUTHORITY, "thread/#/message", UNIFIED_MESSAGE_ON_THREAD);

        // example query: content://rcs/thread/5/message/40. Only supports querying.
        URL_MATCHER.addURI(AUTHORITY, "thread/#/message/#", UNIFIED_MESSAGE_ON_THREAD_WITH_ID);

        // only available for inserting incoming messages onto a 1 to 1 thread.
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/incoming_message",
                INCOMING_MESSAGE_ON_P2P_THREAD);

        // example query: content://rcs/p2p_thread/11/incoming_message/45. Only supports querying
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/incoming_message/#",
                INCOMING_MESSAGE_ON_P2P_THREAD_WITH_ID);

        // only available for inserting outgoing messages onto a 1 to 1 thread.
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/outgoing_message",
                OUTGOING_MESSAGE_ON_P2P_THREAD);

        // example query: content://rcs/p2p_thread/11/outgoing_message/46. Only supports querying
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/outgoing_message/#",
                OUTGOING_MESSAGE_ON_P2P_THREAD_WITH_ID);

        // only available for inserting incoming messages onto a group thread.
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/incoming_message",
                INCOMING_MESSAGE_ON_GROUP_THREAD);

        // example query: content://rcs/group_thread/13/incoming_message/71. Only supports querying
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/incoming_message/#",
                INCOMING_MESSAGE_ON_GROUP_THREAD_WITH_ID);

        // only available for inserting outgoing messages onto a group thread.
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/outgoing_message",
                OUTGOING_MESSAGE_ON_GROUP_THREAD);

        // example query: content://rcs/group_thread/13/outgoing_message/72. Only supports querying
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/outgoing_message/#",
                OUTGOING_MESSAGE_ON_GROUP_THREAD_WITH_ID);

        // example query: content://rcs/file_transfer/1. Does not support insertion
        URL_MATCHER.addURI(AUTHORITY, "file_transfer/#", FILE_TRANSFER_WITH_ID);
    }

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        // Use the credential encrypted mmssms.db for RCS messages.
        mDbOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        mParticipantHelper = new RcsProviderParticipantHelper(mDbOpenHelper);
        mThreadHelper = new RcsProviderThreadHelper(mDbOpenHelper);
        mMessageHelper = new RcsProviderMessageHelper(mDbOpenHelper);
        mEventHelper = new RcsProviderEventHelper(mDbOpenHelper);
        return true;
    }

    @Override
    public synchronized Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        int match = URL_MATCHER.match(uri);

        switch (match) {
            case UNIFIED_RCS_THREAD:
                return mThreadHelper.queryUnifiedThread(projection, selection,
                        selectionArgs, sortOrder);
            case UNIFIED_RCS_THREAD_WITH_ID:
                return mThreadHelper.queryUnifiedThreadUsingId(uri, projection);
            case PARTICIPANT:
                return mParticipantHelper.queryParticipant(projection, selection,
                        selectionArgs, sortOrder);
            case PARTICIPANT_WITH_ID:
                return mParticipantHelper.queryParticipantWithId(uri, projection);
            case PARTICIPANT_ALIAS_CHANGE_EVENT:
                // TODO(109759350) - implement
                break;
            case PARTICIPANT_ALIAS_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Querying participant events with id's is not supported, uri: " + uri);
                break;
            case P2P_THREAD:
                return mThreadHelper.query1to1Thread(projection, selection,
                        selectionArgs, sortOrder);
            case P2P_THREAD_WITH_ID:
                return mThreadHelper.query1To1ThreadUsingId(uri, projection);
            case P2P_THREAD_PARTICIPANT:
                return mParticipantHelper.queryParticipantIn1To1Thread(uri);
            case P2P_THREAD_PARTICIPANT_WITH_ID:
                Log.e(TAG,
                        "Querying participants in 1 to 1 threads via id's is not supported, uri: "
                                + uri);
                break;
            case GROUP_THREAD:
                return mThreadHelper.queryGroupThread(projection, selection,
                        selectionArgs, sortOrder);
            case GROUP_THREAD_WITH_ID:
                return mThreadHelper.queryGroupThreadUsingId(uri, projection);
            case GROUP_THREAD_PARTICIPANT:
                return mParticipantHelper.queryParticipantsInGroupThread(uri);
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                return mParticipantHelper.queryParticipantInGroupThreadWithId(uri);
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT:
                // TODO(109759350) - implement
                break;
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT_WITH_ID:
                Log.e(TAG, "Querying thread events with id's is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT:
                // TODO(109759350) - implement
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT_WITH_ID:
                Log.e(TAG, "Querying thread events with id's is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT:
                // TODO(109759350) - implement
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Querying thread events with id's is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT:
                // TODO(109759350) - implement
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Querying thread events with id's is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE:
                return mMessageHelper.queryUnifiedMessageWithSelection(selection, selectionArgs);
            case UNIFIED_MESSAGE_WITH_ID:
                return mMessageHelper.queryUnifiedMessageWithId(uri);
            case UNIFIED_MESSAGE_WITH_FILE_TRANSFER:
                Log.e(TAG,
                        "Querying file transfers through messages is not supported, uri: " + uri);
            case INCOMING_MESSAGE:
                return mMessageHelper.queryIncomingMessageWithSelection(selection, selectionArgs);
            case INCOMING_MESSAGE_WITH_ID:
                return mMessageHelper.queryIncomingMessageWithId(uri);
            case OUTGOING_MESSAGE:
                return mMessageHelper.queryOutgoingMessageWithSelection(selection, selectionArgs);
            case OUTGOING_MESSAGE_WITH_ID:
                return mMessageHelper.queryOutgoingMessageWithId(uri);
            case OUTGOING_MESSAGE_DELIVERY:
                return mMessageHelper.queryOutgoingMessageDeliveries(uri);
            case OUTGOING_MESSAGE_DELIVERY_WITH_ID:
                Log.e(TAG,
                        "Querying deliveries with message and participant ids is not supported, "
                                + "uri: "
                                + uri);
            case UNIFIED_MESSAGE_ON_THREAD:
                return mMessageHelper.queryAllMessagesOnThread(uri, selection, selectionArgs);
            case UNIFIED_MESSAGE_ON_THREAD_WITH_ID:
                return mMessageHelper.queryUnifiedMessageWithIdInThread(uri);
            case INCOMING_MESSAGE_ON_P2P_THREAD:
                Log.e(TAG,
                        "Querying incoming messages on P2P thread with selection is not "
                                + "supported, uri: "
                                + uri);
                break;
            case INCOMING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                return mMessageHelper.queryUnifiedMessageWithIdInThread(uri);
            case OUTGOING_MESSAGE_ON_P2P_THREAD:
                Log.e(TAG,
                        "Querying incoming messages on P2P thread with selection is not "
                                + "supported, uri: "
                                + uri);
                break;
            case OUTGOING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                return mMessageHelper.queryUnifiedMessageWithIdInThread(uri);
            case INCOMING_MESSAGE_ON_GROUP_THREAD:
                Log.e(TAG,
                        "Querying incoming messages on group thread with selection is not "
                                + "supported, uri: "
                                + uri);
                break;
            case INCOMING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                return mMessageHelper.queryUnifiedMessageWithIdInThread(uri);
            case OUTGOING_MESSAGE_ON_GROUP_THREAD:
                Log.e(TAG,
                        "Querying outgoing messages on group thread with selection is not "
                                + "supported, uri: "
                                + uri);
                break;
            case OUTGOING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                return mMessageHelper.queryUnifiedMessageWithIdInThread(uri);
            case FILE_TRANSFER_WITH_ID:
                return mMessageHelper.queryFileTransfer(uri);
            default:
                Log.e(TAG, "Invalid query: " + uri);
        }

        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public synchronized Uri insert(Uri uri, ContentValues values) {
        int match = URL_MATCHER.match(uri);

        Uri returnUri = null;
        long rowId;

        switch (match) {
            case UNIFIED_RCS_THREAD:
                Log.e(TAG, "Inserting into unified thread view is not supported, uri: " + uri);
                break;
            case UNIFIED_RCS_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a thread with a specified ID is not supported, uri: " + uri);
                break;
            case PARTICIPANT:
                rowId = mParticipantHelper.insertParticipant(values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = Uri.parse(PARTICIPANT_URI_PREFIX + rowId);
                break;
            case PARTICIPANT_WITH_ID:
                Log.e(TAG, "Inserting participant with a specified ID is not supported, uri: "
                        + uri);
                break;
            case PARTICIPANT_ALIAS_CHANGE_EVENT:
                rowId = mEventHelper.insertParticipantEvent(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri.buildUpon().appendPath(Long.toString(rowId)).build();
                break;
            case PARTICIPANT_ALIAS_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Inserting participant events with id's is not supported, uri: " + uri);
                break;
            case P2P_THREAD:
                rowId = mThreadHelper.insert1To1Thread(values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = Uri.parse(P2P_THREAD_URI_PREFIX + rowId);
                break;
            case P2P_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a thread with a specified ID is not supported, uri: " + uri);
                break;
            case P2P_THREAD_PARTICIPANT:
                Log.e(TAG,
                        "Inserting a participant into a thread via content values is not "
                                + "supported, uri: "
                                + uri);
                break;
            case P2P_THREAD_PARTICIPANT_WITH_ID:
                rowId = mParticipantHelper.insertParticipantIntoP2pThread(uri);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri;
                break;
            case GROUP_THREAD:
                rowId = mThreadHelper.insertGroupThread(values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = Uri.parse(GROUP_THREAD_URI_PREFIX + rowId);
                break;
            case GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a thread with a specified ID is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT:
                rowId = mEventHelper.insertParticipantJoinedEvent(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri.buildUpon().appendPath(Long.toString(rowId)).build();
                break;
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT_WITH_ID:
                Log.e(TAG, "Inserting thread events with id's is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT:
                rowId = mEventHelper.insertParticipantLeftEvent(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri.buildUpon().appendPath(Long.toString(rowId)).build();
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT_WITH_ID:
                Log.e(TAG, "Inserting thread events with id's is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT:
                rowId = mEventHelper.insertThreadNameChangeEvent(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri.buildUpon().appendPath(Long.toString(rowId)).build();
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Inserting thread events with id's is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT:
                rowId = mEventHelper.insertThreadIconChangeEvent(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri.buildUpon().appendPath(Long.toString(rowId)).build();
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Inserting thread events with id's is not supported, uri: " + uri);
                break;

            case GROUP_THREAD_PARTICIPANT:
                rowId = mParticipantHelper.insertParticipantIntoGroupThread(values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = mParticipantHelper.getParticipantInThreadUri(values, rowId);
                break;
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                rowId = mParticipantHelper.insertParticipantIntoGroupThreadWithId(uri);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri;
                break;
            case UNIFIED_MESSAGE:
                Log.e(TAG, "Inserting into unified message view is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_WITH_ID:
                Log.e(TAG, "Inserting into unified message view is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_WITH_FILE_TRANSFER:
                rowId = mMessageHelper.insertFileTransferToMessage(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = Uri.parse(FILE_TRANSFER_PREFIX + rowId);
                break;
            case INCOMING_MESSAGE:
                Log.e(TAG, "Inserting an incoming message without a thread is not supported, uri: "
                        + uri);
                break;
            case INCOMING_MESSAGE_WITH_ID:
                Log.e(TAG, "Inserting an incoming message without a thread is not supported, uri: "
                        + uri);
                break;
            case OUTGOING_MESSAGE:
                Log.e(TAG, "Inserting an outgoing message without a thread is not supported, uri: "
                        + uri);
                break;
            case OUTGOING_MESSAGE_WITH_ID:
                Log.e(TAG, "Inserting an outgoing message without a thread is not supported, uri: "
                        + uri);
                break;
            case OUTGOING_MESSAGE_DELIVERY:
                Log.e(TAG,
                        "Inserting an outgoing message delivery without a participant is not "
                                + "supported, uri: "
                                + uri);
                break;
            case OUTGOING_MESSAGE_DELIVERY_WITH_ID:
                rowId = mMessageHelper.insertMessageDelivery(uri, values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                returnUri = uri;
                break;
            case UNIFIED_MESSAGE_ON_THREAD:
                Log.e(TAG,
                        "Inserting a message on unified thread view is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_ON_THREAD_WITH_ID:
                Log.e(TAG,
                        "Inserting a message on unified thread view is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_P2P_THREAD:
                return mMessageHelper.insertMessageOnThread(uri, values, /* isIncoming= */
                        true, /* is1To1 */ true);
            case INCOMING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a message with a specific id is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_P2P_THREAD:
                return mMessageHelper.insertMessageOnThread(uri, values, /* isIncoming= */
                        false, /* is1To1 */ true);
            case OUTGOING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a message with a specific id is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_GROUP_THREAD:
                return mMessageHelper.insertMessageOnThread(uri, values, /* isIncoming= */
                        true, /* is1To1 */ false);
            case INCOMING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a message with a specific id is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_GROUP_THREAD:
                return mMessageHelper.insertMessageOnThread(uri, values, /* isIncoming= */
                        false, /* is1To1 */ false);
            case OUTGOING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a message with a specific id is not supported, uri: " + uri);
                break;
            case FILE_TRANSFER_WITH_ID:
                Log.e(TAG, "Inserting a file transfer without a message is not supported, uri: "
                        + uri);
                break;
            default:
                Log.e(TAG, "Invalid insert: " + uri);
        }

        return returnUri;
    }

    @Override
    public synchronized int delete(Uri uri, String selection, String[] selectionArgs) {
        int match = URL_MATCHER.match(uri);
        int deletedCount = 0;

        switch (match) {
            case UNIFIED_RCS_THREAD:
                return mThreadHelper.deleteUnifiedThread(selection, selectionArgs);
            case UNIFIED_RCS_THREAD_WITH_ID:
                return mThreadHelper.deleteUnifiedThreadWithId(uri);
            case PARTICIPANT:
                Log.e(TAG, "Deleting participant with selection is not allowed: " + uri);
                break;
            case PARTICIPANT_WITH_ID:
                return mParticipantHelper.deleteParticipantWithId(uri);
            case PARTICIPANT_ALIAS_CHANGE_EVENT:
                Log.e(TAG, "Deleting participant events without id is not allowed: " + uri);
                break;
            case PARTICIPANT_ALIAS_CHANGE_EVENT_WITH_ID:
                return mEventHelper.deleteParticipantEvent(uri);
            case P2P_THREAD:
                return mThreadHelper.delete1To1Thread(selection, selectionArgs);
            case P2P_THREAD_WITH_ID:
                return mThreadHelper.delete1To1ThreadWithId(uri);
            case P2P_THREAD_PARTICIPANT:
                Log.e(TAG, "Removing participant from 1 to 1 thread is not allowed, uri: " + uri);
                break;
            case GROUP_THREAD:
                return mThreadHelper.deleteGroupThread(selection, selectionArgs);
            case GROUP_THREAD_WITH_ID:
                return mThreadHelper.deleteGroupThreadWithId(uri);
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT:
                Log.e(TAG, "Deleting thread events via selection is not allowed, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT_WITH_ID:
                return mEventHelper.deleteGroupThreadEvent(uri);
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT:
                Log.e(TAG, "Deleting thread events via selection is not allowed, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT_WITH_ID:
                return mEventHelper.deleteGroupThreadEvent(uri);
            case GROUP_THREAD_NAME_CHANGE_EVENT:
                Log.e(TAG, "Deleting thread events via selection is not allowed, uri: " + uri);
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT_WITH_ID:
                return mEventHelper.deleteGroupThreadEvent(uri);
            case GROUP_THREAD_ICON_CHANGE_EVENT:
                Log.e(TAG, "Deleting thread events via selection is not allowed, uri: " + uri);
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT_WITH_ID:
                return mEventHelper.deleteGroupThreadEvent(uri);
            case GROUP_THREAD_PARTICIPANT:
                Log.e(TAG,
                        "Deleting a participant from group thread via selection is not allowed, "
                                + "uri: "
                                + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                return mParticipantHelper.deleteParticipantFromGroupThread(uri);
            case UNIFIED_MESSAGE:
                Log.e(TAG,
                        "Deleting message from unified view with selection is not allowed: " + uri);
                break;
            case UNIFIED_MESSAGE_WITH_ID:
                Log.e(TAG, "Deleting message from unified view with id is not allowed: " + uri);
                break;
            case UNIFIED_MESSAGE_WITH_FILE_TRANSFER:
                Log.e(TAG, "Deleting file transfer using message uri is not allowed, uri: " + uri);
                break;
            case INCOMING_MESSAGE:
                return mMessageHelper.deleteIncomingMessageWithSelection(selection, selectionArgs);
            case INCOMING_MESSAGE_WITH_ID:
                return mMessageHelper.deleteIncomingMessageWithId(uri);
            case OUTGOING_MESSAGE:
                return mMessageHelper.deleteOutgoingMessageWithSelection(selection, selectionArgs);
            case OUTGOING_MESSAGE_WITH_ID:
                return mMessageHelper.deleteOutgoingMessageWithId(uri);
            case OUTGOING_MESSAGE_DELIVERY:
                Log.e(TAG, "Deleting message deliveries is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_DELIVERY_WITH_ID:
                Log.e(TAG, "Deleting message deliveries is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_ON_THREAD:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_ON_THREAD_WITH_ID:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_P2P_THREAD:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_P2P_THREAD:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_GROUP_THREAD:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_GROUP_THREAD:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Deleting messages using thread uris is not supported, uri: " + uri);
                break;
            case FILE_TRANSFER_WITH_ID:
                return mMessageHelper.deleteFileTransfer(uri);
            default:
                Log.e(TAG, "Invalid delete: " + uri);
        }

        return deletedCount;
    }

    @Override
    public synchronized int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        int match = URL_MATCHER.match(uri);
        int updatedCount = 0;

        switch (match) {
            case UNIFIED_RCS_THREAD:
                Log.e(TAG, "Updating unified thread view is not supported, uri: " + uri);
                break;
            case UNIFIED_RCS_THREAD_WITH_ID:
                Log.e(TAG, "Updating unified thread view is not supported, uri: " + uri);
                break;
            case PARTICIPANT:
                return mParticipantHelper.updateParticipant(values, selection, selectionArgs);
            case PARTICIPANT_WITH_ID:
                return mParticipantHelper.updateParticipantWithId(values, uri);
            case PARTICIPANT_ALIAS_CHANGE_EVENT:
                Log.e(TAG, "Updating events is not supported, uri: " + uri);
                break;
            case PARTICIPANT_ALIAS_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Updating events is not supported, uri: " + uri);
                break;
            case P2P_THREAD:
                return mThreadHelper.update1To1Thread(values, selection, selectionArgs);
            case P2P_THREAD_WITH_ID:
                return mThreadHelper.update1To1ThreadWithId(values, uri);
            case P2P_THREAD_PARTICIPANT:
                Log.e(TAG, "Updating junction table entries is not supported, uri: " + uri);
                break;
            case GROUP_THREAD:
                return mThreadHelper.updateGroupThread(values, selection, selectionArgs);
            case GROUP_THREAD_WITH_ID:
                return mThreadHelper.updateGroupThreadWithId(values, uri);
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_JOINED_EVENT_WITH_ID:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_LEFT_EVENT_WITH_ID:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_NAME_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_ICON_CHANGE_EVENT_WITH_ID:
                Log.e(TAG, "Updating thread events is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT:
                Log.e(TAG, "Updating junction table entries is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                Log.e(TAG, "Updating junction table entries is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE:
                Log.e(TAG, "Updating unified message view via selection is not supported, uri: "
                        + uri);
                break;
            case UNIFIED_MESSAGE_WITH_ID:
                Log.e(TAG, "Updating unified message view is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_WITH_FILE_TRANSFER:
                Log.e(TAG,
                        "Updating file transfer using unified message uri is not supported, uri: "
                                + uri);
            case INCOMING_MESSAGE:
                Log.e(TAG,
                        "Updating an incoming message via selection is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_WITH_ID:
                return mMessageHelper.updateIncomingMessage(uri, values);
            case OUTGOING_MESSAGE:
                Log.e(TAG,
                        "Updating an outgoing message via selection is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_WITH_ID:
                return mMessageHelper.updateOutgoingMessage(uri, values);
            case OUTGOING_MESSAGE_DELIVERY:
                Log.e(TAG, "Updating message deliveries using message uris is not supported, uri: "
                        + uri);
                break;
            case OUTGOING_MESSAGE_DELIVERY_WITH_ID:
                return mMessageHelper.updateDelivery(uri, values);
            case UNIFIED_MESSAGE_ON_THREAD:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case UNIFIED_MESSAGE_ON_THREAD_WITH_ID:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_P2P_THREAD:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_P2P_THREAD:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_P2P_THREAD_WITH_ID:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_GROUP_THREAD:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case INCOMING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_GROUP_THREAD:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case OUTGOING_MESSAGE_ON_GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Updating messages using threads uris is not supported, uri: " + uri);
                break;
            case FILE_TRANSFER_WITH_ID:
                return mMessageHelper.updateFileTransfer(uri, values);
            default:
                Log.e(TAG, "Invalid update: " + uri);
        }

        return updatedCount;
    }
}
