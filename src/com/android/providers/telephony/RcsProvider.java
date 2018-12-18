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

import android.annotation.Nullable;
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
 * @hide
 */
public class RcsProvider extends ContentProvider {
    static final String TAG = "RcsProvider";
    static final String AUTHORITY = "rcs";
    static final long TRANSACTION_FAILED = Long.MIN_VALUE;

    private static final Uri THREAD_URI_PREFIX = Uri.parse("content://" + AUTHORITY + "/thread");
    private static final Uri PARTICIPANT_URI_PREFIX = Uri.parse(
            "content://" + AUTHORITY + "/participant");
    private static final Uri P2P_THREAD_URI_PREFIX = Uri.parse(
            "content://" + AUTHORITY + "/p2p_thread");
    static final Uri GROUP_THREAD_URI_PREFIX = Uri.parse(
            "content://" + AUTHORITY + "/group_thread");

    private static final UriMatcher URL_MATCHER = new UriMatcher(UriMatcher.NO_MATCH);

    private static final int UNIFIED_RCS_THREAD = 1;
    private static final int UNIFIED_RCS_THREAD_WITH_ID = 2;
    private static final int PARTICIPANT = 3;
    private static final int PARTICIPANT_WITH_ID = 4;
    private static final int P2P_THREAD = 5;
    private static final int P2P_THREAD_WITH_ID = 6;
    private static final int P2P_THREAD_PARTICIPANT = 7;
    private static final int P2P_THREAD_PARTICIPANT_WITH_ID = 8;
    private static final int GROUP_THREAD = 9;
    private static final int GROUP_THREAD_WITH_ID = 10;
    private static final int GROUP_THREAD_PARTICIPANT = 11;
    private static final int GROUP_THREAD_PARTICIPANT_WITH_ID = 12;

    SQLiteOpenHelper mDbOpenHelper;

    @VisibleForTesting
    RcsProviderThreadHelper mThreadHelper;
    @VisibleForTesting
    RcsProviderParticipantHelper mParticipantHelper;

    static {
        // example URI: content://rcs/thread
        URL_MATCHER.addURI(AUTHORITY, "thread", UNIFIED_RCS_THREAD);

        // example URI: content://rcs/thread/4
        URL_MATCHER.addURI(AUTHORITY, "thread/#", UNIFIED_RCS_THREAD_WITH_ID);

        // example URI: content://rcs/participant
        URL_MATCHER.addURI(AUTHORITY, "participant", PARTICIPANT);

        // example URI: content://rcs/participant/12
        URL_MATCHER.addURI(AUTHORITY, "participant/#", PARTICIPANT_WITH_ID);

        // example URI: content://rcs/p2p_thread
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread", P2P_THREAD);

        // example URI: content://rcs/p2p_thread/4 , where 4 is the _id in rcs_threads table.
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#", P2P_THREAD_WITH_ID);

        // example URI: content://rcs/p2p_thread/7/participant
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/participant", P2P_THREAD_PARTICIPANT);

        // example URI: content://rcs/p2p_thread/9/participant/3", only supports a 1 time insert
        URL_MATCHER.addURI(AUTHORITY, "p2p_thread/#/participant/#", P2P_THREAD_PARTICIPANT_WITH_ID);

        // example URI: content://rcs/group_thread
        URL_MATCHER.addURI(AUTHORITY, "group_thread", GROUP_THREAD);

        // example URI: content://rcs/group_thread/13
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#", GROUP_THREAD_WITH_ID);

        // example URI: content://rcs/group_thread/18/participant
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant",
                GROUP_THREAD_PARTICIPANT);

