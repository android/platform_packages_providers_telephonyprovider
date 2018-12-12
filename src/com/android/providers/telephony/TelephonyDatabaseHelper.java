package com.android.providers.telephony;

import static android.provider.Telephony.Carriers.APN;
import static android.provider.Telephony.Carriers.APN_SET_ID;
import static android.provider.Telephony.Carriers.AUTH_TYPE;
import static android.provider.Telephony.Carriers.BEARER;
import static android.provider.Telephony.Carriers.BEARER_BITMASK;
import static android.provider.Telephony.Carriers.CARRIER_DELETED;
import static android.provider.Telephony.Carriers.CARRIER_DELETED_BUT_PRESENT_IN_XML;
import static android.provider.Telephony.Carriers.CARRIER_EDITED;
import static android.provider.Telephony.Carriers.CARRIER_ENABLED;
import static android.provider.Telephony.Carriers.CARRIER_ID;
import static android.provider.Telephony.Carriers.CURRENT;
import static android.provider.Telephony.Carriers.EDITED;
import static android.provider.Telephony.Carriers.MAX_CONNS;
import static android.provider.Telephony.Carriers.MAX_CONNS_TIME;
import static android.provider.Telephony.Carriers.MCC;
import static android.provider.Telephony.Carriers.MMSC;
import static android.provider.Telephony.Carriers.MMSPORT;
import static android.provider.Telephony.Carriers.MMSPROXY;
import static android.provider.Telephony.Carriers.MNC;
import static android.provider.Telephony.Carriers.MODEM_COGNITIVE;
import static android.provider.Telephony.Carriers.MTU;
import static android.provider.Telephony.Carriers.MVNO_MATCH_DATA;
import static android.provider.Telephony.Carriers.MVNO_TYPE;
import static android.provider.Telephony.Carriers.NAME;
import static android.provider.Telephony.Carriers.NETWORK_TYPE_BITMASK;
import static android.provider.Telephony.Carriers.NO_SET_SET;
import static android.provider.Telephony.Carriers.NUMERIC;
import static android.provider.Telephony.Carriers.OWNED_BY;
import static android.provider.Telephony.Carriers.OWNED_BY_OTHERS;
import static android.provider.Telephony.Carriers.PASSWORD;
import static android.provider.Telephony.Carriers.PORT;
import static android.provider.Telephony.Carriers.PROFILE_ID;
import static android.provider.Telephony.Carriers.PROTOCOL;
import static android.provider.Telephony.Carriers.PROXY;
import static android.provider.Telephony.Carriers.ROAMING_PROTOCOL;
import static android.provider.Telephony.Carriers.SERVER;
import static android.provider.Telephony.Carriers.SUBSCRIPTION_ID;
import static android.provider.Telephony.Carriers.TYPE;
import static android.provider.Telephony.Carriers.UNEDITED;
import static android.provider.Telephony.Carriers.USER;
import static android.provider.Telephony.Carriers.USER_DELETED;
import static android.provider.Telephony.Carriers.USER_DELETED_BUT_PRESENT_IN_XML;
import static android.provider.Telephony.Carriers.USER_EDITABLE;
import static android.provider.Telephony.Carriers.USER_EDITED;
import static android.provider.Telephony.Carriers.USER_VISIBLE;
import static android.provider.Telephony.Carriers.WAIT_TIME;

import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Environment;
import android.os.FileUtils;
import android.provider.Telephony;
import android.telephony.ServiceState;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Log;
import android.util.Xml;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.XmlUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;

public class TelephonyDatabaseHelper {
    private final static String TAG = "TelephonyDatabaseHelper";

    private static final String DATABASE_NAME = "telephony.db";
    private static final int IDLE_CONNECTION_TIMEOUT_MS = 30000;
    static final boolean DBG = true;
    static final boolean VDBG = false; // STOPSHIP if true
    static final int DATABASE_VERSION = 31 << 16;
    static final String CARRIERS_TABLE = "carriers";
    private static final String CARRIERS_TABLE_TMP = "carriers_tmp";
    static final String SIMINFO_TABLE = "siminfo";
    private static final String SIMINFO_TABLE_TMP = "siminfo_tmp";
    private static final String PREF_FILE = "telephonyprovider";
    private static final String APN_CONF_CHECKSUM = "apn_conf_checksum";
    private static final String PARTNER_APNS_PATH = "etc/apns-conf.xml";
    private static final String OEM_APNS_PATH = "telephony/apns-conf.xml";
    private static final String OTA_UPDATED_APNS_PATH = "misc/apns/apns-conf.xml";
    private static final String OLD_APNS_PATH = "etc/old-apns-conf.xml";
    private static final String DEFAULT_PROTOCOL = "IP";
    private static final String DEFAULT_ROAMING_PROTOCOL = "IP";
    static final String IS_UNEDITED = EDITED + "=" + UNEDITED;
    private static final String IS_EDITED = EDITED + "!=" + UNEDITED;
    private static final String IS_USER_DELETED = EDITED + "=" + USER_DELETED;
    private static final String IS_USER_DELETED_BUT_PRESENT_IN_XML =
            EDITED + "=" + USER_DELETED_BUT_PRESENT_IN_XML;
    private static final String IS_CARRIER_DELETED = EDITED + "=" + CARRIER_DELETED;
    private static final String IS_CARRIER_DELETED_BUT_PRESENT_IN_XML =
            EDITED + "=" + CARRIER_DELETED_BUT_PRESENT_IN_XML;
    private static final String ORDER_BY_SUB_ID =
            SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + " ASC";
    static final List<String> CARRIERS_UNIQUE_FIELDS = new ArrayList<String>();

    private static final Set<String> CARRIERS_BOOLEAN_FIELDS = new HashSet<String>();
    private static final Map<String, String> CARRIERS_UNIQUE_FIELDS_DEFAULTS = new HashMap();

    @VisibleForTesting
    static Boolean s_apnSourceServiceExists;

