/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License.
 */
package com.android.providers.telephony;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Telephony;
import android.telephony.SubscriptionManager;
import android.text.TextUtils;
import android.util.Log;

import java.util.List;
import java.util.ArrayList;
import com.android.providers.telephony.TelephonyProvider;
import static android.provider.Telephony.Carriers.*;

/**
 * A subclass of TelephonyProvider used for testing on an in-memory database
 */
public class TelephonyProviderTestable extends TelephonyProvider {
    private static final String TAG = "TelephonyProviderTestable";

    private InMemoryTelephonyProviderDbHelper mDbHelper;

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate called: mDbHelper = new InMemoryTelephonyProviderDbHelper()");
        mDbHelper = new InMemoryTelephonyProviderDbHelper();
        return true;
    }

    // close mDbHelper database object
    protected void closeDatabase() {
        mDbHelper.close();
    }

    @Override
    SQLiteDatabase getReadableDatabase() {
        Log.d(TAG, "getReadableDatabase called");
        return mDbHelper.getReadableDatabase();
    }

    @Override
    SQLiteDatabase getWritableDatabase() {
        Log.d(TAG, "getWritableDatabase called");
        return mDbHelper.getWritableDatabase();
    }

    @Override
    void initDatabaseWithDatabaseHelper(SQLiteDatabase db) {
        Log.d(TAG, "initDatabaseWithDatabaseHelper called; doing nothing");
    }

    @Override
    boolean needApnDbUpdate() {
        Log.d(TAG, "needApnDbUpdate called; returning false");
        return false;
    }

    /**
     * An in memory DB for TelephonyProviderTestable to use
     */
    public static class InMemoryTelephonyProviderDbHelper extends SQLiteOpenHelper {

        private static final List<String> CARRIERS_UNIQUE_FIELDS = new ArrayList<String>();

        // TODO CARRIERS_UNIQUE_FIELDS initialization is copied from TelephonyProvider, find a way
        // to reuse the existing init
        static {
            // Columns not included in UNIQUE constraint: name, current, edited, user, server, password,
            // authtype, type, protocol, roaming_protocol, sub_id, modem_cognitive, max_conns,
            // wait_time, max_conns_time, mtu, bearer_bitmask, user_visible
            CARRIERS_UNIQUE_FIELDS.add(NUMERIC);
            CARRIERS_UNIQUE_FIELDS.add(MCC);
            CARRIERS_UNIQUE_FIELDS.add(MNC);
            CARRIERS_UNIQUE_FIELDS.add(APN);
            CARRIERS_UNIQUE_FIELDS.add(PROXY);
            CARRIERS_UNIQUE_FIELDS.add(PORT);
            CARRIERS_UNIQUE_FIELDS.add(MMSPROXY);
            CARRIERS_UNIQUE_FIELDS.add(MMSPORT);
            CARRIERS_UNIQUE_FIELDS.add(MMSC);
            CARRIERS_UNIQUE_FIELDS.add(CARRIER_ENABLED);
            CARRIERS_UNIQUE_FIELDS.add(BEARER);
            CARRIERS_UNIQUE_FIELDS.add(MVNO_TYPE);
            CARRIERS_UNIQUE_FIELDS.add(MVNO_MATCH_DATA);
            CARRIERS_UNIQUE_FIELDS.add(PROFILE_ID);
        }

        public InMemoryTelephonyProviderDbHelper() {
            super(null,      // no context is needed for in-memory db
                    null,    // db file name is null for in-memory db
                    null,    // CursorFactory is null by default
                    1);      // db version is no-op for tests
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper creating in-memory database");
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper onCreate creating the carriers table");
            String tableName = "carriers";

            // TODO table creation is copied from createCarriersTable in
            // TelephonyManager.DatabaseHelper; find a way to reuse existing code

            // Set up the carriers table
            db.execSQL("CREATE TABLE carriers" +
                    "(_id INTEGER PRIMARY KEY," +
                    NAME + " TEXT DEFAULT ''," +
                    NUMERIC + " TEXT DEFAULT ''," +
                    MCC + " TEXT DEFAULT ''," +
                    MNC + " TEXT DEFAULT ''," +
                    APN + " TEXT DEFAULT ''," +
                    USER + " TEXT DEFAULT ''," +
                    SERVER + " TEXT DEFAULT ''," +
                    PASSWORD + " TEXT DEFAULT ''," +
                    PROXY + " TEXT DEFAULT ''," +
                    PORT + " TEXT DEFAULT ''," +
                    MMSPROXY + " TEXT DEFAULT ''," +
                    MMSPORT + " TEXT DEFAULT ''," +
                    MMSC + " TEXT DEFAULT ''," +
                    AUTH_TYPE + " INTEGER DEFAULT -1," +
                    TYPE + " TEXT DEFAULT ''," +
                    CURRENT + " INTEGER," +
                    PROTOCOL + " TEXT DEFAULT 'IP'," +
                    ROAMING_PROTOCOL + " TEXT DEFAULT 'IP'," +
                    CARRIER_ENABLED + " BOOLEAN DEFAULT 1," +
                    BEARER + " INTEGER DEFAULT 0," +
                    BEARER_BITMASK + " INTEGER DEFAULT 0," +
                    MVNO_TYPE + " TEXT DEFAULT ''," +
                    MVNO_MATCH_DATA + " TEXT DEFAULT ''," +
                    SUBSCRIPTION_ID + " INTEGER DEFAULT "
                    + SubscriptionManager.INVALID_SUBSCRIPTION_ID + "," +
                    PROFILE_ID + " INTEGER DEFAULT 0," +
                    MODEM_COGNITIVE + " BOOLEAN DEFAULT 0," +
                    MAX_CONNS + " INTEGER DEFAULT 0," +
                    WAIT_TIME + " INTEGER DEFAULT 0," +
                    MAX_CONNS_TIME + " INTEGER DEFAULT 0," +
                    MTU + " INTEGER DEFAULT 0," +
                    EDITED + " INTEGER DEFAULT " + UNEDITED + "," +
                    USER_VISIBLE + " BOOLEAN DEFAULT 1," +
                    // Uniqueness collisions are used to trigger merge code so if a field is listed
                    // here it means we will accept both (user edited + new apn_conf definition)
                    // Columns not included in UNIQUE constraint: name, current, edited,
                    // user, server, password, authtype, type, protocol, roaming_protocol, sub_id,
                    // modem_cognitive, max_conns, wait_time, max_conns_time, mtu, bearer_bitmask,
                    // user_visible
                    "UNIQUE (" + TextUtils.join(", ", CARRIERS_UNIQUE_FIELDS) + "));");

            // set up the siminfo table
            db.execSQL("CREATE TABLE siminfo ("
                    + SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID
                        + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + SubscriptionManager.ICC_ID + " TEXT NOT NULL,"
                    + SubscriptionManager.SIM_SLOT_INDEX + " INTEGER DEFAULT "
                        + SubscriptionManager.SIM_NOT_INSERTED + ","
                    + SubscriptionManager.DISPLAY_NAME + " TEXT,"
                    + SubscriptionManager.CARRIER_NAME + " TEXT,"
                    + SubscriptionManager.NAME_SOURCE + " INTEGER DEFAULT "
                        + SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE + ","
                    + SubscriptionManager.COLOR + " INTEGER DEFAULT "
                        + SubscriptionManager.COLOR_DEFAULT + ","
                    + SubscriptionManager.NUMBER + " TEXT,"
                    + SubscriptionManager.DISPLAY_NUMBER_FORMAT + " INTEGER NOT NULL DEFAULT "
                        + SubscriptionManager.DISPLAY_NUMBER_DEFAULT + ","
                    + SubscriptionManager.DATA_ROAMING + " INTEGER DEFAULT "
                        + SubscriptionManager.DATA_ROAMING_DEFAULT + ","
                    + SubscriptionManager.MCC + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.MNC + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_EXTREME_THREAT_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_SEVERE_THREAT_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_AMBER_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_EMERGENCY_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_ALERT_SOUND_DURATION + " INTEGER DEFAULT 4,"
                    + SubscriptionManager.CB_ALERT_REMINDER_INTERVAL + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_ALERT_VIBRATE + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_ALERT_SPEECH + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_ETWS_TEST_ALERT + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_CHANNEL_50_ALERT + " INTEGER DEFAULT 1,"
                    + SubscriptionManager.CB_CMAS_TEST_ALERT + " INTEGER DEFAULT 0,"
                    + SubscriptionManager.CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1"
                    + ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "InMemoryTelephonyProviderDbHelper onUpgrade doing nothing");
            return;
        }
    }
}
