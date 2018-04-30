package io.particle.android.sdk.devicesetup;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Parcel;
import android.os.Parcelable;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.CognitoCachingCredentialsProvider;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.AWSStartupHandler;
import com.amazonaws.mobile.client.AWSStartupResult;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.ComparisonOperator;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.GetItemRequest;
import com.amazonaws.services.dynamodbv2.model.GetItemResult;
import com.amazonaws.services.dynamodbv2.model.QueryRequest;
import com.amazonaws.services.dynamodbv2.model.QueryResult;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.particle.android.sdk.cloud.ParticleCloudException;
import io.particle.android.sdk.cloud.ParticleDevice;
import io.particle.android.sdk.devicesetup.ui.DeviceDetailActivity;
import io.particle.android.sdk.utils.TLog;

/**
 * Created by rlysens on 7/12/2017.
 */

public class HolaDeviceData implements Parcelable {
    public static final int NUM_BUDDIES = 3;
    private static final TLog log = TLog.get(HolaDeviceData.class);

    /**
     * The name of the DynamoDB table used to store data.  If using AWS Mobile Hub, then note
     * that this is the "long name" of the table, as specified in the Resources section of
     * the console.  This should be defined with the Notes schema.
     */
    private final String INTERCOM_TABLE = "Intercom_Table";

    private AWSCredentialsProvider mCredentialsProvider;
    private AmazonDynamoDBClient mDbClient;

    private ParticleDevice mDevice;
    private int mMy_id;
    private String mDeviceName;
    private String[] mBuddyNames = {"","",""};
    private int[] mBuddyIds = {-1, -1, -1};
    private int mBatteryCharge;
    private int mWifiSignal;
    private boolean mIsOnline;

    /*returns cached data*/
    public int getWifiSignal() { return mWifiSignal; }

    /*returns cached data*/
    public int getBatteryCharge() { return mBatteryCharge; }

    /*returns cached data*/
    public ParticleDevice getDevice() {
        return mDevice;
    }

    /*returns cached data*/
    public String getDeviceName() {
        return mDeviceName;
    }

    /*returns cached data*/
    public String getBuddyName(int buddyIdx) {
        assert(buddyIdx < mBuddyNames.length);
        return mBuddyNames[buddyIdx];
    }

    /*returns cached data*/
    public int getBuddyId(int buddyIdx) {
        assert(buddyIdx < mBuddyIds.length);
        return mBuddyIds[buddyIdx];
    }

    /*returns cached data*/
    public boolean isOnline() {
        return mIsOnline;
    }

    /*returns true if successful*/
    public boolean setBuddyName(int buddyIdx, String buddyName) {
        assert(buddyIdx < mBuddyNames.length);

        try {
            // Create our map of values
            Map keyConditions = new HashMap();

            // Specify our key conditions ("intercom_name" == buddyName)
            Condition hashKeyCondition = new Condition()
                    .withComparisonOperator(ComparisonOperator.EQ.toString())
                    .withAttributeValueList(new AttributeValue().withS(buddyName));
            keyConditions.put("intercom_name", hashKeyCondition);

            QueryRequest queryRequest = new QueryRequest()
                    .withTableName(INTERCOM_TABLE)
                    .withKeyConditions(keyConditions)
                    .withIndexName("intercom-name-index");

            QueryResult queryResult = mDbClient.query(queryRequest);

            if (queryResult.getCount() >= 1) {
                java.util.Map<java.lang.String, AttributeValue> item = queryResult.getItems().get(0);
                String buddyIdString = item.get("intercom_id").getN();

                List<String> args = new ArrayList<String>();

                args.add(buddyIdString);

                String functionName = String.format("buddy_%d_id", buddyIdx);

                /*callFunction API says 1=success, but that's not correct.
                 *The value returned is from the called function.
                 *In case of the buddy_name function, 0=success.
                 */
                int result = mDevice.callFunction(functionName, args);

                if (result == 0) {
                    mBuddyNames[buddyIdx] = buddyName;
                    mBuddyIds[buddyIdx] = Integer.parseInt(buddyIdString);
                    return true;
                }
            }

            return false;
        }
        catch (Exception e) {
            log.d("Setup exception thrown: ", e);
            return false;
        }
    }

    // 99.9% of the time you can just ignore this
    @Override
    public int describeContents() {
        return 0;
    }

