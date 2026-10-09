package com.maiawb.core.io;

import java.util.Arrays;

/**
 * 可寻址增长的字节缓冲，用于复刻 CriTableWriter 的 Stream 式写出。
 */
public final class ByteWriter {

    private byte[] buf;
    private int pos;

    public ByteWriter(int initialCapacity) {
        buf = new byte[Math.max(16, initialCapacity)];
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length) {
            int newCap = buf.length;
            while (newCap < pos + extra) {
                newCap <<= 1;
            }
            if (newCap < 0) {
                newCap = pos + extra;
            }
            buf = Arrays.copyOf(buf, newCap);
        }
    }

    public void writeByte(int b) {
        ensure(1);
        buf[pos++] = (byte) b;
    }

    public void write(byte[] data, int off, int len) {
        ensure(len);
        System.arraycopy(data, off, buf, pos, len);
        pos += len;
    }

    public void write(byte[] data) {
        write(data, 0, data.length);
    }

    public void seek(long position) {
        this.pos = (int) position;
        ensure(1);
    }

    public int position() {
        return pos;
    }

    public int size() {
        return pos;
    }

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, pos);
    }
}