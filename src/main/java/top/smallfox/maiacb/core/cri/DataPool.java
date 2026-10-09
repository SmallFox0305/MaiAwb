package top.smallfox.maiacb.core.cri;

import top.smallfox.maiacb.core.io.ByteWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * 数据池（Data 类型字段的负载区），对应 SonicAudioLib.IO.DataPool。
 */
public final class DataPool {

    private final List<byte[]> items = new ArrayList<>();
    private long startPosition;
    private final long align;
    private long length;
    private final long baseLength;

    public long position() {
        return startPosition;
    }

    public long length() {
        return length;
    }

    public long align() {
        return align;
    }

    public long put(byte[] data) {
        if (data == null || data.length <= 0) {
            return 0;
        }
        length = alignUp(length, align);
        long position = length;
        length += data.length;
        items.add(data);
        return position;
    }

    public void write(ByteWriter destination) {
        startPosition = destination.position();
        for (byte[] item : items) {
            pad(destination, align);
            destination.write(item, 0, item.length);
        }
    }

    public void clear() {
        items.clear();
    }

    public DataPool(long align) {
        this(align, 0);
    }

    public DataPool(long align, long baseLength) {
        this.align = align;
        this.baseLength = baseLength;
        this.length = baseLength;
    }

    static long alignUp(long value, long alignment) {
        long a = Math.max(1, alignment);
        while (value % a != 0) {
            value++;
        }
        return value;
    }

    static void pad(ByteWriter destination, long alignment) {
        while (destination.position() % Math.max(1, alignment) != 0) {
            destination.writeByte(0);
        }
    }
}