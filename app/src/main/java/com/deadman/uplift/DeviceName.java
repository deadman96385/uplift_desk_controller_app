package com.deadman.uplift;

import com.welie.blessed.BluetoothBytesParser;

import java.io.Serializable;

class DeviceName implements Serializable {

    String name;

    DeviceName(byte[] value) {
        BluetoothBytesParser parser = new BluetoothBytesParser(value);
        name = parser.getStringValue(0);
    }
}
