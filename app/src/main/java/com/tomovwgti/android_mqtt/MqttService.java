
package com.tomovwgti.android_mqtt;

import java.util.Locale;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.client.mqttv3.MqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import org.eclipse.paho.client.mqttv3.MqttTopic;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;

public class MqttService extends Service implements MqttCallback {
    public static final String TAG = MqttService.class.getSimpleName();

    private MqttService self = this;

    interface resultCallback {
        void onResult(String action, int status, String message);
    }

    // handler Thread ID
    private static final String MQTT_THREAD_NAME = "MqttService[" + TAG + "]";
    // QOS Level 0 ( Delivery Once no confirmation )
    public static final int MQTT_QOS_0 = 0;
    // QOS Level 1 ( Delevery at least Once with confirmation )
    public static final int MQTT_QOS_1 = 1;
    // QOS Level 2 ( Delivery only once with confirmation with handshake )
    public static final int MQTT_QOS_2 = 2;
    // retained message
    public static final boolean MQTT_NO_RETAIN = false;
    // This the application level keep-alive interval, that is used by the
    // AlarmManager
    // to keep the connection active, even when the device goes to sleep.
    private static final long KEEP_ALIVE_INTERVAL = 1000 * 60 * 28;

    // Retry intervals, when the connection is lost.
    private static final long INITIAL_RETRY_INTERVAL = 1000 * 10;
    private static final long MAXIMUM_RETRY_INTERVAL = 1000 * 60 * 30;

    // Topic format for KeepAlives
    private static final String MQTT_KEEP_ALIVE_TOPIC_FORAMT = "/users/%s/keepalive";
    private static final byte[] MQTT_KEEP_ALIVE_MESSAGE = {
        0
    }; // Keep Alive message to send

    // Default KeepAlive QOS
    private static final int MQTT_KEEP_ALIVE_QOS = MQTT_QOS_0;
    // Start a clean session ?
    private static final boolean MQTT_CLEAN_SESSION = true;
    // URL Format normaly don't change
    private static final String MQTT_URL_FORMAT = "tcp://%s:%d";
    // Action to start
    public static final String ACTION_START = TAG + ".START";
    // Action to stop
    public static final String ACTION_STOP = TAG + ".STOP";
    // Action to subscribe
    private static final String ACTION_SUBSCRIBE = TAG + ".SUBSCRIBE";
    // Action to publish
    private static final String ACTION_PUBLISH = TAG + ".PUBLISH";
    // Action to keep alive used by alarm manager
    private static final String ACTION_KEEPALIVE = TAG + ".KEEPALIVE";
    // Action to reconnect
    private static final String ACTION_RECONNECT = TAG + ".RECONNECT";
    // Is the Client started?
    private boolean mStarted = false;
    // Seperate Handler thread for networking
    private Handler mConnHandler;

    // Defaults to FileStore
    private MqttDefaultFilePersistence mDataStore;
    // On Fail reverts to MemoryStore
    private MemoryPersistence mMemStore;
    // Connection Options
    private MqttConnectOptions mOpts;
    // Instance Variable for Keepalive topic
    private MqttTopic mKeepAliveTopic;

    // Mqtt Client
    private MqttClient mClient;

    private long mStartTime;

    // Alarm manager to perform repeating tasks
    private AlarmManager mAlarmManager;
    // To check for connectivity changes
    private ConnectivityManager mConnectivityManager;

    // Preferences instance
    private SharedPreferences mPrefs;
    // We store in the preferences, whether or not the service has been started
    public static final String PREF_STARTED = "isStarted";
    // We also store the client ID
    public static final String PREF_CLIENT_ID = "clientID";
    // We also store the server address
    public static final String PREF_SERVER_ADDRESS = "server";
    // We also store the server port
    public static final String PREF_SERVER_PORT = "port";
    // We also store the username for server
    public static final String PREF_USERNAME = "username";
    // We also store the password for server
    public static final String PREF_PASSWORD = "password";
    // We also store the topic
    public static final String PREF_TOPIC = "topic";
    // We also store the session
    public static final String PREF_SESSION = "session";
    // We also store the publish topic
    public static final String PREF_PUBLISH_TOPIC = "publish";
    // We also store the publish message
    public static final String PREF_PUBLISH_MESSAGE = "message";

    // We store the last retry interval
    public static final String PREF_RETRY = "retryInterval";

