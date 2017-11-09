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
import android.database.ContentObserver;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.os.Process;
import android.provider.Telephony.Carriers;
import android.telephony.SubscriptionManager;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;
import android.util.Log;

import com.android.providers.telephony.TelephonyProvider;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;


/**
 * Tests for testing CRUD operations of TelephonyProvider.
 * Uses a MockContentResolver to get permission WRITE_APN_SETTINGS in order to test insert/delete
 * Uses TelephonyProviderTestable to set up in-memory database
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/TelephonyProviderTest.java \
 *                 --test-method testInsertCarriers
 */
public class TelephonyProviderTest extends TestCase {
    private static final String TAG = "TelephonyProviderTest";

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private TelephonyProviderTestable mTelephonyProviderTestable;

    private int notifyChangeCount;

    /**
     * This is used to give the TelephonyProviderTest a mocked context which takes a
     * TelephonyProvider and attaches it to the ContentResolver with telephony authority.
     * The mocked context also gives WRITE_APN_SETTINGS permissions
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        public MockContextWithProvider(TelephonyProvider telephonyProvider) {
            mResolver = new MockContentResolver() {
                @Override
                public void notifyChange(Uri uri, ContentObserver observer, boolean syncToNetwork,
                        int userHandle) {
                    notifyChangeCount++;
                }
            };

            // Add authority="telephony" to given telephonyProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = "telephony";

            // Add context to given telephonyProvider
            telephonyProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: telephonyProvider.getContext(): "
                    + telephonyProvider.getContext());

            // Add given telephonyProvider to mResolver with authority="telephony" so that
            // mResolver can send queries to mTelephonyProvider
            mResolver.addProvider("telephony", telephonyProvider);
            Log.d(TAG, "MockContextWithProvider: Add telephonyProvider to mResolver");
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
                        + ", returning PackageManager.PERMISSION_GRANTED");
                return PackageManager.PERMISSION_GRANTED;
            } else {
                Log.d(TAG, "checkCallingOrSelfPermission: permission=" + permission
                        + ", returning PackageManager.PERMISSION_DENIED");
                return PackageManager.PERMISSION_DENIED;
            }
        }


    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mTelephonyProviderTestable = new TelephonyProviderTestable();
        mContext = new MockContextWithProvider(mTelephonyProviderTestable);
        mContentResolver = (MockContentResolver) mContext.getContentResolver();
        notifyChangeCount = 0;
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
        mTelephonyProviderTestable.closeDatabase();
    }

    /**
     * Test bulk inserting, querying;
     * Verify that the inserted values match the result of the query.
     */
    @Test
    @SmallTest
    public void testBulkInsertCarriers() {
        // insert 2 test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final Integer insertCurrent = 1;
        final String insertNumeric = "123456";
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.CURRENT, insertCurrent);
        contentValues.put(Carriers.NUMERIC, insertNumeric);

        ContentValues contentValues2 = new ContentValues();
        final String insertApn2 = "exampleApnName2";
        final String insertName2 = "exampleName2";
        final Integer insertCurrent2 = 1;
        final String insertNumeric2 = "789123";
        contentValues2.put(Carriers.APN, insertApn2);
        contentValues2.put(Carriers.NAME, insertName2);
        contentValues2.put(Carriers.CURRENT, insertCurrent2);
        contentValues2.put(Carriers.NUMERIC, insertNumeric2);