    // write your object's data to the passed-in Parcel
    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeParcelable(mDevice, flags);
        out.writeInt(mMy_id);
        out.writeString(mDeviceName);
        out.writeStringArray(mBuddyNames);
        out.writeIntArray(mBuddyIds);
        out.writeInt(mBatteryCharge);
        out.writeInt(mWifiSignal);
        out.writeByte((byte) (mIsOnline ? 1 : 0));
    }

    // this is used to regenerate your object. All Parcelables must have a CREATOR that implements these two methods
    public static final Parcelable.Creator<HolaDeviceData> CREATOR = new Parcelable.Creator<HolaDeviceData>() {
        public HolaDeviceData createFromParcel(Parcel in) {
            return new HolaDeviceData(in);
        }

        public HolaDeviceData[] newArray(int size) {
            return new HolaDeviceData[size];
        }
    };

    // example constructor that takes a Parcel and gives you an object populated with it's values
    private HolaDeviceData(Parcel in) {
        mCredentialsProvider = null;
        mDbClient = null;

        mDevice = in.readParcelable(ParticleDevice.class.getClassLoader());
        mMy_id = in.readInt();
        mDeviceName = in.readString();
        mBuddyNames = in.createStringArray();
        mBuddyIds = in.createIntArray();
        mBatteryCharge = in.readInt();
        mWifiSignal = in.readInt();
        mIsOnline = in.readByte() != 0;
    }

    private String lookupNameInDB(int intercomId) {
        // Need to specify the key of our item, which is a Map of our primary key attribute(s)
        Map<String, AttributeValue> key = new HashMap<String, AttributeValue>();
        key.put("intercom_id", new AttributeValue(Integer.toString(intercomId)));

        GetItemRequest getItemRequest = new GetItemRequest(INTERCOM_TABLE,key);
        AttributeValue attributeValue = mDbClient.getItem(getItemRequest).getItem().get("intercom_name");

        if (attributeValue != null) {
            return attributeValue.getS();
        }
        else {
            return null;
        }
    }

    public void connectToDB(Context context) {
        AWSMobileClient.getInstance().initialize(context, new AWSStartupHandler() {
            @Override
            public void onComplete(AWSStartupResult awsStartupResult) {
                mCredentialsProvider = AWSMobileClient.getInstance().getCredentialsProvider();
                // Instantiate a AmazonDynamoDBMapperClient
                mDbClient = new AmazonDynamoDBClient(mCredentialsProvider);
            }
        }).execute();
    }

    public HolaDeviceData(ParticleDevice device, Context context) {

        mCredentialsProvider = null;
        mDbClient = null;

        mDevice = device;
        mIsOnline = false;

        for (int buddyIdx=0; buddyIdx<mBuddyNames.length; buddyIdx++) {
            mBuddyNames[buddyIdx] = context.getString(R.string.buddy_not_set);
            mBuddyIds[buddyIdx] =-1;
        }

        String id = device.getID();
        SharedPreferences prefs = context.getSharedPreferences(id, Context.MODE_PRIVATE);
        mMy_id = prefs.getInt("my_id", -1);
        mDeviceName = prefs.getString("my_name", context.getString(R.string.name_unknown));
    }

    public void refresh(Context context) {
        try {
            mDevice.refresh();
            mIsOnline = mDevice.isConnected();
            if (mIsOnline) {
                int my_id = mDevice.getIntVariable("my_id");
                /*Update id in persistent storage if found/changed*/
                if (mMy_id != my_id) {
                    String id = mDevice.getID();
                    SharedPreferences prefs = context.getSharedPreferences(id, Context.MODE_PRIVATE);
                    SharedPreferences.Editor editor = prefs.edit();
                    editor.putInt("my_id", my_id);
                    editor.commit();
                }

                mMy_id = my_id;

                String deviceName = lookupNameInDB(my_id);
                if (deviceName != null) {
                    /*Update name in persistent storage if found/changed*/
                    if (!mDeviceName.equals(deviceName)) {
                        String id = mDevice.getID();
                        SharedPreferences prefs = context.getSharedPreferences(id, Context.MODE_PRIVATE);
                        SharedPreferences.Editor editor = prefs.edit();
                        editor.putString("my_name", deviceName);
                        editor.commit();
                    }

                    mDeviceName = deviceName;
                }

                mBatteryCharge = Integer.parseInt(mDevice.getStringVariable("battery_pct"));
                mWifiSignal = Integer.parseInt(mDevice.getStringVariable("wifi_pct"));

                for (int buddyIdx = 0; buddyIdx < mBuddyNames.length; buddyIdx++) {
                    String functionName = String.format("buddy_%d_id", buddyIdx);
                    int buddyId = mDevice.getIntVariable(functionName);
                    mBuddyIds[buddyIdx] = buddyId;

                    if (buddyId != -1) {
                        String buddyName = lookupNameInDB(buddyId);

                        if (buddyName != null) {
                            mBuddyNames[buddyIdx] = buddyName;
                        }
                    }
                }
            }
        }
        catch (Exception e) {
            mIsOnline = false;
            log.d("Setup exception thrown: ", e);
        }
    }
}
