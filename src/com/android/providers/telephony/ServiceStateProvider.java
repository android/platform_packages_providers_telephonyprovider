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

import com.android.internal.telephony.PhoneFactory;

public class ServiceStateProvider extends ContentProvider {
    // ===================================
    // Move this part to your public API.
    public static final String AUTHORITY = "com.android.telephony";
    public static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    public static final class ServiceStateTable { // Or whatever name of the "table".
        public static final Uri CONTENT_URI = Uri.withAppendedPath(AUTHORITY_URI, "service_state");
        /**
         * The MIME-type of {@link #CONTENT_URI}.
         */
        public static final String CONTENT_TYPE =
                "vnd.android.cursor.dir/service_state";

        public static final String VOICE_REG_STATE = "voice_reg_state";
        public static final String DATA_REG_STATE = "data_reg_state";
        public static final String VOICE_OPERATOR_ALPHA_LONG = "voice_operator_alpha_long";
        public static final String VOICE_OPERATOR_ALPHA_SHORT = "voice_operator_alpha_short";
        public static final String VOICE_OPERATOR_NUMERIC = "voice_operator_numeric";
        public static final String DATA_OPERATOR_ALPHA_LONG = "data_operator_alpha_long";
        public static final String DATA_OPERATOR_ALPHA_SHORT = "data_operator_alpha_short";
        public static final String DATA_OPERATOR_NUMERIC = "data_operator_numeric";
        public static final String IS_MANUAL_NETWORK_SELECTION = "is_manual_network_selection";
        public static final String RIL_VOICE_RADIO_TECHNOLOGY = "ril_voice_radio_technology";
        public static final String RIL_DATA_RADIO_TECHNOLOGY = "ril_data_radio_technology";
        public static final String CSS_INDICATOR = "css_indicator";
        public static final String NETWORK_ID = "network_id";
        public static final String SYSTEM_ID = "system_id";
        public static final String CDMA_ROAMING_INDICATOR = "cdma_roaming_indicator";
        public static final String CDMA_DEFAULT_ROAMING_INDICATOR = "cdma_default_roaming_indicator";
        public static final String CDMA_ERI_ICON_INDEX = "cdma_eri_icon_index";
        public static final String CDMA_ERI_ICON_MODE = "cdma_eri_icon_mode";
        public static final String IS_EMERGENCY_ONLY = "is_emergency_only";
        public static final String IS_DATA_ROAMING_FROM_REGISTRATION = "is_data_roaming_from_registration";
        public static final String IS_USING_CARRIER_AGGREGATION = "is_using_carrier_aggregation";
    }

    // ===================================
    // Here's the actual implementation.
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
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        // Uri is the URI requested, which should be ServiceStateTable.CONTENT_URI.
        // projection is the columns requested.  If null, it should return all columns (sColumns)
        // by convention.
        // Ignore the rest of the argument.
        if (ServiceStateTable.CONTENT_URI.equals(uri)) {
            ServiceState ss = PhoneFactory.getDefaultPhone().getServiceState();
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

    // copied from elsewhere...
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
    static void notifyChange(Context context) {
        context.getContentResolver().notifyChange(ServiceStateTable.CONTENT_URI,
                /* observer= */ null, /* syncToNetwork= */ false);
    }
}
