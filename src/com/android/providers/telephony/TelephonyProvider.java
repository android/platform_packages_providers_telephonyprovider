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

import static android.provider.Telephony.Carriers.APN;
import static android.provider.Telephony.Carriers.APN_SET_ID;
import static android.provider.Telephony.Carriers.CARRIER_DELETED;
import static android.provider.Telephony.Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML;
import static android.provider.Telephony.Carriers.CARRIER_EDITED;
import static android.provider.Telephony.Carriers.CARRIER_ID;
import static android.provider.Telephony.Carriers.CONTENT_URI;
import static android.provider.Telephony.Carriers.CURRENT;
import static android.provider.Telephony.Carriers.DEFAULT_SORT_ORDER;
import static android.provider.Telephony.Carriers.EDITED;
import static android.provider.Telephony.Carriers.MMSC;
import static android.provider.Telephony.Carriers.MMSPORT;
import static android.provider.Telephony.Carriers.MMSPROXY;
import static android.provider.Telephony.Carriers.MVNO_MATCH_DATA;
import static android.provider.Telephony.Carriers.MVNO_TYPE;
import static android.provider.Telephony.Carriers.NO_SET_SET;
import static android.provider.Telephony.Carriers.NUMERIC;
import static android.provider.Telephony.Carriers.OWNED_BY;
import static android.provider.Telephony.Carriers.OWNED_BY_DPC;
import static android.provider.Telephony.Carriers.OWNED_BY_OTHERS;
import static android.provider.Telephony.Carriers.TYPE;
import static android.provider.Telephony.Carriers.USER_DELETED;
import static android.provider.Telephony.Carriers.USER_DELETED_BUT_PRESENT_IN_XML;
import static android.provider.Telephony.Carriers.USER_EDITABLE;
import static android.provider.Telephony.Carriers.USER_EDITED;
import static android.provider.Telephony.Carriers._ID;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.UriMatcher;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.telephony.data.ApnSetting;
import android.text.TextUtils;
import android.util.Pair;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.IApnSourceService;
import com.android.internal.telephony.PhoneConstants;
import com.android.internal.telephony.dataconnection.ApnSettingUtils;
import com.android.internal.telephony.uicc.IccRecords;
import com.android.internal.telephony.uicc.UiccController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TelephonyProvider extends ContentProvider
{

    private static final int URL_UNKNOWN = 0;
    private static final int URL_TELEPHONY = 1;
    private static final int URL_CURRENT = 2;
    private static final int URL_ID = 3;
    private static final int URL_RESTOREAPN = 4;
    private static final int URL_PREFERAPN = 5;
    private static final int URL_PREFERAPN_NO_UPDATE = 6;
    private static final int URL_SIMINFO = 7;
    private static final int URL_TELEPHONY_USING_SUBID = 8;
    private static final int URL_CURRENT_USING_SUBID = 9;
    private static final int URL_RESTOREAPN_USING_SUBID = 10;
    private static final int URL_PREFERAPN_USING_SUBID = 11;
    private static final int URL_PREFERAPN_NO_UPDATE_USING_SUBID = 12;
    private static final int URL_SIMINFO_USING_SUBID = 13;
    private static final int URL_UPDATE_DB = 14;
    private static final int URL_DELETE = 15;
    private static final int URL_DPC = 16;
    private static final int URL_DPC_ID = 17;
    private static final int URL_FILTERED = 18;
    private static final int URL_FILTERED_ID = 19;
    private static final int URL_ENFORCE_MANAGED = 20;
    private static final int URL_PREFERAPNSET = 21;
    private static final int URL_PREFERAPNSET_USING_SUBID = 22;
    private static final int URL_SIM_APN_LIST = 23;
    private static final int URL_SIM_APN_LIST_ID = 24;

    private static final String PREF_FILE_APN = "preferred-apn";
    private static final String COLUMN_APN_ID = "apn_id";
    private static final String EXPLICIT_SET_CALLED = "explicit_set_called";

    private static final String PREF_FILE_FULL_APN = "preferred-full-apn";
    private static final String DB_VERSION_KEY = "version";

    private static final String BUILD_ID_FILE = "build-id";
    private static final String RO_BUILD_ID = "ro_build_id";

    private static final String ENFORCED_FILE = "dpc-apn-enforced";
    private static final String ENFORCED_KEY = "enforced";

    private static final UriMatcher s_urlMatcher = new UriMatcher(UriMatcher.NO_MATCH);

    private static final ContentValues s_currentNullMap;
    private static final ContentValues s_currentSetMap;

    private static final String IS_USER_EDITED = EDITED + "=" + USER_EDITED;
    private static final String IS_NOT_USER_EDITED = EDITED + "!=" + USER_EDITED;
    private static final String IS_NOT_USER_DELETED = EDITED + "!=" + USER_DELETED;
    private static final String IS_NOT_USER_DELETED_BUT_PRESENT_IN_XML =
            EDITED + "!=" + USER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_CARRIER_EDITED = EDITED + "=" + CARRIER_EDITED;
    private static final String IS_NOT_CARRIER_EDITED = EDITED + "!=" + CARRIER_EDITED;
    private static final String IS_NOT_CARRIER_DELETED = EDITED + "!=" + CARRIER_DELETED;
    private static final String IS_NOT_CARRIER_DELETED_BUT_PRESENT_IN_XML =
            EDITED + "!=" + CARRIER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_OWNED_BY_DPC = OWNED_BY + "=" + OWNED_BY_DPC;
    private static final String IS_NOT_OWNED_BY_DPC = OWNED_BY + "!=" + OWNED_BY_DPC;

    private static final int INVALID_APN_ID = -1;

    protected final Object mLock = new Object();
    @GuardedBy("mLock")
    private IApnSourceService mIApnSourceService;
    private Injector mInjector;

    private boolean mManagedApnEnforced;

    static {
        s_urlMatcher.addURI("telephony", "carriers", URL_TELEPHONY);
        s_urlMatcher.addURI("telephony", "carriers/current", URL_CURRENT);
        s_urlMatcher.addURI("telephony", "carriers/#", URL_ID);
        s_urlMatcher.addURI("telephony", "carriers/restore", URL_RESTOREAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn", URL_PREFERAPN);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update", URL_PREFERAPN_NO_UPDATE);
        s_urlMatcher.addURI("telephony", "carriers/preferapnset", URL_PREFERAPNSET);

        s_urlMatcher.addURI("telephony", "siminfo", URL_SIMINFO);
        s_urlMatcher.addURI("telephony", "siminfo/#", URL_SIMINFO_USING_SUBID);

        s_urlMatcher.addURI("telephony", "carriers/subId/*", URL_TELEPHONY_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/current/subId/*", URL_CURRENT_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/restore/subId/*", URL_RESTOREAPN_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapn/subId/*", URL_PREFERAPN_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapn_no_update/subId/*",
                URL_PREFERAPN_NO_UPDATE_USING_SUBID);
        s_urlMatcher.addURI("telephony", "carriers/preferapnset/subId/*",
                URL_PREFERAPNSET_USING_SUBID);

        s_urlMatcher.addURI("telephony", "carriers/update_db", URL_UPDATE_DB);
        s_urlMatcher.addURI("telephony", "carriers/delete", URL_DELETE);

        // Only called by DevicePolicyManager to manipulate DPC records.
        s_urlMatcher.addURI("telephony", "carriers/dpc", URL_DPC);
        // Only called by DevicePolicyManager to manipulate a DPC record with certain _ID.
        s_urlMatcher.addURI("telephony", "carriers/dpc/#", URL_DPC_ID);
        // Only called by Settings app, DcTracker and other telephony components to get APN list
        // according to whether DPC records are enforced.
        s_urlMatcher.addURI("telephony", "carriers/filtered", URL_FILTERED);
        // Only called by Settings app, DcTracker and other telephony components to get a
        // single APN according to whether DPC records are enforced.
        s_urlMatcher.addURI("telephony", "carriers/filtered/#", URL_FILTERED_ID);
        // Only Called by DevicePolicyManager to enforce DPC records.
        s_urlMatcher.addURI("telephony", "carriers/enforce_managed", URL_ENFORCE_MANAGED);
        s_urlMatcher.addURI("telephony", "carriers/sim_apn_list", URL_SIM_APN_LIST);
        s_urlMatcher.addURI("telephony", "carriers/sim_apn_list/#", URL_SIM_APN_LIST_ID);

        s_currentNullMap = new ContentValues(1);
        s_currentNullMap.put(CURRENT, "0");

        s_currentSetMap = new ContentValues(1);
        s_currentSetMap.put(CURRENT, "1");
    }

    /**
     * Unit test will subclass it to inject mocks.
     */
    @VisibleForTesting
    static class Injector {
        int binderGetCallingUid() {
            return Binder.getCallingUid();
        }
    }

    public TelephonyProvider() {
        this(new Injector());
    }

    @VisibleForTesting
    public TelephonyProvider(Injector injector) {
        mInjector = injector;
    }

    /**
     * These methods can be overridden in a subclass for testing TelephonyProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mOpenHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mOpenHelper.getWritableDatabase();
    }
    void initDatabaseWithDatabaseHelper(SQLiteDatabase db) {
        mOpenHelper.initDatabase(db);
    }
    boolean needApnDbUpdate() {
        return mOpenHelper.apnDbUpdateNeeded();
    }

    private void restoreApnsWithService(int subId) {
        Context context = getContext();
        Resources r = context.getResources();
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName className,
                    IBinder service) {
                TelephonyDatabaseHelper.log("restoreApnsWithService: onServiceConnected");
                synchronized (mLock) {
                    mIApnSourceService = IApnSourceService.Stub.asInterface(service);
                    mLock.notifyAll();
                }
            }

            @Override
            public void onServiceDisconnected(ComponentName arg0) {
                TelephonyDatabaseHelper.loge("mIApnSourceService has disconnected unexpectedly");
                synchronized (mLock) {
                    mIApnSourceService = null;
                }
            }
        };

        Intent intent = new Intent(IApnSourceService.class.getName());
        intent.setComponent(ComponentName.unflattenFromString(
                r.getString(R.string.apn_source_service)));
        TelephonyDatabaseHelper.log("binding to service to restore apns, intent=" + intent);
        try {
            if (context.bindService(intent, connection, Context.BIND_IMPORTANT |
                        Context.BIND_AUTO_CREATE)) {
                synchronized (mLock) {
                    while (mIApnSourceService == null) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            TelephonyDatabaseHelper.loge("Error while waiting for service connection: " + e);
                        }
                    }
                    try {
                        ContentValues[] values = mIApnSourceService.getApns(subId);
                        if (values != null) {
                            // we use the unsynchronized insert because this function is called
                            // within the syncrhonized function delete()
                            unsynchronizedBulkInsert(CONTENT_URI, values);
                            TelephonyDatabaseHelper.log("restoreApnsWithService: restored");
                        }
                    } catch (RemoteException e) {
                        TelephonyDatabaseHelper.loge("Error applying apns from service: " + e);
                    }
                }
            } else {
                TelephonyDatabaseHelper.loge("unable to bind to service from intent=" + intent);
            }
        } catch (SecurityException e) {
            TelephonyDatabaseHelper.loge("Error applying apns from service: " + e);
        } finally {
            if (connection != null) {
                context.unbindService(connection);
            }
            synchronized (mLock) {
                mIApnSourceService = null;
            }
        }
    }


    @Override
    public boolean onCreate() {
        mOpenHelper = new TelephonyDatabaseHelper.DatabaseHelper(getContext());

        if (!TelephonyDatabaseHelper.apnSourceServiceExists(getContext())) {
            // Call getReadableDatabase() to make sure onUpgrade is called
            if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("onCreate: calling getReadableDatabase to trigger onUpgrade");
            SQLiteDatabase db = getReadableDatabase();

            // Update APN db on build update
            String newBuildId = SystemProperties.get("ro.build.id", null);
            if (!TextUtils.isEmpty(newBuildId)) {
                // Check if build id has changed
                SharedPreferences sp = getContext().getSharedPreferences(BUILD_ID_FILE,
                        Context.MODE_PRIVATE);
                String oldBuildId = sp.getString(RO_BUILD_ID, "");
                if (!newBuildId.equals(oldBuildId)) {
                    if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("onCreate: build id changed from " + oldBuildId + " to " +
                            newBuildId);

                    // Get rid of old preferred apn shared preferences
                    SubscriptionManager sm = SubscriptionManager.from(getContext());
                    if (sm != null) {
                        List<SubscriptionInfo> subInfoList = sm.getAllSubscriptionInfoList();
                        for (SubscriptionInfo subInfo : subInfoList) {
                            SharedPreferences spPrefFile = getContext().getSharedPreferences(
                                    PREF_FILE_APN + subInfo.getSubscriptionId(), Context.MODE_PRIVATE);
                            if (spPrefFile != null) {
                                SharedPreferences.Editor editor = spPrefFile.edit();
                                editor.clear();
                                editor.apply();
                            }
                        }
                    }

                    // Update APN DB
                    updateApnDb();
                } else {
                    if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("onCreate: build id did not change: " + oldBuildId);
                }
                sp.edit().putString(RO_BUILD_ID, newBuildId).apply();
            } else {
                if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("onCreate: newBuildId is empty");
            }
        }

        SharedPreferences sp = getContext().getSharedPreferences(ENFORCED_FILE,
                Context.MODE_PRIVATE);
        mManagedApnEnforced = sp.getBoolean(ENFORCED_KEY, false);

        if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("onCreate:- ret true");

        return true;
    }

    private synchronized boolean isManagedApnEnforced() {
        return mManagedApnEnforced;
    }

    private void setManagedApnEnforced(boolean enforced) {
        SharedPreferences sp = getContext().getSharedPreferences(ENFORCED_FILE,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putBoolean(ENFORCED_KEY, enforced);
        editor.apply();
        synchronized (this) {
            mManagedApnEnforced = enforced;
        }
    }

    private void setPreferredApnId(Long id, int subId, boolean saveApn) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(COLUMN_APN_ID + subId, id != null ? id : INVALID_APN_ID);
        // This is for debug purposes. It indicates if this APN was set by DcTracker or user (true)
        // or if this was restored from APN saved in PREF_FILE_FULL_APN (false).
        editor.putBoolean(EXPLICIT_SET_CALLED + subId, saveApn);
        editor.apply();
        if (id == null || id.longValue() == INVALID_APN_ID) {
            deletePreferredApn(subId);
        } else {
            // If id is not invalid, and saveApn is true, save the actual APN in PREF_FILE_FULL_APN
            // too.
            if (saveApn) {
                setPreferredApn(id, subId);
            }
        }
    }

    private long getPreferredApnId(int subId, boolean checkApnSp) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        long apnId = sp.getLong(COLUMN_APN_ID + subId, INVALID_APN_ID);
        if (apnId == INVALID_APN_ID && checkApnSp) {
            apnId = getPreferredApnIdFromApn(subId);
            if (apnId != INVALID_APN_ID) {
                setPreferredApnId(apnId, subId, false);
            }
        }
        return apnId;
    }

    private int getPreferredApnSetId(int subId) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        try {
            return Integer.parseInt(sp.getString(APN_SET_ID + subId, null));
        } catch (NumberFormatException e) {
            return NO_SET_SET;
        }
    }

    private void deletePreferredApnId() {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);

        // Before deleting, save actual preferred apns (not the ids) in a separate SP.
        // NOTE: This code to call setPreferredApn() can be removed since the function is now called
        // from setPreferredApnId(). However older builds (pre oc-mr1) do not have that change, so
        // when devices upgrade from those builds and this function is called, this code is needed
        // otherwise the preferred APN will be lost.
        Map<String, ?> allPrefApnId = sp.getAll();
        for (String key : allPrefApnId.keySet()) {
            // extract subId from key by removing COLUMN_APN_ID
            try {
                int subId = Integer.parseInt(key.replace(COLUMN_APN_ID, ""));
                long apnId = getPreferredApnId(subId, false);
                if (apnId != INVALID_APN_ID) {
                    setPreferredApn(apnId, subId);
                }
            } catch (Exception e) {
                TelephonyDatabaseHelper.loge("Skipping over key " + key + " due to exception " + e);
            }
        }

        SharedPreferences.Editor editor = sp.edit();
        editor.clear();
        editor.apply();
    }

    private void setPreferredApn(Long id, int subId) {
        TelephonyDatabaseHelper.log("setPreferredApn: _id " + id + " subId " + subId);
        SQLiteDatabase db = getWritableDatabase();
        // query all unique fields from id
        String[] proj = TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS.toArray(new String[TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS.size()]);

        Cursor c = db.query(TelephonyDatabaseHelper.CARRIERS_TABLE, proj, "_id=" + id, null, null, null, null);
        if (c != null) {
            if (c.getCount() == 1) {
                c.moveToFirst();
                SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                        Context.MODE_PRIVATE);
                SharedPreferences.Editor editor = sp.edit();
                // store values of all unique fields to SP
                for (String key : TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS) {
                    editor.putString(key + subId, c.getString(c.getColumnIndex(key)));
                }
                // also store the version number
                editor.putString(DB_VERSION_KEY + subId, "" + TelephonyDatabaseHelper.DATABASE_VERSION);
                editor.apply();
            } else {
                TelephonyDatabaseHelper.log("setPreferredApn: # matching APNs found " + c.getCount());
            }
            c.close();
        } else {
            TelephonyDatabaseHelper.log("setPreferredApn: No matching APN found");
        }
    }

    private long getPreferredApnIdFromApn(int subId) {
        TelephonyDatabaseHelper.log("getPreferredApnIdFromApn: for subId " + subId);
        SQLiteDatabase db = getReadableDatabase();
        String where = TextUtils.join("=? and ", TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS) + "=?";
        String[] whereArgs = new String[TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS.size()];
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        long apnId = INVALID_APN_ID;
        int i = 0;
        for (String key : TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS) {
            whereArgs[i] = sp.getString(key + subId, null);
            if (whereArgs[i] == null) {
                return INVALID_APN_ID;
            }
            i++;
        }
        Cursor c = db.query(TelephonyDatabaseHelper.CARRIERS_TABLE, new String[]{"_id"}, where, whereArgs, null, null,
                null);
        if (c != null) {
            if (c.getCount() == 1) {
                c.moveToFirst();
                apnId = c.getInt(c.getColumnIndex("_id"));
            } else {
                TelephonyDatabaseHelper.log("getPreferredApnIdFromApn: returning INVALID. # matching APNs found " +
                        c.getCount());
            }
            c.close();
        } else {
            TelephonyDatabaseHelper.log("getPreferredApnIdFromApn: returning INVALID. No matching APN found");
        }
        return apnId;
    }

    private void deletePreferredApn(int subId) {
        TelephonyDatabaseHelper.log("deletePreferredApn: for subId " + subId);
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        if (sp.contains(DB_VERSION_KEY + subId)) {
            TelephonyDatabaseHelper.log("deletePreferredApn: apn is stored. Deleting it now for subId " + subId);
            SharedPreferences.Editor editor = sp.edit();
            editor.remove(DB_VERSION_KEY + subId);
            for (String key : TelephonyDatabaseHelper.CARRIERS_UNIQUE_FIELDS) {
                editor.remove(key + subId);
            }
            editor.apply();
        }
    }

    boolean isCallingFromSystemOrPhoneUid() {
        return mInjector.binderGetCallingUid() == Process.SYSTEM_UID ||
                mInjector.binderGetCallingUid() == Process.PHONE_UID;
    }

    void ensureCallingFromSystemOrPhoneUid(String message) {
        if (!isCallingFromSystemOrPhoneUid()) {
            throw new SecurityException(message);
        }
    }

    @Override
    public synchronized Cursor query(Uri url, String[] projectionIn, String selection,
            String[] selectionArgs, String sort) {
        if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("query: url=" + url + ", projectionIn=" + projectionIn + ", selection="
                + selection + "selectionArgs=" + selectionArgs + ", sort=" + sort);
        TelephonyManager mTelephonyManager =
                (TelephonyManager)getContext().getSystemService(Context.TELEPHONY_SERVICE);
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        String subIdString;
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setStrict(true); // a little protection from injection attacks
        qb.setTables(TelephonyDatabaseHelper.CARRIERS_TABLE);

        List<String> constraints = new ArrayList<String>();

        int match = s_urlMatcher.match(url);
        switch (match) {
            case URL_TELEPHONY_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return null;
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                constraints.add(NUMERIC + " = '" + mTelephonyManager.getSimOperator(subId) + "'");
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            // intentional fall through from above case
            case URL_TELEPHONY: {
                constraints.add(IS_NOT_OWNED_BY_DPC);
                break;
            }

            case URL_CURRENT_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return null;
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            //intentional fall through from above case
            case URL_CURRENT: {
                constraints.add("current IS NOT NULL");
                constraints.add(IS_NOT_OWNED_BY_DPC);
                // do not ignore the selection since MMS may use it.
                //selection = null;
                break;
            }

            case URL_ID: {
                constraints.add("_id = " + url.getPathSegments().get(1));
                constraints.add(IS_NOT_OWNED_BY_DPC);
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return null;
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            //intentional fall through from above case
            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE: {
                constraints.add("_id = " + getPreferredApnId(subId, true));
                break;
            }

            // TODO ldm
            case URL_PREFERAPNSET_USING_SUBID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return null;
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                // TODO b/74213956 turn this back on once insertion includes correct sub id
                // constraints.add(SUBSCRIPTION_ID + "=" + subIdString);
            }
            // intentional fall through from above case
            case URL_PREFERAPNSET: {
                final int set = getPreferredApnSetId(subId);
                if (set != NO_SET_SET) {
                    constraints.add(APN_SET_ID + "=" + set);
                }
                break;
            }

            case URL_DPC: {
                ensureCallingFromSystemOrPhoneUid("URL_DPC called from non SYSTEM_UID.");
                // DPC query only returns DPC records.
                constraints.add(IS_OWNED_BY_DPC);
                break;
            }

            case URL_FILTERED_ID: {
                constraints.add("_id = " + url.getLastPathSegment());
            }
            //intentional fall through from above case
            case URL_FILTERED: {
                if (isManagedApnEnforced()) {
                    // If enforced, return DPC records only.
                    constraints.add(IS_OWNED_BY_DPC);
                } else {
                    // Otherwise return non-DPC records only.
                    constraints.add(IS_NOT_OWNED_BY_DPC);
                }
                break;
            }

            case URL_ENFORCE_MANAGED: {
                ensureCallingFromSystemOrPhoneUid(
                        "URL_ENFORCE_MANAGED called from non SYSTEM_UID.");
                MatrixCursor cursor = new MatrixCursor(new String[]{ENFORCED_KEY});
                cursor.addRow(new Object[]{isManagedApnEnforced() ? 1 : 0});
                return cursor;
            }

            case URL_SIMINFO: {
                qb.setTables(TelephonyDatabaseHelper.SIMINFO_TABLE);
                break;
            }
            case URL_SIM_APN_LIST_ID: {
                subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return null;
                }
            }
            //intentional fall through from above case
            case URL_SIM_APN_LIST: {
                return getSubscriptionMatchingAPNList(qb, projectionIn, sort, subId);
            }

            default: {
                return null;
            }
        }

        // appendWhere doesn't add ANDs so we do it ourselves
        if (constraints.size() > 0) {
            qb.appendWhere(TextUtils.join(" AND ", constraints));
        }

        if (match != URL_SIMINFO) {
            if (projectionIn != null) {
                for (String column : projectionIn) {
                    if (TYPE.equals(column) ||
                            MMSC.equals(column) ||
                            MMSPROXY.equals(column) ||
                            MMSPORT.equals(column) ||
                            MVNO_TYPE.equals(column) ||
                            MVNO_MATCH_DATA.equals(column) ||
                            APN.equals(column)) {
                        // noop
                    } else {
                        checkPermission();
                        break;
                    }
                }
            } else {
                // null returns all columns, so need permission check
                checkPermission();
            }
        }

        SQLiteDatabase db = getReadableDatabase();
        Cursor ret = null;
        try {
            // Exclude entries marked deleted
            if (TelephonyDatabaseHelper.CARRIERS_TABLE.equals(qb.getTables())) {
                if (TextUtils.isEmpty(selection)) {
                    selection = "";
                } else {
                    selection += " and ";
                }
                selection += IS_NOT_USER_DELETED + " and " +
                        IS_NOT_USER_DELETED_BUT_PRESENT_IN_XML + " and " +
                        IS_NOT_CARRIER_DELETED + " and " +
                        IS_NOT_CARRIER_DELETED_BUT_PRESENT_IN_XML;
                if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("query: selection modified to " + selection);
            }
            ret = qb.query(db, projectionIn, selection, selectionArgs, null, null, sort);
        } catch (SQLException e) {
            TelephonyDatabaseHelper.loge("got exception when querying: " + e);
        }
        if (ret != null)
            ret.setNotificationUri(getContext().getContentResolver(), url);
        return ret;
    }

    /**
     * To find the current sim APN.
     *
     * There has three steps:
     * 1. Query the APN based on carrier ID and fall back to query { MCC, MNC, MVNO }.
     * 2. If can't find the current APN, then query the parent APN. Query based on
     *    MNO carrier id and { MCC, MNC }.
     * 3. else return empty cursor
     *
     */
    private Cursor getSubscriptionMatchingAPNList(SQLiteQueryBuilder qb, String[] projectionIn,
            String sort, int subId) {

        Cursor ret;
        final TelephonyManager tm = ((TelephonyManager)
                getContext().getSystemService(Context.TELEPHONY_SERVICE))
                .createForSubscriptionId(subId);
        SQLiteDatabase db = getReadableDatabase();

        // For query db one time, append step 1 and step 2 condition in one selection and
        // separate results after the query is completed. Because IMSI has special match rule,
        // so just query the MCC / MNC and filter the MVNO by ourselves
        String carrierIDSelection = CARRIER_ID + " =? OR " + NUMERIC + " =? OR "
                + CARRIER_ID + " =? ";


        String mccmnc = tm.getSimOperator();
        String carrierId = String.valueOf(tm.getSimCarrierId());
        String mnoCarrierId = String.valueOf(tm.getSimMNOCarrierId());

        ret = qb.query(db, null, carrierIDSelection,
                new String[]{carrierId, mccmnc, mnoCarrierId}, null, null, sort);

        if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("match current APN size:  " + ret.getCount());

        MatrixCursor currentCursor = new MatrixCursor(projectionIn);
        MatrixCursor parentCursor = new MatrixCursor(projectionIn);

        int carrierIdIndex = ret.getColumnIndex(CARRIER_ID);
        int numericIndex = ret.getColumnIndex(NUMERIC);
        int mvnoIndex = ret.getColumnIndex(MVNO_TYPE);
        int mvnoDataIndex = ret.getColumnIndex(MVNO_MATCH_DATA);

        IccRecords iccRecords = getIccRecords(subId);

        //Separate the result into MatrixCursor
        while (ret.moveToNext()) {
            List<String> data = new ArrayList<>();
            for (String column : projectionIn) {
                data.add(ret.getString(ret.getColumnIndex(column)));
            }

            if (ret.getString(carrierIdIndex).equals(carrierId)) {
                // 1. APN query result based on SIM carrier id
                currentCursor.addRow(data);
            } else if (!TextUtils.isEmpty(ret.getString(numericIndex)) &&
                    ApnSettingUtils.mvnoMatches(iccRecords,
                            ApnSetting.getMvnoTypeIntFromString(ret.getString(mvnoIndex)),
                            ret.getString(mvnoDataIndex))) {
                // 1. APN query result based on legacy SIM MCC/MCC and MVNO in case APN carrier id
                // migration is not 100%. some APNSettings can not find match id.
                // TODO: remove legacy {mcc,mnc, mvno} support in the future.
                currentCursor.addRow(data);
            } else if (ret.getString(carrierIdIndex).equals(mnoCarrierId)) {
                // 2. APN query result based on SIM MNO carrier id in case no APN found from
                // exact carrier id fallback to query the MNO carrier id
                parentCursor.addRow(data);
            } else if (!TextUtils.isEmpty(ret.getString(numericIndex))) {
                // 2. APN query result based on SIM MCC/MNC
                // TODO: remove legacy {mcc, mnc} support in the future.
                parentCursor.addRow(data);
            }
        }

        if (currentCursor.getCount() > 0) {
            if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("match Carrier Id APN: " + currentCursor.getCount());
            return currentCursor;
        } else if (parentCursor.getCount() > 0) {
            if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("match MNO Carrier ID APN: " + parentCursor.getCount());
            return parentCursor;
        } else {
            if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("APN no match");
            return new MatrixCursor(projectionIn);
        }
    }

    @Override
    public String getType(Uri url)
    {
        switch (s_urlMatcher.match(url)) {
        case URL_TELEPHONY:
        case URL_TELEPHONY_USING_SUBID:
            return "vnd.android.cursor.dir/telephony-carrier";

        case URL_ID:
        case URL_FILTERED_ID:
            return "vnd.android.cursor.item/telephony-carrier";

        case URL_PREFERAPN_USING_SUBID:
        case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
        case URL_PREFERAPN:
        case URL_PREFERAPN_NO_UPDATE:
        case URL_PREFERAPNSET:
        case URL_PREFERAPNSET_USING_SUBID:
            return "vnd.android.cursor.item/telephony-carrier";

        default:
            throw new IllegalArgumentException("Unknown URL " + url);
        }
    }

    /**
     * Insert an array of ContentValues and call notifyChange at the end.
     */
    @Override
    public synchronized int bulkInsert(Uri url, ContentValues[] values) {
        return unsynchronizedBulkInsert(url, values);
    }

    /**
     * Do a bulk insert while inside a synchronized function. This is typically not safe and should
     * only be done when you are sure there will be no conflict.
     */
    private int unsynchronizedBulkInsert(Uri url, ContentValues[] values) {
        int count = 0;
        boolean notify = false;
        for (ContentValues value : values) {
            Pair<Uri, Boolean> rowAndNotify = insertSingleRow(url, value);
            if (rowAndNotify.first != null) {
                count++;
            }
            if (rowAndNotify.second == true) {
                notify = true;
            }
        }
        if (notify) {
            getContext().getContentResolver().notifyChange(CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }
        return count;
    }

    @Override
    public synchronized Uri insert(Uri url, ContentValues initialValues) {
        Pair<Uri, Boolean> rowAndNotify = insertSingleRow(url, initialValues);
        if (rowAndNotify.second) {
            getContext().getContentResolver().notifyChange(CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }
        return rowAndNotify.first;
    }

    /**
     * Internal insert function to prevent code duplication for URL_TELEPHONY and URL_DPC.
     *
     * @param values the value that caller wants to insert
     * @return a pair in which the first element refers to the Uri for the row inserted, the second
     *         element refers to whether sends out nofitication.
     */
    private Pair<Uri, Boolean> insertRowWithValue(ContentValues values) {
        Uri result = null;
        boolean notify = false;
        SQLiteDatabase db = getWritableDatabase();

        try {
            // Abort on conflict of unique fields and attempt merge
            long rowID = db.insertWithOnConflict(TelephonyDatabaseHelper.CARRIERS_TABLE, null, values,
                    SQLiteDatabase.CONFLICT_ABORT);
            if (rowID >= 0) {
                result = ContentUris.withAppendedId(CONTENT_URI, rowID);
                notify = true;
            }
            if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("insert: inserted " + values.toString() + " rowID = " + rowID);
        } catch (SQLException e) {
            TelephonyDatabaseHelper.log("insert: exception " + e);
            // Insertion failed which could be due to a conflict. Check if that is the case
            // and merge the entries
            Cursor oldRow = TelephonyDatabaseHelper.DatabaseHelper.selectConflictingRow(db, TelephonyDatabaseHelper.CARRIERS_TABLE, values);
            if (oldRow != null) {
                ContentValues mergedValues = new ContentValues();
                TelephonyDatabaseHelper.DatabaseHelper.mergeFieldsAndUpdateDb(db, TelephonyDatabaseHelper.CARRIERS_TABLE, oldRow, values,
                        mergedValues, false, getContext());
                oldRow.close();
                notify = true;
            }
        }
        return Pair.create(result, notify);
    }

    private Pair<Uri, Boolean> insertSingleRow(Uri url, ContentValues initialValues) {
        Uri result = null;
        int subId = SubscriptionManager.getDefaultSubscriptionId();

        checkPermission();
        TelephonyDatabaseHelper.syncBearerBitmaskAndNetworkTypeBitmask(initialValues);

        boolean notify = false;
        SQLiteDatabase db = getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return Pair.create(result, notify);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_TELEPHONY:
            {
                ContentValues values;
                if (initialValues != null) {
                    values = new ContentValues(initialValues);
                } else {
                    values = new ContentValues();
                }

                values = TelephonyDatabaseHelper.DatabaseHelper.setDefaultValue(values);
                if (!values.containsKey(EDITED)) {
                    values.put(EDITED, CARRIER_EDITED);
                }
                // Owned_by should be others if inserted via general uri.
                values.put(OWNED_BY, OWNED_BY_OTHERS);

                Pair<Uri, Boolean> ret = insertRowWithValue(values);
                result = ret.first;
                notify = ret.second;
                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return Pair.create(result, notify);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                // zero out the previous operator
                db.update(TelephonyDatabaseHelper.CARRIERS_TABLE, s_currentNullMap, CURRENT + "!=0", null);

                String numeric = initialValues.getAsString(NUMERIC);
                int updated = db.update(TelephonyDatabaseHelper.CARRIERS_TABLE, s_currentSetMap,
                        NUMERIC + " = '" + numeric + "'", null);

                if (updated > 0)
                {
                    if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("Setting numeric '" + numeric + "' to be the current operator");
                }
                else
                {
                    TelephonyDatabaseHelper.loge("Failed setting numeric '" + numeric + "' to the current operator");
                }
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    return Pair.create(result, notify);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (initialValues != null) {
                    if(initialValues.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(initialValues.getAsLong(COLUMN_APN_ID), subId, true);
                    }
                }
                break;
            }

            case URL_DPC: {
                ensureCallingFromSystemOrPhoneUid("URL_DPC called from non SYSTEM_UID.");

                ContentValues values;
                if (initialValues != null) {
                    values = new ContentValues(initialValues);
                } else {
                    values = new ContentValues();
                }

                // Owned_by should be DPC if inserted via URL_DPC.
                values.put(OWNED_BY, OWNED_BY_DPC);
                // DPC records should not be user editable.
                values.put(USER_EDITABLE, false);

                final long rowID = db.insertWithOnConflict(TelephonyDatabaseHelper.CARRIERS_TABLE, null, values,
                        SQLiteDatabase.CONFLICT_IGNORE);
                if (rowID >= 0) {
                    result = ContentUris.withAppendedId(CONTENT_URI, rowID);
                    notify = true;
                }
                if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log("insert: inserted " + values.toString() + " rowID = " + rowID);

                break;
            }

            case URL_SIMINFO: {
               long id = db.insert(TelephonyDatabaseHelper.SIMINFO_TABLE, null, initialValues);
               result = ContentUris.withAppendedId(SubscriptionManager.CONTENT_URI, id);
               break;
            }
        }

        return Pair.create(result, notify);
    }

    @Override
    public synchronized int delete(Uri url, String where, String[] whereArgs) {
        int count = 0;
        int subId = SubscriptionManager.getDefaultSubscriptionId();
        String userOrCarrierEdited = ") and (" +
                IS_USER_EDITED +  " or " +
                IS_CARRIER_EDITED + ")";
        String notUserOrCarrierEdited = ") and (" +
                IS_NOT_USER_EDITED +  " and " +
                IS_NOT_CARRIER_EDITED + ")";
        String unedited = ") and " + TelephonyDatabaseHelper.IS_UNEDITED;
        ContentValues cv = new ContentValues();
        cv.put(EDITED, USER_DELETED);

        checkPermission();

        SQLiteDatabase db = getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_DELETE:
            {
                // Delete preferred APN for all subIds
                deletePreferredApnId();
                // Delete unedited entries
                count = db.delete(
                        TelephonyDatabaseHelper.CARRIERS_TABLE, "(" + where + unedited + " and " +
                        IS_NOT_OWNED_BY_DPC, whereArgs);
                break;
            }

            case URL_TELEPHONY_USING_SUBID:
            {
                 String subIdString = url.getLastPathSegment();
                 try {
                     subId = Integer.parseInt(subIdString);
                 } catch (NumberFormatException e) {
                     TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                     throw new IllegalArgumentException("Invalid subId " + url);
                 }
                 if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in query
            }
            //intentional fall through from above case

            case URL_TELEPHONY:
            {
                // Delete user/carrier edited entries
                count = db.delete(TelephonyDatabaseHelper.CARRIERS_TABLE, "(" + where + userOrCarrierEdited
                        + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                // Otherwise mark as user deleted instead of deleting
                count += db.update(TelephonyDatabaseHelper.CARRIERS_TABLE, cv, "(" + where +
                        notUserOrCarrierEdited + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                break;
            }

            case URL_CURRENT_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                // FIXME use subId in query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                // Delete user/carrier edited entries
                count = db.delete(TelephonyDatabaseHelper.CARRIERS_TABLE, "(" + where + userOrCarrierEdited
                        + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                // Otherwise mark as user deleted instead of deleting
                count += db.update(TelephonyDatabaseHelper.CARRIERS_TABLE, cv, "(" + where +
                        notUserOrCarrierEdited + " and " + IS_NOT_OWNED_BY_DPC, whereArgs);
                break;
            }

            case URL_ID:
            {
                // Delete user/carrier edited entries
                count = db.delete(TelephonyDatabaseHelper.CARRIERS_TABLE,
                        "(" + _ID + "=?" + userOrCarrierEdited +
                                " and " + IS_NOT_OWNED_BY_DPC,
                        new String[] { url.getLastPathSegment() });
                // Otherwise mark as user deleted instead of deleting
                count += db.update(TelephonyDatabaseHelper.CARRIERS_TABLE, cv,
                        "(" + _ID + "=?" + notUserOrCarrierEdited +
                                " and " + IS_NOT_OWNED_BY_DPC,
                        new String[]{url.getLastPathSegment() });
                break;
            }

            case URL_RESTOREAPN_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
            }
            // intentional fall through from above case

            case URL_RESTOREAPN: {
                count = 1;
                restoreDefaultAPN(subId);
                getContext().getContentResolver().notifyChange(
                        Uri.withAppendedPath(CONTENT_URI, "restore/subId/" + subId), null,
                        true, UserHandle.USER_ALL);
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID: {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
            }
            //intentional fall through from above case

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                setPreferredApnId((long)INVALID_APN_ID, subId, true);
                if ((match == URL_PREFERAPN) || (match == URL_PREFERAPN_USING_SUBID)) count = 1;
                break;
            }

            case URL_DPC_ID: {
                ensureCallingFromSystemOrPhoneUid("URL_DPC_ID called from non SYSTEM_UID.");

                // Only delete if owned by DPC.
                count = db.delete(
                        TelephonyDatabaseHelper.CARRIERS_TABLE, "(" + _ID + "=?)" + " and " + IS_OWNED_BY_DPC,
                        new String[] { url.getLastPathSegment() });
                break;
            }

            case URL_SIMINFO: {
                count = db.delete(TelephonyDatabaseHelper.SIMINFO_TABLE, where, whereArgs);
                break;
            }

            case URL_UPDATE_DB: {
                updateApnDb();
                count = 1;
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot delete that URL: " + url);
            }
        }

        if (count > 0) {
            getContext().getContentResolver().notifyChange(CONTENT_URI, null,
                    true, UserHandle.USER_ALL);
        }

        return count;
    }

    @Override
    public synchronized int update(Uri url, ContentValues values, String where, String[] whereArgs)
    {
        int count = 0;
        int uriType = URL_UNKNOWN;
        int subId = SubscriptionManager.getDefaultSubscriptionId();

        checkPermission();
        TelephonyDatabaseHelper.syncBearerBitmaskAndNetworkTypeBitmask(values);

        SQLiteDatabase db = getWritableDatabase();
        int match = s_urlMatcher.match(url);
        switch (match)
        {
            case URL_TELEPHONY_USING_SUBID:
            {
                 String subIdString = url.getLastPathSegment();
                 try {
                     subId = Integer.parseInt(subIdString);
                 } catch (NumberFormatException e) {
                     TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                     throw new IllegalArgumentException("Invalid subId " + url);
                 }
                 if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                //FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_TELEPHONY:
            {
                if (!values.containsKey(EDITED)) {
                    values.put(EDITED, CARRIER_EDITED);
                }

                // Replace on conflict so that if same APN is present in db with edited
                // as UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
                count = db.updateWithOnConflict(TelephonyDatabaseHelper.CARRIERS_TABLE, values, where +
                                " and " + IS_NOT_OWNED_BY_DPC, whereArgs,
                        SQLiteDatabase.CONFLICT_REPLACE);
                break;
            }

            case URL_CURRENT_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                //FIXME use subId in the query
            }
            //intentional fall through from above case

            case URL_CURRENT:
            {
                if (!values.containsKey(EDITED)) {
                    values.put(EDITED, CARRIER_EDITED);
                }
                // Replace on conflict so that if same APN is present in db with edited
                // as UNEDITED or USER/CARRIER_DELETED, it is replaced with
                // edited USER/CARRIER_EDITED
                count = db.updateWithOnConflict(TelephonyDatabaseHelper.CARRIERS_TABLE, values, where +
                                " and " + IS_NOT_OWNED_BY_DPC,
                        whereArgs, SQLiteDatabase.CONFLICT_REPLACE);
                break;
            }

            case URL_ID:
            {
                String rowID = url.getLastPathSegment();
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                if (!values.containsKey(EDITED)) {
                    values.put(EDITED, CARRIER_EDITED);
                }

                try {
                    count = db.updateWithOnConflict(TelephonyDatabaseHelper.CARRIERS_TABLE, values, _ID + "=?" + " and " +
                            IS_NOT_OWNED_BY_DPC, new String[] { rowID },
                            SQLiteDatabase.CONFLICT_ABORT);
                } catch (SQLException e) {
                    // Update failed which could be due to a conflict. Check if that is
                    // the case and merge the entries
                    TelephonyDatabaseHelper.log("update: exception " + e);
                    Cursor oldRow = TelephonyDatabaseHelper.DatabaseHelper.selectConflictingRow(db, TelephonyDatabaseHelper.CARRIERS_TABLE, values);
                    if (oldRow != null) {
                        ContentValues mergedValues = new ContentValues();
                        TelephonyDatabaseHelper.DatabaseHelper.mergeFieldsAndUpdateDb(db, TelephonyDatabaseHelper.CARRIERS_TABLE, oldRow, values,
                                mergedValues, false, getContext());
                        oldRow.close();
                        db.delete(TelephonyDatabaseHelper.CARRIERS_TABLE, _ID + "=?" + " and " + IS_NOT_OWNED_BY_DPC,
                                new String[] { rowID });
                    }
                }
                break;
            }

            case URL_PREFERAPN_USING_SUBID:
            case URL_PREFERAPN_NO_UPDATE_USING_SUBID:
            {
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
            }

            case URL_PREFERAPN:
            case URL_PREFERAPN_NO_UPDATE:
            {
                if (values != null) {
                    if (values.containsKey(COLUMN_APN_ID)) {
                        setPreferredApnId(values.getAsLong(COLUMN_APN_ID), subId, true);
                        if ((match == URL_PREFERAPN) ||
                                (match == URL_PREFERAPN_USING_SUBID)) {
                            count = 1;
                        }
                    }
                }
                break;
            }

            case URL_DPC_ID:
            {
                ensureCallingFromSystemOrPhoneUid("URL_DPC_ID called from non SYSTEM_UID.");

                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.updateWithOnConflict(TelephonyDatabaseHelper.CARRIERS_TABLE, values,
                        _ID + "=?" + " and " + IS_OWNED_BY_DPC,
                        new String[] { url.getLastPathSegment() }, SQLiteDatabase.CONFLICT_IGNORE);
                break;
            }

            case URL_ENFORCE_MANAGED: {
                ensureCallingFromSystemOrPhoneUid(
                        "URL_ENFORCE_MANAGED called from non SYSTEM_UID.");
                if (values != null) {
                    if (values.containsKey(ENFORCED_KEY)) {
                        setManagedApnEnforced(values.getAsBoolean(ENFORCED_KEY));
                        count = 1;
                    }
                }
                break;
            }

            case URL_SIMINFO_USING_SUBID:
                String subIdString = url.getLastPathSegment();
                try {
                    subId = Integer.parseInt(subIdString);
                } catch (NumberFormatException e) {
                    TelephonyDatabaseHelper.loge("NumberFormatException" + e);
                    throw new IllegalArgumentException("Invalid subId " + url);
                }
                if (TelephonyDatabaseHelper.DBG) TelephonyDatabaseHelper.log("subIdString = " + subIdString + " subId = " + subId);
                if (where != null || whereArgs != null) {
                    throw new UnsupportedOperationException(
                            "Cannot update URL " + url + " with a where clause");
                }
                count = db.update(TelephonyDatabaseHelper.SIMINFO_TABLE, values, _ID + "=?",
                        new String[] { subIdString});
                uriType = URL_SIMINFO_USING_SUBID;
                break;

            case URL_SIMINFO: {
                count = db.update(TelephonyDatabaseHelper.SIMINFO_TABLE, values, where, whereArgs);
                uriType = URL_SIMINFO;
                break;
            }

            default: {
                throw new UnsupportedOperationException("Cannot update that URL: " + url);
            }
        }

        if (count > 0) {
            boolean usingSubId = false;
            switch (uriType) {
                case URL_SIMINFO_USING_SUBID:
                    usingSubId = true;
                    // intentional fall through from above case
                case URL_SIMINFO:
                    // skip notifying descendant URLs to avoid unneccessary wake up.
                    // If not set, any change to SIMINFO will notify observers which listens to
                    // specific field of SIMINFO.
                    getContext().getContentResolver().notifyChange(
                            SubscriptionManager.CONTENT_URI, null,
                            ContentResolver.NOTIFY_SYNC_TO_NETWORK
                                    | ContentResolver.NOTIFY_SKIP_NOTIFY_FOR_DESCENDANTS,
                            UserHandle.USER_ALL);
                    // notify observers on specific user settings changes.
                    if (values.containsKey(SubscriptionManager.WFC_IMS_ENABLED)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager.WFC_ENABLED_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(SubscriptionManager.ENHANCED_4G_MODE_ENABLED)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager
                                                .ADVANCED_CALLING_ENABLED_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(SubscriptionManager.VT_IMS_ENABLED)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager.VT_ENABLED_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(SubscriptionManager.WFC_IMS_MODE)) {
                        getContext().getContentResolver().notifyChange(
                                getNotifyContentUri(SubscriptionManager.WFC_MODE_CONTENT_URI,
                                        usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(SubscriptionManager.WFC_IMS_ROAMING_MODE)) {
                        getContext().getContentResolver().notifyChange(getNotifyContentUri(
                                SubscriptionManager.WFC_ROAMING_MODE_CONTENT_URI,
                                usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    if (values.containsKey(SubscriptionManager.WFC_IMS_ROAMING_ENABLED)) {
                        getContext().getContentResolver().notifyChange(getNotifyContentUri(
                                SubscriptionManager.WFC_ROAMING_ENABLED_CONTENT_URI,
                                usingSubId, subId), null, true, UserHandle.USER_ALL);
                    }
                    break;
                default:
                    getContext().getContentResolver().notifyChange(
                            CONTENT_URI, null, true, UserHandle.USER_ALL);
            }
        }

        return count;
    }

    private static Uri getNotifyContentUri(Uri uri, boolean usingSubId, int subId) {
        return (usingSubId) ? Uri.withAppendedPath(uri, "" + subId) : uri;
    }

    private void checkPermission() {
        int status = getContext().checkCallingOrSelfPermission(
                "android.permission.WRITE_APN_SETTINGS");
        if (status == PackageManager.PERMISSION_GRANTED) {
            return;
        }

        PackageManager packageManager = getContext().getPackageManager();
        String[] packages = packageManager.getPackagesForUid(Binder.getCallingUid());

        TelephonyManager telephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        for (String pkg : packages) {
            if (telephonyManager.checkCarrierPrivilegesForPackageAnyPhone(pkg) ==
                    TelephonyManager.CARRIER_PRIVILEGE_STATUS_HAS_ACCESS) {
                return;
            }
        }
        throw new SecurityException("No permission to write APN settings");
    }

    private TelephonyDatabaseHelper.DatabaseHelper mOpenHelper;

    private void restoreDefaultAPN(int subId) {
        SQLiteDatabase db = getWritableDatabase();
        TelephonyManager telephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String where = null;
        if (telephonyManager.getPhoneCount() > 1) {
            where = getWhereClauseForRestoreDefaultApn(db, subId);
        }
        if (TextUtils.isEmpty(where)) {
            where = IS_NOT_OWNED_BY_DPC;
        }
        TelephonyDatabaseHelper.log("restoreDefaultAPN: where: " + where);

        try {
            db.delete(TelephonyDatabaseHelper.CARRIERS_TABLE, where, null);
        } catch (SQLException e) {
            TelephonyDatabaseHelper.loge("got exception when deleting to restore: " + e);
        }

        // delete preferred apn ids and preferred apns (both stored in diff SharedPref) for all
        // subIds
        SharedPreferences spApnId = getContext().getSharedPreferences(PREF_FILE_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editorApnId = spApnId.edit();
        editorApnId.clear();
        editorApnId.apply();

        SharedPreferences spApn = getContext().getSharedPreferences(PREF_FILE_FULL_APN,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editorApn = spApn.edit();
        editorApn.clear();
        editorApn.apply();

        if (TelephonyDatabaseHelper.apnSourceServiceExists(getContext())) {
            restoreApnsWithService(subId);
        } else {
            initDatabaseWithDatabaseHelper(db);
        }
    }

    private String getWhereClauseForRestoreDefaultApn(SQLiteDatabase db, int subId) {
        IccRecords iccRecords = getIccRecords(subId);
        if (iccRecords == null) {
            return null;
        }
        TelephonyManager telephonyManager =
                (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String simOperator = telephonyManager.getSimOperator(subId);
        Cursor cursor = db.query(
                TelephonyDatabaseHelper.CARRIERS_TABLE, new String[] {MVNO_TYPE, MVNO_MATCH_DATA},
                NUMERIC + "='" + simOperator + "'", null, null, null, DEFAULT_SORT_ORDER);
        String where = null;

        if (cursor != null) {
            cursor.moveToFirst();
            while (!cursor.isAfterLast()) {
                String mvnoType = cursor.getString(0 /* MVNO_TYPE index */);
                String mvnoMatchData = cursor.getString(1 /* MVNO_MATCH_DATA index */);
                if (!TextUtils.isEmpty(mvnoType) && !TextUtils.isEmpty(mvnoMatchData)
                        && ApnSettingUtils.mvnoMatches(iccRecords,
                        ApnSetting.getMvnoTypeIntFromString(mvnoType), mvnoMatchData)) {
                    where = NUMERIC + "='" + simOperator + "'"
                            + " AND " + MVNO_TYPE + "='" + mvnoType + "'"
                            + " AND " + MVNO_MATCH_DATA + "='" + mvnoMatchData + "'"
                            + " AND " + IS_NOT_OWNED_BY_DPC;
                    break;
                }
                cursor.moveToNext();
            }
            cursor.close();

            if (TextUtils.isEmpty(where)) {
                where = NUMERIC + "='" + simOperator + "'"
                        + " AND (" + MVNO_TYPE + "='' OR " + MVNO_MATCH_DATA + "='')"
                        + " AND " + IS_NOT_OWNED_BY_DPC;
            }
        }
        return where;
    }

    @VisibleForTesting
    IccRecords getIccRecords(int subId) {
        TelephonyManager telephonyManager =
                TelephonyManager.from(getContext()).createForSubscriptionId(subId);
        int family = telephonyManager.getPhoneType() == PhoneConstants.PHONE_TYPE_GSM ?
                UiccController.APP_FAM_3GPP : UiccController.APP_FAM_3GPP2;
        return UiccController.getInstance().getIccRecords(
                SubscriptionManager.getPhoneId(subId), family);
    }

    private synchronized void updateApnDb() {
        if (TelephonyDatabaseHelper.apnSourceServiceExists(getContext())) {
            TelephonyDatabaseHelper.loge("called updateApnDb when apn source service exists");
            return;
        }

        if (!needApnDbUpdate()) {
            TelephonyDatabaseHelper.log("Skipping apn db update since apn-conf has not changed.");
            return;
        }

        SQLiteDatabase db = getWritableDatabase();

        // Delete preferred APN for all subIds
        deletePreferredApnId();

        // Delete entries in db
        try {
            if (TelephonyDatabaseHelper.VDBG) TelephonyDatabaseHelper.log(
                    "updateApnDb: deleting edited=UNEDITED entries");
            db.delete(TelephonyDatabaseHelper.CARRIERS_TABLE, TelephonyDatabaseHelper.IS_UNEDITED + " and " + IS_NOT_OWNED_BY_DPC, null);
        } catch (SQLException e) {
            TelephonyDatabaseHelper.loge("got exception when deleting to update: " + e);
        }

        initDatabaseWithDatabaseHelper(db);

        // Notify listeners of DB change since DB has been updated
        getContext().getContentResolver().notifyChange(
                CONTENT_URI, null, true, UserHandle.USER_ALL);

    }

}
