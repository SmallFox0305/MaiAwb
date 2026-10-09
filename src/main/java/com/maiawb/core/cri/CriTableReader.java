package com.maiawb.core.cri;

import com.maiawb.core.io.DataStream;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * CriTable 读取器，逐字节复刻 SonicAudioLib.CriMw.CriTableReader。
 */
public final class CriTableReader {

    private byte[] source;
    private Charset encoding;

    private final long headerPosition = 0;
    private long length;
    private int unknownByte;
    private int encodingType;
    private long rowsPosition;
    private long stringPoolPosition;
    private long dataPoolPosition;
    private String tableName = "";
    private int fieldCount;
    private int rowLength;
    private long rowCount;
    private long rowIndex = -1;

    private static final class FieldDiv {
        int flag;
        String name = "";
        Object value;      // 默认值（非行存储时）
        long position;     // Data 默认值时的数据池偏移
        long length;       // Data 默认值时的数据长度
        long offset;       // 行存储偏移
    }

    private final List<FieldDiv> fields = new ArrayList<>();

    public CriTableReader(byte[] input) {
        readTable(input);
    }

    // ------------------------------------------------------------------

    public String getTableName() {
        return tableName;
    }

    public int getNumberOfFields() {
        return fieldCount;
    }

    public long getNumberOfRows() {
        return rowCount;
    }

    public long getCurrentRow() {
        return rowIndex;
    }

    public String getFieldName(int fieldIndex) {
        return fields.get(fieldIndex).name;
    }

    public CriFieldType getFieldType(int fieldIndex) {
        return CriFieldType.fromTypeMask(fields.get(fieldIndex).flag);
    }

    public Object getFieldValue(int fieldIndex) {
        return fields.get(fieldIndex).value;
    }

    public boolean read() {
        if (rowIndex + 1 >= rowCount) {
            return false;
        }
        rowIndex++;
        return true;
    }

    public Object[] getValueArray() {
        Object[] values = new Object[fieldCount];
        for (int i = 0; i < fieldCount; i++) {
            FieldDiv f = fields.get(i);
            if ((f.flag & CriFieldFlags.TYPE_MASK) == CriFieldType.DATA.ordinal()) {
                values[i] = getData(i);
            } else {
                values[i] = getValue(i);
            }
        }
        return values;
    }

    public Object getValue(int fieldIndex) {
        if (fieldIndex < 0 || fieldIndex >= fields.size()) {
            return null;
        }
        FieldDiv f = fields.get(fieldIndex);
        if ((f.flag & CriFieldFlags.ROW_STORAGE) == 0) {
            if ((f.flag & CriFieldFlags.TYPE_MASK) == CriFieldType.DATA.ordinal()) {
                return new byte[0];
            }
            return f.value;
        }
        long pos = headerPosition + rowsPosition + (long) rowLength * rowIndex + f.offset;
        return readValueAt(f.flag, (int) pos);
    }

    public byte[] getData(int fieldIndex) {
        FieldDiv f = fields.get(fieldIndex);
        if ((f.flag & CriFieldFlags.ROW_STORAGE) == 0) {
            return new byte[0];
        }
        long pos = headerPosition + rowsPosition + (long) rowLength * rowIndex + f.offset;
        long dataPosition = DataStream.readUInt32BE(source, (int) pos);
        long dataLength = DataStream.readUInt32BE(source, (int) pos + 4);
        return sliceData(dataPosition, dataLength);
    }

    // ------------------------------------------------------------------

