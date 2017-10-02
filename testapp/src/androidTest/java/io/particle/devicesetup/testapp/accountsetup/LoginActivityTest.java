package io.particle.devicesetup.testapp.accountsetup;

import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.espresso.NoMatchingViewException;
import android.support.test.filters.SmallTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.text.Html;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.particle.android.sdk.devicesetup.ParticleDeviceSetupLibrary;
import io.particle.android.sdk.devicesetup.R;
import io.particle.android.sdk.devicesetup.commands.Command;
import io.particle.android.sdk.devicesetup.commands.CommandClient;
import io.particle.android.sdk.devicesetup.commands.CommandClientFactory;
import io.particle.android.sdk.devicesetup.commands.ScanApCommand;
import io.particle.android.sdk.devicesetup.setupsteps.SetupStepException;
import io.particle.android.sdk.devicesetup.ui.DiscoverProcessWorker;
import io.particle.android.sdk.utils.SSID;
import io.particle.android.sdk.utils.WifiFacade;
import io.particle.devicesetup.testapp.EspressoDaggerMockRule;
import io.particle.devicesetup.testapp.MainActivity;

import static android.support.test.espresso.Espresso.onView;
import static android.support.test.espresso.action.ViewActions.click;
import static android.support.test.espresso.action.ViewActions.closeSoftKeyboard;
import static android.support.test.espresso.action.ViewActions.typeText;
import static android.support.test.espresso.assertion.ViewAssertions.matches;
import static android.support.test.espresso.matcher.ViewMatchers.isDisplayed;
import static android.support.test.espresso.matcher.ViewMatchers.withId;
import static android.support.test.espresso.matcher.ViewMatchers.withText;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LoginActivityTest {

    @Rule public EspressoDaggerMockRule rule = new EspressoDaggerMockRule();

    @Rule
    public ActivityTestRule<MainActivity> activityRule = new ActivityTestRule<>(MainActivity.class, false, false);

    @Mock WifiFacade wifiFacade;

    @Mock DiscoverProcessWorker discoverProcessWorker;

    @Mock CommandClientFactory commandClientFactory;

    @Test
    public void testSetupFlow() {
        //create/mock photon device SSID
        String ssid = (InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getApplicationContext().getString(R.string.network_name_prefix) + "-") + "TestSSID";
        //mock scan results object to fake photon device SSID as scan result
        ScanResult scanResult = mock(ScanResult.class);
        scanResult.SSID = ssid;
        scanResult.capabilities = "WEP";
        //mock wifi wrapper to return fake photon device in scan results
        List<ScanResult> scanResultList = new ArrayList<>();
        scanResultList.add(scanResult);
        when(wifiFacade.getScanResults()).thenReturn(scanResultList);
        //create fake wifi info object for fake photon device
        WifiInfo wifiInfo = mock(WifiInfo.class);
        when(wifiInfo.getSSID()).thenReturn(ssid);
        when(wifiInfo.getNetworkId()).thenReturn(5);
        //mock wifi wrapper to return fake photon device as "connected" network
        when(wifiFacade.getCurrentlyConnectedSSID()).thenReturn(SSID.from(ssid));
        when(wifiFacade.getConnectionInfo()).thenReturn(wifiInfo);
        //mock device discovery process worker to do nothing (would throw exception),
        //doing nothing would mean successful connection to ap
        try {
            doNothing().when(discoverProcessWorker).doTheThing();
        } catch (SetupStepException e) {
            e.printStackTrace();
        }
        //fake scan wifi screen results by creating mock wifi scan result
        String wifiSSID = "fakeWifiToConnectTo";
        ScanApCommand.Scan scan = new ScanApCommand.Scan(wifiSSID, 0, 0);
        CommandClient commandClient = mock(CommandClient.class);
        ScanApCommand.Response response = mock(ScanApCommand.Response.class);
        when(commandClientFactory.newClientUsingDefaultsForDevices(any(WifiFacade.class), any(SSID.class))).thenReturn(commandClient);
        when(response.getScans()).thenReturn(Collections.singletonList(scan));
        try {
            when(commandClient.sendCommand(any(Command.class), any(Class.class))).thenReturn(response);
        } catch (IOException e) {
            e.printStackTrace();
        }
        //launch test activity
        activityRule.launchActivity(null);
        //launch setup process
        ParticleDeviceSetupLibrary.startDeviceSetup(activityRule.getActivity(), MainActivity.class);
        try {
            setupFlow(ssid, wifiSSID);
        } catch (NoMatchingViewException e) {
            loginFlow(ssid, wifiSSID);
        }
    }

    public void setupFlow(String photonSSID, String wifiSSID) {
        onView(withId(R.id.action_im_ready)).check(matches(isDisplayed()));
        onView(withId(R.id.action_im_ready)).perform(click());
        onView(withText(R.string.enable_wifi)).perform(click());
        onView(withText(photonSSID)).perform(click());
        onView(withText(wifiSSID)).perform(click());
        SystemClock.sleep(30000);
    }

    public void loginFlow(String photonSSID, String wifiSSID) {
        onView(withId(R.id.email)).perform(typeText(""));
        onView(withId(R.id.password)).perform(typeText(""));
        closeSoftKeyboard();
        onView(withId(R.id.action_log_in)).perform(click());
        setupFlow(photonSSID, wifiSSID);
    }

    public String getNonHtmlString(int resId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return Html.fromHtml(activityRule.getActivity().getString(resId), Html.FROM_HTML_MODE_LEGACY).toString();
        } else {
            return Html.fromHtml(activityRule.getActivity().getString(resId)).toString();
        }
    }
}