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
 * Provides access to a database of cell broadcast channels. Each channel has a
 * name, a channel index (0..999), a flag of enabled.
 */
public class CbChannelProvider extends ContentProvider {

    private static final String TAG = "CbChannelProvider";

    private static final String DATABASE_NAME = "cbchannel.db";

    private static final int DATABASE_VERSION = 1;

    private static final String CHANNELS_TABLE_NAME = "cbchannels";

    private static HashMap<String, String> sChannelsProjectionMap;

    private static final int CHANNELS = 1;

    private static final int CHANNEL_ID = 2;

    private static final UriMatcher sUriMatcher;

    private static final String _ID = "_id";

    private static final String CHANNEL_NAME = "channel_name";

    private static final String CHANNEL_INDEX = "channel_index";

    private static final String CHANNEL_ENABLED = "channel_enabled";

    private static final String SIM_ID = "sim_id";

    /**
     * The default sort order for this table
     */
    public static final String DEFAULT_SORT_ORDER = "channel_index DESC";

    public static final String AUTHORITY = "com.broadcom.cellbroadcast";

    /**
     * The content:// style URL for this table
     */
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/cbchannels");

    /**
     * The MIME type of {@link #CONTENT_URI} providing a directory of channel.
     */
    public static final String CONTENT_TYPE = "vnd.android.cursor.dir/vnd.google.cbchannel";

    /**
     * The MIME type of a {@link #CONTENT_URI} sub-directory of a single
     * channel.
     */
    public static final String CONTENT_ITEM_TYPE = "vnd.android.cursor.item/vnd.google.cbchannel";

    /**
     * This class helps open, create, and upgrade the database file.
     */
    private static class DatabaseHelper extends SQLiteOpenHelper {

        DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + CHANNELS_TABLE_NAME + " (" + _ID + " INTEGER PRIMARY KEY,"
                    + CHANNEL_NAME + " TEXT,"
                    + CHANNEL_INDEX + " TEXT,"
                    + CHANNEL_ENABLED + " INTEGER,"
                    + SIM_ID + " INTEGER" + ");");
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
        qb.setTables(CHANNELS_TABLE_NAME);

        switch (sUriMatcher.match(uri)) {
            case CHANNELS:
                qb.setProjectionMap(sChannelsProjectionMap);
                break;

            case CHANNEL_ID:
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
            case CHANNELS:
                return CONTENT_TYPE;

            case CHANNEL_ID:
                return CONTENT_ITEM_TYPE;

            default:
                throw new IllegalArgumentException("Unknown URI " + uri);
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues initialValues) {
        // Validate the requested uri
        if (sUriMatcher.match(uri) != CHANNELS) {
            throw new IllegalArgumentException("Unknown URI " + uri);
        }

        ContentValues values;
        if (initialValues != null) {
            values = new ContentValues(initialValues);
        } else {
            values = new ContentValues();
        }

        // Make sure that the fields are all set
        if (values.containsKey(CHANNEL_INDEX) == false) {
            values.put(CHANNEL_INDEX, "0");
        }

        if (values.containsKey(CHANNEL_ENABLED) == false) {
            values.put(CHANNEL_ENABLED, 1);
        }

        if (values.containsKey(SIM_ID) == false) {
            values.put(SIM_ID, 0);
        }

        if (values.containsKey(CHANNEL_NAME) == false) {
            values.put(CHANNEL_NAME, "");
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        long rowId = db.insert(CHANNELS_TABLE_NAME, CHANNEL_INDEX, values);
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
            case CHANNELS:
                count = db.delete(CHANNELS_TABLE_NAME, where, whereArgs);
                break;

            case CHANNEL_ID:
                String cbchannelId = uri.getPathSegments().get(1);
                count = db.delete(CHANNELS_TABLE_NAME,
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
            case CHANNELS:
                count = db.update(CHANNELS_TABLE_NAME, values, where, whereArgs);
                break;

            case CHANNEL_ID:
                String cbchannelId = uri.getPathSegments().get(1);
                count = db.update(CHANNELS_TABLE_NAME, values, _ID + "=" + cbchannelId
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
        sUriMatcher.addURI(AUTHORITY, "cbchannels", CHANNELS);
        sUriMatcher.addURI(AUTHORITY, "cbchannels/#", CHANNEL_ID);

        sChannelsProjectionMap = new HashMap<String, String>();
        sChannelsProjectionMap.put(_ID, _ID);
        sChannelsProjectionMap.put(CHANNEL_NAME, CHANNEL_NAME);
        sChannelsProjectionMap.put(CHANNEL_INDEX, CHANNEL_INDEX);
        sChannelsProjectionMap.put(CHANNEL_ENABLED, CHANNEL_ENABLED);
        sChannelsProjectionMap.put(SIM_ID, SIM_ID);
    }
}
