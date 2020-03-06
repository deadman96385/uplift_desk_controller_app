package com.deadman.uplift;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;

import com.welie.blessed.BluetoothCentral;
import com.welie.blessed.BluetoothCentralCallback;
import com.welie.blessed.BluetoothPeripheral;
import com.welie.blessed.BluetoothPeripheralCallback;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

import java.util.UUID;

import timber.log.Timber;

import static android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
import static com.welie.blessed.BluetoothBytesParser.bytes2String;
import static com.welie.blessed.BluetoothPeripheral.GATT_SUCCESS;

class BluetoothHandler {
    // Overarching service that contains all of the Desk controls and info
    private static final UUID DESK_SERVICE_UUID = UUID.fromString("0000ff12-0000-1000-8000-00805f9b34fb");

    // This is the uuid that does all of the heavy lifting, Moving the desk up/down, saving presets.
    private static final UUID DATA_IN_CHAR_UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb");

    // This returns the current height of the desk
    private static final UUID DATA_OUT_CHAR_UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb");

    // This is the uuid to enable or disable the pair code
    private static final UUID PAIR_CODE_EN_CHAR_UUID = UUID.fromString("0000ff07-0000-1000-8000-00805f9b34fb");

    // This is the uuid to set your pair code
    private static final UUID PAIR_CODE_CHAR_UUID = UUID.fromString("0000ff05-0000-1000-8000-00805f9b34fb");

    // This is the uuid that returns the MCU firmware version
    private static final UUID FW_VERSION_CHAR_UUID = UUID.fromString("0000fff2-0000-1000-8000-00805f9b34fb");

    // This is the uuid that returns the desk's custom bluetooth name
    private static final UUID DEVICE_NAME_CHAR_UUID = UUID.fromString("0000ff06-0000-1000-8000-00805f9b34fb");

    // Unknown reset options, most likely one or both of them is a factory reset option
    private static final UUID FACTORYSET_CHAR_UUID = UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb");
    private static final UUID RESET_CHAR_UUID = UUID.fromString("0000fff1-0000-1000-8000-00805f9b34fb");

    // Dangerous UUID's
    // I never wrote anything to these uuid's because i did not see the need for changing the option
    // due to it having the chance to damage my desk. Primarily because the old official app doesn't
    // use them either. They seem primarily leftover development/manufacturing options and not for
    // the end user. Documenting them here for future reference by myself or others.

    // BLE connection timeout options
    private static final UUID LINK_INTERVAL_CHAR_UUID = UUID.fromString("0000ff04-0000-1000-8000-00805f9b34fb");
    private static final UUID ADV_INTERVAL_CHAR_UUID = UUID.fromString("0000ff08-0000-1000-8000-00805f9b34fb");

    // Internal Micro-processor options
    private static final UUID TX_POWER_CHAR_UUID = UUID.fromString("0000ff09-0000-1000-8000-00805f9b34fb");
    private static final UUID MCU_DELAY_CHAR_UUID = UUID.fromString("0000ff0a-0000-1000-8000-00805f9b34fb");
    private static final UUID BAUNDRATE_CHAR_UUID = UUID.fromString("0000ff03-0000-1000-8000-00805f9b34fb");

    // Desk up
    private String desk_up = "f1f10100017e";

    // Desk Down
    private String desk_down = "f1f10200027e";

    // Desk Stop
    private String desk_stop = "f1f12b002b7e";

    // Preset Save 1
    private String preset_save1 = "f1f10300037e";

    // Preset Save 2
    private String preset_save2 = "f1f10400047e";

    // Go to preset 1
    private String go_preset1 = "f1f10500057e";

    // Go to Preset 2
    private String go_preset2 = "f1f10600067e";

    // Desk Status
    private String desk_status = "f1f10700077e";