    private void readTable(byte[] input) {
        byte[] signature = Arrays.copyOfRange(input, 0, 4);
        if (!Arrays.equals(signature, CriTableMasker.SIGNATURE)) {
            long[] key = CriTableMasker.findKeys(signature);
            source = CriTableMasker.mask(input, input.length, key[0], key[1]);
        } else {
            source = input;
        }
        int p = 4;

        length = DataStream.readUInt32BE(source, p) + 8;
        p += 4;
        unknownByte = DataStream.readByte(source, p);
        p += 1;
        encodingType = DataStream.readByte(source, p);
        p += 1;
        if (unknownByte != 0) {
            throw new IllegalStateException("Invalid byte (" + unknownByte + "). Please report this error with the file(s).");
        }
        switch (encodingType) {
            case 0 -> encoding = Charset.forName("Shift_JIS");
            case 1 -> encoding = StandardCharsets.UTF_8;
            default -> throw new IllegalStateException("Unknown encoding type (" + encodingType + "). Please report this error with the file(s).");
        }

        rowsPosition = DataStream.readUInt16BE(source, p) + 8;
        p += 2;
        stringPoolPosition = DataStream.readUInt32BE(source, p) + 8;
        p += 4;
        dataPoolPosition = DataStream.readUInt32BE(source, p) + 8;
        p += 4;
        tableName = readString(p);
        p += 4;
        fieldCount = DataStream.readUInt16BE(source, p);
        p += 2;
        rowLength = DataStream.readUInt16BE(source, p);
        p += 2;
        rowCount = DataStream.readUInt32BE(source, p);
        p += 4;

        long offset = 0;
        for (int i = 0; i < fieldCount; i++) {
            FieldDiv field = new FieldDiv();
            field.flag = DataStream.readByte(source, p);
            p += 1;

            if ((field.flag & CriFieldFlags.NAME) != 0) {
                field.name = readString(p);
                p += 4;
            }

            if ((field.flag & CriFieldFlags.DEFAULT_VALUE) != 0) {
                if ((field.flag & CriFieldFlags.TYPE_MASK) == CriFieldType.DATA.ordinal()) {
                    field.position = DataStream.readUInt32BE(source, p);
                    p += 4;
                    field.length = DataStream.readUInt32BE(source, p);
                    p += 4;
                    field.value = sliceData(field.position, field.length);
                } else {
                    int consumed = valueSize(CriFieldType.fromTypeMask(field.flag));
                    field.value = readValueAt(field.flag, p);
                    p += consumed;
                }
            } else if ((field.flag & CriFieldFlags.ROW_STORAGE) == 0
                    && (field.flag & CriFieldFlags.DEFAULT_VALUE) == 0) {
                field.value = CriField.nullValue(CriFieldType.fromTypeMask(field.flag));
            }

            if ((field.flag & CriFieldFlags.ROW_STORAGE) != 0) {
                field.offset = offset;
                offset += CriFieldType.fromTypeMask(field.flag).size;
            }

            fields.add(field);
        }
    }

    private String readString(int p) {
        long stringPosition = DataStream.readUInt32BE(source, p);
        return readStringAt(stringPosition);
    }

    private String readStringAt(long stringPosition) {
        long poolPos = headerPosition + stringPoolPosition + stringPosition;
        String s = DataStream.readCString(source, (int) poolPos, encoding);
        if (s.equals(StringPool.ADX_BLANK_STRING)
                || (s.equals(tableName) && stringPosition == 0)) {
            return "";
        }
        return s;
    }

    private Object readValueAt(int flag, int pos) {
        CriFieldType type = CriFieldType.fromTypeMask(flag);
        switch (type) {
            case BYTE:
                return (byte) DataStream.readByte(source, pos);
            case SBYTE:
                return (byte) DataStream.readByte(source, pos);
            case UINT16:
                return (short) DataStream.readUInt16BE(source, pos);
            case INT16:
                return DataStream.readInt16BE(source, pos);
            case UINT32:
                return (int) DataStream.readUInt32BE(source, pos);
            case INT32:
                return DataStream.readInt32BE(source, pos);
            case UINT64:
                return DataStream.readUInt64BE(source, pos);
            case INT64:
                return DataStream.readInt64BE(source, pos);
            case SINGLE:
                return DataStream.readSingleBE(source, pos);
            case DOUBLE:
                return DataStream.readDoubleBE(source, pos);
            case STRING: {
                long stringPosition = DataStream.readUInt32BE(source, pos);
                return readStringAt(stringPosition);
            }
            case DATA: {
                long dataPosition = DataStream.readUInt32BE(source, pos);
                long dataLength = DataStream.readUInt32BE(source, pos + 4);
                return sliceData(dataPosition, dataLength);
            }
            case GUID:
                return Arrays.copyOfRange(source, pos, pos + 16);
            default:
                throw new IllegalStateException("Unknown field type: " + type);
        }
    }

    private byte[] sliceData(long position, long dataLength) {
        long length2 = dataLength;
        if (position > 0 && length2 == 0) {
            long base = headerPosition + dataPoolPosition + position;
            if (base + 4 <= source.length
                    && Arrays.equals(Arrays.copyOfRange(source, (int) base, (int) base + 4), CriTableMasker.SIGNATURE)) {
                length2 = DataStream.readUInt32BE(source, (int) base + 4) + 8;
            }
        }
        long start = headerPosition + dataPoolPosition + position;
        if (start < 0 || start >= source.length) {
            return new byte[0];
        }
        int end = (int) Math.min(source.length, start + Math.max(0, length2));
        if (end < (int) start) {
            end = (int) start;
        }
        return Arrays.copyOfRange(source, (int) start, end);
    }

    static int valueSize(CriFieldType type) {
        return type.size;
    }
}