/* //device/content/providers/telephony/TelephonyProvider.java
**
** Copyright 2006, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.android.providers.telephony;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MatrixCursor.RowBuilder;
import android.net.Uri;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import com.android.internal.telephony.SubscriptionController;

import java.lang.NumberFormatException;

import static android.provider.Telephony.ServiceStateTable;

public class ServiceStateProvider extends ContentProvider {
    public static final String AUTHORITY = "service-state";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    private TelephonyManager mTelephonyManager;
    private SubscriptionController mSubscriptionController;

    private static final String[] sColumns = {
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
    @Override
    public boolean onCreate() {
        return true;
    }
    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new RuntimeException("Not supported");
    }
    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new RuntimeException("Not supported");
    }
    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new RuntimeException("Not supported");
    }
    @Override
    public String getType(Uri uri) {
        if (ServiceStateTable.CONTENT_URI.equals(uri)) {
            return ServiceStateTable.CONTENT_TYPE;
        }
        throw new IllegalArgumentException("Invalid URI: " + uri);
    }

    //@VisibleForTesting
    public ServiceState getServiceState(int subId) {
        mTelephonyManager = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        Log.d("ServiceStateProvider", "onCreate: getContext()=" + getContext());
        Log.d("ServiceStateProvider", "onCreate: mTelephonyManager=" + mTelephonyManager);
        // Get the service state
        ServiceState ss = mTelephonyManager.getServiceStateForSubscriber(subId);
        //ServiceState ss = null;
        /*
        if (ss == null) {
            Log.d("ServiceStateProvider", "mTelephonyManager.getServiceState() is null");
            int phoneId = mSubscriptionController.getPhoneId(subId);
            Log.d("ServiceStateProvider", "phoneId=" + phoneId);
            Phone phone = PhoneFactory.getPhone(phoneId);
            if (phone == null) {
                Log.d("ServiceStateProvider", "phone is null, returning null");
                return null;
            } else {
                ss = phone.getServiceState();
            }
        }
        */
        if (ss == null) {
            Log.d("ServiceStateProvider", "PhoneFactory.getDefaultPhone().getServiceState() is null");
        }
        return ss;
    }

    //@VisibleForTesting
    public int getDefaultSubId() {
        mSubscriptionController = SubscriptionController.getInstance();
        return mSubscriptionController.getDefaultSubId();
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (uri.isPathPrefixMatch(ServiceStateTable.CONTENT_URI)) {
            // Parse the subid
            int subId = 0;
            try {
                subId = Integer.parseInt(uri.getLastPathSegment());
            } catch (NumberFormatException e) {
                Log.d("ServiceStateProvider", "no subId provided, using default.");
                subId = getDefaultSubId();
            }
            Log.d("ServiceStateProvider", "subId=" + subId);

            // Get the service state
            ServiceState ss = getServiceState(subId);
            if (ss == null) {
                Log.d("ServiceStateProvider", "returning null");
                return null;
            }

            // Build the result
            final int voice_reg_state = ss.getVoiceRegState();
            final int data_reg_state = ss.getDataRegState();
            final String voice_operator_alpha_long = ss.getVoiceOperatorAlphaLong();
            final String voice_operator_alpha_short = ss.getVoiceOperatorAlphaShort();
            final String voice_operator_numeric = ss.getVoiceOperatorNumeric();
            final String data_operator_alpha_long = ss.getDataOperatorAlphaLong();
            final String data_operator_alpha_short = ss.getDataOperatorAlphaShort();
            final String data_operator_numeric = ss.getDataOperatorNumeric();
            final int is_manual_network_selection = (ss.getIsManualSelection()) ? 1 : 0;
            final int ril_voice_radio_technology = ss.getRilVoiceRadioTechnology();
            final int ril_data_radio_technology = ss.getRilDataRadioTechnology();
            final int css_indicator = ss.getCssIndicator();
            final int network_id = ss.getNetworkId();
            final int system_id = ss.getSystemId();
            final int cdma_roaming_indicator = ss.getCdmaRoamingIndicator();
            final int cdma_default_roaming_indicator = ss.getCdmaDefaultRoamingIndicator();
            final int cdma_eri_icon_index = ss.getCdmaEriIconIndex();
            final int cdma_eri_icon_mode = ss.getCdmaEriIconMode();
            final int is_emergency_only = (ss.isEmergencyOnly()) ? 1 : 0;
            final int is_data_roaming_from_registration = (ss.getDataRoamingFromRegistration()) ? 1 : 0;
            final int is_using_carrier_aggregation = (ss.isUsingCarrierAggregation()) ? 1 : 0;

            return buildSingleRowResult(projection, sColumns, new Object[] {
                        voice_reg_state,
                        data_reg_state,
                        voice_operator_alpha_long,
                        voice_operator_alpha_short,
                        voice_operator_numeric,
                        data_operator_alpha_long,
                        data_operator_alpha_short,
                        data_operator_numeric,
                        is_manual_network_selection,
                        ril_voice_radio_technology,
                        ril_data_radio_technology,
                        css_indicator,
                        network_id,
                        system_id,
                        cdma_roaming_indicator,
                        cdma_default_roaming_indicator,
                        cdma_eri_icon_index,
                        cdma_eri_icon_mode,
                        is_emergency_only,
                        is_data_roaming_from_registration,
                        is_using_carrier_aggregation,
            });
        }
            throw new IllegalArgumentException("Invalid URI: " + uri);
    }

    static Cursor buildSingleRowResult(String[] projection, String[] availableColumns,
            Object[] data) {
        if (projection == null) {
            projection = availableColumns;
        }
        final MatrixCursor c = new MatrixCursor(projection, 1);
        final RowBuilder row = c.newRow();
        for (int i = 0; i < c.getColumnCount(); i++) {
            final String columnName = c.getColumnName(i);
            boolean found = false;
            for (int j = 0; j < availableColumns.length; j++) {
                if (availableColumns[j].equals(columnName)) {
                    row.add(data[j]);
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("Invalid column " + projection[i]);
            }
        }
        return c;
    }
    // When something changes, call this.  notifyChange can be called from anywhere --
    // doesn't have to be called by the provider itself.
    // TODO establish that the well formed notify URI should be service-state/#/field
    static void notifyChange(Context context) {
        context.getContentResolver().notifyChange(ServiceStateTable.CONTENT_URI,
                /* observer= */ null, /* syncToNetwork= */ false);
    }
}
