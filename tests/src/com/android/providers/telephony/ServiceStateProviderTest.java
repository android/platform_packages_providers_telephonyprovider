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

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Looper;
import android.support.test.InstrumentationRegistry;
import android.telephony.ServiceState;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;
import android.test.mock.MockContext;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.Log;

import com.android.internal.telephony.CarrierActionAgent;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.GsmCdmaPhone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.ServiceStateTracker;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import static android.app.job.JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS;
import static android.provider.Telephony.ServiceStateTable;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Tests for simple queries of ServiceStateProvider.
 *
 * Build, install and run the tests by running the commands below:
 *     runtest --path <dir or file>
 *     runtest --path <dir or file> --test-method <testMethodName>
 *     e.g.)
 *         runtest --path tests/src/com/android/providers/telephony/ServiceStateProviderTest.java \
 *                 --test-method testGetServiceState
 */
public class ServiceStateProviderTest {
    private static final String TAG = "ServiceStateProviderTest";

    private Context mContext;
    private MockContentResolver mContentResolver;

    public static final Uri CONTENT_URI = Uri.parse("content://service-state/");
    public static final Uri SUBID_1_CONTENT_URI = Uri.parse("content://service-state/1");

    private final String[] testProjection =
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

    private ServiceState testServiceState;
    private ServiceState testServiceStateForSubId1;

    @Before
    public void setUp() throws Exception {
        mContext = mock(Context.class);
        mContentResolver = new MockContentResolver(mContext);

        testServiceState = new ServiceState();
        testServiceState.setStateOutOfService();
        testServiceStateForSubId1 = new ServiceState();
        testServiceStateForSubId1.setStateOff();

        // Mock out the actual phone state
        ServiceStateProvider provider = new ServiceStateProvider() {
            @Override
            public ServiceState getServiceState(int subId) {
                if (subId == 1) {
                    return testServiceStateForSubId1;
                } else {
                    return testServiceState;
                }
            }
            @Override
            public int getDefaultSubId() {
                return 0;
            }
        };
        ProviderInfo providerInfo = new ProviderInfo();
        providerInfo.authority = "service-state";
        provider.attachInfoForTesting(mContext, providerInfo);
        mContentResolver.addProvider("service-state", provider);
        doReturn(mContentResolver).when(mContext).getContentResolver();
    }

    /**
     * Test querying the service state for a given subId
     */
    @Test
    @SmallTest
    public void testGetServiceStateForSubId() {
        // Verify that when calling with a specific subId the correct ServiceState is returned
        // In this case the subId is set to 1 and the expected service state is
        // testServiceStateForSubId1
        Cursor cursor = mContentResolver.query(SUBID_1_CONTENT_URI, testProjection, "", null, null);
        assertNotNull(cursor);
        cursor.moveToFirst();

        final ServiceState ss = testServiceStateForSubId1;
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

    /**
     * Test querying the service state
     */
    @Test
    @SmallTest
    public void testGetServiceState() {
        // Verify that when calling without specifying subId the correct ServiceState is returned
        // In this case the default subId is set to 0 and the expected service state is
        // testServiceState
        Cursor cursor = mContentResolver.query(CONTENT_URI, testProjection, "", null, null);
        assertNotNull(cursor);
        cursor.moveToFirst();

        final ServiceState ss = testServiceState;
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
