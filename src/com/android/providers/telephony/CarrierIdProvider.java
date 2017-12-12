/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.os.FileUtils;
import android.os.UserHandle;
import android.provider.Telephony.CarrierIdentification;
import android.text.TextUtils;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.telephony.nano.CarrierIdProto;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import libcore.io.IoUtils;

/**
 * This class provides the ability to query the Carrier Identification databases
 * (A.K.A. cid) which is stored in a SQLite database.
 *
 * Each row in carrier identification db consists of matching rule (e.g., MCCMNC, GID1, GID2, PLMN)
 * and its matched carrier id & carrier name. Each carrier either MNO or MVNO could be
 * identified by multiple matching rules but is assigned with a unique ID (cid).
 *
 *
 * This class provides the ability to retrieve the cid of the current subscription.
 * This is done atomically through a query.
 *
 * This class also provides a way to update carrier identifying attributes of an existing entry.
 * Insert entries for new carriers or an existing carrier.
 */
public class CarrierIdProvider extends ContentProvider {

    private static final boolean VDBG = false; // STOPSHIP if true
    private static final String TAG = CarrierIdProvider.class.getSimpleName();

    private static final String DATABASE_NAME = "carrierIdentification.db";
    private static final int DATABASE_VERSION = 2;

    private static final String ASSETS_PB_FILE = "carrier_list.pb";
    private static final String ASSETS_FILE_CHECKSUM_PREF_KEY = "assets_checksum";
    private static final String PREF_FILE = CarrierIdProvider.class.getSimpleName();

    /**
     * index 0: {@link CarrierIdentification#MCCMNC}
     */
    private static final int MCCMNC_INDEX                = 0;
    /**
     * index 1: {@link CarrierIdentification#IMSI_PREFIX_XPATTERN}
     */
    private static final int IMSI_PREFIX_INDEX           = 1;
    /**
     * index 2: {@link CarrierIdentification#GID1}
     */
    private static final int GID1_INDEX                  = 2;
    /**
     * index 3: {@link CarrierIdentification#GID2}
     */
    private static final int GID2_INDEX                  = 3;
    /**
     * index 4: {@link CarrierIdentification#PLMN}
     */
    private static final int PLMN_INDEX                  = 4;
    /**
     * index 5: {@link CarrierIdentification#SPN}
     */
    private static final int SPN_INDEX                   = 5;
    /**
     * index 6: {@link CarrierIdentification#APN}
     */
    private static final int APN_INDEX                   = 6;
    /**
     * ending index of carrier attribute list.
     */
    private static final int CARRIER_ATTR_END_IDX        = APN_INDEX;
    /**
     * The authority string for the CarrierIdProvider
     */
    @VisibleForTesting
    public static final String AUTHORITY = "carrier_identification";

    public static final String CARRIER_ID_TABLE = "carrier_id";

    private static final List<String> CARRIERS_ID_UNIQUE_FIELDS = new ArrayList<>(Arrays.asList(
            CarrierIdentification.MCCMNC,
            CarrierIdentification.GID1,
            CarrierIdentification.GID2,
            CarrierIdentification.PLMN,
            CarrierIdentification.IMSI_PREFIX_XPATTERN,
            CarrierIdentification.SPN,
            CarrierIdentification.APN));

    private CarrierIdDatabaseHelper mDbHelper;

    @VisibleForTesting
    public static String getStringForCarrierIdTableCreation(String tableName) {
        return "CREATE TABLE " + tableName
                + "(_id INTEGER PRIMARY KEY,"
                + CarrierIdentification.MCCMNC + " TEXT NOT NULL,"
                + CarrierIdentification.GID1 + " TEXT,"
                + CarrierIdentification.GID2 + " TEXT,"
                + CarrierIdentification.PLMN + " TEXT,"
                + CarrierIdentification.IMSI_PREFIX_XPATTERN + " TEXT,"
                + CarrierIdentification.SPN + " TEXT,"
                + CarrierIdentification.APN + " TEXT,"
                + CarrierIdentification.NAME + " TEXT,"
                + CarrierIdentification.CID + " INTEGER DEFAULT -1,"
                + "UNIQUE (" + TextUtils.join(", ", CARRIERS_ID_UNIQUE_FIELDS) + "));";
    }

    @VisibleForTesting
    public static String getStringForIndexCreation(String tableName) {
        return "CREATE INDEX IF NOT EXISTS mccmncIndex ON " + tableName + " ("
                + CarrierIdentification.MCCMNC + ");";
    }

    @Override
    public boolean onCreate() {
        Log.d(TAG, "onCreate");
        mDbHelper = new CarrierIdDatabaseHelper(getContext());
        mDbHelper.getReadableDatabase();
        updateFromAssetsIfNeeded(mDbHelper.getWritableDatabase());
        return true;
    }

