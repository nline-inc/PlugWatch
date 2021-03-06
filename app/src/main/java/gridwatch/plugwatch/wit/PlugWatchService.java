package gridwatch.plugwatch.wit;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.BitmapFactory;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.evernote.android.job.JobManager;
import com.evernote.android.job.JobRequest;
import com.google.android.gms.location.LocationRequest;
import com.google.firebase.crash.FirebaseCrash;
import com.polidea.rxandroidble.RxBleClient;
import com.polidea.rxandroidble.RxBleConnection;
import com.polidea.rxandroidble.RxBleDevice;
import com.polidea.rxandroidble.RxBleScanResult;
import com.polidea.rxandroidble.exceptions.BleScanException;
import com.polidea.rxandroidble.internal.RxBleLog;
import com.polidea.rxandroidble.utils.ConnectionSharingAdapter;
import com.squareup.leakcanary.RefWatcher;

import net.grandcentrix.tray.AppPreferences;
import net.grandcentrix.tray.core.ItemNotFoundException;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import gridwatch.plugwatch.IPlugWatchService;
import gridwatch.plugwatch.R;
import gridwatch.plugwatch.callbacks.RestartOnExceptionHandler;
import gridwatch.plugwatch.configs.AppConfig;
import gridwatch.plugwatch.configs.BluetoothConfig;
import gridwatch.plugwatch.configs.IntentConfig;
import gridwatch.plugwatch.configs.SensorConfig;
import gridwatch.plugwatch.configs.SettingsConfig;
import gridwatch.plugwatch.database.GWDump;
import gridwatch.plugwatch.database.MeasurementRealm;
import gridwatch.plugwatch.gridWatch.GridWatch;
import gridwatch.plugwatch.logs.LatLngWriter;
import gridwatch.plugwatch.logs.MacWriter;
import gridwatch.plugwatch.network.NetworkJob;
import gridwatch.plugwatch.network.NetworkJobCreator;
import gridwatch.plugwatch.network.WitRetrofit;
import gridwatch.plugwatch.utilities.Rebooter;
import gridwatch.plugwatch.utilities.Restart;
import io.realm.Realm;
import io.realm.RealmConfiguration;
import io.realm.RealmResults;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.subjects.PublishSubject;

import static android.view.MotionEvent.ACTION_MASK;
import static gridwatch.plugwatch.wit.App.getContext;

public class PlugWatchService extends Service {

    private static int num_wit;
    private static int num_gw;


    static final AppPreferences appPreferences = new AppPreferences(getContext());


    //GW Variables
    private final static int RECORDER_CHANNELS = AudioFormat.CHANNEL_IN_STEREO;
    private final static int RECORDER_ENCODING = AudioFormat.ENCODING_PCM_16BIT;
    private final static int SAMPLE_FREQUENCY = SensorConfig.MICROPHONE_SAMPLE_FREQUENCY;
    private final static byte BIT_RATE = SensorConfig.MICROPHONE_BIT_RATE;
    private static int recBufferSize;
    public AudioRecord mRecorder;

    //BLE Variables
    private boolean isConnected;
    private static String macAddress;
    long last_good_data = System.currentTimeMillis();
    private Context mContext;
    private RxBleClient rxBleClient;
    private Subscription scanSubscription;
    private RxBleDevice bleDevice;
    ArrayList<byte[]> to_write_slowly = new ArrayList<>();
    private static String mCurrent;
    private static String mFrequency;
    private static String mPower;
    private static String mPowerFactor;
    private static String mVoltage;
    private PublishSubject<Void> disconnectTriggerSubject = PublishSubject.create();
    private Observable<RxBleConnection> connectionObservable;
    private static final int COUNT_DOWN_TIMER = 1;
    private static final int DEVICESETTING = 5;
    private static final int GATT_TIMEOUT = 1000;
    private static final int OVERLOAD = 2;
    private static final int SCHEDULER = 4;
    private static final int STANDBY = 3;
    private static final int PREF_ACT_REQ = 0;
    private static final int ID_ACC = 1;
    private static final int ID_AMB = 5;
    private static final int ID_BAR = 7;
    private static final int ID_GYR = 3;
    private static final int ID_OBJ = 4;
    private static final int ID_OFFSET = 0;
    private static final int ID_OPT = 2;
    private boolean isClockUpdated = false;

    private String realm_filename;
    private String full_realm_filename;

    private String cur_ui_str;

    private String mac_whitelist;
    private boolean is_whitelisted;

    private LocationRequest location_rec;

    private static String cp;

    private String phone_id;
    private String group_id;
    private static String build_str;

    private static String wifi_res;

