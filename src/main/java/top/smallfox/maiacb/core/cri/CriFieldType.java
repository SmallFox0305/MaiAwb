package top.smallfox.maiacb.core.cri;

/**
 * CriTable 字段类型枚举，顺序即类型编号（0..12），对应 C# 侧 CriField.FieldTypes。
 */
public enum CriFieldType {

    BYTE(1),
    SBYTE(1),
    UINT16(2),
    INT16(2),
    UINT32(4),
    INT32(4),
    UINT64(8),
    INT64(8),
    SINGLE(4),
    DOUBLE(8),
    STRING(4),
    DATA(8),
    GUID(16);

    /** 该类型在行存储区占用的字节数。 */
    public final int size;

    CriFieldType(int size) {
        this.size = size;
    }

    public static CriFieldType fromTypeMask(int flag) {
        int idx = flag & CriFieldFlags.TYPE_MASK;
        if (idx < 0 || idx >= values().length) {
            throw new IllegalArgumentException("Unknown CRI field type mask: " + idx);
        }
        return values()[idx];
    }
}