    @Override
    public String getType(Uri uri) {
        Log.d(TAG, "getType");
        return null;
    }

    @Override
    public Cursor query(Uri uri, String[] projectionIn, String selection,
                        String[] selectionArgs, String sortOrder) {
        if (VDBG) {
            Log.d(TAG, "query:"
                    + " uri=" + uri
                    + " values=" + Arrays.toString(projectionIn)
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(CARRIER_ID_TABLE);

        SQLiteDatabase db = getReadableDatabase();
        return qb.query(db, projectionIn, selection, selectionArgs, null, null, sortOrder);
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        final long row = getWritableDatabase().insertOrThrow(CARRIER_ID_TABLE, null, values);
        if (row > 0) {
            final Uri newUri = ContentUris.withAppendedId(CarrierIdentification.CONTENT_URI, row);
            getContext().getContentResolver().notifyChange(newUri, null);
            return newUri;
        }
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (VDBG) {
            Log.d(TAG, "delete:"
                    + " uri=" + uri
                    + " selection={" + selection + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        final int count = getWritableDatabase().delete(CARRIER_ID_TABLE, selection, selectionArgs);
        Log.d(TAG, "  delete.count=" + count);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(CarrierIdentification.CONTENT_URI, null);
        }
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        if (VDBG) {
            Log.d(TAG, "update:"
                    + " uri=" + uri
                    + " values={" + values + "}"
                    + " selection=" + selection
                    + " selectionArgs=" + Arrays.toString(selectionArgs));
        }
        final int count = getWritableDatabase().update(CARRIER_ID_TABLE, values, selection,
                selectionArgs);
        Log.d(TAG, "  update.count=" + count);
        if (count > 0) {
            getContext().getContentResolver().notifyChange(CarrierIdentification.CONTENT_URI, null);
        }
        return count;
    }

    /**
     * These methods can be overridden in a subclass for testing CarrierIdProvider using an
     * in-memory database.
     */
    SQLiteDatabase getReadableDatabase() {
        return mDbHelper.getReadableDatabase();
    }
    SQLiteDatabase getWritableDatabase() {
        return mDbHelper.getWritableDatabase();
    }

    private class CarrierIdDatabaseHelper extends SQLiteOpenHelper {
        private final String TAG = CarrierIdDatabaseHelper.class.getSimpleName();

        /**
         * CarrierIdDatabaseHelper carrier identification database helper class.
         * @param context of the user.
         */
        public CarrierIdDatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            Log.d(TAG, "onCreate");
            db.execSQL(getStringForCarrierIdTableCreation(CARRIER_ID_TABLE));
            db.execSQL(getStringForIndexCreation(CARRIER_ID_TABLE));
        }

        public void createCarrierTable(SQLiteDatabase db) {
            db.execSQL(getStringForCarrierIdTableCreation(CARRIER_ID_TABLE));
            db.execSQL(getStringForIndexCreation(CARRIER_ID_TABLE));
        }

        public void dropCarrierTable(SQLiteDatabase db) {
            db.execSQL("DROP TABLE IF EXISTS " + CARRIER_ID_TABLE + ";");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.d(TAG, "dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            if (oldVersion < DATABASE_VERSION) {
                dropCarrierTable(db);
                createCarrierTable(db);
            }
        }
    }

    /**
     * use check sum to detect assets file update.
     * update database with data from assets only if checksum has been changed
     * and OTA update is unavailable.
     */
    private void updateFromAssetsIfNeeded(SQLiteDatabase db) {
        //TODO skip update from assets if OTA update is available.
        final File assets;
        OutputStream outputStream = null;
        InputStream inputStream = null;
        try {
            // create a temp file to compute check sum.
            assets = new File(getContext().getCacheDir(), ASSETS_PB_FILE);
            outputStream = new FileOutputStream(assets);
            inputStream = getContext().getAssets().open(ASSETS_PB_FILE);
            outputStream.write(readInputStreamToByteArray(inputStream));
        } catch (IOException ex) {
            Log.e(TAG, "assets file not found: " + ex);
            return;
        } finally {
            IoUtils.closeQuietly(outputStream);
            IoUtils.closeQuietly(inputStream);
        }
        long checkSum = getChecksum(assets);
        if (checkSum != getAssetsChecksum()) {
            initDatabaseFromPb(assets, db);
            setAssetsChecksum(checkSum);
        }
    }

    /**
     * parse and persist pb file as database default values.
     */
    private void initDatabaseFromPb(File pb, SQLiteDatabase db) {
        Log.d(TAG, "init database from pb file");
        InputStream inputStream = null;
        try {
            inputStream = new FileInputStream(pb);
            byte[] bytes = readInputStreamToByteArray(inputStream);
            CarrierIdProto.CarrierList carrierList = CarrierIdProto.CarrierList.parseFrom(bytes);
            List<ContentValues> cvs = new ArrayList<>();
            for (CarrierIdProto.CarrierId id : carrierList.carrierId) {
                for (CarrierIdProto.CarrierAttribute attr: id.carrierAttribute) {
                    ContentValues cv = new ContentValues();
                    cv.put(CarrierIdentification.CID, id.canonicalId);
                    cv.put(CarrierIdentification.NAME, id.carrierName);
                    convertCarrierAttrToContentValues(cv, cvs, attr, 0);
                }
            }
            db.delete(CARRIER_ID_TABLE, null, null);
            int rows = 0;
            for (ContentValues cv : cvs) {
                if (db.insertOrThrow(CARRIER_ID_TABLE, null, cv) > 0) rows++;
            }
            Log.d(TAG, "init database from pb. inserted rows = " + rows);
            if (rows > 0) {
                // Notify listener of DB change
                getContext().getContentResolver().notifyChange(CarrierIdentification.CONTENT_URI,
                        null);
            }
        } catch (IOException ex) {
            Log.e(TAG, "init database from pb failure: " + ex);
        } finally {
            IoUtils.closeQuietly(inputStream);
        }
    }

    /**
     * recursively loop through carrier attribute list to get all combinations.
     */
    private void convertCarrierAttrToContentValues(ContentValues cv, List<ContentValues> cvs,
            CarrierIdProto.CarrierAttribute attr, int index) {
        if (index > CARRIER_ATTR_END_IDX) {
            cvs.add(new ContentValues(cv));
            return;
        }
        boolean found = false;
        switch(index) {
            case MCCMNC_INDEX:
                for (String str : attr.mccmncTuple) {
                    cv.put(CarrierIdentification.MCCMNC, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.MCCMNC);
                    found = true;
                }
                break;
            case IMSI_PREFIX_INDEX:
                for (String str : attr.imsiPrefixXpattern) {
                    cv.put(CarrierIdentification.IMSI_PREFIX_XPATTERN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.IMSI_PREFIX_XPATTERN);
                    found = true;
                }
                break;
            case GID1_INDEX:
                for (String str : attr.gid1) {
                    cv.put(CarrierIdentification.GID1, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.GID1);
                    found = true;
                }
                break;
            case GID2_INDEX:
                for (String str : attr.gid2) {
                    cv.put(CarrierIdentification.GID2, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.GID2);
                    found = true;
                }
                break;
            case PLMN_INDEX:
                for (String str : attr.plmn) {
                    cv.put(CarrierIdentification.PLMN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.PLMN);
                    found = true;
                }
                break;
            case SPN_INDEX:
                for (String str : attr.spn) {
                    cv.put(CarrierIdentification.SPN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.SPN);
                    found = true;
                }
                break;
            case APN_INDEX:
                for (String str : attr.preferredApn) {
                    cv.put(CarrierIdentification.APN, str);
                    convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
                    cv.remove(CarrierIdentification.APN);
                    found = true;
                }
                break;
            default:
                Log.e(TAG, "unsupported index: " + index);
                break;
        }
        // if attribute at index is empty, move forward to the next attribute
        if (!found) {
            convertCarrierAttrToContentValues(cv, cvs, attr, index + 1);
        }
    }

    /**
     * util function to convert inputStream to byte array before parsing proto data.
     */
    private static byte[] readInputStreamToByteArray(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        int nRead;
        int size = 16 * 1024; // Read 16k chunks
        byte[] data = new byte[size];
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        buffer.flush();
        return buffer.toByteArray();
    }

    /**
     * util function to calculate checksum of a file
     */
    private long getChecksum(File file) {
        long checksum = -1;
        try {
            checksum = FileUtils.checksumCrc32(file);
            if (VDBG) Log.d(TAG, "Checksum for " + file.getAbsolutePath() + " is " + checksum);
        } catch (FileNotFoundException e) {
            Log.e(TAG, "FileNotFoundException for " + file.getAbsolutePath() + ":" + e);
        } catch (IOException e) {
            Log.e(TAG, "IOException for " + file.getAbsolutePath() + ":" + e);
        }
        return checksum;
    }

    private long getAssetsChecksum() {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        return sp.getLong(ASSETS_FILE_CHECKSUM_PREF_KEY, -1);
    }

    private void setAssetsChecksum(long checksum) {
        SharedPreferences sp = getContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(ASSETS_FILE_CHECKSUM_PREF_KEY, checksum);
        editor.apply();
    }
}