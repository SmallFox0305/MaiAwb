package com.maiawb.core.io;

import java.nio.charset.Charset;

/**
 * CRIMW 表格式所用的大端序 (BE) / 小端序 (LE) 基础字节读写，与 SonicAudioLib.IO.DataStream 逐字节对应。
 */
public final class DataStream {

    private DataStream() {
    }

    // ------------------------------------------------------------------
    // 静态读取（byte[] + offset）
    // ------------------------------------------------------------------

    public static int readByte(byte[] b, int off) {
        return b[off] & 0xFF;
    }

    public static int readUInt16(byte[] b, int off) {
        return (b[off] & 0xFF) | (b[off + 1] & 0xFF) << 8;
    }

    public static int readUInt16BE(byte[] b, int off) {
        return (b[off] & 0xFF) << 8 | (b[off + 1] & 0xFF);
    }

    public static short readInt16BE(byte[] b, int off) {
        return (short) readUInt16BE(b, off);
    }

    public static long readUInt32(byte[] b, int off) {
        return (b[off] & 0xFFL)
                | (b[off + 1] & 0xFFL) << 8
                | (b[off + 2] & 0xFFL) << 16
                | (b[off + 3] & 0xFFL) << 24;
    }

    public static long readUInt32BE(byte[] b, int off) {
        return (b[off] & 0xFFL) << 24
                | (b[off + 1] & 0xFFL) << 16
                | (b[off + 2] & 0xFFL) << 8
                | (b[off + 3] & 0xFFL);
    }

    public static int readInt32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16
                | (b[off + 3] & 0xFF) << 24;
    }

    public static int readInt32BE(byte[] b, int off) {
        return b[off] << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | (b[off + 3] & 0xFF);
    }

    public static long readUInt64BE(byte[] b, int off) {
        return (b[off] & 0xFFL) << 56
                | (b[off + 1] & 0xFFL) << 48
                | (b[off + 2] & 0xFFL) << 40
                | (b[off + 3] & 0xFFL) << 32
                | (b[off + 4] & 0xFFL) << 24
                | (b[off + 5] & 0xFFL) << 16
                | (b[off + 6] & 0xFFL) << 8
                | (b[off + 7] & 0xFFL);
    }

    public static long readInt64BE(byte[] b, int off) {
        return readUInt64BE(b, off);
    }

    public static float readSingleBE(byte[] b, int off) {
        return Float.intBitsToFloat((int) readUInt32BE(b, off));
    }

    public static double readDoubleBE(byte[] b, int off) {
        return Double.longBitsToDouble(readUInt64BE(b, off));
    }

    public static String readCString(byte[] b, int off, Charset charset) {
        int end = off;
        while (end < b.length && b[end] != 0) {
            end++;
        }
        return new String(b, off, end - off, charset);
    }

    // ------------------------------------------------------------------
    // 写出（ByteWriter 光标缓冲）
    // ------------------------------------------------------------------

    public static void writeUInt16BE(ByteWriter w, int v) {
        w.writeByte((v >>> 8) & 0xFF);
        w.writeByte(v & 0xFF);
    }

    public static void writeUInt32BE(ByteWriter w, long v) {
        w.writeByte((int) ((v >>> 24) & 0xFF));
        w.writeByte((int) ((v >>> 16) & 0xFF));
        w.writeByte((int) ((v >>> 8) & 0xFF));
        w.writeByte((int) (v & 0xFF));
    }

    public static void writeInt32BE(ByteWriter w, int v) {
        w.writeByte((v >>> 24) & 0xFF);
        w.writeByte((v >>> 16) & 0xFF);
        w.writeByte((v >>> 8) & 0xFF);
        w.writeByte(v & 0xFF);
    }

    public static void writeUInt64BE(ByteWriter w, long v) {
        w.writeByte((int) ((v >>> 56) & 0xFF));
        w.writeByte((int) ((v >>> 48) & 0xFF));
        w.writeByte((int) ((v >>> 40) & 0xFF));
        w.writeByte((int) ((v >>> 32) & 0xFF));
        w.writeByte((int) ((v >>> 24) & 0xFF));
        w.writeByte((int) ((v >>> 16) & 0xFF));
        w.writeByte((int) ((v >>> 8) & 0xFF));
        w.writeByte((int) (v & 0xFF));
    }

    public static void writeInt64BE(ByteWriter w, long v) {
        writeUInt64BE(w, v);
    }

    public static void writeSingleBE(ByteWriter w, float v) {
        writeUInt32BE(w, Integer.toUnsignedLong(Float.floatToRawIntBits(v)));
    }

    public static void writeDoubleBE(ByteWriter w, double v) {
        writeUInt64BE(w, Double.doubleToRawLongBits(v));
    }

    public static void writeCString(ByteWriter w, String value, Charset charset) {
        byte[] bytes = value.getBytes(charset);
        w.write(bytes, 0, bytes.length);
        w.writeByte(0);
    }
}