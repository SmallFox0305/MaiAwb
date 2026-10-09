package com.maiawb.core.cri;

/**
 * CriTable 字段标志位，与 SonicAudioLib.CriMw.CriFieldFlag 一致。
 */
public final class CriFieldFlags {

    public static final int NAME = 16;
    public static final int DEFAULT_VALUE = 32;
    public static final int ROW_STORAGE = 64;
    public static final int TYPE_MASK = 15;

    private CriFieldFlags() {
    }
}