    // Notification id
    private static final int NOTIF_CONNECTED = 0;

    public final static int STATUS_SUCCESS = 256;
    private static resultCallback mCallback;

    public static void setOnResultListener(resultCallback callback) {
        mCallback = callback;
    }

    public static void action(Context ctx, String action) {
        Intent i = new Intent(ctx, MqttService.class);
        if (action.equals(ACTION_START)) {
            i.setAction(ACTION_START);
            ctx.startService(i);
        } else if (action.equals(ACTION_STOP)) {
            i.setAction(ACTION_STOP);
            ctx.startService(i);
        }
    }

    /**
     * Subscribe topic
     *  @param ctx
     *
     */
    public static void subscribe(Context ctx, String topic) {
        Intent i = new Intent(ctx, MqttService.class);
        i.putExtra(PREF_TOPIC, topic);
        i.setAction(ACTION_SUBSCRIBE);
        ctx.startService(i);
    }

    /**
     * Publish message
     *
     * @param ctx
     * @param topic
     * @param message
     */
    public static void publish(Context ctx, String topic, String message) {
        Intent i = new Intent(ctx, MqttService.class);
        i.putExtra(PREF_PUBLISH_TOPIC, topic);
        i.putExtra(PREF_PUBLISH_MESSAGE, message);
        i.setAction(ACTION_PUBLISH);
        ctx.startService(i);
    }

    /**
     * Send a KeepAlive Message
     * 
     * @param ctx context to start the service with
     * @return void
     */
    public static void actionKeepalive(Context ctx) {
        Intent i = new Intent(ctx, MqttService.class);
        i.setAction(ACTION_KEEPALIVE);
        ctx.startService(i);
    }