    //Realm Variables
    //private RealmConfiguration realmConfiguration;
    //private Realm realm;
    RealmResults<GWDump> gw_db;
    RealmResults<MeasurementRealm> wit_db;




    //////////////////////
    // Lifecycle and Interface
    /////////////////////

    @Override
    public void onCreate() {
        //Crasher c = new Crasher("PlugWatchService:onCreate");
        if (AppConfig.RESTART_ON_EXCEPTION) {
            Thread.setDefaultUncaughtExceptionHandler(new RestartOnExceptionHandler(getBaseContext(), getClass().getName(),
                    PlugWatchUIActivity.class));
        }
        Log.i("PlugWatchService:onCreate", "hit");

        JobManager.create(this).addJobCreator(new NetworkJobCreator());

        mContext = this;
        setup_gw_callback(); //power disconnected
        setup_bluetooth(); //wit
        //setup_realm(); //database
        create_sticky_notification(); //hack to help with long running service

        RefWatcher refWatcher = App.getRefWatcher(this);
        refWatcher.watch(this);


        location_rec = LocationRequest.create()
                .setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY)
                .setNumUpdates(1);




        /*
        gw_db = realm.where(GWDump.class).findAll();
        num_gw = gw_db.size();

        wit_db = realm.where(MeasurementRealm.class).findAll();

        gw_db.addChangeListener(new RealmChangeListener<RealmResults<GWDump>>() {
            @Override
            public void onChange(RealmResults<GWDump> element) {
                num_gw = gw_db.size();
            }
        });


        wit_db.addChangeListener(new RealmChangeListener<RealmResults<MeasurementRealm>>() {
            @Override
            public void onChange(RealmResults<MeasurementRealm> element) {
                num_wit = wit_db.size();
                Log.e("good data: REALM", "new size is " + String.valueOf(num_wit));


                long total_wit = 0;
                try {
                    total_wit = appPreferences.getLong(SettingsConfig.TOTAL_WIT_SIZE);
                } catch (ItemNotFoundException e) {
                    e.printStackTrace();
                }
                total_wit += 1;
                appPreferences.put(SettingsConfig.TOTAL_WIT_SIZE, total_wit);
                appPreferences.put(SettingsConfig.WIT_SIZE, num_wit);

            }
        });



        */
        setupAverageService();


    }

    private void crash() {
        Log.e("crashing", null);
    }



    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        //Crasher c = new Crasher("PlugWatchService:onStartCommand");

        if (intent != null) {
            if (intent.hasExtra(IntentConfig.FAIL_PACKET)) {
                Log.e("PlugWatchService:onStart", "FAIL_PACKET");
                send_fail_packet();
            } else if (intent.hasExtra(IntentConfig.TEST)) {
                Log.e("PlugWatchService:onStart", "TEST");
                do_gw(null);
            } else if (intent.hasExtra(IntentConfig.FALSE_GW)) {
                Log.e("PlugWatchService:onStart", "FALSE_GW");
                do_gw(intent);
            } else if (intent.hasExtra(IntentConfig.CRASH_PLUGWATCH_SERVICE)) {
                Log.e("PlugWatchService:onStart", "WILL CRASH");
                crash();
            } else {
                start_ble();
            }
        }

        //Log.i("PlugWatchService:onStart", "hit with " + intent.getAction());
        /*
        if (intent.getAction().equals(IntentConfig.FAIL_PACKET)) {

        } else {
        }
        start_ble();
        */

        return START_STICKY;
    }



    @Override
    public void onDestroy() {
        super.onDestroy();


        unregisterReceiver(mPowerReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private final IPlugWatchService.Stub mBinder = new IPlugWatchService.Stub() {


        @Override
        public String get_ui_update() throws RemoteException {
            return "";
        }

        @Override
        public long get_last_time() throws RemoteException {
            return last_good_data;
        }



        @Override
        public boolean get_is_connected() throws RemoteException {
            return isConnected;
        }

        @Override
        public int get_num_wit() throws RemoteException {
            //num_wit = realm.where(MeasurementRealm.class).findAll().size();
            return num_wit;
        }

        @Override
        public int get_num_gw() throws RemoteException {
            return num_gw;
        }

        @Override
        public void set_phone_id(String cur_phone_id) throws RemoteException {
            phone_id = cur_phone_id;
        }

        @Override
        public void set_group_id(String cur_group_id) throws RemoteException {
            group_id = cur_group_id;
        }

        @Override
        public String get_mac() throws RemoteException {
            return macAddress;
        }

        @Override
        public void set_whitelist(boolean whitelist, String mac) throws RemoteException {
            is_whitelisted = whitelist;
            mac_whitelist = mac;
        }

        @Override
        public void set_build_str(String cur_build_str) throws RemoteException {
            build_str = cur_build_str;
        }

        @Override
        public String get_realm_filename() throws RemoteException {
            return full_realm_filename;
        }

        @Override
        public void set_wifi(String wifi) throws RemoteException {
            wifi_res = wifi;
        }

        @Override
        public int get_pid() throws RemoteException {
            return android.os.Process.myPid();
        }


    };

    //////////////////////
    // GW CONFIGS
    /////////////////////
    private void setup_gw_callback() {
        registerReceiver(mPowerReceiver, makePowerIntentFilter());
    }

    private void do_gw(Intent intent) {
        //Crasher c = new Crasher("PlugWatchService:do_gw");

        Log.e("GridWatch", "hit");
        GridWatch g = null;
            String phone_id = "-1";
            /*
            try {
                phone_id = appPreferences.getString(SettingsConfig.PHONE_ID);
            } catch (ItemNotFoundException e) {
                e.printStackTrace();
            }
            */
        if (intent == null) {
            g = new GridWatch(getBaseContext(), SensorConfig.NULL, phone_id,
                    String.valueOf(num_wit), macAddress, build_str, last_good_data);
            g.run();
            return;
        }
        if (intent.hasExtra(IntentConfig.FALSE_GW)) {
            Log.e("GridWatch", "doing false_gw");
            g = new GridWatch(getBaseContext(), SensorConfig.FALSE_GW, phone_id,
                    String.valueOf(num_wit), macAddress, build_str, last_good_data);
        } else if (intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
            Log.e("GridWatch", "doing power_connected");
            g = new GridWatch(getBaseContext(), SensorConfig.PLUGGED, phone_id,
                    String.valueOf(num_wit), macAddress, build_str, last_good_data);
        } else if (intent.getAction().equals(Intent.ACTION_POWER_DISCONNECTED)) {
            Log.e("GridWatch", "doing power_disconnected");
            g = new GridWatch(getBaseContext(), SensorConfig.UNPLUGGED, phone_id,
                    String.valueOf(num_wit), macAddress, build_str, last_good_data);
        }
        if (g != null) {
            g.run();
        }
    }

    private final BroadcastReceiver mPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, Intent intent) {
            do_gw(intent);
        }
    };

    public static IntentFilter makePowerIntentFilter() {
        IntentFilter ifilter = new IntentFilter();
        ifilter.addAction(Intent.ACTION_POWER_CONNECTED);
        ifilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        ifilter.addAction(Intent.ACTION_DOCK_EVENT);
        return ifilter;
    }

    //////////////////////
    // REALM CONFIGS
    /////////////////////
    private void setup_realm() {
        try {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd");
            String currentDateandTime = sdf.format(new Date());
            Realm.init(this);
            File f = openRealm();
            realm_filename = "realm_" + Settings.Secure.getString(getBaseContext().getContentResolver(),
                    Settings.Secure.ANDROID_ID) + "_" + currentDateandTime;
            full_realm_filename = f.getAbsolutePath().toString() + "/" + realm_filename;
            Log.e("realm filename", full_realm_filename);
            RealmConfiguration realmConfiguration = new RealmConfiguration.Builder()
                    .name(realm_filename)
                    .directory(f)
                    .build();
            Realm.deleteRealm(realmConfiguration); // Clean slate
            Realm.setDefaultConfiguration(realmConfiguration); // Make this Realm the default
        } catch (Exception e) {
            e.printStackTrace();
            Restart r = new Restart();
            r.do_restart(this, PlugWatchUIActivity.class, getClass().getName(), new Throwable("realm failed"), -1);
        }
    }

    private File openRealm() {
        String secStore = System.getenv("SECONDARY_STORAGE");
        File f_secs = new File(secStore);
        if (!f_secs.exists()) {
            boolean result = f_secs.mkdir();
            Log.i("TTT", "Results: " + result);
        }
        return f_secs;
    }



    /////////////////////////////////
    // BLE CONFIGS AND FUNCTIONALITY
    ////////////////////////////////
    private void setup_bluetooth() {
        //Crasher c = new Crasher("PlugWatchService:setup_bluetooth");

        BluetoothAdapter.getDefaultAdapter().enable();
        rxBleClient = RxBleClient.create(this);
        RxBleClient.setLogLevel(RxBleLog.DEBUG);
    }

    public void start_ble() {
        kill_ble();
        start_scanning();
    }

    public void kill_ble() {
        //BluetoothAdapter.getDefaultAdapter().disable();
        //BluetoothAdapter.getDefaultAdapter().enable();
        //to_write_slowly = new ArrayList<>();
        //bleDevice = null;
        //scanSubscription = null;
        //rxBleClient = null;
        //rxBleConnection
        /*
        if (connectionObservable != null) {
            connectionObservable
                    .flatMap(rxBleConnection -> rxBleConnection.setupNotification(BluetoothConfig.UUID_WIT_FFE1))
                    .doOnNext(new Action1<Observable<byte[]>>() {
                        @Override
                        public void call(Observable<byte[]> observable) {
                            notificationHasBeenSetUp();
                        }
                    })
                    .flatMap(notificationObservable -> notificationObservable)
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(this::onNotificationReceivedFFE1, this::onNotificationSetupFailure).unsubscribe();
        }
        */
    }

    private void start_scanning() {
        if (!BluetoothAdapter.getDefaultAdapter().isEnabled()) {
            BluetoothAdapter.getDefaultAdapter().enable();
        }
        scanSubscription = rxBleClient.scanBleDevices()
                .observeOn(AndroidSchedulers.mainThread())
                .doOnUnsubscribe(this::clearSubscription)
                .subscribe(this::addScanResult, this::onScanFailure);
    }


    private void setupAverageService() {
        AlarmManager am = (AlarmManager) mContext.getSystemService(Context.ALARM_SERVICE);
        Calendar cal = Calendar.getInstance();
        long interval = SensorConfig.AVERAGE_INTERVAL; // 30 seconds in milliseconds
        Intent serviceIntent = new Intent(mContext, AverageService.class);
        PendingIntent servicePendingIntent =
                PendingIntent.getService(mContext,
                        284372, //integer constant used to identify the service
                        serviceIntent,
                        PendingIntent.FLAG_CANCEL_CURRENT);
        am.setRepeating(
                AlarmManager.RTC_WAKEUP,
                cal.getTimeInMillis(),
                interval,
                servicePendingIntent
        );
    }

    private void getWiTenergy() {
        //Crasher c = new Crasher("PlugWatchService:getWiTenergy");

        Log.i("getWiTenergy", "hit");
        connectionObservable
                .flatMap(rxBleConnection -> rxBleConnection.setupNotification(BluetoothConfig.UUID_WIT_FFE1))
                .doOnNext(new Action1<Observable<byte[]>>() {
                    @Override
                    public void call(Observable<byte[]> observable) {
                        notificationHasBeenSetUp();
                    }
                })
                .flatMap(notificationObservable -> notificationObservable)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnTerminate(new Action0() {
                    @Override
                    public void call() {
                        isConnected = false;
                        Log.e("ble on terminate", "restarting scanning");
                        //start_scanning();
                    }
                })
                .doOnError(new Action1<Throwable>() {
                    @Override
                    public void call(Throwable throwable) {
                        Log.e("ble disconnected", throwable.getMessage());
                        FirebaseCrash.log(throwable.getMessage());
                        isConnected = false;
                        //start_scanning();
                    }
                })
                .doOnUnsubscribe(new Action0() {
                    @Override
                    public void call() {
                        isConnected = false;
                        //Log.e("ble on unsubscribe", "restarting scanning");
                        //start_scanning();
                    }
                })
                .subscribe(this::onNotificationReceivedFFE1, this::onNotificationSetupFailure);
    }

    private void create_sticky_notification() {
            Intent intent = new Intent(this, PlugWatchUIActivity.class);

            PendingIntent pendingIntent = PendingIntent.getActivity(this,
                    247281938, intent, PendingIntent.FLAG_UPDATE_CURRENT);

            Notification.Builder builder = new Notification.Builder(this)
                    .setContentTitle("PlugWatch")
                    .setContentText("Long running service")
                    .setContentIntent(pendingIntent)
                    .setSmallIcon(R.drawable.common_google_signin_btn_icon_light)
                    .setLargeIcon(BitmapFactory.decodeResource(getResources(), R.drawable.common_google_signin_btn_icon_dark_disabled))
                    ;
            Notification n;
            n = builder.build();
            n.flags |= Notification.FLAG_NO_CLEAR | Notification.FLAG_ONGOING_EVENT;
            NotificationManager mNotifyMgr =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            mNotifyMgr.notify(247281938, n);

    }

    private void send_fail_packet() {
        //Crasher c = new Crasher("PlugWatchService:send_fail_packet");

        //checkCP();


        String phone_id = "-1";
        String group_id = "-1";
        try {
            phone_id = appPreferences.getString(SettingsConfig.PHONE_ID);
            group_id = appPreferences.getString(SettingsConfig.GROUP_ID);
        } catch (ItemNotFoundException e) {
            FirebaseCrash.log(e.getMessage());
            e.printStackTrace();
        }




        Log.e("phone_id", phone_id);
        double lat = 0.0;
        double lng = 0.0;
        try {
            LatLngWriter lat_lng_writer = new LatLngWriter(getClass().getName());
            String latlng = lat_lng_writer.get_last_value();
            lat = Double.valueOf(latlng.split(",")[0]);
            lng = Double.valueOf(latlng.split(",")[1]);
        } catch (Exception e) {
            FirebaseCrash.log(e.getMessage());
            e.printStackTrace();
        }

        long total_wit = 0;
        long cur_wit = 0;
        try {
            total_wit = appPreferences.getLong(SettingsConfig.TOTAL_WIT_SIZE);
            cur_wit = appPreferences.getLong(SettingsConfig.WIT_SIZE);
        } catch (ItemNotFoundException e) {
            e.printStackTrace();
        }
        total_wit += 1;
        appPreferences.put(SettingsConfig.TOTAL_WIT_SIZE, total_wit);
        cur_wit +=1;
        appPreferences.put(SettingsConfig.WIT_SIZE, cur_wit);


        String ms_time_running = "-1";
        try {
            ms_time_running = appPreferences.getString(SettingsConfig.TIME_RUNNING);
        } catch (ItemNotFoundException e) {
            FirebaseCrash.log(e.getMessage());
            e.printStackTrace();
        }


        WitRetrofit a = new WitRetrofit("-2", "-2", "-2", "-2",
                "-2", System.currentTimeMillis(), lat,
                lng, phone_id, group_id, build_str, String.valueOf(num_wit + 1), macAddress, cp, wifi_res,
                String.valueOf(total_wit), ms_time_running);
        Log.i("fail packet: network scheduling", a.toString());
        Log.i("fail packet: number of jobs: ", String.valueOf(JobManager.instance().getAllJobRequests().size()));
        if (JobManager.instance().getAllJobRequests().size() > SensorConfig.MAX_JOBS) {
            Log.e("fail packet: network", "canceling all jobs");
            JobManager.instance().cancelAll();
        }
        int jobId = new JobRequest.Builder(NetworkJob.TAG)
                .setExecutionWindow(1_000L, 20_000L)
                .setBackoffCriteria(5_000L, JobRequest.BackoffPolicy.EXPONENTIAL)
                .setRequiresCharging(false)
                .setExtras(a.toBundle())
                .setRequiresDeviceIdle(false)
                .setRequiredNetworkType(JobRequest.NetworkType.CONNECTED)
                .setPersisted(true)
                .build()
                .schedule();




    }

    private void addScanResult(RxBleScanResult bleScanResult) {
        if (bleScanResult.getBleDevice().getName().contains("Smart")) {
            bleDevice = bleScanResult.getBleDevice();

            //TODO: cache this
            connectionObservable = bleDevice
                    .establishConnection(mContext, false)
                    .takeUntil(disconnectTriggerSubject)
                    .doOnUnsubscribe(this::clearSubscription)
                    .compose(new ConnectionSharingAdapter());

            macAddress = bleDevice.getMacAddress();
            appPreferences.put(SettingsConfig.STICKY_MAC, macAddress);
            if (!isConnected) {
                getWiTenergy();
            }
            scanSubscription.unsubscribe();
        }
    }
    private void write_command(UUID charac, byte[] data, int length) {
        final byte[] bArr = Arrays.copyOf(data, length);
        to_write_slowly.add(bArr);
    }

    private void onNotificationSetupFailure(Throwable throwable) {
    }

    private void notificationHasBeenSetUp() {
    }

    private void clearSubscription() {
        scanSubscription = null;
    }


    private void onScanFailure(Throwable throwable) {
        if (throwable instanceof BleScanException) {
            handleBleScanException((BleScanException) throwable);
        }
    }

    private static void checkCP() {
        try {
                MacWriter r = new MacWriter("PlugWatchService");
                r.log(String.valueOf(System.currentTimeMillis()), macAddress, "n");
                if (macAddress != null) {
                    String sticky = r.get_last_sticky_value();
                    if (!macAddress.equals(sticky)) {
                        cp = "t";
                    } else {
                        cp = "f";
                    }
                } else {
                    cp = "f";
                }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleBleScanException(BleScanException bleScanException) {
        switch (bleScanException.getReason()) {
            case BleScanException.BLUETOOTH_NOT_AVAILABLE:
                Toast.makeText(mContext, "Bluetooth is not available", Toast.LENGTH_SHORT).show();
                FirebaseCrash.log("handleBleScanException: Bluetooth is not available");
                break;
            case BleScanException.BLUETOOTH_DISABLED:
                Toast.makeText(mContext, "Enable bluetooth and try again", Toast.LENGTH_SHORT).show();
                FirebaseCrash.log("handleBleScanException: Enable bluetooth and try again");
                Rebooter t = new Rebooter(this, getClass().getName(), true, new Throwable("bluetooth stack died"));
                break;
            case BleScanException.LOCATION_PERMISSION_MISSING:
                Toast.makeText(mContext,
                        "On Android 6.0 location permission is required. Implement Runtime Permissions", Toast.LENGTH_SHORT).show();
                break;
            case BleScanException.LOCATION_SERVICES_DISABLED:
                Toast.makeText(mContext, "Location services needs to be enabled on Android 6.0", Toast.LENGTH_SHORT).show();
                break;
            case BleScanException.BLUETOOTH_CANNOT_START:
            default:
                FirebaseCrash.log("handleBleScanException: unable to start scanning");
                Toast.makeText(mContext, "Unable to start scanning", Toast.LENGTH_SHORT).show();
                Rebooter r = new Rebooter(mContext, getClass().getName(), true, new Throwable("unable to start scanning"));
                break;
        }
    }

    private String decode_energyData(byte[] data, int index) {
        if (data == null) {
            return "0.0";
        }
        double value = ((((double) (((data[index + ID_ACC] >> ID_OBJ) & 15) * GATT_TIMEOUT)) + ((double) ((data[index + ID_ACC] & 15) * 100))) + ((double) (((data[index + ID_OPT] >> ID_OBJ) & 15) * 10))) + ((double) (data[index + ID_OPT] & 15));
        Object[] objArr;
        switch (data[index]) {
            case ID_ACC /*1*/:
                objArr = new Object[ID_ACC];
                objArr[ID_OFFSET] = Double.valueOf(value / 1000.0d);
                return String.format(Locale.US, "%4.3f", objArr);
            case ID_OPT /*2*/:
                objArr = new Object[ID_ACC];
                objArr[ID_OFFSET] = Double.valueOf(value / 100.0d);
                return String.format(Locale.US, "%4.2f", objArr);
            case ID_GYR /*3*/:
                objArr = new Object[ID_ACC];
                objArr[ID_OFFSET] = Double.valueOf(value / 10.0d);
                return String.format(Locale.US, "%4.1f", objArr);
            case ID_OBJ /*4*/:
                objArr = new Object[ID_ACC];
                objArr[ID_OFFSET] = Double.valueOf(value);
                return String.format(Locale.US, "%4.1f", objArr);
            case ID_AMB /*5*/:
                objArr = new Object[ID_ACC];
                objArr[ID_OFFSET] = Double.valueOf(value / 1000.0d);
                return String.format(Locale.US, "%4.2f", objArr);
            default:
                return "0.0";
        }
    }

    private void onNotificationReceivedFFE1(byte[] value) {
       // Log.i("notification", "FFE1");
        //There is a state where notifications are coming in but they are not good. This state requires app reboot
        if (isClockUpdated && to_write_slowly.size() == 0) { //don't do this if we are still setting up the connection with writes
            if (System.currentTimeMillis() - last_good_data > SensorConfig.NOTIFICATION_BUT_NO_DECODE_TIMEOUT) {
                Log.e("connection timeout", String.valueOf(System.currentTimeMillis() - last_good_data));
                Restart r = new Restart();
                r.do_restart(mContext, PlugWatchUIActivity.class, getClass().getName(), new Throwable("Restart due to notification but no decode"), Process.myPid()); //figure out why this sometimes launches many services
            }
        }

        if (!isClockUpdated) {
            Log.i("notification", "clock not updated");
            int i;
            byte[] data = new byte[10];
            Calendar now = Calendar.getInstance();
            int year = now.get(COUNT_DOWN_TIMER);
            int month = now.get(OVERLOAD);
            int day = now.get(DEVICESETTING);
            int hour = now.get(Calendar.HOUR_OF_DAY);
            int minute = now.get(Calendar.MINUTE);
            int second = now.get(Calendar.SECOND);
            data[PREF_ACT_REQ] = (byte) 3;
            data[COUNT_DOWN_TIMER] = (byte) (year & ACTION_MASK);
            data[OVERLOAD] = (byte) ((year >> 8) & ACTION_MASK);
            data[STANDBY] = (byte) ((month + COUNT_DOWN_TIMER) & ACTION_MASK);
            data[SCHEDULER] = (byte) (day & ACTION_MASK);
            data[DEVICESETTING] = (byte) (hour & ACTION_MASK);
            data[6] = (byte) (minute & ACTION_MASK);
            data[7] = (byte) (second & ACTION_MASK);
            int encryptKey = getEncryptKey();
            data[8] = (byte) (encryptKey & ACTION_MASK);
            data[9] = (byte) ((encryptKey >> 8) & ACTION_MASK);
            write_command(BluetoothConfig.UUID_WIT_FFE3, data, 10);
            data[PREF_ACT_REQ] = (byte) 20;
            write_command(BluetoothConfig.UUID_WIT_FFE3, data, COUNT_DOWN_TIMER);
            data[PREF_ACT_REQ] = (byte) 22;
            write_command(BluetoothConfig.UUID_WIT_FFE3, data, COUNT_DOWN_TIMER);
            data[PREF_ACT_REQ] = (byte) 23;
            write_command(BluetoothConfig.UUID_WIT_FFE3, data, COUNT_DOWN_TIMER);
            data[PREF_ACT_REQ] = (byte) 24;
            write_command(BluetoothConfig.UUID_WIT_FFE3, data, COUNT_DOWN_TIMER);
            for (i = PREF_ACT_REQ; i < 6; i += COUNT_DOWN_TIMER) {
                data[PREF_ACT_REQ] = (byte) 14;
                data[COUNT_DOWN_TIMER] = (byte) i;
                data[OVERLOAD] = (byte) 0;
                data[STANDBY] = (byte) 5;
                write_command(BluetoothConfig.UUID_WIT_FFE3, data, SCHEDULER);
            }
            isClockUpdated = true;
            int length = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            if (length > 0) {
                data[PREF_ACT_REQ] = (byte) 1;
                data[OVERLOAD] = (byte) 0;
                i = PREF_ACT_REQ;
                while (i < 24 && length > 0) {
                    int i2;
                    data[COUNT_DOWN_TIMER] = (byte) i;
                    if (length > 8) {
                        i2 = 8;
                    } else {
                        i2 = length;
                    }
                    data[STANDBY] = (byte) i2;
                    write_command(BluetoothConfig.UUID_WIT_FFE3, data, SCHEDULER);
                    i += 8;
                    length -= 8;
                }
            }
        } else {
            if (to_write_slowly.size() != 0) {
                Log.e("to write", String.valueOf(to_write_slowly.size()));
                connectionObservable
                        .flatMap(rxBleConnection -> rxBleConnection.writeCharacteristic(BluetoothConfig.UUID_WIT_FFE3, to_write_slowly.remove(0)))
                        .observeOn(AndroidSchedulers.mainThread())
                        .delay(GATT_TIMEOUT, TimeUnit.MILLISECONDS)
                        .buffer(GATT_TIMEOUT, TimeUnit.MILLISECONDS)
                        .subscribe(bytes -> {
                            onWriteSuccess();
                        }, this::onWriteFailure);
            }
            good_data(value);

        }
    }

    private void good_data(byte[] value) {



        isConnected = true;
        last_good_data = System.currentTimeMillis();

        appPreferences.put(SettingsConfig.LAST_WIT, last_good_data);

        mVoltage = (decode_energyData(value, ID_ACC));
        mCurrent = (decode_energyData(value, ID_OBJ));
        mPower = decode_energyData(value, ID_BAR);
        mPowerFactor = (decode_energyData(value, 10));
        mFrequency = (decode_energyData(value, 13));
        Log.d("MEASUREMENT: voltage", mVoltage);
        Log.d("MEASUREMENT: current", mCurrent);
        Log.d("MEASUREMENT: power", mPower);
        Log.d("MEASUREMENT: pf", mPowerFactor);
        Log.d("MEASUREMENT: frequency", mFrequency);
        Log.d("MEASUREMENT: time", String.valueOf(last_good_data));


        Realm realm = Realm.getDefaultInstance();
        realm.executeTransactionAsync(new Realm.Transaction() {
            @Override
            public void execute(Realm realm) {
                MeasurementRealm cur = new MeasurementRealm(mCurrent, mFrequency,
                        mPower, mPowerFactor, mVoltage, macAddress);
                realm.copyToRealm(cur);
            }
        }, new Realm.Transaction.OnSuccess() {
            @Override
            public void onSuccess() {
                Log.e("realm", "Added to realm");
                realm.close();

            }
        }, new Realm.Transaction.OnError() {

            @Override
            public void onError(Throwable error) {
                Log.e("realm", "Error " + error.getMessage());

            }
        });


        //ScheduleNetwork task = new ScheduleNetwork();
        //task.doInBackground();


        /*

            realm = Realm.getDefaultInstance();
            realm.executeTransactionAsync(new Realm.Transaction() {
                @Override
                public void execute(Realm bgRealm) {
                    try {
                        MeasurementRealm cur = new MeasurementRealm(mCurrent, mFrequency,
                                mPower, mPowerFactor, mVoltage, macAddress);
                        bgRealm.copyToRealm(cur);
                    } catch (java.lang.IllegalStateException e) {
                        JobManager.create(mContext).addJobCreator(new NetworkJobCreator());
                        //JobManager.instance().cancelAll();
                    } catch (Exception e) {
                        e.printStackTrace();
                        Log.e("good_data: error", e.getMessage());
                        FirebaseCrash.log(e.getMessage());
                    }
                }
            }, new Realm.Transaction.OnSuccess() {
                @Override
                public void onSuccess() {
                    //Log.e("good_data: REALM", "new size is: " + String.valueOf(realm.where(MeasurementRealm.class).findAll().size()));
                    realm.close();
                }


            }, new Realm.Transaction.OnError() {
                @Override
                public void onError(Throwable error) {
                    Log.e("realm", "error " + error.getMessage());

                }
            });



        /*finally {
            realm.close();
        }
        */






        try {
            Random rand = new Random();
            int randomNum = rand.nextInt((60*60*5 - 0) + 1) + 0;
            if (randomNum == 0) {
                Intent gw = new Intent();
                gw.putExtra(IntentConfig.FALSE_GW, IntentConfig.FALSE_GW);
                do_gw(gw);
            }
        } catch (Exception e) {
            Log.e("random gw", "failed");
        }



        //PlugWatchApp.getInstance().increment_last_time();
        /*
        PlugWatchApp.getInstance().set_last_time(System.currentTimeMillis());
        SharedPreferences settings = mContext.getSharedPreferences(SettingsConfig.SETTINGS_META_DATA, 0);
        settings.edit().putLong(SettingsConfig.LAST_WIT, System.currentTimeMillis()).commit();
        PlugWatchApp.getInstance().set_is_connected(true);
        */



    }


    private int getEncryptKey() {
        int i;
        byte[] KEY = new byte[]{(byte) 105, (byte) 76, (byte) 111, (byte) 103, (byte) 105, (byte) 99};
        byte[] MAC = new byte[6];
        byte[] address = bleDevice.getMacAddress().getBytes().clone();
        int encryptKey = PREF_ACT_REQ;
        int j = 15;
        for (i = PREF_ACT_REQ; i < 6; i += COUNT_DOWN_TIMER) {
            if (address[j] <= 57) {
                MAC[i] = (byte) (address[j] - 48);
            } else {
                MAC[i] = (byte) ((address[j] - 65) + 10);
            }
            MAC[i] = (byte) (MAC[i] << SCHEDULER);
            if (address[j + COUNT_DOWN_TIMER] <= 57) {
                MAC[i] = (byte) (MAC[i] + ((byte) ((address[j + COUNT_DOWN_TIMER] - 48) & 15)));
            } else {
                MAC[i] = (byte) (MAC[i] + ((byte) (((address[j + COUNT_DOWN_TIMER] - 65) + 10) & 15)));
            }
            j -= 3;
        }
        for (i = PREF_ACT_REQ; i < 6; i += COUNT_DOWN_TIMER) {
            encryptKey += (MAC[i] ^ KEY[i]) & ACTION_MASK;
        }
        StringBuilder append = new StringBuilder().append("EncryptKey = ");
        Object[] objArr = new Object[COUNT_DOWN_TIMER];
        objArr[PREF_ACT_REQ] = Integer.valueOf(encryptKey);
        Log.i("encrypt key", append.append(String.format("%x", objArr)).toString());
        Log.i("encrypt key", "MAC =" + String.format("%x:%x:%x:%x:%x:%x", new Object[]{Byte.valueOf(MAC[PREF_ACT_REQ]), Byte.valueOf(MAC[COUNT_DOWN_TIMER]), Byte.valueOf(MAC[OVERLOAD]), Byte.valueOf(MAC[STANDBY]), Byte.valueOf(MAC[SCHEDULER]), Byte.valueOf(MAC[DEVICESETTING])}));
        return encryptKey;
    }



    private void onWriteSuccess() {
        isConnected = true;
        //PlugWatchApp.getInstance().set_is_connected(true);
        clearSubscription();
    }

    private void onWriteFailure(Throwable throwable) {
        isConnected = false;

        //PlugWatchApp.getInstance().set_is_connected(false);
    }





}