package com.deadman.uplift;

import org.apache.commons.codec.binary.Hex;

import java.io.Serializable;

class DeskHeight implements Serializable {

    String height;

    DeskHeight(byte[] value) {
        height = Hex.encodeHexString(value);
    }
}
