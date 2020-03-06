/*
 *   Copyright (c) 2019 Martijn van Welie
 *
 *   Permission is hereby granted, free of charge, to any person obtaining a copy
 *   of this software and associated documentation files (the "Software"), to deal
 *   in the Software without restriction, including without limitation the rights
 *   to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *   copies of the Software, and to permit persons to whom the Software is
 *   furnished to do so, subject to the following conditions:
 *
 *   The above copyright notice and this permission notice shall be included in all
 *   copies or substantial portions of the Software.
 *
 *   THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *   IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *   FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *   AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *   LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *   OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *   SOFTWARE.
 *
 */

package com.welie.blessed;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelUuid;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import timber.log.Timber;

/**
 * Central class to connect and communicate with bluetooth peripherals.
 */
@SuppressWarnings("SpellCheckingInspection")
public class BluetoothCentral {

    // Scanning error codes
    public static final int SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES = 5;
    // Private constants
    private static final long SCAN_TIMEOUT = 180_000L;
    private static final int SCAN_RESTART_DELAY = 1000;
    private static final int MAX_CONNECTION_RETRIES = 1;
    private static final int MAX_CONNECTED_PERIPHERALS = 7;
    // Private variables
    private final Context context;
    private final Handler callBackHandler;
    private final BluetoothAdapter bluetoothAdapter;
    private final BluetoothCentralCallback bluetoothCentralCallback;
    private final Map<String, BluetoothPeripheral> connectedPeripherals = new ConcurrentHashMap<>();
    private final Map<String, BluetoothPeripheral> unconnectedPeripherals = new ConcurrentHashMap<>();
    private final List<String> reconnectPeripheralAddresses = new ArrayList<>();
    private final Map<String, BluetoothPeripheralCallback> reconnectCallbacks = new ConcurrentHashMap<>();
    private final Handler timeoutHandler = new Handler();
    private final Handler autoConnectHandler = new Handler();
    private final Object connectLock = new Object();
    private final ScanSettings autoConnectScanSettings;
    private final Map<String, Integer> connectionRetries = new ConcurrentHashMap<>();
    private final Handler disconnectHandler = new Handler();
    private BluetoothLeScanner bluetoothScanner;
    private BluetoothLeScanner autoConnectScanner;
    private String[] scanPeripheralNames;
    private Runnable timeoutRunnable;
    private Runnable autoConnectRunnable;
    private final ScanCallback autoConnectScanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            synchronized (this) {
                // Get the deviceAdress and corresponding callback
                String deviceAddress = result.getDevice().getAddress();
                Timber.d("peripheral with address '%s' found", deviceAddress);
                BluetoothPeripheralCallback callback = reconnectCallbacks.get(deviceAddress);

                // Clean up first
                reconnectPeripheralAddresses.remove(deviceAddress);
                reconnectCallbacks.remove(deviceAddress);

                // If we have all devices, stop the scan
                if (reconnectPeripheralAddresses.size() == 0) {
                    autoConnectScanner.stopScan(autoConnectScanCallback);
                    autoConnectScanner = null;
                    cancelAutoConnectTimer();
                }

                // The device is now cached so pick up the peripheral and issue normal connect
                BluetoothPeripheral peripheral = getPeripheral(deviceAddress);
                unconnectedPeripherals.remove(deviceAddress);
                connectPeripheral(peripheral, callback);

                // If there are any devices left, restart the reconnection scan
                if (reconnectPeripheralAddresses.size() > 0) {
                    scanForAutoConnectPeripherals();
                }
            }
        }

        @Override
        public void onScanFailed(final int errorCode) {
            Timber.e("autoconnect scan failed with error code %d", errorCode);
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onScanFailed(errorCode);
                }
            });
        }
    };
    private ScanCallback currentCallback;
    /**
     * Callback for scan by peripheral name. Do substring filtering before forwarding result.
     */
    private final ScanCallback scanByNameCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            synchronized (this) {
                String deviceName = result.getDevice().getName();
                if (deviceName != null) {
                    for (String name : scanPeripheralNames) {
                        if (deviceName.contains(name)) {
                            callBackHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    if (currentCallback != null) {
                                        BluetoothPeripheral peripheral = new BluetoothPeripheral(context, result.getDevice(), internalCallback, null, callBackHandler);
                                        bluetoothCentralCallback.onDiscoveredPeripheral(peripheral, result);
                                    }
                                }
                            });
                            return;
                        }
                    }
                }
            }
        }

        @Override
        public void onScanFailed(final int errorCode) {
            Timber.e("scan failed with error code %d", errorCode);
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onScanFailed(errorCode);
                }
            });
        }
    };
    /**
     * Callback for scan by service UUID
     */
    private final ScanCallback scanByServiceUUIDCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, final ScanResult result) {
            synchronized (this) {
                callBackHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (currentCallback != null) {
                            BluetoothPeripheral peripheral = new BluetoothPeripheral(context, result.getDevice(), internalCallback, null, callBackHandler);
                            bluetoothCentralCallback.onDiscoveredPeripheral(peripheral, result);
                        }
                    }
                });
            }
        }

        @Override
        public void onScanFailed(final int errorCode) {
            Timber.e("scan failed with error code %d", errorCode);
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onScanFailed(errorCode);
                }
            });
        }
    };
    private List<ScanFilter> currentFilters;

    //region Callbacks
    private ScanSettings scanSettings;
    private boolean expectingBluetoothOffDisconnects = false;
    private Runnable disconnectRunnable;
    /**
     * Callback from each connected peripheral
     */
    private final BluetoothPeripheral.InternalCallback internalCallback = new BluetoothPeripheral.InternalCallback() {

        /**
         * Successfully connected with the peripheral, add it to the connected peripherals list
         * and notify the listener.
         * @param peripheral {@link BluetoothPeripheral} that connected.
         */
        @Override
        public void connected(final BluetoothPeripheral peripheral) {
            // Remove peripheral from retry map if it is there
            connectionRetries.remove(peripheral.getAddress());

            // Do some administration work
            connectedPeripherals.put(peripheral.getAddress(), peripheral);
            if (connectedPeripherals.size() == MAX_CONNECTED_PERIPHERALS) {
                Timber.w("maximum amount (%d) of connected peripherals reached", MAX_CONNECTED_PERIPHERALS);
            }

            // Remove peripheral from unconnected peripherals map if it is there
            if (unconnectedPeripherals.get(peripheral.getAddress()) != null) {
                unconnectedPeripherals.remove(peripheral.getAddress());
            }

            // Inform the listener that we are now connected
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onConnectedPeripheral(peripheral);
                }
            });
        }

        /**
         * The connection with the peripheral failed, remove it from the connected peripheral list
         * and notify the listener.
         * @param peripheral {@link BluetoothPeripheral} of which connect failed.
         */
        @Override
        public void connectFailed(final BluetoothPeripheral peripheral, final int status) {
            // Remove from unconnected peripherals list
            if (unconnectedPeripherals.get(peripheral.getAddress()) != null) {
                unconnectedPeripherals.remove(peripheral.getAddress());
            }

            // Get the number of retries for this peripheral
            int nrRetries = 0;
            if (connectionRetries.get(peripheral.getAddress()) != null) {
                Integer retries = connectionRetries.get(peripheral.getAddress());
                if (retries != null) nrRetries = retries;
            }

            // Retry connection or conclude the connection has failed
            if (nrRetries < MAX_CONNECTION_RETRIES && status != BluetoothPeripheral.GATT_CONN_TIMEOUT) {
                Timber.i("retrying connection to '%s' (%s)", peripheral.getName(), peripheral.getAddress());
                nrRetries++;
                connectionRetries.put(peripheral.getAddress(), nrRetries);
                unconnectedPeripherals.put(peripheral.getAddress(), peripheral);

                // Retry with autoconnect
                peripheral.autoConnect();
            } else {
                Timber.i("connection to '%s' (%s) failed", peripheral.getName(), peripheral.getAddress());
                callBackHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        bluetoothCentralCallback.onConnectionFailed(peripheral, status);
                    }
                });
            }
        }

        /**
         * The peripheral disconnected, remove it from the connected peripherals list
         * and notify the listener.
         * @param peripheral {@link BluetoothPeripheral} that disconnected.
         */
        @Override
        public void disconnected(final BluetoothPeripheral peripheral, final int status) {
            if (expectingBluetoothOffDisconnects) {
                cancelDisconnectionTimer();
                expectingBluetoothOffDisconnects = false;
            }

            // Remove it from the connected peripherals map
            connectedPeripherals.remove(peripheral.getAddress());

            // Make sure it is also not in unconnected peripherals map
            if (unconnectedPeripherals.get(peripheral.getAddress()) != null) {
                unconnectedPeripherals.remove(peripheral.getAddress());
            }

            // Trigger callback
            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onDisconnectedPeripheral(peripheral, status);
                }
            });
        }
    };
    //endregion
    private final BroadcastReceiver adapterStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action == null) return;

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        expectingBluetoothOffDisconnects = true;
                        startDisconnectionTimer();
                        Timber.d("bluetooth turned off");
                        break;
                    case BluetoothAdapter.STATE_TURNING_OFF:
                        expectingBluetoothOffDisconnects = true;
                        Timber.d("bluetooth turning off");
                        break;
                    case BluetoothAdapter.STATE_ON:
                        expectingBluetoothOffDisconnects = false;
                        Timber.d("bluetooth turned on");
                        break;
                    case BluetoothAdapter.STATE_TURNING_ON:
                        expectingBluetoothOffDisconnects = false;
                        Timber.d("bluetooth turning on");
                        break;
                }
            }
        }
    };

    /**
     * Construct a new BluetoothCentral object
     *
     * @param context                  Android application environment.
     * @param bluetoothCentralCallback the callback to call for updates
     * @param handler                  Handler to use for callbacks.
     */
    public BluetoothCentral(Context context, BluetoothCentralCallback bluetoothCentralCallback, Handler handler) {
        if (context == null) {
            Timber.e("context is 'null', cannot create BluetoothCentral");
        }
        if (bluetoothCentralCallback == null) {
            Timber.e("callback is 'null', cannot create BluetoothCentral");
        }
        this.context = context;
        this.bluetoothCentralCallback = bluetoothCentralCallback;
        if (handler != null) {
            this.callBackHandler = handler;
        } else {
            this.callBackHandler = new Handler();
        }
        this.bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            this.autoConnectScanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                    .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                    .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                    .setReportDelay(0L)
                    .build();
        } else {
            this.autoConnectScanSettings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .setReportDelay(0L)
                    .build();
        }
        setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY);

        // Register for broadcasts on BluetoothAdapter state change
        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(adapterStateReceiver, filter);
    }

    /**
     * Set the default scanMode.
     *
     * <p>Must be ScanSettings.SCAN_MODE_LOW_POWER, ScanSettings.SCAN_MODE_LOW_LATENCY, ScanSettings.SCAN_MODE_BALANCED or ScanSettings.SCAN_MODE_OPPORTUNISTIC.
     * The default value is SCAN_MODE_LOW_LATENCY.
     *
     * @param scanMode the scanMode to set
     * @return true if a valid scanMode was provided, otherwise false
     */
    public boolean setScanMode(int scanMode) {
        if (scanMode == ScanSettings.SCAN_MODE_LOW_POWER ||
                scanMode == ScanSettings.SCAN_MODE_LOW_LATENCY ||
                scanMode == ScanSettings.SCAN_MODE_BALANCED ||
                scanMode == ScanSettings.SCAN_MODE_OPPORTUNISTIC) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                this.scanSettings = new ScanSettings.Builder()
                        .setScanMode(scanMode)
                        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                        .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
                        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT)
                        .setReportDelay(0L)
                        .build();
            } else {
                this.scanSettings = new ScanSettings.Builder()
                        .setScanMode(scanMode)
                        .setReportDelay(0L)
                        .build();
            }
            return true;
        }
        return false;
    }

    private void startScan(List<ScanFilter> filters, ScanSettings scanSettings, ScanCallback scanCallback) {
        // Check is BLE is available, enabled and all permission granted
        if (!isBleReady()) return;

        // Make sure we are not already scanning, we only want one scan at the time
        if (currentCallback != null) {
            Timber.e("other scan still active, please stop other scan first");
            return;
        }

        // Get a new scanner object
        if (bluetoothScanner == null) {
            bluetoothScanner = bluetoothAdapter.getBluetoothLeScanner();
        }

        // If get scanner was succesful, start the scan
        if (bluetoothScanner != null) {
            // Start the scanner
            setScanTimer();
            currentCallback = scanCallback;
            currentFilters = filters;
            bluetoothScanner.startScan(filters, scanSettings, scanCallback);
            Timber.i("scan started");
        } else {
            Timber.e("starting scan failed");
        }
    }

    /**
     * Scan for peripherals that advertise at least one of the specified service UUIDs
     *
     * @param serviceUUIDs an array of service UUIDs
     */
    public void scanForPeripheralsWithServices(final UUID[] serviceUUIDs) {
        // Build filters list
        currentFilters = null;
        if (serviceUUIDs != null) {
            currentFilters = new ArrayList<>();
            for (UUID serviceUUID : serviceUUIDs) {
                ScanFilter filter = new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(serviceUUID))
                        .build();
                currentFilters.add(filter);
            }
        }
        startScan(currentFilters, scanSettings, scanByServiceUUIDCallback);
    }

    /**
     * Scan for peripherals with advertisement names containing any of the specified peripheral names.
     *
     * <p>Substring matching is used so only a partial peripheral names has to be supplied.
     *
     * @param peripheralNames array of partial peripheral names
     */
    public void scanForPeripheralsWithNames(final String[] peripheralNames) {
        // Start the scanner with no filter because we'll do the filtering ourselves
        scanPeripheralNames = peripheralNames;
        startScan(null, scanSettings, scanByNameCallback);
    }

    /**
     * Scan for peripherals that have any of the specified peripheral mac addresses
     *
     * @param peripheralAddresses array of peripheral mac addresses to scan for
     */
    public void scanForPeripheralsWithAddresses(final String[] peripheralAddresses) {
        // Build filters list
        List<ScanFilter> filters = null;
        if (peripheralAddresses != null) {
            filters = new ArrayList<>();
            for (String address : peripheralAddresses) {
                if (BluetoothAdapter.checkBluetoothAddress(address)) {
                    ScanFilter filter = new ScanFilter.Builder()
                            .setDeviceAddress(address)
                            .build();
                    filters.add(filter);
                } else {
                    Timber.e("%s is not a valid address. Make sure all alphabetic characters are uppercase.", address);
                }
            }
        }
        startScan(filters, scanSettings, scanByServiceUUIDCallback);
    }

    /**
     * Scan for any peripheral that is advertising
     */
    public void scanForPeripherals() {
        startScan(null, scanSettings, scanByServiceUUIDCallback);
    }

    /**
     * Scan for peripherals that need to be autoconnected but are not cached
     */
    private void scanForAutoConnectPeripherals() {
        // Check is BLE is available, enabled and all permission granted
        if (!isBleReady()) return;

        // Stop previous autoconnect scans if any
        if (autoConnectScanner != null) {
            autoConnectScanner.stopScan(autoConnectScanCallback);
            autoConnectScanner = null;
            cancelAutoConnectTimer();
        }

        // Start the scanner
        autoConnectScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (autoConnectScanner != null) {
            List<ScanFilter> filters = null;
            if (reconnectPeripheralAddresses != null) {
                filters = new ArrayList<>();
                for (String address : reconnectPeripheralAddresses) {
                    ScanFilter filter = new ScanFilter.Builder()
                            .setDeviceAddress(address)
                            .build();
                    filters.add(filter);
                }
            }

            // Start the scanner
            autoConnectScanner.startScan(filters, autoConnectScanSettings, autoConnectScanCallback);
            setAutoConnectTimer();
        } else {
            Timber.e("starting autoconnect scan failed");
        }
    }

    /**
     * Stop scanning for peripherals
     */
    public void stopScan() {
        cancelTimeoutTimer();
        if (bluetoothScanner != null & currentCallback != null) {
            bluetoothScanner.stopScan(currentCallback);
            Timber.i("scan stopped");
        } else {
            Timber.i("no scan to stop because no scan is running");
        }
        currentCallback = null;
        currentFilters = null;
    }

    /**
     * Connect to a known peripheral immediately. The peripheral must have been found by scanning for this call to succeed. This method will time out in max 30 seconds.
     * If the peripheral is already connected, no connection attempt will be made. This method is asynchronous.
     *
     * @param peripheral BLE peripheral to connect with
     */
    public void connectPeripheral(BluetoothPeripheral peripheral, BluetoothPeripheralCallback peripheralCallback) {
        synchronized (connectLock) {
            // Make sure peripheral is valid
            if (peripheral == null) {
                Timber.e("no valid peripheral specified, aborting connection");
                return;
            }

            // Check if we are already connected to this peripheral
            if (connectedPeripherals.containsKey(peripheral.getAddress())) {
                Timber.w("already connected to %s'", peripheral.getAddress());
                return;
            }

            // Check if we already have an outstanding connection request for this peripheral
            if (unconnectedPeripherals.containsKey(peripheral.getAddress())) {
                Timber.w("already connecting to %s'", peripheral.getAddress());
                return;
            }

            // Check if the peripheral is cached or not. If not, issue a warning
            int deviceType = peripheral.getType();
            if (deviceType == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                // The peripheral is not cached so connection is likely to fail
                Timber.w("peripheral with address '%s' is not in the Bluetooth cache, hence connection may fail", peripheral.getAddress());
            }

            // It is all looking good! Set the callback and prepare to connect
            peripheral.setPeripheralCallback(peripheralCallback);
            unconnectedPeripherals.put(peripheral.getAddress(), peripheral);

            // Now connect
            peripheral.connect();
        }
    }

    /**
     * Automatically connect to a peripheral when it is advertising. It is not necessary to scan for the peripheral first. This call is asynchronous and will not time out.
     *
     * @param peripheral the peripheral
     */
    public void autoConnectPeripheral(BluetoothPeripheral peripheral, BluetoothPeripheralCallback peripheralCallback) {
        // Make sure we are the only ones executing this method
        synchronized (connectLock) {
            // Make sure peripheral is valid
            if (peripheral == null) {
                Timber.e("no valid peripheral specified, aborting connection");
                return;
            }

            // Check if we are already connected to this peripheral
            if (connectedPeripherals.containsKey(peripheral.getAddress())) {
                Timber.w("already connected to %s'", peripheral.getAddress());
                return;
            }

            // Check if we are not already asking this peripheral for data
            if (unconnectedPeripherals.get(peripheral.getAddress()) != null) {
                Timber.w("already issued autoconnect for '%s' ", peripheral.getAddress());
                return;
            }

            // Check if the peripheral is cached or not
            int deviceType = peripheral.getType();
            if (deviceType == BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                // The peripheral is not cached so we cannot autoconnect
                Timber.d("peripheral with address '%s' not in Bluetooth cache, autoconnecting by scanning", peripheral.getAddress());
                unconnectedPeripherals.put(peripheral.getAddress(), peripheral);
                autoConnectPeripheralByScan(peripheral.getAddress(), peripheralCallback);
                return;
            }

            // Check if the peripheral supports BLE
            if (!(deviceType == BluetoothDevice.DEVICE_TYPE_LE || deviceType == BluetoothDevice.DEVICE_TYPE_DUAL)) {
                // This device does not support Bluetooth LE, so we cannot connect
                Timber.e("peripheral does not support Bluetooth LE");
                return;
            }

            // It is all looking good! Set the callback and prepare for autoconnect
            peripheral.setPeripheralCallback(peripheralCallback);
            unconnectedPeripherals.put(peripheral.getAddress(), peripheral);

            // Autoconnect to this peripheral
            peripheral.autoConnect();
        }
    }

    private void autoConnectPeripheralByScan(String peripheralAddress, BluetoothPeripheralCallback peripheralCallback) {
        // Check if this peripheral is already on the list or not
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) {
            Timber.w("peripheral already on list for reconnection");
            return;
        }

        // Add this peripheral to the list
        reconnectPeripheralAddresses.add(peripheralAddress);
        reconnectCallbacks.put(peripheralAddress, peripheralCallback);

        // Scan to peripherals that need to be autoConnected
        scanForAutoConnectPeripherals();
    }

    /**
     * Cancel an active or pending connection for a peripheral
     *
     * @param peripheral the peripheral
     */
    public void cancelConnection(final BluetoothPeripheral peripheral) {
        // Check if peripheral is valid
        if (peripheral == null) {
            Timber.e("cannot cancel connection, peripheral is null");
            return;
        }

        // Get the peripheral address
        String peripheralAddress = peripheral.getAddress();

        // First check if we are doing a reconnection scan for this peripheral
        if (reconnectPeripheralAddresses.contains(peripheralAddress)) {
            // Clean up first
            reconnectPeripheralAddresses.remove(peripheralAddress);
            reconnectCallbacks.remove(peripheralAddress);
            autoConnectScanner.stopScan(autoConnectScanCallback);
            autoConnectScanner = null;
            cancelAutoConnectTimer();
            Timber.d("cancelling autoconnect for %s", peripheralAddress);

            // If there are any devices left, restart the reconnection scan
            if (reconnectPeripheralAddresses.size() > 0) {
                scanForAutoConnectPeripherals();
            }

            callBackHandler.post(new Runnable() {
                @Override
                public void run() {
                    bluetoothCentralCallback.onDisconnectedPeripheral(peripheral, BluetoothPeripheral.GATT_SUCCESS);
                }
            });

            return;
        }

        // Check if it is an unconnected peripheral
        if (unconnectedPeripherals.containsKey(peripheralAddress)) {
            BluetoothPeripheral unconnectedPeripheral = unconnectedPeripherals.get(peripheralAddress);
            if (unconnectedPeripheral != null) {
                unconnectedPeripheral.cancelConnection();
            }
            return;
        }

        // Check if this is a connected peripheral
        if (connectedPeripherals.containsKey(peripheralAddress)) {
            BluetoothPeripheral connectedPeripheral = connectedPeripherals.get(peripheralAddress);
            if (connectedPeripheral != null) {
                connectedPeripheral.cancelConnection();
            }
        } else {
            Timber.e("cannot cancel connection to unknown peripheral %s", peripheralAddress);
        }
    }

    /**
     * Get a peripheral object matching the specified mac address
     *
     * @param peripheralAddress mac address
     * @return a BluetoothPeripheral object matching the specified mac address or null if it was not found
     */
    public BluetoothPeripheral getPeripheral(String peripheralAddress) {
        // Check if it is valid address
        if (!BluetoothAdapter.checkBluetoothAddress(peripheralAddress)) {
            Timber.e("%s is not a valid address. Make sure all alphabetic characters are uppercase.", peripheralAddress);
            return null;
        }

        // Lookup or create BluetoothPeripheral object
        if (connectedPeripherals.containsKey(peripheralAddress)) {
            return connectedPeripherals.get(peripheralAddress);
        } else if (unconnectedPeripherals.containsKey(peripheralAddress)) {
            return unconnectedPeripherals.get(peripheralAddress);
        } else {
            return new BluetoothPeripheral(context, bluetoothAdapter.getRemoteDevice(peripheralAddress), internalCallback, null, callBackHandler);
        }
    }

    /**
     * Get the list of connected peripherals
     *
     * @return list of connected peripherals
     */
    public List<BluetoothPeripheral> getConnectedPeripherals() {
        return new ArrayList<>(connectedPeripherals.values());
    }

    private boolean isBleReady() {
        if (isBleSupported()) {
            if (isBleEnabled()) {
                return permissionsGranted();
            }
        }
        return false;
    }

    private boolean isBleSupported() {
        if (bluetoothAdapter != null && context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            return true;
        }

        Timber.e("BLE not supported");
        return false;
    }

    private boolean isBleEnabled() {
        if (bluetoothAdapter.isEnabled()) {
            return true;
        }
        Timber.e("Bluetooth disabled");
        return false;
    }

    private boolean permissionsGranted() {
        int targetSdkVersion = context.getApplicationInfo().targetSdkVersion;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && targetSdkVersion >= Build.VERSION_CODES.Q) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Timber.e("no ACCESS_FINE_LOCATION permission, cannot scan");
                return false;
            } else return true;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                Timber.e("no ACCESS_COARSE_LOCATION permission, cannot scan");
                return false;
            } else return true;
        } else {
            return true;
        }
    }

    /**
     * Set scan timeout timer, timeout time is {@code SCAN_TIMEOUT}.
     * If timeout is executed the scan is stopped and automatically restarted. This is done to avoid Android 9 scan restrictions
     */
    private void setScanTimer() {
        // Cancel runnable if it exists
        cancelTimeoutTimer();

        // Prepare runnable
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Timber.d("scanning timeout, restarting scan");
                final ScanCallback callback = currentCallback;
                final List<ScanFilter> filters = currentFilters;
                stopScan();

                // Restart the scan and timer
                callBackHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        startScan(filters, scanSettings, callback);
                    }
                }, SCAN_RESTART_DELAY);
            }
        };

        // Start timer
        timeoutHandler.postDelayed(timeoutRunnable, SCAN_TIMEOUT);
    }

    /**
     * Cancel the scan timeout timer
     */
    private void cancelTimeoutTimer() {
        if (timeoutRunnable != null) {
            timeoutHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    /**
     * Set scan timeout timer, timeout time is {@code SCAN_TIMEOUT}.
     * If timeout is executed the scan is stopped and automatically restarted. This is done to avoid Android 9 scan restrictions
     */
    private void setAutoConnectTimer() {
        // Cancel runnanle if it exists
        cancelAutoConnectTimer();

        // Prepare autoconnect runnable
        autoConnectRunnable = new Runnable() {
            @Override
            public void run() {
                Timber.d("autoconnect scan timeout, restarting scan");

                // Stop previous autoconnect scans if any
                if (autoConnectScanner != null) {
                    autoConnectScanner.stopScan(autoConnectScanCallback);
                    autoConnectScanner = null;
                }

                // Restart the auto connect scan and timer
                callBackHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        scanForAutoConnectPeripherals();
                    }
                }, SCAN_RESTART_DELAY);
            }
        };

        // Start autoconnect timer
        autoConnectHandler.postDelayed(autoConnectRunnable, SCAN_TIMEOUT);
    }

    /**
     * Cancel the scan timeout timer
     */
    private void cancelAutoConnectTimer() {
        if (autoConnectRunnable != null) {
            autoConnectHandler.removeCallbacks(autoConnectRunnable);
            autoConnectRunnable = null;
        }
    }

    /**
     * Remove bond for a peripheral
     *
     * @param peripheralAddress the address of the peripheral
     * @return true if the peripheral was succesfully unpaired or it wasn't paired, false if it was paired and removing it failed
     */
    public boolean removeBond(String peripheralAddress) {
        boolean result;
        BluetoothDevice peripheralToUnBond = null;

        // Get the set of bonded devices
        Set<BluetoothDevice> bondedDevices = bluetoothAdapter.getBondedDevices();

        // See if the device is bonded
        if (bondedDevices.size() > 0) {
            for (BluetoothDevice device : bondedDevices) {
                if (device.getAddress().equals(peripheralAddress)) {
                    peripheralToUnBond = device;
                }
            }
        } else {
            return true;
        }

        // Try to remove the bond
        if (peripheralToUnBond != null) {
            try {
                Method method = peripheralToUnBond.getClass().getMethod("removeBond", (Class[]) null);
                result = (boolean) method.invoke(peripheralToUnBond, (Object[]) null);
                if (result) {
                    Timber.i("Succesfully removed bond for '%s'", peripheralToUnBond.getName());
                }
                return result;
            } catch (Exception e) {
                Timber.i("could not remove bond");
                e.printStackTrace();
                return false;
            }
        } else {
            return true;
        }
    }

    /*
     * Make the pairing popup appear in the foreground by doing a 1 sec discovery.
     * If the pairing popup is shown within 60 seconds, it will be shown in the foreground
     *
     */
    public void startPairingPopupHack() {
        // Check if we are on a Samsung device because those don't need the hack
        String manufacturer = Build.MANUFACTURER;
        if (!manufacturer.equals("samsung")) {
            bluetoothAdapter.startDiscovery();

            callBackHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Timber.d("popup hack completed");
                    bluetoothAdapter.cancelDiscovery();
                }
            }, 1000);
        }
    }

    /**
     * Some phones, like Google/Pixel phones, don't automatically disconnect devices so this method does it manually
     */
    private void cancelAllConnectionsWhenBluetoothOff() {
        Timber.d("disconnect all peripherals because bluetooth is off");
        // Call cancelConnection for connected peripherals
        for (final BluetoothPeripheral peripheral : connectedPeripherals.values()) {
            peripheral.disconnectWhenBluetoothOff();
        }
        connectedPeripherals.clear();

        // Call cancelConnection for unconnected peripherals
        for (final BluetoothPeripheral peripheral : unconnectedPeripherals.values()) {
            peripheral.disconnectWhenBluetoothOff();
        }
        unconnectedPeripherals.clear();

        // Clean up autoconnect by scanning information
        reconnectPeripheralAddresses.clear();
        reconnectCallbacks.clear();
    }

    /**
     * Timer to determine if manual disconnection in case of bluetooth off is needed
     */
    private void startDisconnectionTimer() {
        if (disconnectRunnable != null) {
            disconnectHandler.removeCallbacks(disconnectRunnable);
        }

        disconnectRunnable = new Runnable() {
            @Override
            public void run() {
                Timber.e("bluetooth turned off but no automatic disconnects happening, so doing it ourselves");
                cancelAllConnectionsWhenBluetoothOff();
                disconnectRunnable = null;
            }
        };

        disconnectHandler.postDelayed(disconnectRunnable, 1000);
    }

    /**
     * Cancel timer for bluetooth off disconnects
     */
    private void cancelDisconnectionTimer() {
        if (disconnectRunnable != null) {
            disconnectHandler.removeCallbacks(disconnectRunnable);
            disconnectRunnable = null;
        }
    }
}
