package top.smallfox.maiacb.core.cri;

import top.smallfox.maiacb.core.io.ByteWriter;
import top.smallfox.maiacb.core.io.DataStream;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * CriTable 写入器，逐字节复刻 SonicAudioLib.CriMw.CriTableWriter。
 */
public final class CriTableWriter {

    private enum Status {
        BEGIN, START, FIELD_COLLECTION, ROW, IDLE, END
    }

    private static final class Field {
        int flag;
        String name = "";
        Long offset;

        Field(int flag, String name, Long offset) {
            this.flag = flag;
            this.name = name;
            this.offset = offset;
        }
    }

    private final CriTableWriterSettings settings;
    private final List<Field> fields = new ArrayList<>();
    private final ByteWriter destination;
    private final DataPool vldPool;
    private final StringPool stringPool;

    private Status status = Status.BEGIN;
    private long headerPosition;
    private long rowsPosition;
    private long stringPoolPosition;
    private long dataPoolPosition;
    private long tableNamePosition;
    private long length;
    private int fieldCount;
    private int rowLength;
    private long rowCount;

    public CriTableWriter(ByteWriter destination, CriTableWriterSettings settings) {
        this.destination = destination;
        this.settings = settings;
        this.stringPool = new StringPool(settings.getEncodingType());
        this.vldPool = new DataPool(settings.getAlign());
    }

    // ------------------------------------------------------------------

    public void writeStartTable(String tableName) {
        if (status != Status.BEGIN) {
            throw new IllegalStateException("Attempted to start table when the status wasn't BEGIN");
        }
        status = Status.START;
        headerPosition = destination.position();

        if (settings.isPutBlankString()) {
            stringPool.put(StringPool.ADX_BLANK_STRING);
        }
        tableNamePosition = stringPool.put(tableName);

        // 32 字节表头占位
        for (int i = 0; i < 32; i++) {
            destination.writeByte(0);
        }
    }

    public void writeEndTable() {
        if (status == Status.FIELD_COLLECTION) {
            writeEndFieldCollection();
        }
        if (status == Status.ROW) {
            writeEndRow();
        }
        status = Status.END;

        destination.seek(headerPosition + rowsPosition + rowLength * rowCount);

        stringPool.write(destination);
        stringPoolPosition = stringPool.position() - headerPosition;

        DataPool.pad(destination, vldPool.align());

        vldPool.write(destination);
        dataPoolPosition = vldPool.position() - headerPosition;

        DataPool.pad(destination, vldPool.align());

        length = destination.position() - headerPosition;

        destination.seek(headerPosition);
        destination.write(CriTableMasker.SIGNATURE, 0, 4);
        int encType = settings.getEncodingType().equals(java.nio.charset.StandardCharsets.UTF_8) ? 1 : 0;
        DataStream.writeUInt32BE(destination, length - 8);
        destination.writeByte(0); // unknown
        destination.writeByte(encType);
        DataStream.writeUInt16BE(destination, (int) (rowsPosition - 8));
        DataStream.writeUInt32BE(destination, stringPoolPosition - 8);
        DataStream.writeUInt32BE(destination, dataPoolPosition - 8);
        DataStream.writeUInt32BE(destination, tableNamePosition);
        DataStream.writeUInt16BE(destination, fieldCount);
        DataStream.writeUInt16BE(destination, rowLength);
        DataStream.writeUInt32BE(destination, rowCount);

        if (settings.isEnableMask()) {
            byte[] all = destination.toByteArray();
            byte[] masked = CriTableMasker.mask(all, length, settings.getMaskXor(), settings.getMaskXorMultiplier());
            destination.seek(headerPosition);
            for (int i = 0; i < (int) length; i++) {
                destination.writeByte(masked[i]);
            }
        }

        destination.seek(headerPosition + length);
    }

    public void writeStartFieldCollection() {
        if (status != Status.START) {
            throw new IllegalStateException("Attempted to start field collection when the status wasn't START");
        }
        status = Status.FIELD_COLLECTION;
    }

    public void writeField(String fieldName, CriFieldType fieldType, Object defaultValue) {
        if (status != Status.FIELD_COLLECTION) {
            writeStartFieldCollection();
        }
        int flag = fieldType.ordinal();
        if (fieldName != null && !fieldName.isEmpty()) {
            flag |= CriFieldFlags.NAME;
        }
        if (defaultValue != null) {
            flag |= CriFieldFlags.DEFAULT_VALUE;
        }
        fields.add(new Field(flag, fieldName, null));

        destination.writeByte(flag);
        if (fieldName != null && !fieldName.isEmpty()) {
            writeString(fieldName);
        }
        if (defaultValue != null) {
            writePrimitiveValue(defaultValue);
        }
        fieldCount++;
    }

