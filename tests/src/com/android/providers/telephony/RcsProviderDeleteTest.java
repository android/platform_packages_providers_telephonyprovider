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
public class RcsProviderDeleteTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        RcsProviderTestable.MockContextWithProvider
                context = new RcsProviderTestable.MockContextWithProvider();
        mRcsProvider = new RcsProviderTestable(context);
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

        // insert one 1 to 1 thread
        ContentValues p2pContentValues = new ContentValues();
        Uri p2pThreadUri = Uri.parse("content://rcs/p2p_thread");
        p2pContentValues.put(FALLBACK_THREAD_ID_COLUMN, 1);
        assertThat(mContentResolver.insert(p2pThreadUri, p2pContentValues)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1"));

        // insert one group thread
        ContentValues groupContentValues = new ContentValues();
        groupContentValues.put(OWNER_PARTICIPANT, 1);
        groupContentValues.put(GROUP_NAME_COLUMN, "name");
        Uri groupThreadUri = Uri.parse("content://rcs/group_thread");
        assertThat(mContentResolver.insert(groupThreadUri, groupContentValues)).isEqualTo(
                Uri.parse("content://rcs/group_thread/2"));
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testDeleteThreadWithId() {
        assertThat(
                mContentResolver.delete(Uri.parse("content://rcs/thread/1"), null, null)).isEqualTo(
                1);
        assertDeletionViaQuery("content://rcs/thread/1");
    }

    @Test
    public void testDeleteThreadWithSelection() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/thread"), "owner_participant=1",
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/thread/2");
    }

    @Test
    public void testDelete1To1ThreadWithId() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/p2p_thread/1"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/p2p_thread/1");
    }

    @Test
    public void testDelete1To1ThreadWithSelection() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/p2p_thread"),
                "rcs_fallback_thread_id=1", null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/p2p_thread/1");
    }

    @Test
    public void testDeleteGroupThreadWithId() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread/2"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/group_thread/2");
    }

    @Test
    public void testDeleteGroupThreadWithSelection() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/group_thread"),
                "group_name=\"name\"", null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/group_thread/1");
    }

    @Test
    public void testDeleteParticipantWithId() {
        assertThat(mContentResolver.delete(Uri.parse("content://rcs/participant/1"), null,
                null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/participant/1");
    }

    @Test
    public void testDeleteParticipantWithSelection() {
        assertThat(
                mContentResolver.delete(Uri.parse("content://rcs/participant"), "rcs_alias=\"Bob\"",
                        null)).isEqualTo(1);
        assertDeletionViaQuery("content://rcs/participant/1");
    }

    private void assertDeletionViaQuery(String queryUri) {
        Cursor cursor = mContentResolver.query(Uri.parse(queryUri), null, null, null);
        assertThat(cursor.getCount()).isEqualTo(0);
        cursor.close();
    }
}
