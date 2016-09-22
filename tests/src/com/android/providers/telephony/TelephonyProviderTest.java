/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.annotation.TargetApi;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;

import android.provider.Telephony.Carriers;

import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;

import android.text.TextUtils;
import android.util.Log;

import com.android.providers.telephony.TelephonyProvider;


/**
 * Tests for testing CRUD operations of TelephonyProvider.
 * Uses a MockContentResolver to get permission WRITE_APN_SETTINGS in order to test insert/delete
 * Uses TelephonyProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 */
// TODO what is TargetApi for? Do I need to update it to N?
@TargetApi(Build.VERSION_CODES.M)
// TODO AndroidTestCase is marked deprecated
public class TelephonyProviderTest extends AndroidTestCase {
    private static final String TAG = "TelephonyProviderTest";
    private WriteApnSettingsMockContext mContext;
    private MockContentResolver mContentResolver;

    /**
     * This private class is used to give the TelephonyProviderTest a mocked context which allows
     * WRITE_APN_SETTINGS permissions.
     */
    private class WriteApnSettingsMockContext extends MockContext {
        private final MockContentResolver mResolver;
        private TelephonyProviderTestable mTelephonyProvider;

        public WriteApnSettingsMockContext() {
            mResolver = new MockContentResolver();
            mTelephonyProvider = new TelephonyProviderTestable();

            // Add authority="telephony" to mTelephonyProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = "telephony";

            // Add context to mTelephonyProvider
            mTelephonyProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "WriteApnSettingsMockContext: mTelephonyProvider.getContext(): "
                    + mTelephonyProvider.getContext());

            // Add mTelephonyProvider to mResolver with authority="telephony" so that mResolver can
            // send queries to mTelephonyProvider
            mResolver.addProvider("telephony", mTelephonyProvider);
            Log.d(TAG, "WriteApnSettingsMockContext: Add TelephonyProviderTestable to mResolver");
        }

        @Override
        public Object getSystemService(String name) {
            Log.d(TAG, "getSystemService: returning null");
            return null;
        }

        @Override
        public Resources getResources() {
            Log.d(TAG, "getResources: returning null");
            return null;
        }

        @Override
        public MockContentResolver getContentResolver() {
            return mResolver;
        }

        // Gives permission to write to the APN table within the MockContext
        @Override
        public int checkCallingOrSelfPermission(String permission) {
            if (TextUtils.equals(permission, "android.permission.WRITE_APN_SETTINGS")) {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning " + PackageManager.PERMISSION_GRANTED);
                return PackageManager.PERMISSION_GRANTED;
            } else {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning " + PackageManager.PERMISSION_DENIED);
                return PackageManager.PERMISSION_DENIED;
            }
        }

        // close the database object within TelephonyProviderTestable
        private void closeDatabase() {
            Log.d(TAG, "closeDatabase: closing database in TelephonyProviderTestable");
            mTelephonyProvider.closeDatabase();
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mContext = new WriteApnSettingsMockContext();
        mContentResolver = (MockContentResolver) mContext.getContentResolver();

    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mContext.closeDatabase();
    }

    /**
     * Test inserting and then querying values in carriers table. Verify that the inserted values
     * match the result of the query.
     */
    public void testInsert() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final Integer insertCurrent = 1;
        final String insertNumeric = "123456";
        contentValues.put(Carriers.APN, insertApn); // APN name
        contentValues.put(Carriers.NAME, insertName); // entry name
        contentValues.put(Carriers.CURRENT, insertCurrent); // this is the current APN
        contentValues.put(Carriers.NUMERIC, insertNumeric);

        Log.d(TAG, "testInsert: Inserting contentValues: " + contentValues);
        Log.d(TAG, "testInsert: Carriers.CONTENT_URI: " + Carriers.CONTENT_URI);
        Log.d(TAG, "testInsert: Carriers.CONTENT_URI.getAuthority(): "
                + Carriers.CONTENT_URI.getAuthority());
        mContentResolver.insert(Carriers.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
        };
        final String selection = Carriers.NUMERIC + " = " + insertNumeric;
        String[] selectionArgs = null;
        Log.d(TAG, "testInsert:\nquery projection: " + testProjection
                + "\nselection: " + selection);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String apn = cursor.getString(0);
        final String name = cursor.getString(1);
        final Integer current = cursor.getInt(2);
        assertEquals(apn, insertApn);
        assertEquals(name, insertName);
        assertEquals(current, insertCurrent);

        // TODO test deletion
    }
}
