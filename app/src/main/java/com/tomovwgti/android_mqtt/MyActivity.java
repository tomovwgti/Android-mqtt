package com.tomovwgti.android_mqtt;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;


public class MyActivity extends Activity implements MqttService.resultCallback {
    private static final String TAG = MyActivity.class.getCanonicalName();

    private MyActivity self = this;

    private EditText server;
    private EditText port;
    private EditText clientid;
    private EditText username;
    private EditText password;
    private EditText topic;
    private CheckBox session;
    private Button connectBtn;
    private Button disconnectBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        server = (EditText) findViewById(R.id.server);
        port = (EditText)findViewById(R.id.port);
        clientid = (EditText) findViewById(R.id.client_id);
        username = (EditText)findViewById(R.id.username);
        password = (EditText)findViewById(R.id.password);
        topic = (EditText) findViewById(R.id.topic);
        session = (CheckBox)findViewById(R.id.session);
        session.setChecked(true);

        connectBtn = (Button) findViewById(R.id.connect);
        disconnectBtn = (Button) findViewById(R.id.disconnect);

        connectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (clientid.getText().toString().equals("") ||
                        server.getText().toString().equals("") ||
                        topic.getText().toString().equals("") ||
                        port.getText().toString().equals("")) {
                    return;
                }

                SharedPreferences.Editor editor = getSharedPreferences(MqttService.TAG,
                        MODE_PRIVATE).edit();
                editor.putString(MqttService.PREF_CLIENT_ID, clientid.getText().toString());
                editor.putString(MqttService.PREF_SERVER_ADDRESS, server.getText().toString());
                editor.putInt(MqttService.PREF_SERVER_PORT, Integer.parseInt(port.getText().toString()));
                editor.putString(MqttService.PREF_USERNAME, username.getText().toString());
                editor.putString(MqttService.PREF_PASSWORD, password.getText().toString());
                editor.putString(MqttService.PREF_TOPIC, topic.getText().toString());
                editor.putBoolean(MqttService.PREF_SESSION, session.isChecked());
                editor.apply();

                MqttService.setOnResultListener(self);
                MqttService.action(self.getApplicationContext(), MqttService.ACTION_START);
                MqttService.subscribe(self.getApplicationContext(), topic.getText().toString());
            }
        });

        disconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                MqttService.action(self.getApplicationContext(), MqttService.ACTION_STOP);
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
        clientid.setText(p.getString(MqttService.PREF_CLIENT_ID, ""));
        username.setText(p.getString(MqttService.PREF_USERNAME, ""));
        password.setText(p.getString(MqttService.PREF_PASSWORD, ""));
        topic.setText(p.getString(MqttService.PREF_TOPIC, ""));

        ((Button) findViewById(R.id.connect)).setEnabled(!started);
        ((Button) findViewById(R.id.disconnect)).setEnabled(started);
        if (started) {
            connecting();
        }
    }

    @Override
    public void onResult(String action, int status, String message) {
        if (action.equals(MqttService.ACTION_START)) {
            if (status == MqttService.STATUS_SUCCESS) {
                Toast.makeText(self, "Connect Success", Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        connecting();
                    }
                });
            } else {
                Toast.makeText(self, "Connect Failed: " + message, Toast.LENGTH_SHORT).show();
            }
        } else if (action.equals(MqttService.ACTION_STOP)) {
            if (status == MqttService.STATUS_SUCCESS) {
                Toast.makeText(self, "Disconnect Success", Toast.LENGTH_SHORT).show();
                new Handler(Looper.getMainLooper()).post(new Runnable() {
                    @Override
                    public void run() {
                        disconnecting();
                    }
                });
            } else {
                Toast.makeText(self, "Disconnect Failed: " + message, Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void connecting() {
        server.setEnabled(false);
        port.setEnabled(false);
        clientid.setEnabled(false);
        username.setEnabled(false);
        password.setEnabled(false);
        topic.setEnabled(false);
        session.setEnabled(false);
        connectBtn.setEnabled(false);
        disconnectBtn.setEnabled(true);
    }

    private void disconnecting() {
        server.setEnabled(true);
        port.setEnabled(true);
        clientid.setEnabled(true);
        username.setEnabled(true);
        password.setEnabled(true);
        topic.setEnabled(true);
        session.setEnabled(true);
        connectBtn.setEnabled(true);
        disconnectBtn.setEnabled(false);
    }
}