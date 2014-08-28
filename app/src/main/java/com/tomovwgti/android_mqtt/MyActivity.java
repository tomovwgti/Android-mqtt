package com.tomovwgti.android_mqtt;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;


public class MyActivity extends Activity {
    private static final String TAG = MyActivity.class.getCanonicalName();

    private MyActivity self = this;

    public static final String TOPIC_SUBSCRIBE_ALL = "PUBLIC/location/state/#";

    private EditText server;
    private EditText port;
    private EditText id;
    private EditText username;
    private EditText password;
    private EditText topic;
    private CheckBox session;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        server = (EditText) findViewById(R.id.server);
        port = (EditText)findViewById(R.id.port);
        id = (EditText) findViewById(R.id.user_id);
        username = (EditText)findViewById(R.id.username);
        password = (EditText)findViewById(R.id.password);
        topic = (EditText) findViewById(R.id.topic);
        session = (CheckBox)findViewById(R.id.session);
        session.setChecked(true);

        final Button connectBtn = (Button) findViewById(R.id.connect);
        final Button disconnectBtn = (Button) findViewById(R.id.disconnect);

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (id.getText().toString().equals("")) {
                    return;
                }

                SharedPreferences.Editor editor = getSharedPreferences(MqttService.TAG,
                        MODE_PRIVATE).edit();
                editor.putString(MqttService.PREF_DEVICE_ID, id.getText().toString());
                editor.putString(MqttService.PREF_SERVER_ADDRESS, server.getText().toString());
                editor.putInt(MqttService.PREF_SERVER_PORT, Integer.parseInt(port.getText().toString()));
                editor.putString(MqttService.PREF_USERNAME, username.getText().toString());
                editor.putString(MqttService.PREF_PASSWORD, password.getText().toString());
                editor.putString(MqttService.PREF_TOPIC, topic.getText().toString());
                editor.putBoolean(MqttService.PREF_SESSION, session.isChecked());
                editor.apply();

                MqttService.actionStart(self.getApplicationContext());
                MqttService.actionSubscribe(self.getApplicationContext(), topic.getText().toString());
                server.setEnabled(false);
                port.setEnabled(false);
                id.setEnabled(false);
                username.setEnabled(false);
                password.setEnabled(false);
                topic.setEnabled(false);
                session.setEnabled(false);
                connectBtn.setEnabled(false);
                disconnectBtn.setEnabled(true);
            }
        });

        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MqttService.actionStop(self.getApplicationContext());
                server.setEnabled(true);
                port.setEnabled(true);
                id.setEnabled(true);
                username.setEnabled(true);
                password.setEnabled(true);
                topic.setEnabled(true);
                session.setEnabled(true);
                connectBtn.setEnabled(true);
                disconnectBtn.setEnabled(false);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        SharedPreferences p = getSharedPreferences(MqttService.TAG, MODE_PRIVATE);
        boolean started = p.getBoolean(MqttService.PREF_STARTED, false);

        server.setText(p.getString(MqttService.PREF_SERVER_ADDRESS, ""));
        port.setText(String.valueOf(p.getInt(MqttService.PREF_SERVER_PORT, 1883)));
        id.setText(p.getString(MqttService.PREF_DEVICE_ID, ""));
        username.setText(p.getString(MqttService.PREF_USERNAME, ""));
        password.setText(p.getString(MqttService.PREF_PASSWORD, ""));
        topic.setText(p.getString(MqttService.PREF_TOPIC, ""));

        ((Button) findViewById(R.id.connect)).setEnabled(!started);
        ((Button) findViewById(R.id.disconnect)).setEnabled(started);
        if (started) {
            id.setEnabled(false);
        }
    }
}
