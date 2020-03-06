package com.deadman.uplift;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Objects;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_ENABLE_BT = 1;
    private static final int ACCESS_LOCATION_REQUEST = 2;

    TextView fw_version;
    private final BroadcastReceiver HeightDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DeskHeight DataOut = (DeskHeight) intent.getSerializableExtra("DataOut");
            fw_version.setText(Objects.requireNonNull(DataOut).height);
        }
    };
    TextView desk_view;
    private final BroadcastReceiver DeviceNameDataReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DeviceName device_name = (DeviceName) intent.getSerializableExtra("DeviceName");
            desk_view.setText(Objects.requireNonNull(device_name).name);
        }
    };
    Button stand_button;
    Button sit_button;
    Button up_button;
    Button down_button;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Timber.d("onCreate called");

        fw_version = findViewById(R.id.height_number_text);
        desk_view = findViewById(R.id.desk_name_field);
        stand_button = findViewById(R.id.stand_button);
        sit_button = findViewById(R.id.sit_button);
        up_button = findViewById(R.id.up_button);
        down_button = findViewById(R.id.down_button);

        Timber.plant(new Timber.DebugTree());

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) return;

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }

        if (hasPermissions()) {
            initBluetoothHandler();
        }
    }

    private void initBluetoothHandler() {
        BluetoothHandler.getInstance(getApplicationContext());
        stand_button.setOnClickListener(BluetoothHandler.getInstance(this).StandButtonListener());
        sit_button.setOnClickListener(BluetoothHandler.getInstance(this).SitButtonListener());

        down_button.setOnTouchListener(BluetoothHandler.getInstance(this).MovingDownListener());
        up_button.setOnTouchListener(BluetoothHandler.getInstance(this).MovingUpListener());

        registerReceiver(DeviceNameDataReceiver, new IntentFilter("DeviceSender"));
        registerReceiver(HeightDataReceiver, new IntentFilter("HeightMeasurement"));
    }

    private boolean hasPermissions() {
        int targetSdkVersion = getApplicationInfo().targetSdkVersion;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSdkVersion >= Build.VERSION_CODES.Q) {
            if (getApplicationContext().checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, ACCESS_LOCATION_REQUEST);
                return false;
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (getApplicationContext().checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, ACCESS_LOCATION_REQUEST);
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == ACCESS_LOCATION_REQUEST) {
            if (grantResults.length > 0) {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    initBluetoothHandler();
                }
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }
}