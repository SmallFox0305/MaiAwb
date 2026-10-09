package com.maiawb.core.cri;

import java.util.ArrayList;
import java.util.List;

/**
 * CriTable 的一行，对应 C# 侧 CriRow。
 */
public final class CriRow {

    private static final class Record {
        final CriField field;
        Object value;

        Record(CriField field, Object value) {
            this.field = field;
            this.value = value;
        }
    }

    private final List<Record> records = new ArrayList<>();

    // 包内构造：由 CriTable 建立行（初始值使用各字段 NullValue）
    CriRow(CriTable parent) {
        parent.fields().forEach(f -> records.add(new Record(f, f.getDefaultValue())));
    }

    // 由读取器构建行
    void buildFromValues(Object[] values) {
        for (int i = 0; i < records.size(); i++) {
            Object v = i < values.length ? values[i] : null;
            records.get(i).value = convertToFieldType(records.get(i).field, v);
        }
    }

    private static Object convertToFieldType(CriField field, Object v) {
        if (v == null) {
            return field.getDefaultValue();
        }
        // C# CriRow 直接赋值（不转换类型），此处仅在读取端把读取器数值原样存入。
        return v;
    }

    public int fieldCount() {
        return records.size();
    }

    public Object get(int index) {
        if (index < 0 || index >= records.size()) {
            return null;
        }
        return records.get(index).value;
    }

    public void set(int index, Object value) {
        if (index < 0 || index >= records.size()) {
            return;
        }
        records.get(index).value = value;
    }

    public Object get(String name) {
        return get(findField(name));
    }

    public void set(String name, Object value) {
        set(findField(name), value);
    }

    public Object get(CriField field) {
        return get(findField(field));
    }

    private int findField(String name) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).field.getFieldName().equals(name)) {
                return i;
            }
        }
        return -1;
    }

    private int findField(CriField field) {
        for (int i = 0; i < records.size(); i++) {
            if (records.get(i).field == field) {
                return i;
            }
        }
        return -1;
    }

    public Object[] getValueArray() {
        Object[] values = new Object[records.size()];
        for (int i = 0; i < records.size(); i++) {
            values[i] = records.get(i).value;
        }
        return values;
    }

    /** 按字段序号取值（供 CriTable.Write 判定默认值时使用）。 */
    Object getByFieldIndex(CriField field) {
        int idx = findField(field);
        return idx < 0 ? null : records.get(idx).value;
    }
}