        // example URI: content://rcs/group_thread/21/participant/4, only supports inserts and
        // deletes
        URL_MATCHER.addURI(AUTHORITY, "group_thread/#/participant/#",
                GROUP_THREAD_PARTICIPANT_WITH_ID);
    }

    @Override
    public boolean onCreate() {
        setAppOps(AppOpsManager.OP_READ_SMS, AppOpsManager.OP_WRITE_SMS);
        // Use the credential encrypted mmssms.db for RCS messages.
        mDbOpenHelper = MmsSmsDatabaseHelper.getInstanceForCe(getContext());
        mParticipantHelper = new RcsProviderParticipantHelper(mDbOpenHelper);
        mThreadHelper = new RcsProviderThreadHelper(mDbOpenHelper);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
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
            case P2P_THREAD:
                return mThreadHelper.query1to1Thread(projection, selection,
                        selectionArgs, sortOrder);
            case P2P_THREAD_WITH_ID:
                return mThreadHelper.query1To1ThreadUsingId(uri, projection);
            case P2P_THREAD_PARTICIPANT:
                return mParticipantHelper.queryParticipantIn1To1Thread(uri);
            case P2P_THREAD_PARTICIPANT_WITH_ID:
                Log.e(TAG, "Querying participants in 1 to 1 threads via id's is not supported, uri "
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
    public Uri insert(Uri uri, ContentValues values) {
        int match = URL_MATCHER.match(uri);
        switch (match) {
            case UNIFIED_RCS_THREAD:
                Log.e(TAG, "Inserting into unified thread view is not supported, uri:" + uri);
                break;
            case UNIFIED_RCS_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a thread with a specified ID is not supported, uri:" + uri);
                break;
            case PARTICIPANT:
                return buildUriFromHelperRowId(PARTICIPANT_URI_PREFIX,
                        mParticipantHelper.insertParticipant(values));
            case PARTICIPANT_WITH_ID:
                Log.e(TAG, "Inserting participant with a specified ID is not supported, uri:"
                        + uri);
                break;
            case P2P_THREAD:
                return buildUriFromHelperRowId(P2P_THREAD_URI_PREFIX,
                        mThreadHelper.insert1To1Thread(values));
            case P2P_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a thread with a specified ID is not supported, uri:" + uri);
                break;
            case P2P_THREAD_PARTICIPANT:
                Log.e(TAG,
                        "Inserting a participant into a thread via content values is not "
                                + "supported, uri: "
                                + uri);
                break;
            case P2P_THREAD_PARTICIPANT_WITH_ID:
                return returnSameUriIfSuccessful(uri,
                        mParticipantHelper.insertParticipantIntoP2pThread(uri));
            case GROUP_THREAD:
                return buildUriFromHelperRowId(GROUP_THREAD_URI_PREFIX,
                        mThreadHelper.insertGroupThread(values));
            case GROUP_THREAD_WITH_ID:
                Log.e(TAG, "Inserting a thread with a specified ID is not supported, uri:" + uri);
                break;
            case GROUP_THREAD_PARTICIPANT:
                long rowId = mParticipantHelper.insertParticipantIntoGroupThread(values);
                if (rowId == TRANSACTION_FAILED) {
                    return null;
                }
                return mParticipantHelper.getParticipantInThreadUri(values, rowId);
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                return returnSameUriIfSuccessful(uri,
                        mParticipantHelper.insertParticipantIntoGroupThreadWithId(uri));
            default:
                Log.e(TAG, "Invalid insert: " + uri);
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
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
            case GROUP_THREAD_PARTICIPANT:
                Log.e(TAG,
                        "Deleting a participant from group thread via selection is not allowed, "
                                + "uri: "
                                + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                return mParticipantHelper.deleteParticipantFromGroupThread(uri);
            default:
                Log.e(TAG, "Invalid delete: " + uri);
        }

        return deletedCount;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
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
            case GROUP_THREAD_PARTICIPANT:
                Log.e(TAG, "Updating junction table entries is not supported, uri: " + uri);
                break;
            case GROUP_THREAD_PARTICIPANT_WITH_ID:
                Log.e(TAG, "Updating junction table entries is not supported, uri: " + uri);
                break;
            default:
                Log.e(TAG, "Invalid update: " + uri);
        }
        return updatedCount;
    }

    @Nullable
    private Uri buildUriFromHelperRowId(Uri prefix, long rowId) {
        if (rowId == TRANSACTION_FAILED) {
            return null;
        }
        return Uri.withAppendedPath(prefix, Long.toString(rowId));
    }

    @Nullable
    private Uri returnSameUriIfSuccessful(Uri uri, long rowId) {
        if (rowId == TRANSACTION_FAILED) {
            return null;
        }
        return uri;
    }
}
