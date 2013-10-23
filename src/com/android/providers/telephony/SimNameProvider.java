/*
 * Copyright (C) 2007 The Android Open Source Project
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
import android.content.UriMatcher;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.database.sqlite.SQLiteQueryBuilder;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;
import java.util.HashMap;

/**
 * Provides access to a database of sim names.
 * Each row has a sim_imsi, a sim_name, a flag of enabled.
 * BRCM SIM_NAME
 */
public class SimNameProvider extends ContentProvider {

    private static final String TAG = "SimNameProvider";

    private static final String DATABASE_NAME = "simname.db";

    private static final int DATABASE_VERSION = 1;

    private static final String SIMNAME_TABLE_NAME = "simnames";

    private static HashMap<String, String> sChannelsProjectionMap;

    private static final int SIM_NAMES = 1;

    private static final int SIM_NAME_ID = 2;

    private static final UriMatcher sUriMatcher;

    private static final String _ID = "_id";

    private static final String SIM_IMSI = "sim_imsi";

    private static final String SIM_NAME = "sim_name";

    private static final String SIM_NAME_ENABLED = "sim_name_enabled";

    private static final String SIM_IN_USE = "sim_in_use";

    /**
     * The default sort order for this table
     */
    public static final String DEFAULT_SORT_ORDER = "_id DESC";

    public static final String AUTHORITY = "com.broadcom.simname";

    /**
     * The content:// style URL for this table
     */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/simnames");

    /**
     * The MIME type of {@link #CONTENT_URI} providing a directory of channel.
     */
    public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.simname";

    /**
     * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
     * channel.
     */
    public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.simname";

    /**
     * This class helps open, create, and upgrade the database file.
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + SIMNAME_TABLE_NAME + " (" + _ID + " INTEGER PRIMARY KEY,"
                       + SIM_IMSI + " TEXT,"
                       + SIM_NAME + " TEXT,"
                       + SIM_NAME_ENABLED + " INTEGER,"
                       + SIM_IN_USE + " INTEGER" + ");");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            Log.w(TAG, "Upgrading database from version " + oldVersion + " to " + newVersion
                  + ", which will destroy all old data");
            db.execSQL("DROP TABLE IF EXISTS cbchannels");
            onCreate(db);
        }
    }

    private DatabaseHelper mOpenHelper;

    @Override
    public boolean onCreate() {
        mOpenHelper = new DatabaseHelper(getContext());
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
                        String sortOrder) {
        Log.d(TAG, "=>query(): uri = " + uri);
        SQLiteQueryBuilder qb = new SQLiteQueryBuilder();
        qb.setTables(SIMNAME_TABLE_NAME);

        switch (sUriMatcher.match(uri)) {
        case SIM_NAMES:
            qb.setProjectionMap(sChannelsProjectionMap);
            break;

        case SIM_NAME_ID:
            qb.setProjectionMap(sChannelsProjectionMap);
            qb.appendWhere(_ID + "=" + uri.getPathSegments().get(1));
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        // If no sort order is specified use the default
        String orderBy;
        if (TextUtils.isEmpty(sortOrder)) {
            orderBy = DEFAULT_SORT_ORDER;
        } else {
            orderBy = sortOrder;
        }

        // Get the database and run the query
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor c = qb.query(db, projection, selection, selectionArgs, null, null, orderBy);

        // Tell the cursor what uri to watch, so it knows when its source data
        // changes
        c.setNotificationUri(getContext().getContentResolver(), uri);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        switch (sUriMatcher.match(uri)) {
        case SIM_NAMES:
            return CONTENT_TYPE;

        case SIM_NAME_ID:
            return CONTENT_ITEM_TYPE;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        // Validate the requested uri
        if (sUriMatcher.match(uri) != SIM_NAMES) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        ContentValues values;
        if (initialValues != null) {
            values = new ContentValues(initialValues);
        } else {
            values = new ContentValues();
        }

        // Make sure that the fields are all set
        if (values.containsKey(SIM_IMSI) == false) {
            values.put(SIM_IMSI, "0");
        }

        if (values.containsKey(SIM_NAME) == false) {
            values.put(SIM_NAME, "");
        }

        if (values.containsKey(SIM_NAME_ENABLED) == false) {
            values.put(SIM_NAME_ENABLED, 0);
        }

        if (values.containsKey(SIM_IN_USE) == false) {
            values.put(SIM_IN_USE, 0);
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long rowId = db.insert(SIMNAME_TABLE_NAME, null, values);
        if (rowId > 0) {
            Uri cbchannelUri = ContentUris.withAppendedId(CONTENT_URI, rowId);
            getContext().getContentResolver().notifyChange(cbchannelUri, null);
            return cbchannelUri;
        }

        throw new SQLException("Failed to insert row into " + uri);
    }

    @Override
    public int delete(Uri uri, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        switch (sUriMatcher.match(uri)) {
        case SIM_NAMES:
            count = db.delete(SIMNAME_TABLE_NAME, where, whereArgs);
            break;

        case SIM_NAME_ID:
            String cbchannelId = uri.getPathSegments().get(1);
            count = db.delete(SIMNAME_TABLE_NAME,
                              _ID + "=" + cbchannelId
                              + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""),
                              whereArgs);
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    @Override
    public int update(Uri uri, ContentValues values, String where, String[] whereArgs) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        int count;
        switch (sUriMatcher.match(uri)) {
        case SIM_NAMES:
            count = db.update(SIMNAME_TABLE_NAME, values, where, whereArgs);
            break;

        case SIM_NAME_ID:
            String cbchannelId = uri.getPathSegments().get(1);
            count = db.update(SIMNAME_TABLE_NAME, values, _ID + "=" + cbchannelId
                              + (!TextUtils.isEmpty(where) ? " AND (" + where + ')' : ""), whereArgs);
            break;

        default:
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        getContext().getContentResolver().notifyChange(uri, null);
        return count;
    }

    static {
        sUriMatcher = new UriMatcher(UriMatcher.NO_MATCH);
        sUriMatcher.addURI(AUTHORITY, "simnames", SIM_NAMES);
        sUriMatcher.addURI(AUTHORITY, "simnames/#", SIM_NAME_ID);

        sChannelsProjectionMap = new HashMap<String, String>();
        sChannelsProjectionMap.put(_ID, _ID);
        sChannelsProjectionMap.put(SIM_IMSI, SIM_IMSI);
        sChannelsProjectionMap.put(SIM_NAME, SIM_NAME);
        sChannelsProjectionMap.put(SIM_NAME_ENABLED, SIM_NAME_ENABLED);
        sChannelsProjectionMap.put(SIM_IN_USE, SIM_IN_USE);
    }
}