    static {
        // Columns not included in UNIQUE constraint: name, current, edited, user, server, password,
        // authtype, type, protocol, roaming_protocol, sub_id, modem_cognitive, max_conns,
        // wait_time, max_conns_time, mtu, bearer_bitmask, user_visible, network_type_bitmask
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(NUMERIC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MCC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MNC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(APN, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PROXY, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PORT, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MMSPROXY, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MMSPORT, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MMSC, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(CARRIER_ENABLED, "1");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(BEARER, "0");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MVNO_TYPE, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(MVNO_MATCH_DATA, "");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PROFILE_ID, "0");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(PROTOCOL, "IP");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(ROAMING_PROTOCOL, "IP");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(USER_EDITABLE, "1");
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(OWNED_BY, String.valueOf(OWNED_BY_OTHERS));
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(APN_SET_ID, String.valueOf(NO_SET_SET));
        CARRIERS_UNIQUE_FIELDS_DEFAULTS.put(CARRIER_ID,
                String.valueOf(TelephonyManager.UNKNOWN_CARRIER_ID));

        CARRIERS_UNIQUE_FIELDS.addAll(
                CARRIERS_UNIQUE_FIELDS_DEFAULTS.keySet());

        // SQLite databases store bools as ints but the ContentValues objects passed in through
        // queries use bools. As a result there is some special handling of boolean fields within
        // the TelephonyProvider.
        CARRIERS_BOOLEAN_FIELDS.add(CARRIER_ENABLED);
        CARRIERS_BOOLEAN_FIELDS.add(MODEM_COGNITIVE);
        CARRIERS_BOOLEAN_FIELDS.add(USER_VISIBLE);
        CARRIERS_BOOLEAN_FIELDS.add(USER_EDITABLE);
    }

    @VisibleForTesting
    public static String getStringForCarrierTableCreation(String tableName) {
        return "CREATE TABLE " + tableName +
                "(_id INTEGER PRIMARY KEY," +
                NAME + " TEXT DEFAULT ''," +
                NUMERIC + " TEXT DEFAULT ''," +
                MCC + " TEXT DEFAULT ''," +
                MNC + " TEXT DEFAULT ''," +
                CARRIER_ID + " INTEGER DEFAULT " + TelephonyManager.UNKNOWN_CARRIER_ID  + "," +
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
                PROTOCOL + " TEXT DEFAULT " + DEFAULT_PROTOCOL + "," +
                ROAMING_PROTOCOL + " TEXT DEFAULT " + DEFAULT_ROAMING_PROTOCOL + "," +
                CARRIER_ENABLED + " BOOLEAN DEFAULT 1," + // SQLite databases store bools as ints
                BEARER + " INTEGER DEFAULT 0," +
                BEARER_BITMASK + " INTEGER DEFAULT 0," +
                NETWORK_TYPE_BITMASK + " INTEGER DEFAULT 0," +
                MVNO_TYPE + " TEXT DEFAULT ''," +
                MVNO_MATCH_DATA + " TEXT DEFAULT ''," +
                SUBSCRIPTION_ID + " INTEGER DEFAULT " +
                SubscriptionManager.INVALID_SUBSCRIPTION_ID + "," +
                PROFILE_ID + " INTEGER DEFAULT 0," +
                MODEM_COGNITIVE + " BOOLEAN DEFAULT 0," +
                MAX_CONNS + " INTEGER DEFAULT 0," +
                WAIT_TIME + " INTEGER DEFAULT 0," +
                MAX_CONNS_TIME + " INTEGER DEFAULT 0," +
                MTU + " INTEGER DEFAULT 0," +
                EDITED + " INTEGER DEFAULT " + UNEDITED + "," +
                USER_VISIBLE + " BOOLEAN DEFAULT 1," +
                USER_EDITABLE + " BOOLEAN DEFAULT 1," +
                OWNED_BY + " INTEGER DEFAULT " + OWNED_BY_OTHERS + "," +
                APN_SET_ID + " INTEGER DEFAULT " + NO_SET_SET + "," +
                // Uniqueness collisions are used to trigger merge code so if a field is listed
                // here it means we will accept both (user edited + new apn_conf definition)
                // Columns not included in UNIQUE constraint: name, current, edited,
                // user, server, password, authtype, type, sub_id, modem_cognitive, max_conns,
                // wait_time, max_conns_time, mtu, bearer_bitmask, user_visible,
                // network_type_bitmask.
                "UNIQUE (" + TextUtils.join(", ", CARRIERS_UNIQUE_FIELDS) + "));";
    }

    @VisibleForTesting
    public static String getStringForSimInfoTableCreation(String tableName) {
        return "CREATE TABLE " + tableName + "("
                + SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID
                + " INTEGER PRIMARY KEY AUTOINCREMENT,"
                + SubscriptionManager.ICC_ID + " TEXT NOT NULL,"
                + SubscriptionManager.SIM_SLOT_INDEX
                + " INTEGER DEFAULT " + SubscriptionManager.SIM_NOT_INSERTED + ","
                + SubscriptionManager.DISPLAY_NAME + " TEXT,"
                + SubscriptionManager.CARRIER_NAME + " TEXT,"
                + SubscriptionManager.NAME_SOURCE
                + " INTEGER DEFAULT " + SubscriptionManager.NAME_SOURCE_DEFAULT_SOURCE + ","
                + SubscriptionManager.COLOR + " INTEGER DEFAULT "
                + SubscriptionManager.COLOR_DEFAULT + ","
                + SubscriptionManager.NUMBER + " TEXT,"
                + SubscriptionManager.DISPLAY_NUMBER_FORMAT
                + " INTEGER NOT NULL DEFAULT " + SubscriptionManager.DISPLAY_NUMBER_DEFAULT + ","
                + SubscriptionManager.DATA_ROAMING
                + " INTEGER DEFAULT " + SubscriptionManager.DATA_ROAMING_DEFAULT + ","
                + SubscriptionManager.MCC + " INTEGER DEFAULT 0,"
                + SubscriptionManager.MNC + " INTEGER DEFAULT 0,"
                + SubscriptionManager.MCC_STRING + " TEXT,"
                + SubscriptionManager.MNC_STRING + " TEXT,"
                + SubscriptionManager.SIM_PROVISIONING_STATUS
                + " INTEGER DEFAULT " + SubscriptionManager.SIM_PROVISIONED + ","
                + SubscriptionManager.IS_EMBEDDED + " INTEGER DEFAULT 0,"
                + SubscriptionManager.CARD_ID + " TEXT NOT NULL,"
                + SubscriptionManager.ACCESS_RULES + " BLOB,"
                + SubscriptionManager.IS_REMOVABLE + " INTEGER DEFAULT 0,"
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
                + SubscriptionManager.CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1,"
                + SubscriptionManager.ENHANCED_4G_MODE_ENABLED + " INTEGER DEFAULT -1,"
                + SubscriptionManager.VT_IMS_ENABLED + " INTEGER DEFAULT -1,"
                + SubscriptionManager.WFC_IMS_ENABLED + " INTEGER DEFAULT -1,"
                + SubscriptionManager.WFC_IMS_MODE + " INTEGER DEFAULT -1,"
                + SubscriptionManager.WFC_IMS_ROAMING_MODE + " INTEGER DEFAULT -1,"
                + SubscriptionManager.WFC_IMS_ROAMING_ENABLED + " INTEGER DEFAULT -1,"
                + SubscriptionManager.IS_OPPORTUNISTIC + " INTEGER DEFAULT 0,"
                + SubscriptionManager.PARENT_SUB_ID + " INTEGER DEFAULT -1,"
                + SubscriptionManager.GROUP_UUID + " TEXT,"
                + SubscriptionManager.IS_METERED + " INTEGER DEFAULT 1"
                + ");";
    }

    /**
     * Convert "true" and "false" to "1" and "0".
     * If the passed in string is already "1" or "0" returns the passed in string.
     */
    private static String convertStringToIntString(String boolString) {
        if ("0".equals(boolString) || "false".equalsIgnoreCase(boolString)) return "0";
        return "1";
    }

    /**
     * Convert "1" and "0" to "true" and "false".
     * If the passed in string is already "true" or "false" returns the passed in string.
     */
    private static String convertStringToBoolString(String intString) {
        if ("0".equals(intString) || "false".equalsIgnoreCase(intString)) return "false";
        return "true";
    }

    static boolean apnSourceServiceExists(Context context) {
        if (s_apnSourceServiceExists != null) {
            return s_apnSourceServiceExists;
        }
        try {
            String service = context.getResources().getString(R.string.apn_source_service);
            if (TextUtils.isEmpty(service)) {
                s_apnSourceServiceExists = false;
            } else {
                s_apnSourceServiceExists = context.getPackageManager().getServiceInfo(
                        ComponentName.unflattenFromString(service), 0)
                        != null;
            }
        } catch (PackageManager.NameNotFoundException e) {
            s_apnSourceServiceExists = false;
        }
        return s_apnSourceServiceExists;
    }

    public static void fillInMccMncStringAtCursor(Context context, SQLiteDatabase db, Cursor c) {
        int mcc, mnc;
        String subId;
        try {
            mcc = c.getInt(c.getColumnIndexOrThrow(SubscriptionManager.MCC));
            mnc = c.getInt(c.getColumnIndexOrThrow(SubscriptionManager.MNC));
            subId = c.getString(c.getColumnIndexOrThrow(
                    SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID));
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Possible database corruption -- some columns not found.");
            return;
        }

        String mccString = String.format(Locale.getDefault(), "%03d", mcc);
        String mncString = getBestStringMnc(context, mccString, mnc);
        ContentValues cv = new ContentValues(2);
        cv.put(SubscriptionManager.MCC_STRING, mccString);
        cv.put(SubscriptionManager.MNC_STRING, mncString);
        db.update(SIMINFO_TABLE, cv,
                SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID + "=?",
                new String[]{subId});
    }

    /*
     * Find the best string-form mnc by looking up possibilities in the carrier id db.
     * Default to the three-digit version if neither/both are valid.
     */
    private static String getBestStringMnc(Context context, String mcc, int mnc) {
        if (mnc >= 100 && mnc <= 999) {
            return String.valueOf(mnc);
        }
        String twoDigitMnc = String.format(Locale.getDefault(), "%02d", mnc);
        String threeDigitMnc = "0" + twoDigitMnc;

        try (
                Cursor twoDigitMncCursor = context.getContentResolver().query(
                        Telephony.CarrierId.All.CONTENT_URI,
                        /* projection */ null,
                        /* selection */ Telephony.CarrierId.All.MCCMNC + "=?",
                        /* selectionArgs */ new String[]{mcc + twoDigitMnc}, null)
        ) {
            if (twoDigitMncCursor.getCount() > 0) {
                return twoDigitMnc;
            }
            return threeDigitMnc;
        }
    }

    /**
     * Sync the bearer bitmask and network type bitmask when inserting and updating.
     * Since bearerBitmask is deprecating, map the networkTypeBitmask to bearerBitmask if
     * networkTypeBitmask was provided. But if networkTypeBitmask was not provided, map the
     * bearerBitmask to networkTypeBitmask.
     */
    static void syncBearerBitmaskAndNetworkTypeBitmask(ContentValues values) {
        if (values.containsKey(NETWORK_TYPE_BITMASK)) {
            int convertedBitmask = ServiceState.convertNetworkTypeBitmaskToBearerBitmask(
                    values.getAsInteger(NETWORK_TYPE_BITMASK));
            if (values.containsKey(BEARER_BITMASK)
                    && convertedBitmask != values.getAsInteger(BEARER_BITMASK)) {
                loge("Network type bitmask and bearer bitmask are not compatible.");
            }
            values.put(BEARER_BITMASK, ServiceState.convertNetworkTypeBitmaskToBearerBitmask(
                    values.getAsInteger(NETWORK_TYPE_BITMASK)));
        } else {
            if (values.containsKey(BEARER_BITMASK)) {
                int convertedBitmask = ServiceState.convertBearerBitmaskToNetworkTypeBitmask(
                        values.getAsInteger(BEARER_BITMASK));
                values.put(NETWORK_TYPE_BITMASK, convertedBitmask);
            }
        }
    }

    /**
     * Log with debug
     *
     * @param s is string log
     */
    static void log(String s) {
        Log.d(TAG, s);
    }

    static void loge(String s) {
        Log.e(TAG, s);
    }

    @VisibleForTesting
    public static class DatabaseHelper extends SQLiteOpenHelper {
        // Context to access resources with
        private Context mContext;

        /**
         * DatabaseHelper helper class for loading apns into a database.
         *
         * @param context of the user.
         */
        public DatabaseHelper(Context context) {
            super(context, DATABASE_NAME, null, getVersion(context));
            mContext = context;
            // Memory optimization - close idle connections after 30s of inactivity
            setIdleConnectionTimeout(IDLE_CONNECTION_TIMEOUT_MS);
        }

        @VisibleForTesting
        public static int getVersion(Context context) {
            if (VDBG) log("getVersion:+");
            // Get the database version, combining a static schema version and the XML version
            Resources r = context.getResources();
            if (r == null) {
                loge("resources=null, return version=" + Integer.toHexString(DATABASE_VERSION));
                return DATABASE_VERSION;
            }
            XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
            try {
                XmlUtils.beginDocument(parser, "apns");
                int publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                int version = DATABASE_VERSION | publicversion;
                if (VDBG) log("getVersion:- version=0x" + Integer.toHexString(version));
                return version;
            } catch (Exception e) {
                loge("Can't get version of APN database" + e + " return version=" +
                        Integer.toHexString(DATABASE_VERSION));
                return DATABASE_VERSION;
            } finally {
                parser.close();
            }
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            if (DBG) log("dbh.onCreate:+ db=" + db);
            createSimInfoTable(db, SIMINFO_TABLE);
            createCarriersTable(db, CARRIERS_TABLE);
            // if CarrierSettings app is installed, we expect it to do the initializiation instead
            if (apnSourceServiceExists(mContext)) {
                log("dbh.onCreate: Skipping apply APNs from xml.");
            } else {
                log("dbh.onCreate: Apply apns from xml.");
                initDatabase(db);
            }
            if (DBG) log("dbh.onCreate:- db=" + db);
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            if (VDBG) log("dbh.onOpen:+ db=" + db);
            try {
                // Try to access the table and create it if "no such table"
                db.query(SIMINFO_TABLE, null, null, null, null, null, null);
                if (DBG) log("dbh.onOpen: ok, queried table=" + SIMINFO_TABLE);
            } catch (SQLiteException e) {
                loge("Exception " + SIMINFO_TABLE + "e=" + e);
                if (e.getMessage().startsWith("no such table")) {
                    createSimInfoTable(db, SIMINFO_TABLE);
                }
            }
            try {
                db.query(CARRIERS_TABLE, null, null, null, null, null, null);
                if (DBG) log("dbh.onOpen: ok, queried table=" + CARRIERS_TABLE);
            } catch (SQLiteException e) {
                loge("Exception " + CARRIERS_TABLE + " e=" + e);
                if (e.getMessage().startsWith("no such table")) {
                    createCarriersTable(db, CARRIERS_TABLE);
                }
            }
            if (VDBG) log("dbh.onOpen:- db=" + db);
        }

        private void createSimInfoTable(SQLiteDatabase db, String tableName) {
            if (DBG) log("dbh.createSimInfoTable:+ " + tableName);
            db.execSQL(getStringForSimInfoTableCreation(tableName));
            if (DBG) log("dbh.createSimInfoTable:-");
        }

        private void createCarriersTable(SQLiteDatabase db, String tableName) {
            // Set up the database schema
            if (DBG) log("dbh.createCarriersTable: " + tableName);
            db.execSQL(getStringForCarrierTableCreation(tableName));
            if (DBG) log("dbh.createCarriersTable:-");
        }

        private long getChecksum(File file) {
            long checksum = -1;
            try {
                checksum = FileUtils.checksumCrc32(file);
                if (DBG) log("Checksum for " + file.getAbsolutePath() + " is " + checksum);
            } catch (FileNotFoundException e) {
                loge("FileNotFoundException for " + file.getAbsolutePath() + ":" + e);
            } catch (IOException e) {
                loge("IOException for " + file.getAbsolutePath() + ":" + e);
            }

            // The RRO may have been updated in a firmware upgrade. Add checksum for the
            // resources to the total checksum so that apns in an RRO update is not missed.
            try (InputStream inputStream = mContext.getResources().
                        openRawResource(com.android.internal.R.xml.apns)) {
                byte[] array = toByteArray(inputStream);
                CRC32 c = new CRC32();
                c.update(array);
                checksum += c.getValue();
                if (DBG) log("Checksum after adding resource is " + checksum);
            } catch (IOException | Resources.NotFoundException e) {
                loge("Exception when calculating checksum for internal apn resources: " + e);
            }
            return checksum;
        }

        private static byte[] toByteArray(InputStream input) throws IOException {
            byte[] buffer = new byte[128];
            int bytesRead;
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            return output.toByteArray();
        }

        private long getApnConfChecksum() {
            SharedPreferences sp = mContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            return sp.getLong(APN_CONF_CHECKSUM, -1);
        }

        private void setApnConfChecksum(long checksum) {
            SharedPreferences sp = mContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = sp.edit();
            editor.putLong(APN_CONF_CHECKSUM, checksum);
            editor.apply();
        }

        private File getApnConfFile() {
            // Environment.getRootDirectory() is a fancy way of saying ANDROID_ROOT or "/system".
            File confFile = new File(Environment.getRootDirectory(), PARTNER_APNS_PATH);
            File oemConfFile =  new File(Environment.getOemDirectory(), OEM_APNS_PATH);
            File updatedConfFile = new File(Environment.getDataDirectory(), OTA_UPDATED_APNS_PATH);
            confFile = pickSecondIfExists(confFile, oemConfFile);
            confFile = pickSecondIfExists(confFile, updatedConfFile);
            return confFile;
        }

        /**
         * This function computes checksum for the file to be read and compares it against the
         * last read file. DB needs to be updated only if checksum has changed, or old checksum does
         * not exist.
         * @return true if DB should be updated with new conf file, false otherwise
         */
        boolean apnDbUpdateNeeded() {
            File confFile = getApnConfFile();
            long newChecksum = getChecksum(confFile);
            long oldChecksum = getApnConfChecksum();
            if (DBG) log("newChecksum: " + newChecksum);
            if (DBG) log("oldChecksum: " + oldChecksum);
            if (newChecksum == oldChecksum) {
                return false;
            } else {
                return true;
            }
        }

        /**
         *  This function adds APNs from xml file(s) to db. The db may or may not be empty to begin
         *  with.
         */
        void initDatabase(SQLiteDatabase db) {
            if (VDBG) log("dbh.initDatabase:+ db=" + db);
            // Read internal APNS data
            Resources r = mContext.getResources();
            int publicversion = -1;
            if (r != null) {
                XmlResourceParser parser = r.getXml(com.android.internal.R.xml.apns);
                try {
                    XmlUtils.beginDocument(parser, "apns");
                    publicversion = Integer.parseInt(parser.getAttributeValue(null, "version"));
                    loadApns(db, parser);
                } catch (Exception e) {
                    loge("Got exception while loading APN database." + e);
                } finally {
                    parser.close();
                }
            } else {
                loge("initDatabase: resources=null");
            }

            // Read external APNS data (partner-provided)
            XmlPullParser confparser = null;
            File confFile = getApnConfFile();

            FileReader confreader = null;
            if (DBG) log("confFile = " + confFile);
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                // Sanity check. Force internal version and confidential versions to agree
                int confversion = Integer.parseInt(confparser.getAttributeValue(null, "version"));
                if (publicversion != confversion) {
                    log("initDatabase: throwing exception due to version mismatch");
                    throw new IllegalStateException("Internal APNS file version doesn't match "
                            + confFile.getAbsolutePath());
                }

                loadApns(db, confparser);
            } catch (FileNotFoundException e) {
                // It's ok if the file isn't found. It means there isn't a confidential file
                // Log.e(TAG, "File not found: '" + confFile.getAbsolutePath() + "'");
            } catch (Exception e) {
                loge("initDatabase: Exception while parsing '" + confFile.getAbsolutePath() + "'" +
                        e);
            } finally {
                // Get rid of user/carrier deleted entries that are not present in apn xml file.
                // Those entries have edited value USER_DELETED/CARRIER_DELETED.
                if (VDBG) {
                    log("initDatabase: deleting USER_DELETED and replacing "
                            + "DELETED_BUT_PRESENT_IN_XML with DELETED");
                }

                // Delete USER_DELETED
                db.delete(CARRIERS_TABLE, IS_USER_DELETED + " or " + IS_CARRIER_DELETED, null);

                // Change USER_DELETED_BUT_PRESENT_IN_XML to USER_DELETED
                ContentValues cv = new ContentValues();
                cv.put(EDITED, USER_DELETED);
                db.update(CARRIERS_TABLE, cv, IS_USER_DELETED_BUT_PRESENT_IN_XML, null);

                // Change CARRIER_DELETED_BUT_PRESENT_IN_XML to CARRIER_DELETED
                cv = new ContentValues();
                cv.put(EDITED, CARRIER_DELETED);
                db.update(CARRIERS_TABLE, cv, IS_CARRIER_DELETED_BUT_PRESENT_IN_XML, null);

                if (confreader != null) {
                    try {
                        confreader.close();
                    } catch (IOException e) {
                        // do nothing
                    }
                }

                // Update the stored checksum
                setApnConfChecksum(getChecksum(confFile));
            }
            if (VDBG) log("dbh.initDatabase:- db=" + db);

        }

        private File pickSecondIfExists(File sysApnFile, File altApnFile) {
            if (altApnFile.exists()) {
                if (DBG) log("Load APNs from " + altApnFile.getPath() +
                        " instead of " + sysApnFile.getPath());
                return altApnFile;
            } else {
                if (DBG) log("Load APNs from " + sysApnFile.getPath() +
                        " instead of " + altApnFile.getPath());
                return sysApnFile;
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (DBG) {
                log("dbh.onUpgrade:+ db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }

            if (oldVersion < (5 << 16 | 6)) {
                // 5 << 16 is the Database version and 6 in the xml version.

                // This change adds a new authtype column to the database.
                // The auth type column can have 4 values: 0 (None), 1 (PAP), 2 (CHAP)
                // 3 (PAP or CHAP). To avoid breaking compatibility, with already working
                // APNs, the unset value (-1) will be used. If the value is -1.
                // the authentication will default to 0 (if no user / password) is specified
                // or to 3. Currently, there have been no reported problems with
                // pre-configured APNs and hence it is set to -1 for them. Similarly,
                // if the user, has added a new APN, we set the authentication type
                // to -1.

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN authtype INTEGER DEFAULT -1;");

                oldVersion = 5 << 16 | 6;
            }
            if (oldVersion < (6 << 16 | 6)) {
                // Add protcol fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN protocol TEXT DEFAULT IP;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN roaming_protocol TEXT DEFAULT IP;");
                oldVersion = 6 << 16 | 6;
            }
            if (oldVersion < (7 << 16 | 6)) {
                // Add carrier_enabled, bearer fields to the APN. The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN carrier_enabled BOOLEAN DEFAULT 1;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN bearer INTEGER DEFAULT 0;");
                oldVersion = 7 << 16 | 6;
            }
            if (oldVersion < (8 << 16 | 6)) {
                // Add mvno_type, mvno_match_data fields to the APN.
                // The XML file does not change.
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mvno_type TEXT DEFAULT '';");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mvno_match_data TEXT DEFAULT '';");
                oldVersion = 8 << 16 | 6;
            }
            if (oldVersion < (9 << 16 | 6)) {
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN sub_id INTEGER DEFAULT " +
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID + ";");
                oldVersion = 9 << 16 | 6;
            }
            if (oldVersion < (10 << 16 | 6)) {
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN profile_id INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN modem_cognitive BOOLEAN DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN max_conns INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN wait_time INTEGER DEFAULT 0;");
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN max_conns_time INTEGER DEFAULT 0;");
                oldVersion = 10 << 16 | 6;
            }
            if (oldVersion < (11 << 16 | 6)) {
                db.execSQL("ALTER TABLE " + CARRIERS_TABLE +
                        " ADD COLUMN mtu INTEGER DEFAULT 0;");
                oldVersion = 11 << 16 | 6;
            }
            if (oldVersion < (12 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + SubscriptionManager.MCC + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + SubscriptionManager.MNC + " INTEGER DEFAULT 0;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 12 << 16 | 6;
            }
            if (oldVersion < (13 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            SubscriptionManager.CARRIER_NAME + " TEXT DEFAULT '';");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 13 << 16 | 6;
            }
            if (oldVersion < (14 << 16 | 6)) {
                // Do nothing. This is to avoid recreating table twice. Table is anyway recreated
                // for next version and that takes care of updates for this version as well.
                // This version added a new column user_edited to carriers db.
            }
            if (oldVersion < (15 << 16 | 6)) {
                // Most devices should be upgrading from version 13. On upgrade new db will be
                // populated from the xml included in OTA but user and carrier edited/added entries
                // need to be preserved. This new version also adds new columns EDITED and
                // BEARER_BITMASK to the table. Upgrade steps from version 13 are:
                // 1. preserve user and carrier added/edited APNs (by comparing against
                // old-apns-conf.xml included in OTA) - done in preserveUserAndCarrierApns()
                // 2. add new columns EDITED and BEARER_BITMASK (create a new table for that) - done
                // in createCarriersTable()
                // 3. copy over preserved APNs from old table to new table - done in
                // copyPreservedApnsToNewTable()
                // The only exception if upgrading from version 14 is that EDITED field is already
                // present (but is called USER_EDITED)
                /*********************************************************************************
                 * IMPORTANT NOTE: SINCE CARRIERS TABLE IS RECREATED HERE, IT WILL BE THE LATEST
                 * VERSION AFTER THIS. AS A RESULT ANY SUBSEQUENT UPDATES TO THE TABLE WILL FAIL
                 * (DUE TO COLUMN-ALREADY-EXISTS KIND OF EXCEPTION). ALL SUBSEQUENT UPDATES SHOULD
                 * HANDLE THAT GRACEFULLY.
                 *********************************************************************************/
                Cursor c;
                String[] proj = {"_id"};
                if (VDBG) {
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- before upgrading total number of rows: " + c.getCount());
                }

                // Compare db with old apns xml file so that any user or carrier edited/added
                // entries can be preserved across upgrade
                preserveUserAndCarrierApns(db);

                c = db.query(CARRIERS_TABLE, null, null, null, null, null, null);

                if (VDBG) {
                    log("dbh.onUpgrade:- after preserveUserAndCarrierApns() total number of " +
                            "rows: " + ((c == null) ? 0 : c.getCount()));
                }

                createCarriersTable(db, CARRIERS_TABLE_TMP);

                copyPreservedApnsToNewTable(db, c);
                c.close();

                db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE);

                db.execSQL("ALTER TABLE " + CARRIERS_TABLE_TMP + " rename to " + CARRIERS_TABLE +
                        ";");

                if (VDBG) {
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, IS_UNEDITED, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with " + IS_UNEDITED +
                            ": " + c.getCount());
                    c.close();
                    c = db.query(CARRIERS_TABLE, proj, IS_EDITED, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with " + IS_EDITED +
                            ": " + c.getCount());
                    c.close();
                }

                oldVersion = 15 << 16 | 6;
            }
            if (oldVersion < (16 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    // These columns may already be present in which case execSQL will throw an
                    // exception
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_EXTREME_THREAT_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_SEVERE_THREAT_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_AMBER_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_EMERGENCY_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_SOUND_DURATION + " INTEGER DEFAULT 4;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_REMINDER_INTERVAL + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_VIBRATE + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ALERT_SPEECH + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_ETWS_TEST_ALERT + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_CHANNEL_50_ALERT + " INTEGER DEFAULT 1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_CMAS_TEST_ALERT + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.CB_OPT_OUT_DIALOG + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 16 << 16 | 6;
            }
            if (oldVersion < (17 << 16 | 6)) {
                Cursor c = null;
                try {
                    c = db.query(CARRIERS_TABLE, null, null, null, null, null, null,
                            String.valueOf(1));
                    if (c == null || c.getColumnIndex(USER_VISIBLE) == -1) {
                        db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                                USER_VISIBLE + " BOOLEAN DEFAULT 1;");
                    } else {
                        if (DBG) {
                            log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade.  Column " +
                                    USER_VISIBLE + " already exists.");
                        }
                    }
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
                oldVersion = 17 << 16 | 6;
            }
            if (oldVersion < (18 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            SubscriptionManager.SIM_PROVISIONING_STATUS + " INTEGER DEFAULT " +
                            SubscriptionManager.SIM_PROVISIONED + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                oldVersion = 18 << 16 | 6;
            }
            if (oldVersion < (19 << 16 | 6)) {
                // Do nothing. This is to avoid recreating table twice. Table is anyway recreated
                // for version 24 and that takes care of updates for this version as well.
                // This version added more fields protocol and roaming protocol to the primary key.
            }
            if (oldVersion < (20 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            SubscriptionManager.IS_EMBEDDED + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            SubscriptionManager.ACCESS_RULES + " BLOB;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN " +
                            SubscriptionManager.IS_REMOVABLE + " INTEGER DEFAULT 0;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 20 << 16 | 6;
            }
            if (oldVersion < (21 << 16 | 6)) {
                try {
                    // Try to update the carriers table. It might not be there.
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            USER_EDITABLE + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    // This is possible if the column already exists which may be the case if the
                    // table was just created as part of upgrade to version 19
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 21 << 16 | 6;
            }
            if (oldVersion < (22 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.ENHANCED_4G_MODE_ENABLED
                            + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.VT_IMS_ENABLED + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.WFC_IMS_ENABLED + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.WFC_IMS_MODE + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.WFC_IMS_ROAMING_MODE + " INTEGER DEFAULT -1;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.WFC_IMS_ROAMING_ENABLED + " INTEGER DEFAULT -1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 22 << 16 | 6;
            }
            if (oldVersion < (23 << 16 | 6)) {
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            OWNED_BY + " INTEGER DEFAULT " + OWNED_BY_OTHERS + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 23 << 16 | 6;
            }
            if (oldVersion < (24 << 16 | 6)) {
                Cursor c = null;
                String[] proj = {"_id"};
                recreateDB(db, proj, /* version */24);
                if (VDBG) {
                    c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(
                            CARRIERS_TABLE, proj, NETWORK_TYPE_BITMASK, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with "
                            + NETWORK_TYPE_BITMASK + ": " + c.getCount());
                    c.close();
                }
                oldVersion = 24 << 16 | 6;
            }
            if (oldVersion < (25 << 16 | 6)) {
                // Add a new column SubscriptionManager.CARD_ID into the database and set the value
                // to be the same as the existing column SubscriptionManager.ICC_ID. In order to do
                // this, we need to first make a copy of the existing SIMINFO_TABLE, set the value
                // of the new column SubscriptionManager.CARD_ID, and replace the SIMINFO_TABLE with
                // the new table.
                Cursor c = null;
                String[] proj = {SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID};
                recreateSimInfoDB(c, db, proj);
                if (VDBG) {
                    c = db.query(SIMINFO_TABLE, proj, null, null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading " + SIMINFO_TABLE
                            + " total number of rows: " + c.getCount());
                    c.close();
                    c = db.query(SIMINFO_TABLE, proj, SubscriptionManager.CARD_ID + " IS NOT NULL",
                            null, null, null, null);
                    log("dbh.onUpgrade:- after upgrading total number of rows with "
                            + SubscriptionManager.CARD_ID + ": " + c.getCount());
                    c.close();
                }
                oldVersion = 25 << 16 | 6;
            }
            if (oldVersion < (26 << 16 | 6)) {
                // Add a new column Carriers.APN_SET_ID into the database and set the value to
                // Carriers.NO_SET_SET by default.
                try {
                    db.execSQL("ALTER TABLE " + CARRIERS_TABLE + " ADD COLUMN " +
                            APN_SET_ID + " INTEGER DEFAULT " + NO_SET_SET + ";");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 26 << 16 | 6;
            }

            if (oldVersion < (27 << 16 | 6)) {
                // Add the new MCC_STRING and MNC_STRING columns into the subscription table,
                // and attempt to populate them.
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + SubscriptionManager.MCC_STRING + " TEXT;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE +
                            " ADD COLUMN " + SubscriptionManager.MNC_STRING + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                " The table will get created in onOpen.");
                    }
                }
                // Migrate the old integer values over to strings
                String[] proj = {SubscriptionManager.UNIQUE_KEY_SUBSCRIPTION_ID,
                        SubscriptionManager.MCC, SubscriptionManager.MNC};
                try (Cursor c = db.query(SIMINFO_TABLE, proj, null, null, null, null, null)) {
                    while (c.moveToNext()) {
                        fillInMccMncStringAtCursor(mContext, db, c);
                    }
                }
                oldVersion = 27 << 16 | 6;
            }

            if (oldVersion < (28 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.IS_OPPORTUNISTIC + " INTEGER DEFAULT 0;");
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.PARENT_SUB_ID + " INTEGER DEFAULT -1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 28 << 16 | 6;
            }

            if (oldVersion < (29 << 16 | 6)) {
                try {
                    // Add a new column Telephony.CARRIER_ID into the database and add UNIQUE
                    // constraint into table. However, sqlite cannot add constraints to an existing
                    // table, so recreate the table.
                    String[] proj = {"_id"};
                    recreateDB(db, proj,  /* version */29);
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + CARRIERS_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 29 << 16 | 6;
            }

            if (oldVersion < (30 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                        + SubscriptionManager.GROUP_UUID + " TEXT;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                            "The table will get created in onOpen.");
                    }
                }
                oldVersion = 30 << 16 | 6;
            }

            if (oldVersion < (31 << 16 | 6)) {
                try {
                    // Try to update the siminfo table. It might not be there.
                    db.execSQL("ALTER TABLE " + SIMINFO_TABLE + " ADD COLUMN "
                            + SubscriptionManager.IS_METERED + " INTEGER DEFAULT 1;");
                } catch (SQLiteException e) {
                    if (DBG) {
                        log("onUpgrade skipping " + SIMINFO_TABLE + " upgrade. " +
                                "The table will get created in onOpen.");
                    }
                }
                oldVersion = 31 << 16 | 6;
            }

            if (DBG) {
                log("dbh.onUpgrade:- db=" + db + " oldV=" + oldVersion + " newV=" + newVersion);
            }
            // when adding fields to onUpgrade, also add a unit test to TelephonyDatabaseHelperTest
            // and update the DATABASE_VERSION field and add a column in copyAllApnValues
        }

        private void recreateSimInfoDB(Cursor c, SQLiteDatabase db, String[] proj) {
            if (VDBG) {
                c = db.query(SIMINFO_TABLE, proj, null, null, null, null, null);
                log("dbh.onUpgrade:+ before upgrading " + SIMINFO_TABLE +
                        " total number of rows: " + c.getCount());
                c.close();
            }

            // Sort in ascending order by subscription id to make sure the rows do not get flipped
            // during the query and added in the new sim info table in another order (sub id is
            // stored in settings between migrations).
            c = db.query(SIMINFO_TABLE, null, null, null, null, null, ORDER_BY_SUB_ID);

            db.execSQL("DROP TABLE IF EXISTS " + SIMINFO_TABLE_TMP);

            createSimInfoTable(db, SIMINFO_TABLE_TMP);

            copySimInfoDataToTmpTable(db, c);
            c.close();

            db.execSQL("DROP TABLE IF EXISTS " + SIMINFO_TABLE);

            db.execSQL("ALTER TABLE " + SIMINFO_TABLE_TMP + " rename to " + SIMINFO_TABLE + ";");

        }

        private void copySimInfoDataToTmpTable(SQLiteDatabase db, Cursor c) {
            // Move entries from SIMINFO_TABLE to SIMINFO_TABLE_TMP
            if (c != null) {
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    copySimInfoValuesV24(cv, c);
                    // The card ID is supposed to be the ICCID of the profile for UICC card, and
                    // the EID of the card for eUICC card. Since EID is unknown for old entries in
                    // SIMINFO_TABLE, we use ICCID as the card ID for all the old entries while
                    // upgrading the SIMINFO_TABLE. In UiccController, both the card ID and ICCID
                    // will be checked when user queries the slot information using the card ID
                    // from the database.
                    getCardIdfromIccid(cv, c);
                    try {
                        db.insert(SIMINFO_TABLE_TMP, null, cv);
                        if (VDBG) {
                            log("dbh.copySimInfoDataToTmpTable: db.insert returned >= 0; " +
                                "insert successful for cv " + cv);
                        }
                    } catch (SQLException e) {
                        if (VDBG)
                            log("dbh.copySimInfoDataToTmpTable insertWithOnConflict exception " +
                                e + " for cv " + cv);
                    }
                }
            }
        }

        private void copySimInfoValuesV24(ContentValues cv, Cursor c) {
            // String vals
            getStringValueFromCursor(cv, c, SubscriptionManager.ICC_ID);
            getStringValueFromCursor(cv, c, SubscriptionManager.DISPLAY_NAME);
            getStringValueFromCursor(cv, c, SubscriptionManager.CARRIER_NAME);
            getStringValueFromCursor(cv, c, SubscriptionManager.NUMBER);

            // bool/int vals
            getIntValueFromCursor(cv, c, SubscriptionManager.SIM_SLOT_INDEX);
            getIntValueFromCursor(cv, c, SubscriptionManager.NAME_SOURCE);
            getIntValueFromCursor(cv, c, SubscriptionManager.COLOR);
            getIntValueFromCursor(cv, c, SubscriptionManager.DISPLAY_NUMBER_FORMAT);
            getIntValueFromCursor(cv, c, SubscriptionManager.DATA_ROAMING);
            getIntValueFromCursor(cv, c, SubscriptionManager.MCC);
            getIntValueFromCursor(cv, c, SubscriptionManager.MNC);
            getIntValueFromCursor(cv, c, SubscriptionManager.SIM_PROVISIONING_STATUS);
            getIntValueFromCursor(cv, c, SubscriptionManager.IS_EMBEDDED);
            getIntValueFromCursor(cv, c, SubscriptionManager.IS_REMOVABLE);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_EXTREME_THREAT_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_SEVERE_THREAT_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_AMBER_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_EMERGENCY_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_ALERT_SOUND_DURATION);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_ALERT_REMINDER_INTERVAL);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_ALERT_VIBRATE);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_ALERT_SPEECH);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_ETWS_TEST_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_CHANNEL_50_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_CMAS_TEST_ALERT);
            getIntValueFromCursor(cv, c, SubscriptionManager.CB_OPT_OUT_DIALOG);
            getIntValueFromCursor(cv, c, SubscriptionManager.ENHANCED_4G_MODE_ENABLED);
            getIntValueFromCursor(cv, c, SubscriptionManager.VT_IMS_ENABLED);
            getIntValueFromCursor(cv, c, SubscriptionManager.WFC_IMS_ENABLED);
            getIntValueFromCursor(cv, c, SubscriptionManager.WFC_IMS_MODE);
            getIntValueFromCursor(cv, c, SubscriptionManager.WFC_IMS_ROAMING_MODE);
            getIntValueFromCursor(cv, c, SubscriptionManager.WFC_IMS_ROAMING_ENABLED);

            // Blob vals
            getBlobValueFromCursor(cv, c, SubscriptionManager.ACCESS_RULES);
        }

        private void getCardIdfromIccid(ContentValues cv, Cursor c) {
            int columnIndex = c.getColumnIndex(SubscriptionManager.ICC_ID);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor)) {
                    cv.put(SubscriptionManager.CARD_ID, fromCursor);
                }
            }
        }

        private void recreateDB(SQLiteDatabase db, String[] proj, int version) {
            // Upgrade steps are:
            // 1. Create a temp table- done in createCarriersTable()
            // 2. copy over APNs from old table to new table - done in copyDataToTmpTable()
            // 3. Drop the existing table.
            // 4. Copy over the tmp table.
            Cursor c;
            if (VDBG) {
                c = db.query(CARRIERS_TABLE, proj, null, null, null, null, null);
                log("dbh.onUpgrade:- before upgrading total number of rows: " + c.getCount());
                c.close();
            }

            c = db.query(CARRIERS_TABLE, null, null, null, null, null, null);

            if (VDBG) {
                log("dbh.onUpgrade:- starting data copy of existing rows: " +
                        + ((c == null) ? 0 : c.getCount()));
            }

            db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE_TMP);

            createCarriersTable(db, CARRIERS_TABLE_TMP);

            copyDataToTmpTable(db, c, version);
            c.close();

            db.execSQL("DROP TABLE IF EXISTS " + CARRIERS_TABLE);

            db.execSQL("ALTER TABLE " + CARRIERS_TABLE_TMP + " rename to " + CARRIERS_TABLE + ";");
        }

        private void preserveUserAndCarrierApns(SQLiteDatabase db) {
            if (VDBG) log("preserveUserAndCarrierApns");
            XmlPullParser confparser;
            File confFile = new File(Environment.getRootDirectory(), OLD_APNS_PATH);
            FileReader confreader = null;
            try {
                confreader = new FileReader(confFile);
                confparser = Xml.newPullParser();
                confparser.setInput(confreader);
                XmlUtils.beginDocument(confparser, "apns");

                deleteMatchingApns(db, confparser);
            } catch (FileNotFoundException e) {
                // This function is called only when upgrading db to version 15. Details about the
                // upgrade are mentioned in onUpgrade(). This file missing means user/carrier added
                // APNs cannot be preserved. Log an error message so that OEMs know they need to
                // include old apns file for comparison.
                loge("PRESERVEUSERANDCARRIERAPNS: " + OLD_APNS_PATH +
                        " NOT FOUND. IT IS NEEDED TO UPGRADE FROM OLDER VERSIONS OF APN " +
                        "DB WHILE PRESERVING USER/CARRIER ADDED/EDITED ENTRIES.");
            } catch (Exception e) {
                loge("preserveUserAndCarrierApns: Exception while parsing '" +
                        confFile.getAbsolutePath() + "'" + e);
            } finally {
                if (confreader != null) {
                    try {
                        confreader.close();
                    } catch (IOException e) {
                        // do nothing
                    }
                }
            }
        }

        private void deleteMatchingApns(SQLiteDatabase db, XmlPullParser parser) {
            if (VDBG) log("deleteMatchingApns");
            if (parser != null) {
                if (VDBG) log("deleteMatchingApns: parser != null");
                try {
                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        ContentValues row = getRow(parser);
                        if (row == null) {
                            throw new XmlPullParserException("Expected 'apn' tag", parser, null);
                        }
                        deleteRow(db, row);
                        XmlUtils.nextElement(parser);
                    }
                } catch (XmlPullParserException e) {
                    loge("deleteMatchingApns: Got XmlPullParserException while deleting apns." + e);
                } catch (IOException e) {
                    loge("deleteMatchingApns: Got IOException while deleting apns." + e);
                } catch (SQLException e) {
                    loge("deleteMatchingApns: Got SQLException while deleting apns." + e);
                }
            }
        }

        private String queryValFirst(String field) {
            return field + "=?";
        }

        private String queryVal(String field) {
            return " and " + field + "=?";
        }

        private String queryValOrNull(String field) {
            return " and (" + field + "=? or " + field + " is null)";
        }

        private String queryVal2OrNull(String field) {
            return " and (" + field + "=? or " + field + "=? or " + field + " is null)";
        }

        private void deleteRow(SQLiteDatabase db, ContentValues values) {
            if (VDBG) log("deleteRow");
            String where = queryValFirst(NUMERIC) +
                    queryVal(MNC) +
                    queryVal(MNC) +
                    queryValOrNull(APN) +
                    queryValOrNull(USER) +
                    queryValOrNull(SERVER) +
                    queryValOrNull(PASSWORD) +
                    queryValOrNull(PROXY) +
                    queryValOrNull(PORT) +
                    queryValOrNull(MMSPROXY) +
                    queryValOrNull(MMSPORT) +
                    queryValOrNull(MMSC) +
                    queryValOrNull(AUTH_TYPE) +
                    queryValOrNull(TYPE) +
                    queryValOrNull(PROTOCOL) +
                    queryValOrNull(ROAMING_PROTOCOL) +
                    queryVal2OrNull(CARRIER_ENABLED) +
                    queryValOrNull(BEARER) +
                    queryValOrNull(MVNO_TYPE) +
                    queryValOrNull(MVNO_MATCH_DATA) +
                    queryValOrNull(PROFILE_ID) +
                    queryVal2OrNull(MODEM_COGNITIVE) +
                    queryValOrNull(MAX_CONNS) +
                    queryValOrNull(WAIT_TIME) +
                    queryValOrNull(MAX_CONNS_TIME) +
                    queryValOrNull(MTU);
            String[] whereArgs = new String[29];
            int i = 0;
            whereArgs[i++] = values.getAsString(NUMERIC);
            whereArgs[i++] = values.getAsString(MCC);
            whereArgs[i++] = values.getAsString(MNC);
            whereArgs[i++] = values.getAsString(NAME);
            whereArgs[i++] = values.containsKey(APN) ?
                    values.getAsString(APN) : "";
            whereArgs[i++] = values.containsKey(USER) ?
                    values.getAsString(USER) : "";
            whereArgs[i++] = values.containsKey(SERVER) ?
                    values.getAsString(SERVER) : "";
            whereArgs[i++] = values.containsKey(PASSWORD) ?
                    values.getAsString(PASSWORD) : "";
            whereArgs[i++] = values.containsKey(PROXY) ?
                    values.getAsString(PROXY) : "";
            whereArgs[i++] = values.containsKey(PORT) ?
                    values.getAsString(PORT) : "";
            whereArgs[i++] = values.containsKey(MMSPROXY) ?
                    values.getAsString(MMSPROXY) : "";
            whereArgs[i++] = values.containsKey(MMSPORT) ?
                    values.getAsString(MMSPORT) : "";
            whereArgs[i++] = values.containsKey(MMSC) ?
                    values.getAsString(MMSC) : "";
            whereArgs[i++] = values.containsKey(AUTH_TYPE) ?
                    values.getAsString(AUTH_TYPE) : "-1";
            whereArgs[i++] = values.containsKey(TYPE) ?
                    values.getAsString(TYPE) : "";
            whereArgs[i++] = values.containsKey(PROTOCOL) ?
                    values.getAsString(PROTOCOL) : DEFAULT_PROTOCOL;
            whereArgs[i++] = values.containsKey(ROAMING_PROTOCOL) ?
                    values.getAsString(ROAMING_PROTOCOL) : DEFAULT_ROAMING_PROTOCOL;

            if (values.containsKey(CARRIER_ENABLED)) {
                whereArgs[i++] = convertStringToBoolString(values.getAsString(CARRIER_ENABLED));
                whereArgs[i++] = convertStringToIntString(values.getAsString(CARRIER_ENABLED));
            } else {
                String defaultIntString = CARRIERS_UNIQUE_FIELDS_DEFAULTS.get(CARRIER_ENABLED);
                whereArgs[i++] = convertStringToBoolString(defaultIntString);
                whereArgs[i++] = defaultIntString;
            }

            whereArgs[i++] = values.containsKey(BEARER) ?
                    values.getAsString(BEARER) : "0";
            whereArgs[i++] = values.containsKey(MVNO_TYPE) ?
                    values.getAsString(MVNO_TYPE) : "";
            whereArgs[i++] = values.containsKey(MVNO_MATCH_DATA) ?
                    values.getAsString(MVNO_MATCH_DATA) : "";
            whereArgs[i++] = values.containsKey(PROFILE_ID) ?
                    values.getAsString(PROFILE_ID) : "0";

            if (values.containsKey(MODEM_COGNITIVE) &&
                    (values.getAsString(MODEM_COGNITIVE).
                            equalsIgnoreCase("true") ||
                            values.getAsString(MODEM_COGNITIVE).equals("1"))) {
                whereArgs[i++] = "true";
                whereArgs[i++] = "1";
            } else {
                whereArgs[i++] = "false";
                whereArgs[i++] = "0";
            }

            whereArgs[i++] = values.containsKey(MAX_CONNS) ?
                    values.getAsString(MAX_CONNS) : "0";
            whereArgs[i++] = values.containsKey(WAIT_TIME) ?
                    values.getAsString(WAIT_TIME) : "0";
            whereArgs[i++] = values.containsKey(MAX_CONNS_TIME) ?
                    values.getAsString(MAX_CONNS_TIME) : "0";
            whereArgs[i++] = values.containsKey(MTU) ?
                    values.getAsString(MTU) : "0";

            if (VDBG) {
                log("deleteRow: where: " + where);

                StringBuilder builder = new StringBuilder();
                for (String s : whereArgs) {
                    builder.append(s + ", ");
                }

                log("deleteRow: whereArgs: " + builder.toString());
            }
            db.delete(CARRIERS_TABLE, where, whereArgs);
        }

        private void copyDataToTmpTable(SQLiteDatabase db, Cursor c, int version) {
            // Move entries from CARRIERS_TABLE to CARRIERS_TABLE_TMP
            if (c != null) {
                while (c.moveToNext()) {
                    ContentValues cv = new ContentValues();
                    copyAllApnValues(cv, c);
                    if (version == 24) {
                        // Sync bearer bitmask and network type bitmask
                        getNetworkTypeBitmaskFromCursor(cv, c);
                    }
                    try {
                        db.insertWithOnConflict(CARRIERS_TABLE_TMP, null, cv,
                                SQLiteDatabase.CONFLICT_ABORT);
                        if (VDBG) {
                            log("dbh.copyPreservedApnsToNewTable: db.insert returned >= 0; " +
                                    "insert successful for cv " + cv);
                        }
                    } catch (SQLException e) {
                        if (VDBG)
                            log("dbh.copyPreservedApnsToNewTable insertWithOnConflict exception " +
                                    e + " for cv " + cv);
                    }
                }
            }
        }

        private void copyApnValuesV17(ContentValues cv, Cursor c) {
            // Include only non-null values in cv so that null values can be replaced
            // with default if there's a default value for the field

            // String vals
            getStringValueFromCursor(cv, c, NAME);
            getStringValueFromCursor(cv, c, NUMERIC);
            getStringValueFromCursor(cv, c, MCC);
            getStringValueFromCursor(cv, c, MNC);
            getStringValueFromCursor(cv, c, APN);
            getStringValueFromCursor(cv, c, USER);
            getStringValueFromCursor(cv, c, SERVER);
            getStringValueFromCursor(cv, c, PASSWORD);
            getStringValueFromCursor(cv, c, PROXY);
            getStringValueFromCursor(cv, c, PORT);
            getStringValueFromCursor(cv, c, MMSPROXY);
            getStringValueFromCursor(cv, c, MMSPORT);
            getStringValueFromCursor(cv, c, MMSC);
            getStringValueFromCursor(cv, c, TYPE);
            getStringValueFromCursor(cv, c, PROTOCOL);
            getStringValueFromCursor(cv, c, ROAMING_PROTOCOL);
            getStringValueFromCursor(cv, c, MVNO_TYPE);
            getStringValueFromCursor(cv, c, MVNO_MATCH_DATA);

            // bool/int vals
            getIntValueFromCursor(cv, c, AUTH_TYPE);
            getIntValueFromCursor(cv, c, CURRENT);
            getIntValueFromCursor(cv, c, CARRIER_ENABLED);
            getIntValueFromCursor(cv, c, BEARER);
            getIntValueFromCursor(cv, c, SUBSCRIPTION_ID);
            getIntValueFromCursor(cv, c, PROFILE_ID);
            getIntValueFromCursor(cv, c, MODEM_COGNITIVE);
            getIntValueFromCursor(cv, c, MAX_CONNS);
            getIntValueFromCursor(cv, c, WAIT_TIME);
            getIntValueFromCursor(cv, c, MAX_CONNS_TIME);
            getIntValueFromCursor(cv, c, MTU);
            getIntValueFromCursor(cv, c, BEARER_BITMASK);
            getIntValueFromCursor(cv, c, EDITED);
            getIntValueFromCursor(cv, c, USER_VISIBLE);
        }

        private void copyAllApnValues(ContentValues cv, Cursor c) {
            // String vals
            getStringValueFromCursor(cv, c, NAME);
            getStringValueFromCursor(cv, c, NUMERIC);
            getStringValueFromCursor(cv, c, MCC);
            getStringValueFromCursor(cv, c, MNC);
            getStringValueFromCursor(cv, c, APN);
            getStringValueFromCursor(cv, c, USER);
            getStringValueFromCursor(cv, c, SERVER);
            getStringValueFromCursor(cv, c, PASSWORD);
            getStringValueFromCursor(cv, c, PROXY);
            getStringValueFromCursor(cv, c, PORT);
            getStringValueFromCursor(cv, c, MMSPROXY);
            getStringValueFromCursor(cv, c, MMSPORT);
            getStringValueFromCursor(cv, c, MMSC);
            getStringValueFromCursor(cv, c, TYPE);
            getStringValueFromCursor(cv, c, PROTOCOL);
            getStringValueFromCursor(cv, c, ROAMING_PROTOCOL);
            getStringValueFromCursor(cv, c, MVNO_TYPE);
            getStringValueFromCursor(cv, c, MVNO_MATCH_DATA);

            // bool/int vals
            getIntValueFromCursor(cv, c, AUTH_TYPE);
            getIntValueFromCursor(cv, c, CURRENT);
            getIntValueFromCursor(cv, c, CARRIER_ENABLED);
            getIntValueFromCursor(cv, c, BEARER);
            getIntValueFromCursor(cv, c, SUBSCRIPTION_ID);
            getIntValueFromCursor(cv, c, PROFILE_ID);
            getIntValueFromCursor(cv, c, MODEM_COGNITIVE);
            getIntValueFromCursor(cv, c, MAX_CONNS);
            getIntValueFromCursor(cv, c, WAIT_TIME);
            getIntValueFromCursor(cv, c, MAX_CONNS_TIME);
            getIntValueFromCursor(cv, c, MTU);
            getIntValueFromCursor(cv, c, NETWORK_TYPE_BITMASK);
            getIntValueFromCursor(cv, c, BEARER_BITMASK);
            getIntValueFromCursor(cv, c, EDITED);
            getIntValueFromCursor(cv, c, USER_VISIBLE);
            getIntValueFromCursor(cv, c, USER_EDITABLE);
            getIntValueFromCursor(cv, c, OWNED_BY);
            getIntValueFromCursor(cv, c, APN_SET_ID);
        }

        private void copyPreservedApnsToNewTable(SQLiteDatabase db, Cursor c) {
            // Move entries from CARRIERS_TABLE to CARRIERS_TABLE_TMP
            if (c != null && mContext.getResources() != null) {
                try {
                    String[] persistApnsForPlmns = mContext.getResources().getStringArray(
                            R.array.persist_apns_for_plmn);
                    while (c.moveToNext()) {
                        ContentValues cv = new ContentValues();
                        String val;
                        // Using V17 copy function for V15 upgrade. This should be fine since it handles
                        // columns that may not exist properly (getStringValueFromCursor() and
                        // getIntValueFromCursor() handle column index -1)
                        copyApnValuesV17(cv, c);
                        // Change bearer to a bitmask
                        String bearerStr = c.getString(c.getColumnIndex(BEARER));
                        if (!TextUtils.isEmpty(bearerStr)) {
                            int bearer_bitmask = ServiceState.getBitmaskForTech(
                                    Integer.parseInt(bearerStr));
                            cv.put(BEARER_BITMASK, bearer_bitmask);

                            int networkTypeBitmask = ServiceState.getBitmaskForTech(
                                    ServiceState.rilRadioTechnologyToNetworkType(
                                            Integer.parseInt(bearerStr)));
                            cv.put(NETWORK_TYPE_BITMASK, networkTypeBitmask);
                        }

                        int userEditedColumnIdx = c.getColumnIndex("user_edited");
                        if (userEditedColumnIdx != -1) {
                            String user_edited = c.getString(userEditedColumnIdx);
                            if (!TextUtils.isEmpty(user_edited)) {
                                cv.put(EDITED, new Integer(user_edited));
                            }
                        } else {
                            cv.put(EDITED, CARRIER_EDITED);
                        }

                        // New EDITED column. Default value (UNEDITED) will
                        // be used for all rows except for non-mvno entries for plmns indicated
                        // by resource: those will be set to CARRIER_EDITED to preserve
                        // their current values
                        val = c.getString(c.getColumnIndex(NUMERIC));
                        for (String s : persistApnsForPlmns) {
                            if (!TextUtils.isEmpty(val) && val.equals(s) &&
                                    (!cv.containsKey(MVNO_TYPE) ||
                                            TextUtils.isEmpty(cv.getAsString(MVNO_TYPE)))) {
                                if (userEditedColumnIdx == -1) {
                                    cv.put(EDITED, CARRIER_EDITED);
                                } else { // if (oldVersion == 14) -- if db had user_edited column
                                    if (cv.getAsInteger(EDITED) == USER_EDITED) {
                                        cv.put(EDITED, CARRIER_EDITED);
                                    }
                                }

                                break;
                            }
                        }

                        try {
                            db.insertWithOnConflict(CARRIERS_TABLE_TMP, null, cv,
                                    SQLiteDatabase.CONFLICT_ABORT);
                            if (VDBG) {
                                log("dbh.copyPreservedApnsToNewTable: db.insert returned >= 0; " +
                                        "insert successful for cv " + cv);
                            }
                        } catch (SQLException e) {
                            if (VDBG)
                                log("dbh.copyPreservedApnsToNewTable insertWithOnConflict exception " +
                                        e + " for cv " + cv);
                            // Insertion failed which could be due to a conflict. Check if that is
                            // the case and merge the entries
                            Cursor oldRow = DatabaseHelper.selectConflictingRow(db,
                                    CARRIERS_TABLE_TMP, cv);
                            if (oldRow != null) {
                                ContentValues mergedValues = new ContentValues();
                                mergeFieldsAndUpdateDb(db, CARRIERS_TABLE_TMP, oldRow, cv,
                                        mergedValues, true, mContext);
                                oldRow.close();
                            }
                        }
                    }
                } catch (Resources.NotFoundException e) {
                    loge("array.persist_apns_for_plmn is not found");
                    return;
                }
            }
        }

        private void getStringValueFromCursor(ContentValues cv, Cursor c, String key) {
            int columnIndex = c.getColumnIndex(key);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor)) {
                    cv.put(key, fromCursor);
                }
            }
        }

        /**
         * If NETWORK_TYPE_BITMASK does not exist (upgrade from version 23 to version 24), generate
         * NETWORK_TYPE_BITMASK with the use of BEARER_BITMASK. If NETWORK_TYPE_BITMASK existed
         * (upgrade from version 24 to forward), always map NETWORK_TYPE_BITMASK to BEARER_BITMASK.
         */
        private void getNetworkTypeBitmaskFromCursor(ContentValues cv, Cursor c) {
            int columnIndex = c.getColumnIndex(NETWORK_TYPE_BITMASK);
            if (columnIndex != -1) {
                getStringValueFromCursor(cv, c, NETWORK_TYPE_BITMASK);
                // Map NETWORK_TYPE_BITMASK to BEARER_BITMASK if NETWORK_TYPE_BITMASK existed;
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor) && fromCursor.matches("\\d+")) {
                    int networkBitmask = Integer.valueOf(fromCursor);
                    int bearerBitmask = ServiceState.convertNetworkTypeBitmaskToBearerBitmask(
                            networkBitmask);
                    cv.put(BEARER_BITMASK, String.valueOf(bearerBitmask));
                }
                return;
            }
            columnIndex = c.getColumnIndex(BEARER_BITMASK);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor) && fromCursor.matches("\\d+")) {
                    int bearerBitmask = Integer.valueOf(fromCursor);
                    int networkBitmask = ServiceState.convertBearerBitmaskToNetworkTypeBitmask(
                            bearerBitmask);
                    cv.put(NETWORK_TYPE_BITMASK, String.valueOf(networkBitmask));
                }
            }
        }

        private void getIntValueFromCursor(ContentValues cv, Cursor c, String key) {
            int columnIndex = c.getColumnIndex(key);
            if (columnIndex != -1) {
                String fromCursor = c.getString(columnIndex);
                if (!TextUtils.isEmpty(fromCursor)) {
                    try {
                        cv.put(key, new Integer(fromCursor));
                    } catch (NumberFormatException nfe) {
                        // do nothing
                    }
                }
            }
        }

        private void getBlobValueFromCursor(ContentValues cv, Cursor c, String key) {
            int columnIndex = c.getColumnIndex(key);
            if (columnIndex != -1) {
                byte[] fromCursor = c.getBlob(columnIndex);
                if (fromCursor != null) {
                    cv.put(key, fromCursor);
                }
            }
        }

        /**
         * Gets the next row of apn values.
         *
         * @param parser the parser
         * @return the row or null if it's not an apn
         */
        private ContentValues getRow(XmlPullParser parser) {
            if (!"apn".equals(parser.getName())) {
                return null;
            }

            ContentValues map = new ContentValues();

            String mcc = parser.getAttributeValue(null, "mcc");
            String mnc = parser.getAttributeValue(null, "mnc");
            String numeric = mcc + mnc;

            map.put(NUMERIC, numeric);
            map.put(MCC, mcc);
            map.put(MNC, mnc);
            map.put(NAME, parser.getAttributeValue(null, "carrier"));

            // do not add NULL to the map so that default values can be inserted in db
            addStringAttribute(parser, "apn", map, APN);
            addStringAttribute(parser, "user", map, USER);
            addStringAttribute(parser, "server", map, SERVER);
            addStringAttribute(parser, "password", map, PASSWORD);
            addStringAttribute(parser, "proxy", map, PROXY);
            addStringAttribute(parser, "port", map, PORT);
            addStringAttribute(parser, "mmsproxy", map, MMSPROXY);
            addStringAttribute(parser, "mmsport", map, MMSPORT);
            addStringAttribute(parser, "mmsc", map, MMSC);

            String apnType = parser.getAttributeValue(null, "type");
            if (apnType != null) {
                // Remove spaces before putting it in the map.
                apnType = apnType.replaceAll("\\s+", "");
                map.put(TYPE, apnType);
            }

            addStringAttribute(parser, "protocol", map, PROTOCOL);
            addStringAttribute(parser, "roaming_protocol", map, ROAMING_PROTOCOL);

            addIntAttribute(parser, "authtype", map, AUTH_TYPE);
            addIntAttribute(parser, "bearer", map, BEARER);
            addIntAttribute(parser, "profile_id", map, PROFILE_ID);
            addIntAttribute(parser, "max_conns", map, MAX_CONNS);
            addIntAttribute(parser, "wait_time", map, WAIT_TIME);
            addIntAttribute(parser, "max_conns_time", map, MAX_CONNS_TIME);
            addIntAttribute(parser, "mtu", map, MTU);
            addIntAttribute(parser, "apn_set_id", map, APN_SET_ID);


            addBoolAttribute(parser, "carrier_enabled", map, CARRIER_ENABLED);
            addBoolAttribute(parser, "modem_cognitive", map, MODEM_COGNITIVE);
            addBoolAttribute(parser, "user_visible", map, USER_VISIBLE);
            addBoolAttribute(parser, "user_editable", map, USER_EDITABLE);

            int networkTypeBitmask = 0;
            String networkTypeList = parser.getAttributeValue(null, "network_type_bitmask");
            if (networkTypeList != null) {
                networkTypeBitmask = ServiceState.getBitmaskFromString(networkTypeList);
            }
            map.put(NETWORK_TYPE_BITMASK, networkTypeBitmask);

            int bearerBitmask = 0;
            if (networkTypeList != null) {
                bearerBitmask =
                        ServiceState.convertNetworkTypeBitmaskToBearerBitmask(networkTypeBitmask);
            } else {
                String bearerList = parser.getAttributeValue(null, "bearer_bitmask");
                if (bearerList != null) {
                    bearerBitmask = ServiceState.getBitmaskFromString(bearerList);
                }
                // Update the network type bitmask to keep them sync.
                networkTypeBitmask = ServiceState.convertBearerBitmaskToNetworkTypeBitmask(
                        bearerBitmask);
                map.put(NETWORK_TYPE_BITMASK, networkTypeBitmask);
            }
            map.put(BEARER_BITMASK, bearerBitmask);

            String mvno_type = parser.getAttributeValue(null, "mvno_type");
            if (mvno_type != null) {
                String mvno_match_data = parser.getAttributeValue(null, "mvno_match_data");
                if (mvno_match_data != null) {
                    map.put(MVNO_TYPE, mvno_type);
                    map.put(MVNO_MATCH_DATA, mvno_match_data);
                }
            }

            return map;
        }

        private void addStringAttribute(XmlPullParser parser, String att,
                                        ContentValues map, String key) {
            String val = parser.getAttributeValue(null, att);
            if (val != null) {
                map.put(key, val);
            }
        }

        private void addIntAttribute(XmlPullParser parser, String att,
                                     ContentValues map, String key) {
            String val = parser.getAttributeValue(null, att);
            if (val != null) {
                map.put(key, Integer.parseInt(val));
            }
        }

        private void addBoolAttribute(XmlPullParser parser, String att,
                                      ContentValues map, String key) {
            String val = parser.getAttributeValue(null, att);
            if (val != null) {
                map.put(key, Boolean.parseBoolean(val));
            }
        }

        /*
         * Loads apns from xml file into the database
         *
         * @param db the sqlite database to write to
         * @param parser the xml parser
         *
         */
        private void loadApns(SQLiteDatabase db, XmlPullParser parser) {
            if (parser != null) {
                try {
                    db.beginTransaction();
                    XmlUtils.nextElement(parser);
                    while (parser.getEventType() != XmlPullParser.END_DOCUMENT) {
                        ContentValues row = getRow(parser);
                        if (row == null) {
                            throw new XmlPullParserException("Expected 'apn' tag", parser, null);
                        }
                        insertAddingDefaults(db, row);
                        XmlUtils.nextElement(parser);
                    }
                    db.setTransactionSuccessful();
                } catch (XmlPullParserException e) {
                    loge("Got XmlPullParserException while loading apns." + e);
                } catch (IOException e) {
                    loge("Got IOException while loading apns." + e);
                } catch (SQLException e) {
                    loge("Got SQLException while loading apns." + e);
                } finally {
                    db.endTransaction();
                }
            }
        }

        static public ContentValues setDefaultValue(ContentValues values) {
            if (!values.containsKey(SUBSCRIPTION_ID)) {
                int subId = SubscriptionManager.getDefaultSubscriptionId();
                values.put(SUBSCRIPTION_ID, subId);
            }

            return values;
        }

        private void insertAddingDefaults(SQLiteDatabase db, ContentValues row) {
            row = setDefaultValue(row);
            try {
                db.insertWithOnConflict(CARRIERS_TABLE, null, row, SQLiteDatabase.CONFLICT_ABORT);
                if (VDBG) log("dbh.insertAddingDefaults: db.insert returned >= 0; insert " +
                        "successful for cv " + row);
            } catch (SQLException e) {
                if (VDBG) log("dbh.insertAddingDefaults: exception " + e);
                // Insertion failed which could be due to a conflict. Check if that is the case and
                // update edited field accordingly.
                // Search for the exact same entry and update edited field.
                // If it is USER_EDITED/CARRIER_EDITED change it to UNEDITED,
                // and if USER/CARRIER_DELETED change it to USER/CARRIER_DELETED_BUT_PRESENT_IN_XML.
                Cursor oldRow = selectConflictingRow(db, CARRIERS_TABLE, row);
                if (oldRow != null) {
                    // Update the row
                    ContentValues mergedValues = new ContentValues();
                    int edited = oldRow.getInt(oldRow.getColumnIndex(EDITED));
                    int old_edited = edited;
                    if (edited != UNEDITED) {
                        if (edited == USER_DELETED) {
                            // USER_DELETED_BUT_PRESENT_IN_XML indicates entry has been deleted
                            // by user but present in apn xml file.
                            edited = USER_DELETED_BUT_PRESENT_IN_XML;
                        } else if (edited == CARRIER_DELETED) {
                            // CARRIER_DELETED_BUT_PRESENT_IN_XML indicates entry has been deleted
                            // by user but present in apn xml file.
                            edited = CARRIER_DELETED_BUT_PRESENT_IN_XML;
                        }
                        mergedValues.put(EDITED, edited);
                    }

                    mergeFieldsAndUpdateDb(db, CARRIERS_TABLE, oldRow, row, mergedValues, false,
                            mContext);

                    if (VDBG) log("dbh.insertAddingDefaults: old edited = " + old_edited
                            + " new edited = " + edited);

                    oldRow.close();
                }
            }
        }

        public static void mergeFieldsAndUpdateDb(SQLiteDatabase db, String table, Cursor oldRow,
                                                  ContentValues newRow, ContentValues mergedValues,
                                                  boolean onUpgrade, Context context) {
            if (newRow.containsKey(TYPE)) {
                // Merge the types
                String oldType = oldRow.getString(oldRow.getColumnIndex(TYPE));
                String newType = newRow.getAsString(TYPE);

                if (!oldType.equalsIgnoreCase(newType)) {
                    if (oldType.equals("") || newType.equals("")) {
                        newRow.put(TYPE, "");
                    } else {
                        String[] oldTypes = oldType.toLowerCase().split(",");
                        String[] newTypes = newType.toLowerCase().split(",");

                        if (VDBG) {
                            log("mergeFieldsAndUpdateDb: Calling separateRowsNeeded() oldType=" +
                                    oldType + " old bearer=" + oldRow.getInt(oldRow.getColumnIndex(
                                    BEARER_BITMASK)) +  " old networkType=" +
                                    oldRow.getInt(oldRow.getColumnIndex(NETWORK_TYPE_BITMASK)) +
                                    " old profile_id=" + oldRow.getInt(oldRow.getColumnIndex(
                                    PROFILE_ID)) + " newRow " + newRow);
                        }

                        // If separate rows are needed, do not need to merge any further
                        if (separateRowsNeeded(db, table, oldRow, newRow, context, oldTypes,
                                newTypes)) {
                            if (VDBG) log("mergeFieldsAndUpdateDb: separateRowsNeeded() returned " +
                                    "true");
                            return;
                        }

                        // Merge the 2 types
                        ArrayList<String> mergedTypes = new ArrayList<String>();
                        mergedTypes.addAll(Arrays.asList(oldTypes));
                        for (String s : newTypes) {
                            if (!mergedTypes.contains(s.trim())) {
                                mergedTypes.add(s);
                            }
                        }
                        StringBuilder mergedType = new StringBuilder();
                        for (int i = 0; i < mergedTypes.size(); i++) {
                            mergedType.append((i == 0 ? "" : ",") + mergedTypes.get(i));
                        }
                        newRow.put(TYPE, mergedType.toString());
                    }
                }
                mergedValues.put(TYPE, newRow.getAsString(TYPE));
            }

            if (newRow.containsKey(BEARER_BITMASK)) {
                int oldBearer = oldRow.getInt(oldRow.getColumnIndex(BEARER_BITMASK));
                int newBearer = newRow.getAsInteger(BEARER_BITMASK);
                if (oldBearer != newBearer) {
                    if (oldBearer == 0 || newBearer == 0) {
                        newRow.put(BEARER_BITMASK, 0);
                    } else {
                        newRow.put(BEARER_BITMASK, (oldBearer | newBearer));
                    }
                }
                mergedValues.put(BEARER_BITMASK, newRow.getAsInteger(BEARER_BITMASK));
            }

            if (newRow.containsKey(NETWORK_TYPE_BITMASK)) {
                int oldBitmask = oldRow.getInt(oldRow.getColumnIndex(NETWORK_TYPE_BITMASK));
                int newBitmask = newRow.getAsInteger(NETWORK_TYPE_BITMASK);
                if (oldBitmask != newBitmask) {
                    if (oldBitmask == 0 || newBitmask == 0) {
                        newRow.put(NETWORK_TYPE_BITMASK, 0);
                    } else {
                        newRow.put(NETWORK_TYPE_BITMASK, (oldBitmask | newBitmask));
                    }
                }
                mergedValues.put(NETWORK_TYPE_BITMASK, newRow.getAsInteger(NETWORK_TYPE_BITMASK));
            }

            if (newRow.containsKey(BEARER_BITMASK)
                    && newRow.containsKey(NETWORK_TYPE_BITMASK)) {
                syncBearerBitmaskAndNetworkTypeBitmask(mergedValues);
            }

            if (!onUpgrade) {
                // Do not overwrite a carrier or user edit with EDITED=UNEDITED
                if (newRow.containsKey(EDITED)) {
                    int oldEdited = oldRow.getInt(oldRow.getColumnIndex(EDITED));
                    int newEdited = newRow.getAsInteger(EDITED);
                    if (newEdited == UNEDITED && (oldEdited == CARRIER_EDITED
                                || oldEdited == CARRIER_DELETED
                                || oldEdited == CARRIER_DELETED_BUT_PRESENT_IN_XML
                                || oldEdited == USER_EDITED
                                || oldEdited == USER_DELETED
                                || oldEdited == USER_DELETED_BUT_PRESENT_IN_XML)) {
                        newRow.remove(EDITED);
                    }
                }
                mergedValues.putAll(newRow);
            }

            if (mergedValues.size() > 0) {
                db.update(table, mergedValues, "_id=" + oldRow.getInt(oldRow.getColumnIndex("_id")),
                        null);
            }
        }

        private static boolean separateRowsNeeded(SQLiteDatabase db, String table, Cursor oldRow,
                                                  ContentValues newRow, Context context,
                                                  String[] oldTypes, String[] newTypes) {
            // If this APN falls under persist_apns_for_plmn, and the
            // only difference between old type and new type is that one has dun, and
            // the APNs have profile_id 0 or not set, then set the profile_id to 1 for
            // the dun APN/remove dun from type. This will ensure both oldRow and newRow exist
            // separately in db.

            boolean match = false;

            // Check if APN falls under persist_apns_for_plmn
            if (context.getResources() != null) {
                String[] persistApnsForPlmns = context.getResources().getStringArray(
                        R.array.persist_apns_for_plmn);
                for (String s : persistApnsForPlmns) {
                    if (s.equalsIgnoreCase(newRow.getAsString(NUMERIC))) {
                        match = true;
                        break;
                    }
                }
            } else {
                loge("separateRowsNeeded: resources=null");
            }

            if (!match) return false;

            // APN falls under persist_apns_for_plmn
            // Check if only difference between old type and new type is that
            // one has dun
            ArrayList<String> oldTypesAl = new ArrayList<String>(Arrays.asList(oldTypes));
            ArrayList<String> newTypesAl = new ArrayList<String>(Arrays.asList(newTypes));
            ArrayList<String> listWithDun = null;
            ArrayList<String> listWithoutDun = null;
            boolean dunInOld = false;
            if (oldTypesAl.size() == newTypesAl.size() + 1) {
                listWithDun = oldTypesAl;
                listWithoutDun = newTypesAl;
                dunInOld = true;
            } else if (oldTypesAl.size() + 1 == newTypesAl.size()) {
                listWithDun = newTypesAl;
                listWithoutDun = oldTypesAl;
            } else {
                return false;
            }

            if (listWithDun.contains("dun") && !listWithoutDun.contains("dun")) {
                listWithoutDun.add("dun");
                if (!listWithDun.containsAll(listWithoutDun)) {
                    return false;
                }

                // Only difference between old type and new type is that
                // one has dun
                // Check if profile_id is 0/not set
                if (oldRow.getInt(oldRow.getColumnIndex(PROFILE_ID)) == 0) {
                    if (dunInOld) {
                        // Update oldRow to remove dun from its type field
                        ContentValues updateOldRow = new ContentValues();
                        StringBuilder sb = new StringBuilder();
                        boolean first = true;
                        for (String s : listWithDun) {
                            if (!s.equalsIgnoreCase("dun")) {
                                sb.append(first ? s : "," + s);
                                first = false;
                            }
                        }
                        String updatedType = sb.toString();
                        if (VDBG) {
                            log("separateRowsNeeded: updating type in oldRow to " + updatedType);
                        }
                        updateOldRow.put(TYPE, updatedType);
                        db.update(table, updateOldRow,
                                "_id=" + oldRow.getInt(oldRow.getColumnIndex("_id")), null);
                        return true;
                    } else {
                        if (VDBG) log("separateRowsNeeded: adding profile id 1 to newRow");
                        // Update newRow to set profile_id to 1
                        newRow.put(PROFILE_ID, new Integer(1));
                    }
                } else {
                    return false;
                }

                // If match was found, both oldRow and newRow need to exist
                // separately in db. Add newRow to db.
                try {
                    db.insertWithOnConflict(table, null, newRow, SQLiteDatabase.CONFLICT_REPLACE);
                    if (VDBG) log("separateRowsNeeded: added newRow with profile id 1 to db");
                    return true;
                } catch (SQLException e) {
                    loge("Exception on trying to add new row after updating profile_id");
                }
            }

            return false;
        }

        public static Cursor selectConflictingRow(SQLiteDatabase db, String table,
                                                  ContentValues row) {
            // Conflict is possible only when numeric, mcc, mnc (fields without any default value)
            // are set in the new row
            if (!row.containsKey(NUMERIC) || !row.containsKey(MCC) || !row.containsKey(MNC)) {
                loge("dbh.selectConflictingRow: called for non-conflicting row: " + row);
                return null;
            }

            String[] columns = { "_id",
                    TYPE,
                    EDITED,
                    BEARER_BITMASK,
                    NETWORK_TYPE_BITMASK,
                    PROFILE_ID };
            String selection = TextUtils.join("=? AND ", CARRIERS_UNIQUE_FIELDS) + "=?";
            int i = 0;
            String[] selectionArgs = new String[CARRIERS_UNIQUE_FIELDS.size()];
            for (String field : CARRIERS_UNIQUE_FIELDS) {
                if (!row.containsKey(field)) {
                    selectionArgs[i++] = CARRIERS_UNIQUE_FIELDS_DEFAULTS.get(field);
                } else {
                    if (CARRIERS_BOOLEAN_FIELDS.contains(field)) {
                        // for boolean fields we overwrite the strings "true" and "false" with "1"
                        // and "0"
                        selectionArgs[i++] = convertStringToIntString(row.getAsString(field));
                    } else {
                        selectionArgs[i++] = row.getAsString(field);
                    }
                }
            }

            Cursor c = db.query(table, columns, selection, selectionArgs, null, null, null);

            if (c != null) {
                if (c.getCount() == 1) {
                    if (VDBG) log("dbh.selectConflictingRow: " + c.getCount() + " conflicting " +
                            "row found");
                    if (c.moveToFirst()) {
                        return c;
                    } else {
                        loge("dbh.selectConflictingRow: moveToFirst() failed");
                    }
                } else {
                    loge("dbh.selectConflictingRow: Expected 1 but found " + c.getCount() +
                            " matching rows found for cv " + row);
                }
                c.close();
            } else {
                loge("dbh.selectConflictingRow: Error - c is null; no matching row found for " +
                        "cv " + row);
            }

            return null;
        }
    }
}