    private static BluetoothHandler instance = null;
    // Local variables
    private BluetoothCentral central;
    private Context context;
    private Handler handler = new Handler();
    private BluetoothGattCharacteristic write_line;
    private BluetoothPeripheral peripheral_test;
    // Callback for peripherals
    private final BluetoothPeripheralCallback peripheralCallback = new BluetoothPeripheralCallback() {
        @Override
        public void onServicesDiscovered(BluetoothPeripheral peripheral) {
            Timber.i("discovered services");

            peripheral_test = peripheral;

            write_line = peripheral.getCharacteristic(DESK_SERVICE_UUID, DATA_IN_CHAR_UUID);

            if (peripheral.getService(DESK_SERVICE_UUID) != null) {
                peripheral.setNotify(peripheral.getCharacteristic(DESK_SERVICE_UUID, DATA_OUT_CHAR_UUID), true);
            }
        }

        @Override
        public void onNotificationStateUpdate(BluetoothPeripheral peripheral, BluetoothGattCharacteristic characteristic, int status) {
            if (status == GATT_SUCCESS) {
                if (peripheral.isNotifying(characteristic)) {
                    Timber.i("SUCCESS: Notify set to 'on' for %s", characteristic.getUuid());
                } else {
                    Timber.i("SUCCESS: Notify set to 'off' for %s", characteristic.getUuid());
                }
            } else {
                Timber.e("ERROR: Changing notification state failed for %s", characteristic.getUuid());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            if (status == GATT_SUCCESS) {
                Timber.i("SUCCESS: Writing <%s> to <%s>", bytes2String(value), characteristic.getUuid().toString());
            } else {
                Timber.i("ERROR: Failed writing <%s> to <%s>", bytes2String(value), characteristic.getUuid().toString());
            }
        }

        @Override
        public void onCharacteristicUpdate(BluetoothPeripheral peripheral, byte[] value, BluetoothGattCharacteristic characteristic, int status) {
            if (status != GATT_SUCCESS) return;
            UUID characteristicUUID = characteristic.getUuid();

            if (characteristicUUID.equals(DEVICE_NAME_CHAR_UUID)) {
                DeviceName device_name = new DeviceName(value);
                Intent intent = new Intent("DeviceSender");
                intent.putExtra("DeviceName", device_name);
                context.sendBroadcast(intent);
            } else if (characteristicUUID.equals(DATA_OUT_CHAR_UUID)) {
                DeskHeight height = new DeskHeight(value);
                Intent intent = new Intent("HeightMeasurement");
                intent.putExtra("DataOut", height);
                context.sendBroadcast(intent);
            }

        }
    };

    private BluetoothHandler(Context context) {
        this.context = context;

        // Reconnect to this device when it becomes available again
        BluetoothCentralCallback bluetoothCentralCallback = new BluetoothCentralCallback() {

            @Override
            public void onConnectedPeripheral(BluetoothPeripheral peripheral) {
                Timber.i("connected to '%s'", peripheral.getName());
            }

            @Override
            public void onConnectionFailed(BluetoothPeripheral peripheral, final int status) {
                Timber.e("connection '%s' failed with status %d", peripheral.getName(), status);
            }

            @Override
            public void onDisconnectedPeripheral(final BluetoothPeripheral peripheral, final int status) {
                Timber.i("disconnected '%s' with status %d", peripheral.getName(), status);

                // Reconnect to this device when it becomes available again
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        central.autoConnectPeripheral(peripheral, peripheralCallback);
                    }
                }, 5000);
            }

            @Override
            public void onDiscoveredPeripheral(BluetoothPeripheral peripheral, ScanResult scanResult) {
                Timber.i("Found peripheral '%s'", peripheral.getName());
                central.stopScan();
                central.connectPeripheral(peripheral, peripheralCallback);
            }
        };
        central = new BluetoothCentral(context, bluetoothCentralCallback, new Handler());

        // Scan for peripherals with a certain service UUIDs
        central.startPairingPopupHack();
        central.scanForPeripheralsWithServices(new UUID[]{DESK_SERVICE_UUID});
    }

    static synchronized BluetoothHandler getInstance(Context context) {
        if (instance == null) {
            instance = new BluetoothHandler(context.getApplicationContext());
        }
        return instance;
    }

    View.OnClickListener StandButtonListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(go_preset2), WRITE_TYPE_NO_RESPONSE);
                    peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(go_preset2), WRITE_TYPE_NO_RESPONSE);
                } catch (DecoderException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    View.OnClickListener SitButtonListener() {
        return new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(go_preset1), WRITE_TYPE_NO_RESPONSE);
                    peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(go_preset1), WRITE_TYPE_NO_RESPONSE);
                } catch (DecoderException e) {
                    e.printStackTrace();
                }
            }
        };
    }

    View.OnTouchListener MovingDownListener() {
        return new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    try {
                        peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(desk_down), WRITE_TYPE_NO_RESPONSE);
                        peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(desk_down), WRITE_TYPE_NO_RESPONSE);
                    } catch (DecoderException e) {
                        e.printStackTrace();
                    }
                }
                return false;
            }
        };
    }

    View.OnTouchListener MovingUpListener() {
        return new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        try {
                            peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(desk_up), WRITE_TYPE_NO_RESPONSE);
                            peripheral_test.writeCharacteristic(write_line, Hex.decodeHex(desk_up), WRITE_TYPE_NO_RESPONSE);
                        } catch (DecoderException e) {
                            e.printStackTrace();
                        }
                    case MotionEvent.ACTION_UP:
                        // Released
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        // Released - Dragged finger outside
                        return true;
                }
                return false;
            }
        };
    }
}