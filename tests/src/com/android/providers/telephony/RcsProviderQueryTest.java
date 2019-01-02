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
 * limitations under the License
 */
package com.android.providers.telephony;

import static com.android.providers.telephony.RcsProviderMessageHelper.FILE_SIZE;
import static com.android.providers.telephony.RcsProviderMessageHelper.SESSION_ID;
import static com.android.providers.telephony.RcsProviderParticipantHelper.CANONICAL_ADDRESS_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderParticipantHelper.RCS_ALIAS_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.GROUP_NAME_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.RCS_THREAD_ID_COLUMN;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import com.android.providers.telephony.RcsProviderTestable.MockContextWithProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RcsProviderQueryTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    private static final String GROUP_NAME = "group name";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRcsProvider = new RcsProviderTestable();
        MockContextWithProvider context = new MockContextWithProvider(mRcsProvider);
        mContentResolver = context.getContentResolver();

        // insert a participant
        Uri participantUri = Uri.parse("content://rcs/participant");
        ContentValues contentValues = new ContentValues();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 99);
        contentValues.put(RCS_ALIAS_COLUMN, "Some alias");

        mContentResolver.insert(participantUri, contentValues);

        // insert two 1 to 1 threads
        ContentValues p2pContentValues = new ContentValues(0);
        Uri threadsUri = Uri.parse("content://rcs/p2p_thread");
        assertThat(mContentResolver.insert(threadsUri, p2pContentValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1"));
        assertThat(mContentResolver.insert(threadsUri, p2pContentValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/2"));

        // insert one group thread
        ContentValues groupContentValues = new ContentValues(1);
        groupContentValues.put(GROUP_NAME_COLUMN, GROUP_NAME);
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/group_thread"),
                groupContentValues)).isEqualTo(Uri.parse("content://rcs/group_thread/3"));
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testCanQueryUnifiedThreads() {
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/thread"),
                new String[]{GROUP_NAME_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(3);

        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo(null);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo(null);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo(GROUP_NAME);
    }

    @Test
    public void testQuery1To1Threads() {
        // verify two threads are returned in the query
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/p2p_thread"),
                new String[]{RCS_THREAD_ID_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(2);
    }

    @Test
    public void testQueryGroupThreads() {
        // verify one thread is returned in the query
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/group_thread"),
                new String[]{GROUP_NAME_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo(GROUP_NAME);
    }

    @Test
    public void testQueryParticipant() {
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/participant"),
                new String[]{RCS_ALIAS_COLUMN}, null, null, null);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("Some alias");
    }

    @Test
    public void testQueryParticipantOf1To1Thread() {
        // insert the participant into a 1 to 1 thread
        Uri insertUri = Uri.parse("content://rcs/p2p_thread/1/participant/1");
        assertThat(mContentResolver.insert(insertUri, null)).isNotNull();

        // query the participant back
        Uri queryUri = Uri.parse("content://rcs/p2p_thread/1/participant");
        Cursor cursor = mContentResolver.query(queryUri, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();

        assertThat(cursor.getInt(1)).isEqualTo(99);
        assertThat(cursor.getString(2)).isEqualTo("Some alias");
    }

    @Test
    public void testQueryParticipantOfGroupThread() {
        // insert the participant into a group thread
        Uri insertUri = Uri.parse("content://rcs/group_thread/3/participant/1");
        assertThat(mContentResolver.insert(insertUri, null)).isNotNull();

        // query all the participants in this thread
        Uri queryUri = Uri.parse("content://rcs/group_thread/3/participant");
        Cursor cursor = mContentResolver.query(queryUri, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();

        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(99);
        assertThat(cursor.getString(2)).isEqualTo("Some alias");
    }

    @Test
    public void testQueryParticipantOfGroupThreadWithId() {
        // insert the participant into a group thread
        Uri uri = Uri.parse("content://rcs/group_thread/3/participant/1");
        assertThat(mContentResolver.insert(uri, null)).isNotNull();

        // query the participant back
        Cursor cursor = mContentResolver.query(uri, null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();

        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(99);
        assertThat(cursor.getString(2)).isEqualTo("Some alias");
    }

    @Test
    public void testQueryFileTransfer() {
        ContentValues values = new ContentValues();
        // add an incoming message to the thread 2
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/p2p_thread/2/incoming_message"),
                values)).isEqualTo(Uri.parse("content://rcs/p2p_thread/2/incoming_message/1"));

        // add a file transfer
        values.put(SESSION_ID, "session_id");
        values.put(FILE_SIZE, 1234567890);
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/message/1/file_transfer"),
                        values)).isEqualTo(Uri.parse("content://rcs/file_transfer/1"));

        // query the file transfer back
        Cursor cursor = mContentResolver.query(Uri.parse("content://rcs/file_transfer/1"), null,
                null, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(1);
        assertThat(cursor.getString(2)).isEqualTo("session_id");
        assertThat(cursor.getLong(5)).isEqualTo(1234567890);
    }
}