    /**
     * Initalizes the DeviceId and most instance variables Including the
     * Connection Handler, Datastore, Alarm Manager and ConnectivityManager.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        Log.i(TAG, "Creating service");
        mStartTime = System.currentTimeMillis();

        HandlerThread thread = new HandlerThread(MQTT_THREAD_NAME);
        thread.start();

        mConnHandler = new Handler(thread.getLooper());

        mDataStore = new MqttDefaultFilePersistence(getCacheDir().getAbsolutePath());

        mOpts = new MqttConnectOptions();

        // Do not set keep alive interval on mOpts we keep track of it with
        // alarm's

        mAlarmManager = (AlarmManager) getSystemService(ALARM_SERVICE);

        // Get instances of preferences, connectivity manager and notification
        // manager
        mPrefs = getSharedPreferences(TAG, MODE_PRIVATE);
        mConnectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);

        /*
         * If our process was reaped by the system for any reason we need to
         * restore our state with merely a call to onCreate. We record the last
         * "started" value and restore it here if necessary.
         */
        handleCrashedService();
    }

    /**
     * Service onStartCommand Handles the action passed via the Intent
     * 
     * @return START_REDELIVER_INTENT
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {

        Log.i(TAG, "Starting service");

        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        Log.i(TAG, "Received action of " + action);

        if (action == null) {
            Log.i(TAG, "Starting service with no action\n Probably from a crash");
        } else {
            if (action.equals(ACTION_START)) {
                Log.i(TAG, "Received ACTION_START");
                start();
            } else if (action.equals(ACTION_STOP)) {
                String topic = mPrefs.getString(PREF_TOPIC, null);
                if (topic != null && !topic.equals("")) {
                    try {
                        mClient.unsubscribe(topic);
                        Toast.makeText(this, "Unsubscribe: " + topic, Toast.LENGTH_SHORT).show();
                    } catch (MqttException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
                stop();
                stopSelf();
            } else if (action.equals(ACTION_KEEPALIVE)) {
                keepAlive();
            } else if (intent.getAction().equals(ACTION_SUBSCRIBE)) {
                subscribe(intent.getStringExtra(PREF_TOPIC));
            } else if (intent.getAction().equals(ACTION_PUBLISH)) {
                publish(intent.getStringExtra(PREF_PUBLISH_TOPIC), intent.getStringExtra(PREF_PUBLISH_MESSAGE));
            } else if (action.equals(ACTION_RECONNECT)) {
                if (isNetworkAvailable()) {
                    reconnectIfNecessary();
                }
            }
        }

        // return START_REDELIVER_INTENT;
        return START_STICKY;
    }

    /**
     * Attempts connect to the Mqtt Broker and listen for Connectivity changes
     * via ConnectivityManager.CONNECTVITIY_ACTION BroadcastReceiver
     */
    private synchronized void start() {
        // Do nothing, if the service is already running.
        if (mStarted) {
            Log.i(TAG, "Attempt to start while already started");
            return;
        }

        if (hasScheduledKeepAlives()) {
            stopKeepAlives();
        }

        // Establish an MQTT connection
        connect();

        // Register a connectivity listener
        registerReceiver(mConnectivityReceiver, new IntentFilter(
                ConnectivityManager.CONNECTIVITY_ACTION));
    }

    /**
     * Attempts to stop the Mqtt client as well as halting all keep alive
     * messages queued in the alarm manager
     */
    private synchronized void stop() {
        if (!mStarted) {
            Log.i(TAG, "Attemtpign to stop connection that isn't running");
            return;
        }

        if (mClient != null) {
            mConnHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        mClient.disconnect();
                    } catch (MqttException ex) {
                        ex.printStackTrace();
                    }
                    mClient = null;
                    setStarted(false);

                    stopKeepAlives();
                }
            });
        }
        unregisterReceiver(mConnectivityReceiver);
        // disconnect success
        if (mCallback != null) {
            mCallback.onResult(ACTION_STOP, STATUS_SUCCESS, null);
        }
    }

    /**
     * Connects to the broker with the appropriate datastore
     */
    private synchronized void connect() {
        Log.i(TAG, "Connecting...");

        // fetch the server, topic from the preferences.
        final String server = mPrefs.getString(PREF_SERVER_ADDRESS, null);
        final int port = mPrefs.getInt(PREF_SERVER_PORT, 1883);
        final String clientid = mPrefs.getString(PREF_CLIENT_ID, null);
        final String username = mPrefs.getString(PREF_USERNAME, null);
        final String password = mPrefs.getString(PREF_PASSWORD, null);
        final String topic = mPrefs.getString(PREF_TOPIC, null);
        final boolean session = mPrefs.getBoolean(PREF_SESSION, MQTT_CLEAN_SESSION);
        Log.d(TAG, "server: " + server);
        Log.d(TAG, "topic: " + topic);

        String url = String.format(Locale.US, MQTT_URL_FORMAT, server, port);
        Log.i(TAG, "Connecting with URL: " + url);
        try {
            if (mDataStore != null) {
                Log.i(TAG, "Connecting with DataStore");
                mClient = new MqttClient(url, clientid, mDataStore);
            } else {
                Log.i(TAG, "Connecting with MemStore");
                mClient = new MqttClient(url, clientid, mMemStore);
            }

            // username, password
            if (!username.equals("")) {
                mOpts.setUserName(username);
            }
            if (!password.equals("")) {
                mOpts.setPassword(password.toCharArray());
            }
            // clean session
            mOpts.setCleanSession(session);
        } catch (MqttException e) {
            e.printStackTrace();
        }

        mConnHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mClient.connect(mOpts);

                    mClient.setCallback(MqttService.this);
                    // subscribe topic
                    mClient.subscribe(topic);

                    // Service is now connected
                    setStarted(true);

                    Log.i(TAG, "Successfully connected and subscribed starting keep alives");

                    // Save start time
                    mStartTime = System.currentTimeMillis();
                    // Star the keep-alives
                    startKeepAlives();
                    // connect success
                    if (mCallback != null) {
                        mCallback.onResult(ACTION_START, STATUS_SUCCESS, null);
                    }
                } catch (MqttException e) {
                    // Schedule a reconnect, if we failed to connect
                    setStarted(false);
                    if (mCallback != null) {
                        mCallback.onResult(ACTION_START, e.getReasonCode(), e.getMessage());
                    }
                    if (isNetworkAvailable()) {
                        scheduleReconnect(mStartTime);
                    }
                }
            }
        });
    }

    /**
     * Schedules keep alives via a PendingIntent in the Alarm Manager
     */
    private void startKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        mAlarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis()
                + KEEP_ALIVE_INTERVAL, KEEP_ALIVE_INTERVAL, pi);
    }

    /**
     * Cancels the Pending Intent in the alarm manager
     */
    private void stopKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        mAlarmManager.cancel(pi);
    }

    /**
     * Publishes a KeepALive to the topic in the broker
     */
    private synchronized void keepAlive() {
        if (isConnected()) {
            try {
                sendKeepAlive();
            } catch (MqttConnectivityException ex) {
                ex.printStackTrace();
                reconnectIfNecessary();
            } catch (MqttPersistenceException ex) {
                ex.printStackTrace();
                stop();
            } catch (MqttException ex) {
                ex.printStackTrace();
                stop();
            }
        }
    }

    /**
     * subscribe topic
     * @param topic
     */
    private synchronized void subscribe(String topic) {
        if (mStarted) try {
            Log.i(TAG, "subscribe: " + topic);
            mClient.subscribe(topic, MQTT_QOS_1);
        } catch (MqttException e) {
            Log.e(TAG, "MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);
        }
    }

    /**
     * publish message
     *
     * @param topic
     * @param message
     */
    private synchronized void publish(String topic, String message) {
        if (mStarted) try {
                mClient.publish(topic, message.getBytes(), MQTT_QOS_1, MQTT_NO_RETAIN);
        } catch (MqttException e) {
            Log.e(TAG, "MqttException: " + (e.getMessage() != null ? e.getMessage() : "NULL"), e);
        }
    }

    // We schedule a reconnect based on the starttime of the service
    public void scheduleReconnect(long startTime) {

        // the last keep-alive interval
        long interval = mPrefs.getLong(PREF_RETRY, INITIAL_RETRY_INTERVAL);

        // Calculate the elapsed time since the start
        long now = System.currentTimeMillis();
        long elapsed = now - startTime;

        // Set an appropriate interval based on the elapsed time since start
        if (elapsed < interval) {
            interval = Math.min(interval * 4, MAXIMUM_RETRY_INTERVAL);
        } else {
            interval = INITIAL_RETRY_INTERVAL;
        }

        Log.i(TAG, "Rescheduling connection in " + interval + "ms.");

        // Save the new internval
        mPrefs.edit().putLong(PREF_RETRY, interval).apply();

        // Schedule a reconnect using the alarm manager.
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_RECONNECT);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.set(AlarmManager.RTC_WAKEUP, now + interval, pi);
    }

    // Remove the scheduled reconnect
    public void cancelReconnect() {
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_RECONNECT);
        PendingIntent pi = PendingIntent.getService(this, 0, i, 0);
        AlarmManager alarmMgr = (AlarmManager) getSystemService(ALARM_SERVICE);
        alarmMgr.cancel(pi);
    }

    // This method does any necessary clean-up need in case the server has been
    // destroyed by the system
    // and then restarted
    private void handleCrashedService() {
        if (wasStarted()) {
            Log.i(TAG, "Handling crashed service...");
            // stop the keep alives
            stopKeepAlives();

            // Do a clean start
            start();
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Service destroyed (started=" + mStarted + ")");

        // Stop the services, if it has been started
        if (mStarted) {
            stop();
        }
    }

    // Reads whether or not the service has been started from the preferences
    private boolean wasStarted() {
        return mPrefs.getBoolean(PREF_STARTED, false);
    }

    // Sets whether or not the services has been started in the preferences.
    private void setStarted(boolean started) {
        mPrefs.edit().putBoolean(PREF_STARTED, started).apply();
        mStarted = started;
    }

    /**
     * Checkes the current connectivity and reconnects if it is required.
     */
    private synchronized void reconnectIfNecessary() {
        if (mStarted && mClient == null) {
            Log.i(TAG, "Reconnecting...");
            connect();
        }
    }

    /**
     * Query's the NetworkInfo via ConnectivityManager to return the current
     * connected state
     * 
     * @return boolean true if we are connected false otherwise
     */
    private boolean isNetworkAvailable() {
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();

        return (info != null) && info.isConnected();
    }

    /**
     * Verifies the client State with our local connected state
     * 
     * @return true if its a match we are connected false if we aren't connected
     */
    private boolean isConnected() {
        if (mStarted && mClient != null && !mClient.isConnected()) {
            Log.i(TAG, "Mismatch between what we think is connected and what is connected");
        }

        return mClient != null && (mStarted && mClient.isConnected());

    }

    /**
     * Receiver that listens for connectivity chanes via ConnectivityManager
     */
    private final BroadcastReceiver mConnectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Get network info
            NetworkInfo info = (NetworkInfo) intent
                    .getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);

            // Is there connectivity?
            boolean hasConnectivity = (info != null && info.isConnected());

            Log.i(TAG, "Connectivity changed: connected=" + hasConnectivity);

            if (hasConnectivity) {
                reconnectIfNecessary();
            } else if (mClient != null) {
                // if there no connectivity, make sure MQTT connection is
                // destroyed
                try {
                    mClient.disconnect();
                } catch (MqttException e) {
                    e.printStackTrace();
                }
                cancelReconnect();
                mClient = null;
            }
        }
    };

    /**
     * Sends a Keep Alive message to the specified topic
     * 
     * @return MqttDeliveryToken specified token you can choose to wait for
     *         completion
     */
    private synchronized MqttDeliveryToken sendKeepAlive() throws MqttConnectivityException, MqttException {
        if (!isConnected())
            throw new MqttConnectivityException();

        if (mKeepAliveTopic == null) {
            mKeepAliveTopic = mClient.getTopic(String.format(Locale.US,
                    MQTT_KEEP_ALIVE_TOPIC_FORAMT, mPrefs.getString(PREF_CLIENT_ID, "")));
        }

        Log.i(TAG, "Sending Keepalive to " + mPrefs.getString(PREF_SERVER_ADDRESS, ""));

        MqttMessage message = new MqttMessage(MQTT_KEEP_ALIVE_MESSAGE);
        message.setQos(MQTT_KEEP_ALIVE_QOS);

        return mKeepAliveTopic.publish(message);
    }

    /**
     * Query's the AlarmManager to check if there is a keep alive currently
     * scheduled
     * 
     * @return true if there is currently one scheduled false otherwise
     */
    private synchronized boolean hasScheduledKeepAlives() {
        Intent i = new Intent();
        i.setClass(this, MqttService.class);
        i.setAction(ACTION_KEEPALIVE);
        PendingIntent pi = PendingIntent.getBroadcast(this, 0, i, PendingIntent.FLAG_NO_CREATE);

        return (pi != null);
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    /**
     * Connectivity Lost from broker
     */
    @Override
    public void connectionLost(Throwable arg0) {
        stopKeepAlives();

        mClient = null;

        if (isNetworkAvailable()) {
            reconnectIfNecessary();
        }
    }

    /**
     * Received Message from broker
     */
    @Override
    public void messageArrived(String s, MqttMessage mqttMessage) throws Exception {
        Log.i(TAG,
                "  Topic:\t" + "  Message:\t" + new String(mqttMessage.getPayload()) + "  QoS:\t" + mqttMessage.getQos());
        // Show a notification
        showNotification(s);
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

    }

    /**
     * MqttConnectivityException Exception class
     */
    private class MqttConnectivityException extends Exception {
        private static final long serialVersionUID = -7385866796799469420L;
    }

    /**
     * Notification
     *
     * @param notifyString
     */
    private void showNotification(String notifyString) {
        // Intent の作成
        Intent intent = new Intent(this, MyActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT);

        // LargeIcon の Bitmap を生成
        Bitmap largeIcon = BitmapFactory.decodeResource(getResources(), R.drawable.ic_launcher);

        // NotificationBuilderを作成
        Notification.Builder builder = new Notification.Builder(getApplicationContext());
        builder.setContentIntent(contentIntent);
        // ステータスバーに表示されるテキスト
        builder.setTicker("お知らせ！");
        // アイコン
        builder.setSmallIcon(R.drawable.ic_launcher);
        // Notificationを開いたときに表示されるタイトル
        builder.setContentTitle("MQTT Message");
        // Notificationを開いたときに表示されるサブタイトル
        builder.setContentText(notifyString);
        // Notificationを開いたときに表示されるアイコン
        builder.setLargeIcon(largeIcon);
        // 通知するタイミング
        builder.setWhen(System.currentTimeMillis());
        // 通知時の音・バイブ・ライト
        builder.setDefaults(Notification.DEFAULT_SOUND | Notification.DEFAULT_VIBRATE
                | Notification.DEFAULT_LIGHTS);
        // タップするとキャンセル(消える)
        builder.setAutoCancel(true);

        // NotificationManagerを取得
        NotificationManager manager = (NotificationManager) getSystemService(Service.NOTIFICATION_SERVICE);
        // Notificationを作成して通知
        manager.notify(NOTIF_CONNECTED, builder.build());
    }
}