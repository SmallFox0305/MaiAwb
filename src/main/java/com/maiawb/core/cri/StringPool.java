package com.maiawb.core.cri;

import com.maiawb.core.io.ByteWriter;
import com.maiawb.core.io.DataStream;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * 字符串池，对应 SonicAudioLib.IO.StringPool。
 */
public final class StringPool {

    public static final String ADX_BLANK_STRING = "<NULL>";

    private static final class StringItem {
        final String value;
        final long position;

        StringItem(String value, long position) {
            this.value = value;
            this.position = position;
        }
    }

    private final List<StringItem> items = new ArrayList<>();
    private long startPosition;
    private long length;
    private final Charset encoding;

    public long position() {
        return startPosition;
    }

    public long length() {
        return length;
    }

    public long put(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }
        long position = length;
        items.add(new StringItem(value, position));
        length += value.getBytes(encoding).length + 1;
        return position;
    }

    public void write(ByteWriter destination) {
        startPosition = destination.position();
        for (StringItem item : items) {
            DataStream.writeCString(destination, item.value, encoding);
        }
    }

    public boolean contains(String value) {
        for (StringItem item : items) {
            if (item.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    public long getPosition(String value) {
        for (StringItem item : items) {
            if (item.value.equals(value)) {
                return item.position;
            }
        }
        throw new IllegalStateException("String not found in pool: " + value);
    }

    public void clear() {
        items.clear();
    }

    public StringPool(Charset encoding) {
        this.encoding = encoding;
    }
}