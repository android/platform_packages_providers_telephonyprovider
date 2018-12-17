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

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentValues;
import android.net.Uri;
import android.support.test.runner.AndroidJUnit4;
import android.test.mock.MockContentResolver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RcsProviderInsertTest {
    private MockContentResolver mContentResolver;
    private RcsProviderTestable mRcsProvider;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mRcsProvider = new RcsProviderTestable();
        RcsProviderTestable.MockContextWithProvider
                context = new RcsProviderTestable.MockContextWithProvider(mRcsProvider);
        mContentResolver = context.getContentResolver();
    }

    @After
    public void tearDown() {
        mRcsProvider.tearDown();
    }

    @Test
    public void testInsertUnifiedThreadFails() {
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/thread"), null)).isNull();
    }

    @Test
    public void testInsert1To1Thread() {
        ContentValues values = new ContentValues(1);
        values.put(FALLBACK_THREAD_ID_COLUMN, 445);
        assertThat(
                mContentResolver.insert(Uri.parse("content://rcs/p2p_thread"), values)).isEqualTo(
                Uri.parse("content://rcs/p2p_thread/1"));
    }

    @Test
    public void testInsertGroupThread() {
        ContentValues contentValues = new ContentValues(3);
        contentValues.put(RcsProviderThreadHelper.CONFERENCE_URI_COLUMN, "conference uri");
        contentValues.put(RcsProviderThreadHelper.GROUP_NAME_COLUMN, "group name");
        contentValues.put(RcsProviderThreadHelper.GROUP_ICON_COLUMN, "groupIcon");
        assertThat(mContentResolver.insert(Uri.parse("content://rcs/group_thread"),
                contentValues)).isEqualTo(Uri.parse("content://rcs/group_thread/1"));
    }

    @Test
    public void testInsertParticipant() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(CANONICAL_ADDRESS_ID_COLUMN, 6);
        contentValues.put(RCS_ALIAS_COLUMN, "Alias");

        Uri uri = mContentResolver.insert(Uri.parse("content://rcs/participant"), contentValues);
        assertThat(uri).isEqualTo(Uri.parse("content://rcs/participant/1"));
    }

}
