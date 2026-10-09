package com.maiawb.core.cri;

import com.maiawb.core.io.ByteWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * CRI 表（ACB/UTF 表），对应 C# 侧 CriTable。
 * 提供 Load(byte[]) / Save() 以及字段/行访问，用于复刻 MaiChartManager 的 CriUtils 逻辑。
 */
public final class CriTable {

    private final List<CriField> fields = new ArrayList<>();
    private final List<CriRow> rows = new ArrayList<>();
    private String tableName = "(no name)";
    private CriTableWriterSettings writerSettings = new CriTableWriterSettings();

    public CriTable() {
    }

    public CriTable(String tableName) {
        this.tableName = tableName;
    }

    // ------------------------------------------------------------------
    // 读写
    // ------------------------------------------------------------------

    public void load(byte[] bytes) {
        CriTableReader reader = new CriTableReader(bytes);
        tableName = reader.getTableName();
        fields.clear();
        rows.clear();
        for (int i = 0; i < reader.getNumberOfFields(); i++) {
            fields.add(new CriField(reader.getFieldName(i), reader.getFieldType(i), reader.getFieldValue(i)));
        }
        while (reader.read()) {
            CriRow row = new CriRow(this);
            row.buildFromValues(reader.getValueArray());
            rows.add(row);
        }
    }

    public byte[] save() {
        ByteWriter out = new ByteWriter(1024);
        CriTableWriter writer = new CriTableWriter(out, writerSettings);
        writer.writeStartTable(tableName);

        writer.writeStartFieldCollection();
        for (CriField criField : fields) {
            boolean useDefaultValue = false;
            Object defaultValue = null;
            if (rows.size() > 1) {
                useDefaultValue = true;
                defaultValue = rows.get(0).get(criField);
                for (CriRow row : rows) {
                    if (!CriTableWriter.valuesEqual(row.get(criField), defaultValue)) {
                        useDefaultValue = false;
                        break;
                    }
                }
            } else if (rows.isEmpty()) {
                useDefaultValue = true;
            }

            if (useDefaultValue) {
                writer.writeField(criField.getFieldName(), criField.getFieldType(), defaultValue);
            } else {
                writer.writeField(criField.getFieldName(), criField.getFieldType());
            }
        }
        writer.writeEndFieldCollection();

        for (CriRow row : rows) {
            writer.writeRow(true, row.getValueArray());
        }
        writer.writeEndTable();

        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 访问器
    // ------------------------------------------------------------------

    public List<CriField> fields() {
        return fields;
    }

    public List<CriRow> rows() {
        return rows;
    }

    public String tableName() {
        return tableName;
    }

    public void setTableName(String tableName) {
        this.tableName = tableName;
    }

    public CriTableWriterSettings writerSettings() {
        return writerSettings;
    }

    public void setWriterSettings(CriTableWriterSettings writerSettings) {
        this.writerSettings = writerSettings;
    }

    public int rowCount() {
        return rows.size();
    }
}