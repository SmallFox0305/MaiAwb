package com.maiawb.core.cri;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * CriTable 写出设置，对应 C# 侧 CriTableWriterSettings。
 * <p>
 * Adx2Settings：Align=32、不放置空字符串、不去重字符串、不启用掩码。
 */
public final class CriTableWriterSettings {

    private long align = 1;
    private boolean putBlankString = true;
    private boolean leaveOpen = false;
    // 与 MaiChartManager 所用 clansty/SonicAudioTools 分叉一致：默认 UTF-8
    private Charset encodingType = StandardCharsets.UTF_8;
    private boolean removeDuplicateStrings = true;
    private boolean enableMask = false;
    private long maskXor;
    private long maskXorMultiplier;

    public long getAlign() {
        return align;
    }

    public void setAlign(long align) {
        this.align = Math.max(1, align);
    }

    public boolean isPutBlankString() {
        return putBlankString;
    }

    public void setPutBlankString(boolean putBlankString) {
        this.putBlankString = putBlankString;
    }

    public boolean isLeaveOpen() {
        return leaveOpen;
    }

    public void setLeaveOpen(boolean leaveOpen) {
        this.leaveOpen = leaveOpen;
    }

    public Charset getEncodingType() {
        return encodingType;
    }

    public void setEncodingType(Charset encodingType) {
        this.encodingType = encodingType;
    }

    public boolean isRemoveDuplicateStrings() {
        return removeDuplicateStrings;
    }

    public void setRemoveDuplicateStrings(boolean removeDuplicateStrings) {
        this.removeDuplicateStrings = removeDuplicateStrings;
    }

    public boolean isEnableMask() {
        return enableMask;
    }

    public void setEnableMask(boolean enableMask) {
        this.enableMask = enableMask;
    }

    public long getMaskXor() {
        return maskXor;
    }

    public void setMaskXor(long maskXor) {
        this.maskXor = maskXor;
    }

    public long getMaskXorMultiplier() {
        return maskXorMultiplier;
    }

    public void setMaskXorMultiplier(long maskXorMultiplier) {
        this.maskXorMultiplier = maskXorMultiplier;
    }

    public static CriTableWriterSettings adx() {
        CriTableWriterSettings s = new CriTableWriterSettings();
        s.setAlign(8);
        s.setPutBlankString(true);
        s.setRemoveDuplicateStrings(true);
        return s;
    }

    public static CriTableWriterSettings adx2() {
        CriTableWriterSettings s = new CriTableWriterSettings();
        s.setAlign(32);
        s.setPutBlankString(false);
        s.setRemoveDuplicateStrings(false);
        return s;
    }
}