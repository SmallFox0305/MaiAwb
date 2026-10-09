package com.maiawb.core.cri;

import java.util.UUID;

/**
 * 字段定义（名称 + 类型 + 默认值），对应 C# 侧 CriField。
 * 未提供默认值或默认值为 null 时，使用对应类型的零值（C# NullValues 语义）。
 */
public final class CriField {

    private final String fieldName;
    private final CriFieldType fieldType;
    private final Object defaultValue;

    public CriField(String name, CriFieldType type) {
        this.fieldName = name;
        this.fieldType = type;
        this.defaultValue = nullValue(type);
    }

    public CriField(String name, CriFieldType type, Object defaultValue) {
        this.fieldName = name;
        this.fieldType = type;
        this.defaultValue = defaultValue == null ? nullValue(type) : defaultValue;
    }

    public String getFieldName() {
        return fieldName;
    }

    public CriFieldType getFieldType() {
        return fieldType;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    /** C# 侧 CriField.NullValues。 */
    public static Object nullValue(CriFieldType type) {
        return switch (type) {
            case BYTE, SBYTE -> (byte) 0;
            case UINT16, INT16 -> (short) 0;
            case UINT32, INT32 -> 0;
            case UINT64, INT64 -> 0L;
            case SINGLE -> 0.0f;
            case DOUBLE -> 0.0d;
            case STRING -> "";
            case DATA -> new byte[0];
            case GUID -> new byte[16];
        };
    }
}