    public void writeField(String fieldName, CriFieldType fieldType) {
        if (status != Status.FIELD_COLLECTION) {
            writeStartFieldCollection();
        }
        int flag = fieldType.ordinal() | CriFieldFlags.ROW_STORAGE;
        if (fieldName != null && !fieldName.isEmpty()) {
            flag |= CriFieldFlags.NAME;
        }
        Long offset = (long) rowLength;
        rowLength += fieldType.size;
        fields.add(new Field(flag, fieldName, offset));

        destination.writeByte(flag);
        if (fieldName != null && !fieldName.isEmpty()) {
            writeString(fieldName);
        }
        fieldCount++;
    }

    public void writeEndFieldCollection() {
        if (status != Status.FIELD_COLLECTION) {
            throw new IllegalStateException("Attempted to end field collection when the status wasn't FIELD_COLLECTION");
        }
        status = Status.IDLE;
        rowsPosition = destination.position() - headerPosition;
    }

    public void writeStartRow() {
        if (status == Status.FIELD_COLLECTION) {
            writeEndFieldCollection();
        }
        if (status != Status.IDLE) {
            throw new IllegalStateException("Attempted to start row when the status wasn't IDLE");
        }
        status = Status.ROW;
        rowCount++;
        for (int i = 0; i < rowLength; i++) {
            destination.writeByte(0);
        }
    }

    public void writeValue(int fieldIndex, Object rowValue) {
        if (fieldIndex >= fields.size() || fieldIndex < 0) {
            return;
        }
        Field f = fields.get(fieldIndex);
        if ((f.flag & CriFieldFlags.ROW_STORAGE) == 0 || rowValue == null) {
            return;
        }
        goToValue(fieldIndex);
        writePrimitiveValue(rowValue);
    }

    private void goToValue(int fieldIndex) {
        Field f = fields.get(fieldIndex);
        destination.seek(headerPosition + rowsPosition + rowLength * (rowCount - 1) + f.offset);
    }

    public void writeRow(boolean close, Object... rowValues) {
        writeStartRow();
        for (int i = 0; i < Math.min(rowValues.length, fields.size()); i++) {
            writeValue(i, rowValues[i]);
        }
        if (close) {
            writeEndRow();
        }
    }

    public void writeEndRow() {
        if (status != Status.ROW) {
            throw new IllegalStateException("Attempted to end row when the status wasn't ROW");
        }
        status = Status.IDLE;
    }

    private void writeString(String value) {
        if (settings.isRemoveDuplicateStrings() && stringPool.contains(value)) {
            DataStream.writeUInt32BE(destination, stringPool.getPosition(value));
        } else {
            DataStream.writeUInt32BE(destination, stringPool.put(value));
        }
    }

    private void writePrimitiveValue(Object val) {
        if (val instanceof Byte b) {
            destination.writeByte(b.intValue() & 0xFF);
        } else if (val instanceof Short s) {
            DataStream.writeUInt16BE(destination, s.intValue() & 0xFFFF);
        } else if (val instanceof Integer i) {
            DataStream.writeInt32BE(destination, i);
        } else if (val instanceof Long l) {
            DataStream.writeInt64BE(destination, l);
        } else if (val instanceof Float f) {
            DataStream.writeSingleBE(destination, f);
        } else if (val instanceof Double d) {
            DataStream.writeDoubleBE(destination, d);
        } else if (val instanceof String s) {
            writeString(s);
        } else if (val instanceof byte[] bytes) {
            DataStream.writeUInt32BE(destination, vldPool.put(bytes));
            DataStream.writeUInt32BE(destination, bytes.length);
        } else {
            throw new IllegalArgumentException("Unsupported value type: " + val.getClass().getName());
        }
    }

    // 判定「整表同值 → 用默认值存储」。参考 ACB 表明 AisacControlMap 等空 byte[]
    // 应折叠为 DEFAULT 存储，因此 byte[] 按内容比较（等价于各行为同一数组）。
    static boolean valuesEqual(Object a, Object b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof byte[] ba && b instanceof byte[] bb) {
            return Arrays.equals(ba, bb);
        }
        return Objects.equals(a, b);
    }
}