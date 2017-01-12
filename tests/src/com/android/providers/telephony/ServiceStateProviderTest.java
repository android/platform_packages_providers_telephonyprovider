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
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.net.Uri;
import android.os.Build;
import android.os.FileUtils;
import android.os.Handler;
import android.os.Message;
import android.provider.Telephony.Carriers;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.test.AndroidTestCase;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.text.TextUtils;
import android.util.Log;


import com.android.internal.telephony.PhoneFactory;
import com.android.providers.telephony.ServiceStateProvider;

import junit.framework.TestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static com.android.providers.telephony.ServiceStateProvider.ServiceStateTable;


/**
 * Tests for testing CRUD operations of ServiceStateProvider.
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/ServiceStateProviderTest.java \
 *                 --test-method testGetServiceState
 */
public class ServiceStateProviderTest extends TestCase {
    private static final String TAG = "ServiceStateProviderTest";

    private MockContextWithProvider mContext;
    private MockContentResolver mContentResolver;
    private ServiceStateProvider mServiceStateProvider;

    public static final String AUTHORITY = "com.android.telephony";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);
    public static final Uri SERVICE_STATE_CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "service_state");

    /**
     * This is used to give the TelephonyProviderTest a mocked context which takes a
     * TelephonyProvider and attaches it to the ContentResolver with sim-state authority.
     * The mocked context also gives WRITE_APN_SETTINGS permissions
     */
    private class MockContextWithProvider extends MockContext {
        private final MockContentResolver mResolver;

        public MockContextWithProvider(ServiceStateProvider serviceStateProvider) {
            mResolver = new MockContentResolver();

            // Add authority= to given serviceStateProvider
            ProviderInfo providerInfo = new ProviderInfo();
            providerInfo.authority = AUTHORITY;

            // Add context to given provider
            serviceStateProvider.attachInfoForTesting(this, providerInfo);
            Log.d(TAG, "MockContextWithProvider: serviceStateProvider.getContext(): "
                    + serviceStateProvider.getContext());

            // Add given serviceStateProvider to mResolver with authority so that
            // mResolver can send queries to mTelephonyProvider
            mResolver.addProvider(AUTHORITY, serviceStateProvider);
            Log.d(TAG, "MockContextWithProvider: Add serviceStateProvider to mResolver");
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
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mServiceStateProvider = new ServiceStateProvider();
        mContext = new MockContextWithProvider(mServiceStateProvider);
        mContentResolver = (MockContentResolver) mContext.getContentResolver();
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Test querying the service state
     */
    @Test
    @SmallTest
    public void testGetServiceState() {
        // test query
        final String[] testProjection =
        {
            ServiceStateTable.VOICE_REG_STATE,
            ServiceStateTable.DATA_REG_STATE,
            ServiceStateTable.VOICE_OPERATOR_ALPHA_LONG,
            ServiceStateTable.VOICE_OPERATOR_ALPHA_SHORT,
            ServiceStateTable.VOICE_OPERATOR_NUMERIC,
            ServiceStateTable.DATA_OPERATOR_ALPHA_LONG,
            ServiceStateTable.DATA_OPERATOR_ALPHA_SHORT,
            ServiceStateTable.DATA_OPERATOR_NUMERIC,
            ServiceStateTable.IS_MANUAL_NETWORK_SELECTION,
            ServiceStateTable.RIL_VOICE_RADIO_TECHNOLOGY,
            ServiceStateTable.RIL_DATA_RADIO_TECHNOLOGY,
            ServiceStateTable.CSS_INDICATOR,
            ServiceStateTable.NETWORK_ID,
            ServiceStateTable.SYSTEM_ID,
            ServiceStateTable.CDMA_ROAMING_INDICATOR,
            ServiceStateTable.CDMA_DEFAULT_ROAMING_INDICATOR,
            ServiceStateTable.CDMA_ERI_ICON_INDEX,
            ServiceStateTable.CDMA_ERI_ICON_MODE,
            ServiceStateTable.IS_EMERGENCY_ONLY,
            ServiceStateTable.IS_DATA_ROAMING_FROM_REGISTRATION,
            ServiceStateTable.IS_USING_CARRIER_AGGREGATION,
        };
        Log.d(TAG, "testGetServiceState: query projection: " + testProjection);
        Cursor cursor = mContentResolver.query(SERVICE_STATE_CONTENT_URI,
                testProjection, "", null, null);
        assertNotNull(cursor);
        cursor.moveToFirst();

        ServiceState ss = PhoneFactory.getDefaultPhone().getServiceState();
        final int voiceRegState = ss.getVoiceRegState();
        final int dataRegState = ss.getDataRegState();
        final String voiceOperatorAlphaLong = ss.getVoiceOperatorAlphaLong();
        final String voiceOperatorAlphaShort = ss.getVoiceOperatorAlphaShort();
        final String voiceOperatorNumeric = ss.getVoiceOperatorNumeric();
        final String dataOperatorAlphaLong = ss.getDataOperatorAlphaLong();
        final String dataOperatorAlphaShort = ss.getDataOperatorAlphaShort();
        final String dataOperatorNumeric = ss.getDataOperatorNumeric();
        final int isManualNetworkSelection = (ss.getIsManualSelection()) ? 1 : 0;
        final int rilVoiceRadioTechnology = ss.getRilVoiceRadioTechnology();
        final int rilDataRadioTechnology = ss.getRilDataRadioTechnology();
        final int cssIndicator = ss.getCssIndicator();
        final int networkId = ss.getNetworkId();
        final int systemId = ss.getSystemId();
        final int cdmaRoamingIndicator = ss.getCdmaRoamingIndicator();
        final int cdmaDefaultRoamingIndicator = ss.getCdmaDefaultRoamingIndicator();
        final int cdmaEriIconIndex = ss.getCdmaEriIconIndex();
        final int cdmaEriIconMode = ss.getCdmaEriIconMode();
        final int isEmergencyOnly = (ss.isEmergencyOnly()) ? 1 : 0;
        final int isDataRoamingFromRegistration = (ss.getDataRoamingFromRegistration()) ? 1 : 0;
        final int isUsingCarrierAggregation = (ss.isUsingCarrierAggregation()) ? 1 : 0;

        assertEquals(voiceRegState, cursor.getInt(0));
        assertEquals(dataRegState, cursor.getInt(1));
        assertEquals(voiceOperatorAlphaLong, cursor.getString(2));
        assertEquals(voiceOperatorAlphaShort, cursor.getString(3));
        assertEquals(voiceOperatorNumeric, cursor.getString(4));
        assertEquals(dataOperatorAlphaLong, cursor.getString(5));
        assertEquals(dataOperatorAlphaShort, cursor.getString(6));
        assertEquals(dataOperatorNumeric, cursor.getString(7));
        assertEquals(isManualNetworkSelection, cursor.getInt(8));
        assertEquals(rilVoiceRadioTechnology, cursor.getInt(9));
        assertEquals(rilDataRadioTechnology, cursor.getInt(10));
        assertEquals(cssIndicator, cursor.getInt(11));
        assertEquals(networkId, cursor.getInt(12));
        assertEquals(systemId, cursor.getInt(13));
        assertEquals(cdmaRoamingIndicator, cursor.getInt(14));
        assertEquals(cdmaDefaultRoamingIndicator, cursor.getInt(15));
        assertEquals(cdmaEriIconIndex, cursor.getInt(16));
        assertEquals(cdmaEriIconMode, cursor.getInt(17));
        assertEquals(isEmergencyOnly, cursor.getInt(18));
        assertEquals(isDataRoamingFromRegistration, cursor.getInt(19));
        assertEquals(isUsingCarrierAggregation, cursor.getInt(20));
    }
}