        Log.d(TAG, "testInsertCarriers: Bulk inserting contentValues=" + contentValues
                + ", " + contentValues2);
        ContentValues[] values = new ContentValues[]{ contentValues, contentValues2 };
        int rows = mContentResolver.bulkInsert(Carriers.CONTENT_URI, values);
        assertEquals(2, rows);
        assertEquals(1, notifyChangeCount);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        final Integer resultCurrent = cursor.getInt(2);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);
        assertEquals(insertCurrent, resultCurrent);
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testInsertCarriers() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final String insertApn = "exampleApnName";
        final String insertName = "exampleName";
        final Integer insertCurrent = 1;
        final String insertNumeric = "123456";
        final Integer insertOwnedby = Carriers.OWNED_BY_DPC;
        final Integer expectedOwnedby = Carriers.OWNED_BY_OTHERS;
        contentValues.put(Carriers.APN, insertApn);
        contentValues.put(Carriers.NAME, insertName);
        contentValues.put(Carriers.CURRENT, insertCurrent);
        contentValues.put(Carriers.NUMERIC, insertNumeric);
        contentValues.put(Carriers.OWNED_BY, insertOwnedby);

        Log.d(TAG, "testInsertCarriers Inserting contentValues: " + contentValues);
        mContentResolver.insert(Carriers.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            Carriers.APN,
            Carriers.NAME,
            Carriers.CURRENT,
            Carriers.OWNED_BY,
        };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testInsertCarriers query projection: " + testProjection
                + "\ntestInsertCarriers selection: " + selection
                + "\ntestInsertCarriers selectionArgs: " + selectionArgs);
        Cursor cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final String resultApn = cursor.getString(0);
        final String resultName = cursor.getString(1);
        final Integer resultCurrent = cursor.getInt(2);
        final Integer resultOwnedby = cursor.getInt(3);
        assertEquals(insertApn, resultApn);
        assertEquals(insertName, resultName);
        assertEquals(insertCurrent, resultCurrent);
        // Verify that OWNED_BY is force set to OWNED_BY_OTHERS when inserted with general uri.
        assertEquals(expectedOwnedby, resultOwnedby);

        // delete test content
        final String selectionToDelete = Carriers.NUMERIC + "=?";
        String[] selectionArgsToDelete = { insertNumeric };
        Log.d(TAG, "testInsertCarriers deleting selection: " + selectionToDelete
                + "testInsertCarriers selectionArgs: " + selectionArgsToDelete);
        int numRowsDeleted = mContentResolver.delete(Carriers.CONTENT_URI,
                selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    /**
     * Test inserting, querying, and deleting values in carriers table.
     * Verify that the inserted values match the result of the query and are deleted.
     */
    @Test
    @SmallTest
    public void testSimTable() {
        // insert test contentValues
        ContentValues contentValues = new ContentValues();
        final int insertSubId = 11;
        final String insertDisplayName = "exampleDisplayName";
        final String insertCarrierName = "exampleCarrierName";
        final String insertIccId = "exampleIccId";
        contentValues.put(SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID, insertSubId);
        contentValues.put(SubscriptionManager.DISPLAY_NAME, insertDisplayName);
        contentValues.put(SubscriptionManager.CARRIER_NAME, insertCarrierName);
        contentValues.put(SubscriptionManager.ICC_ID, insertIccId);

        Log.d(TAG, "testSimTable Inserting contentValues: " + contentValues);
        mContentResolver.insert(SubscriptionManager.CONTENT_URI, contentValues);

        // get values in table
        final String[] testProjection =
        {
            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
            SubscriptionManager.CARRIER_NAME,
        };
        final String selection = SubscriptionManager.DISPLAY_NAME + "=?";
        String[] selectionArgs = { insertDisplayName };
        Log.d(TAG,"\ntestSimTable selection: " + selection
                + "\ntestSimTable selectionArgs: " + selectionArgs.toString());
        Cursor cursor = mContentResolver.query(SubscriptionManager.CONTENT_URI,
                testProjection, selection, selectionArgs, null);

        // verify that inserted values match results of query
        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        final int resultSubId = cursor.getInt(0);
        final String resultCarrierName = cursor.getString(1);
        assertEquals(insertSubId, resultSubId);
        assertEquals(insertCarrierName, resultCarrierName);

        // delete test content
        final String selectionToDelete = SubscriptionManager.DISPLAY_NAME + "=?";
        String[] selectionArgsToDelete = { insertDisplayName };
        Log.d(TAG, "testSimTable deleting selection: " + selectionToDelete
                + "testSimTable selectionArgs: " + selectionArgsToDelete);
        int numRowsDeleted = mContentResolver.delete(SubscriptionManager.CONTENT_URI,
                selectionToDelete, selectionArgsToDelete);
        assertEquals(1, numRowsDeleted);

        // verify that deleted values are gone
        cursor = mContentResolver.query(SubscriptionManager.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertEquals(0, cursor.getCount());
    }

    /**
     * Test inserting, querying, and deleting DPC records in carriers table.
     * Verify that the access control to DPC records with URL_TELEPHONY, URL_DPC, URL_ALL, and
     * URL_PRIORITIZED is correct.
     * Verify that the inserted values are correctly queried, updated and deleted.
     */
    @Test
    @SmallTest
    public void testDPCRecordsAccessControl() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID);

        final Uri URI_DPC = Uri.parse("content://telephony/carriers/dpc");
        final Uri URI_PRIORITIZED = Uri.parse("content://telephony/carriers/prioritized");
        final Uri URI_ALL = Uri.parse("content://telephony/carriers/all");

        // Insert test DPC contentValues.
        ContentValues contentValuesDPC = new ContentValues();
        final String insertApnDPC = "exampleApnNameDPC";
        final String insertNameDPC = "exampleNameDPC";
        final int insertCurrent = 1;
        final String insertNumeric = "123456789";
        contentValuesDPC.put(Carriers.APN, insertApnDPC);
        contentValuesDPC.put(Carriers.NAME, insertNameDPC);
        contentValuesDPC.put(Carriers.CURRENT, insertCurrent);
        contentValuesDPC.put(Carriers.NUMERIC, insertNumeric);
        Log.d(TAG, "testDPCRecordsAccessControl Inserting DPC contentValues: " + contentValuesDPC);
        mContentResolver.insert(URI_DPC, contentValuesDPC);

        // Insert test OTHERS contentValues.
        ContentValues contentValuesOTHERS = new ContentValues();
        final String insertApnOTHERS = "exampleApnNameOTHERS";
        final String insertNameOTHERS = "exampleNameDPOTHERS";
        contentValuesOTHERS.put(Carriers.APN, insertApnOTHERS);
        contentValuesOTHERS.put(Carriers.NAME, insertNameOTHERS);
        contentValuesOTHERS.put(Carriers.CURRENT, insertCurrent);
        contentValuesOTHERS.put(Carriers.NUMERIC, insertNumeric);
        Log.d(TAG, "testDPCRecordsAccessControl Inserting OTHERS contentValues: "
                + contentValuesOTHERS);
        mContentResolver.insert(Carriers.CONTENT_URI, contentValuesOTHERS);

        // Verify that DPC records query control is correct.
        // The columns to get in table.
        final String[] testProjection =
                {
                        Carriers.APN,
                        Carriers.NAME,
                        Carriers.CURRENT,
                        Carriers.OWNED_BY,
                };
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = { insertNumeric };
        Log.d(TAG, "testDPCRecordsAccessControl query projection: " + testProjection
                + "\ntestDPCRecordsAccessControl selection: " + selection
                + "\ntestDPCRecordsAccessControl selectionArgs: " + selectionArgs);
        Cursor cursorGeneral = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        Cursor cursorDPC = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);
        Cursor cursorAll = mContentResolver.query(URI_ALL,
                testProjection, selection, selectionArgs, null);
        Cursor cursorPrioritized = mContentResolver.query(URI_PRIORITIZED,
                testProjection, selection, selectionArgs, null);
        // Verify that general query returns only OTHERS records.
        assertNotNull(cursorGeneral);
        assertEquals(1, cursorGeneral.getCount());
        cursorGeneral.moveToFirst();
        assertEquals(insertApnOTHERS, cursorGeneral.getString(0));
        assertEquals(insertNameOTHERS, cursorGeneral.getString(1));
        assertEquals(insertCurrent, cursorGeneral.getInt(2));
        assertEquals(Carriers.OWNED_BY_OTHERS, cursorGeneral.getInt(3));
        // Verify that DPC query returns only DPC records.
        assertNotNull(cursorDPC);
        assertEquals(1, cursorDPC.getCount());
        cursorDPC.moveToFirst();
        assertEquals(insertApnDPC, cursorDPC.getString(0));
        assertEquals(insertNameDPC, cursorDPC.getString(1));
        assertEquals(insertCurrent, cursorDPC.getInt(2));
        assertEquals(Carriers.OWNED_BY_DPC, cursorDPC.getInt(3));
        // Verify that ALL query returns all records.
        assertNotNull(cursorAll);
        assertEquals(2, cursorAll.getCount());
        // Verify that PRIORITIZED query returns DPC records when matching DPC records exist.
        assertNotNull(cursorPrioritized);
        assertEquals(1, cursorPrioritized.getCount());
        cursorPrioritized.moveToFirst();
        assertEquals(insertApnDPC, cursorPrioritized.getString(0));
        assertEquals(insertNameDPC, cursorPrioritized.getString(1));
        assertEquals(insertCurrent, cursorPrioritized.getInt(2));
        assertEquals(Carriers.OWNED_BY_DPC, cursorPrioritized.getInt(3));

        // Test URI_DPC updates only DPC records.
        ContentValues contentValuesDPCUpdate = new ContentValues();
        final String updateApnDPC = "exampleApnNameDPCUpdated";
        final String updateNameDPC = "exampleNameDPCUpdated";
        contentValuesDPCUpdate.put(Carriers.APN, updateApnDPC);
        contentValuesDPCUpdate.put(Carriers.NAME, updateNameDPC);
        final String where = Carriers.NUMERIC + "=?";
        String[] whereArgs = { insertNumeric };
        Log.d(TAG, "testDPCRecordsAccessControl update where: " + where
                + "\ntestDPCRecordsAccessControl update whereArgs: " + whereArgs);
        final int updateCount = mContentResolver.update(URI_DPC, contentValuesDPCUpdate,
                where, whereArgs);
        assertEquals(1, updateCount);
        Log.d(TAG, "testDPCRecordsAccessControl query projection after update: " + testProjection
                + "\ntestDPCRecordsAccessControl selection after update: " + selection
                + "\ntestDPCRecordsAccessControl selectionArgs after update: " + selectionArgs);
        Cursor cursorGeneralUpdate = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        Cursor cursorDPCUpdate = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);
        // Verify that OTHERS records are not updated.
        assertNotNull(cursorGeneralUpdate);
        assertEquals(1, cursorGeneralUpdate.getCount());
        cursorGeneralUpdate.moveToFirst();
        assertEquals(insertApnOTHERS, cursorGeneralUpdate.getString(0));
        assertEquals(insertNameOTHERS, cursorGeneralUpdate.getString(1));
        // Verify that DPC records are updated.
        assertNotNull(cursorDPCUpdate);
        assertEquals(1, cursorDPCUpdate.getCount());
        cursorDPCUpdate.moveToFirst();
        assertEquals(updateApnDPC, cursorDPCUpdate.getString(0));
        assertEquals(updateNameDPC, cursorDPCUpdate.getString(1));

        // Test URI_DPC deletes only DPC records.
        Log.d(TAG, "testDPCRecordsAccessControl delete selection: " + selection
                + "testDPCRecordsAccessControl delete selectionArgs: " + selectionArgs);
        int numRowsDeleted = mContentResolver.delete(URI_DPC, selection, selectionArgs);
        assertEquals(1, numRowsDeleted);
        Log.d(TAG, "testDPCRecordsAccessControl query projection after delete: " + testProjection
                + "\ntestDPCRecordsAccessControl selection after delete: " + selection
                + "\ntestDPCRecordsAccessControl selectionArgs after delete: " + selectionArgs);
        Cursor cursorGeneralDelete = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        Cursor cursorDPCDelete = mContentResolver.query(URI_DPC,
                testProjection, selection, selectionArgs, null);
        Cursor cursorAllDelete = mContentResolver.query(URI_ALL,
                testProjection, selection, selectionArgs, null);
        Cursor cursorPrioritizedDelete = mContentResolver.query(URI_PRIORITIZED,
                testProjection, selection, selectionArgs, null);
        // Verify that OTHERS records are not deleted.
        assertNotNull(cursorGeneralDelete);
        assertEquals(1, cursorGeneralDelete.getCount());
        cursorGeneralDelete.moveToFirst();
        assertEquals(insertApnOTHERS, cursorGeneralDelete.getString(0));
        assertEquals(insertNameOTHERS, cursorGeneralDelete.getString(1));
        assertEquals(insertCurrent, cursorGeneralDelete.getInt(2));
        assertEquals(Carriers.OWNED_BY_OTHERS, cursorGeneralDelete.getInt(3));
        // Verify that DPC records are deleted.
        assertNotNull(cursorDPCDelete);
        assertEquals(0, cursorDPCDelete.getCount());
        // Verify that ALL query returns all records.
        assertNotNull(cursorAllDelete);
        assertEquals(1, cursorAllDelete.getCount());
        // Verify that PRIORITIZED query returns OTHERS records when no matching DPC records exist.
        assertNotNull(cursorPrioritizedDelete);
        assertEquals(1, cursorPrioritizedDelete.getCount());
        cursorPrioritizedDelete.moveToFirst();
        assertEquals(insertApnOTHERS, cursorPrioritizedDelete.getString(0));
        assertEquals(insertNameOTHERS, cursorPrioritizedDelete.getString(1));
        assertEquals(insertCurrent, cursorPrioritizedDelete.getInt(2));
        assertEquals(Carriers.OWNED_BY_OTHERS, cursorPrioritizedDelete.getInt(3));

        // Delete OTHERS records.
        Log.d(TAG, "testDPCRecordsAccessControl delete selection: " + selection
                + "testDPCRecordsAccessControl delete selectionArgs: " + selectionArgs);
        numRowsDeleted = mContentResolver.delete(Carriers.CONTENT_URI,
                selection, selectionArgs);
        assertEquals(1, numRowsDeleted);

        // Verify that deleted values are gone.
        Cursor finalCursor = mContentResolver.query(Carriers.CONTENT_URI,
                testProjection, selection, selectionArgs, null);
        assertNotNull(finalCursor);
        assertEquals(0, finalCursor.getCount());
    }

    /**
     * Verify that SecurityException is thrown if URL_DPC, URL_ALL, URL_PRIORITIZED is accessed
     * from non-SYSTEM_UID.
     */
    @Test
    @SmallTest
    public void testAccessURLDPCThrowSecurityExceptionFromOtherUid() {
        mTelephonyProviderTestable.fakeCallingUid(Process.SYSTEM_UID + 1);

        final Uri URI_DPC = Uri.parse("content://telephony/carriers/dpc");
        final Uri URI_PRIORITIZED = Uri.parse("content://telephony/carriers/prioritized");
        final Uri URI_ALL = Uri.parse("content://telephony/carriers/all");

        // Test insert().
        ContentValues contentValuesDPC = new ContentValues();
        try {
            mContentResolver.insert(URI_DPC, contentValuesDPC);
            assertTrue("SecurityException should be thrown when URI_DPC is called from"
                    + " non-SYSTEM_UID", false);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test query().
        final String[] testProjection = {};
        final String selection = Carriers.NUMERIC + "=?";
        String[] selectionArgs = {"123"};
        try {
            mContentResolver.query(URI_DPC,
                    testProjection, selection, selectionArgs, null);
            assertTrue("SecurityException should be thrown when URI_DPC is called from"
                    + " non-SYSTEM_UID", false);

        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
        try {
            mContentResolver.query(URI_ALL,
                    testProjection, selection, selectionArgs, null);
            assertTrue("SecurityException should be thrown when URI_ALL is called from"
                    + " non-SYSTEM_UID", false);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
        try {
            mContentResolver.query(URI_PRIORITIZED,
                    testProjection, selection, selectionArgs, null);
            assertTrue("SecurityException should be thrown when URI_PRIORITIZED is called"
                    + " from non-SYSTEM_UID", false);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test update().
        ContentValues contentValuesDPCUpdate = new ContentValues();
        final String where = "";
        String[] whereArgs = {};
        try {
            mContentResolver.update(URI_DPC, contentValuesDPCUpdate, where, whereArgs);
            assertTrue("SecurityException should be thrown when URI_DPC is called"
                    + " from non-SYSTEM_UID", false);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }

        // Test delete().
        try {
            mContentResolver.delete(URI_DPC, selection, selectionArgs);
            assertTrue("SecurityException should be thrown when URI_DPC is called"
                    + " from non-SYSTEM_UID", false);
        } catch (SecurityException e) {
            // Should catch SecurityException.
        }
    }
}
