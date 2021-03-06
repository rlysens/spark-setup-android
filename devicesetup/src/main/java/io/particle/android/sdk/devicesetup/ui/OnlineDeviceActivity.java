package io.particle.android.sdk.devicesetup.ui;

import android.Manifest.permission;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.mobile.client.AWSMobileClient;
import com.amazonaws.mobile.client.AWSStartupHandler;
import com.amazonaws.mobile.client.AWSStartupResult;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.squareup.phrase.Phrase;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.particle.android.sdk.accountsetup.LoginActivity;
import io.particle.android.sdk.cloud.ParticleCloud;
import io.particle.android.sdk.cloud.ParticleCloudException;
import io.particle.android.sdk.cloud.ParticleCloudSDK;
import io.particle.android.sdk.cloud.ParticleDevice;
import io.particle.android.sdk.devicesetup.HolaDeviceData;
import io.particle.android.sdk.devicesetup.OnlineDeviceListAdapter;
import io.particle.android.sdk.devicesetup.R;
import io.particle.android.sdk.utils.EZ;
import io.particle.android.sdk.utils.TLog;
import io.particle.android.sdk.utils.Tuple;
import io.particle.android.sdk.utils.ui.Toaster;
import io.particle.android.sdk.utils.ui.Ui;

public class OnlineDeviceActivity extends AppCompatActivity
        implements OnlineDeviceListAdapter.ListItemClickListener, PermissionsFragment.Client {

    interface MoveToActivity {
        void doIt(OnlineDeviceActivity currentActivity);
    }

    private static final TLog log = TLog.get(OnlineDeviceActivity.class);
    private ParticleCloud sparkCloud;
    private OnlineDeviceListAdapter mAdapter;
    private RecyclerView mRecyclerView;
    private TextView mNoDevicesFoundTV;
    private View mProgressBar;
    private AsyncTask<Void, Void, Boolean> mListDevicesTask;
    private List<HolaDeviceData> mDeviceList;
    private MoveToActivity mMoveToActivity;

    private AWSCredentialsProvider mCredentialsProvider;
    private AmazonDynamoDBClient mDbClient;

    Map<String, HolaDeviceData> mHolaDeviceMap;

    private void connectToDB(Context context) {
        AWSMobileClient.getInstance().initialize(context, new AWSStartupHandler() {
            @Override
            public void onComplete(AWSStartupResult awsStartupResult) {
                mCredentialsProvider = AWSMobileClient.getInstance().getCredentialsProvider();
                // Instantiate a AmazonDynamoDBMapperClient
                mDbClient = new AmazonDynamoDBClient(mCredentialsProvider);
                mDbClient.setEndpoint(context.getString(R.string.dynamodb_endpoint));
                log.i("connectToDB completed. mDbClient:" + (mDbClient!=null));
            }
        }).execute();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_online_device);

        sparkCloud = ParticleCloudSDK.getCloud();

        PermissionsFragment.ensureAttached(this);

        mDbClient = null;
        mHolaDeviceMap = new HashMap<String, HolaDeviceData>();
        connectToDB(this);

        mRecyclerView = (RecyclerView) findViewById(R.id.rv_online_list);
        mNoDevicesFoundTV = (TextView) findViewById(R.id.tv_msg_no_dev_found);

        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        mRecyclerView.setLayoutManager(layoutManager);

        mAdapter = new OnlineDeviceListAdapter(this, this);
        mDeviceList = null;

        mRecyclerView.setAdapter(mAdapter);

        mProgressBar = findViewById(R.id.indeterminateBar);
        mNoDevicesFoundTV.setVisibility(View.INVISIBLE);

        Ui.setText(this, R.id.online_list_header,
                Phrase.from(this, R.string.online_list_header_text)
                        .put("device_name", getString(R.string.device_name))
                        .format()
        );

        Ui.setText(this, R.id.logged_in_as,
                Phrase.from(this, R.string.you_are_logged_in_as)
                        .put("username", sparkCloud.getLoggedInUsername())
                        .format()
        );

        Ui.findView(this, R.id.action_log_out).setOnClickListener(view -> {
            if (mListDevicesTask!=null) {
                mListDevicesTask.cancel(true);
            }
            mMoveToActivity = (currentActivity) -> currentActivity.moveToLoginActivity();
        });

        Ui.findView(this, R.id.action_add_device).setOnClickListener(view -> {
            if (mListDevicesTask!=null) {
                mListDevicesTask.cancel(true);
            }
            mMoveToActivity = (currentActivity) -> currentActivity.moveToDeviceDiscovery();
        });
    }

    public void moveToLoginActivity() {
        sparkCloud.logOut();
        log.i("logged out, username is: " + sparkCloud.getLoggedInUsername());
        startActivity(new Intent(OnlineDeviceActivity.this, LoginActivity.class));
        finish();
    }

    public void moveToDeviceDiscovery() {
        if (PermissionsFragment.hasPermission(this, permission.ACCESS_COARSE_LOCATION)) {
            startActivity(new Intent(OnlineDeviceActivity.this, DiscoverDeviceActivity.class));
        } else {
            PermissionsFragment.get(this).ensurePermission(permission.ACCESS_COARSE_LOCATION);
        }
    }

    @Override
    public void onUserAllowedPermission(String permission) {
        moveToDeviceDiscovery();
    }

    @Override
    public void onUserDeniedPermission(String permission) {
        Toaster.s(this, getString(R.string.location_permission_denied_cannot_start_setup));
    }

    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
    @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionsFragment.get(this).onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void onStart() {
        mMoveToActivity = null;

        super.onStart();
        startWorker();
    }

    protected void onStop() {
        if (mListDevicesTask!=null) {
            mListDevicesTask.cancel(true);
        }

        super.onStop();
    }

    private void showRecyclerView() {
         /* First, make sure the not found error is invisible */
        mNoDevicesFoundTV.setVisibility(View.INVISIBLE);
        mProgressBar.setVisibility(View.INVISIBLE);
         /* Then, make sure the recycler data is visible */
        mRecyclerView.setVisibility(View.VISIBLE);
    }

    private void showNotFoundView() {
        Ui.setText(this, R.id.tv_msg_no_dev_found,
                Phrase.from(this, R.string.msg_no_devices_found)
                        .put("device_name", getString(R.string.device_name))
                        .format()
        );

        mRecyclerView.setVisibility(View.INVISIBLE);
        mProgressBar.setVisibility(View.INVISIBLE);
        mNoDevicesFoundTV.setVisibility(View.VISIBLE);
    }

    /**
     * This is where we receive our callback from
     * OnlineDeviceListAdapter.ListItemClickListener
     *
     * This callback is invoked when you click on an item in the list.
     *
     * @param clickedItemIndex Index in the list of the item that was clicked.
     */
    @Override
    public void onListItemClick(int clickedItemIndex) {
        Intent intent = new Intent(OnlineDeviceActivity.this, DeviceDetailActivity.class);

        if (mDeviceList != null) {
            HolaDeviceData deviceData = mDeviceList.get(clickedItemIndex);

            intent.putExtra("HOLA_DEVICE_DATA", deviceData);
        }

        startActivity(intent);
    }

    private void startWorker() {
        // first, make sure we haven't actually been called twice...
        if (mListDevicesTask != null) {
            log.d("Already running connect worker " + mListDevicesTask + ", refusing to start another");
            return;
        }

        // This just has doInBackground() return null on success, or if an
        // exception was thrown, it passes that along instead to indicate failure.
        mListDevicesTask = new AsyncTask<Void, Void, Boolean>() {

            @Override
            protected Boolean doInBackground(Void... voids) {

                try {
                    log.i("doInBackground: mDbClient:" + (mDbClient!=null));
                    if (mDbClient == null) {
                        return false;
                    }

                    if (isCancelled()) {
                        return false;
                    }

                    // including this sleep because without it,
                    // we seem to attempt a socket connection too early,
                    // and it makes the process time out(!)
                    log.d("Waiting a couple seconds before trying the socket connection...");
                    EZ.threadSleep(2000);

                    if (isCancelled()) {
                        return false;
                    }

                    List<ParticleDevice> devices = ParticleCloudSDK.getCloud().getDevices();

                    for (ParticleDevice device : devices) {
                        if (isCancelled()) {
                            return false;
                        }

                        String deviceId = device.getID();
                        if (!mHolaDeviceMap.containsKey(deviceId)) {
                            HolaDeviceData deviceData = new HolaDeviceData(device, OnlineDeviceActivity.this);
                            deviceData.setDBClient(mDbClient);
                            mHolaDeviceMap.put(deviceId, deviceData);
                        }

                        mHolaDeviceMap.get(deviceId).refresh(OnlineDeviceActivity.this);
                    }

                    return true;

                } catch (ParticleCloudException e) {
                    log.d("Setup exception thrown: ", e);
                    return false;
                }
            }

            protected void onCancelled(Boolean result) {
                mListDevicesTask = null;

                if (mMoveToActivity != null) {
                    mMoveToActivity.doIt(OnlineDeviceActivity.this);
                }
            }

            @Override
            protected void onPostExecute(Boolean result) {
                mListDevicesTask = null;

                if (mMoveToActivity != null) {
                    mMoveToActivity.doIt(OnlineDeviceActivity.this);
                }
                else {
                    if (result && (!mHolaDeviceMap.isEmpty())) {
                        mDeviceList = new ArrayList<HolaDeviceData>(mHolaDeviceMap.values());
                        mAdapter.setDeviceData(mDeviceList);
                        showRecyclerView();
                    }

                    startWorker();
                }
            }
        }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }
}
