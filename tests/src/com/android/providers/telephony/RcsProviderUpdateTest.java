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
 * limitations under the License
 */
package com.android.providers.telephony;

import static com.android.providers.telephony.RcsProviderParticipantHelper.CANONICAL_ADDRESS_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderParticipantHelper.RCS_ALIAS_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.FALLBACK_THREAD_ID_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.GROUP_NAME_COLUMN;
import static com.android.providers.telephony.RcsProviderThreadHelper.OWNER_PARTICIPANT;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RcsProviderUpdateTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRcsProvider = new RcsProviderTestable();
        RcsProviderTestable.MockContextWithProvider
                context = new RcsProviderTestable.MockContextWithProvider(mRcsProvider);
        mContentResolver = context.getContentResolver();

        // insert a participant
        //  first into the MmsSmsProvider
        mRcsProvider.getWritableDatabase().execSQL(
                "INSERT INTO canonical_addresses VALUES (1, \"+15551234567\")");

        //  then into the RcsProvider
        ContentValues participantValues = new ContentValues();
        participantValues.put(RCS_ALIAS_COLUMN, "Bob");
        participantValues.put(CANONICAL_ADDRESS_ID_COLUMN, 1);
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/participant"),
                participantValues)).isEqualTo(Uri.parse("content://rcs/participant/1"));

        // insert fallback threads
        mRcsProvider.getWritableDatabase().execSQL("INSERT INTO threads(_id) VALUES (1)");
        mRcsProvider.getWritableDatabase().execSQL("INSERT INTO threads(_id) VALUES (2)");

        // insert one 1 to 1 thread
        ContentValues p2pContentValues = new ContentValues();
        Uri p2pThreadUri = Uri.parse("content://rcs/p2p_thread");
        p2pContentValues.put(FALLBACK_THREAD_ID_COLUMN, 1);
        assertThat(mContentResolver.insert(p2pThreadUri, p2pContentValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1"));

        // insert one group thread
        ContentValues groupContentValues = new ContentValues();
        groupContentValues.put(OWNER_PARTICIPANT, 1);
        groupContentValues.put(GROUP_NAME_COLUMN, "Name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread");
        assertThat(mContentResolver.insert(groupThreadUri, groupContentValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2"));

        // Add participant to both threads
        Uri p2pInsertionUri = Uri.parse("content://rcs/p2p_thread/1/participant/1");
        assertThat(mContentResolver.insert(p2pInsertionUri, null)).isEqualTo(p2pInsertionUri);

        Uri groupInsertionUri = Uri.parse("content://rcs/group_thread/2/participant/1");
        assertThat(mContentResolver.insert(groupInsertionUri, null)).isEqualTo(groupInsertionUri);
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testUpdate1To1ThreadWithSelection() {
        // update the fallback thread id
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(FALLBACK_THREAD_ID_COLUMN, 2);
        Uri p2pThreadUri = Uri.parse("content://rcs/p2p_thread");

        assertThat(mContentResolver.update(p2pThreadUri, contentValues, "rcs_fallback_thread_id=1",
                null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(p2pThreadUri,
                new String[]{FALLBACK_THREAD_ID_COLUMN}, "rcs_fallback_thread_id=2", null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
    }

    @Test
    public void testUpdate1To1ThreadWithId() {
        // update the fallback thread id
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(FALLBACK_THREAD_ID_COLUMN, 2);
        Uri p2pThreadUri = Uri.parse("content://rcs/p2p_thread/1");
        assertThat(mContentResolver.update(p2pThreadUri, contentValues, null, null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(p2pThreadUri,
                new String[]{FALLBACK_THREAD_ID_COLUMN}, null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getInt(0)).isEqualTo(2);
    }

    @Test
    public void testUpdateGroupThreadWithSelection() {
        // update the group name
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(GROUP_NAME_COLUMN, "New name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread");
        assertThat(mContentResolver.update(groupThreadUri, contentValues, "group_name=\"Name\"",
                null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(groupThreadUri, new String[]{GROUP_NAME_COLUMN},
                "group_name=\"New name\"", null, null);
        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("New name");
    }

    @Test
    public void testUpdateGroupThreadWithId() {
        // update the group name
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(GROUP_NAME_COLUMN, "New name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread/2");
        assertThat(mContentResolver.update(groupThreadUri, contentValues, null, null)).isEqualTo(1);

        // verify the thread is actually updated
        Cursor cursor = mContentResolver.query(groupThreadUri, new String[]{GROUP_NAME_COLUMN},
                null, null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("New name");
    }

    @Test
    public void testUpdateParticipantWithSelection() {
        // change the participant name from Bob to Bobby
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(RCS_ALIAS_COLUMN, "Bobby");

        Uri participantUri = Uri.parse("content://rcs/participant");

        assertThat(mContentResolver.update(participantUri, contentValues, "rcs_alias=?",
                new String[]{"Bob"})).isEqualTo(1);

        // verify participant is actually updated
        Cursor cursor = mContentResolver.query(participantUri, new String[]{RCS_ALIAS_COLUMN},
                "rcs_alias=?", new String[]{"Bobby"}, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("Bobby");
    }

    @Test
    public void testUpdateParticipantWithId() {
        // change the participant name from Bob to Bobby
        ContentValues contentValues = new ContentValues(1);
        contentValues.put(RCS_ALIAS_COLUMN, "Bobby");

        Uri participantUri = Uri.parse("content://rcs/participant/1");

        assertThat(mContentResolver.update(participantUri, contentValues, null, null)).isEqualTo(1);

        // verify participant is actually updated
        Cursor cursor = mContentResolver.query(participantUri, new String[]{RCS_ALIAS_COLUMN}, null,
                null, null);
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToNext();
        assertThat(cursor.getString(0)).isEqualTo("Bobby");
    }

    @Test
    public void testUpdate1To1ThreadParticipantFails() {
        assertThat(
                mContentResolver.update(Uri.parse("content://rcs/p2p_thread/1/participant/1"), null,
                        null, null)).isEqualTo(0);
    }

    @Test
    public void testUpdateGroupParticipantFails() {
        assertThat(mContentResolver.update(Uri.parse("content://rcs/group_thread/2/participant/1"),
                null, null, null)).isEqualTo(0);
    }
}
