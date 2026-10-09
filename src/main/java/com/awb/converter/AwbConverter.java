package com.awb.converter;

import com.maiawb.core.CriUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * AWB → ACB 转换入口（功能性门面），签名与 MaiChartManager 的
 * {@code CreateAcbWithPreview(wavPath, awbBytes, loopStart, loopEnd)} 对齐。
 * 实际转换逻辑全部委托给 {@link com.maiawb.core.CriUtils}。
 */
public final class AwbConverter {

    private AwbConverter() {
    }

    /**
     * 对应 C# CreateAcbWithPreview(string wavPath, byte[] awbBytes, TimeSpan loopStart, TimeSpan loopEnd)。
     *
     * @param wavPath       预览 WAV 文件路径（PCM16）
     * @param awbBytes      AWB/AFS2 字节
     * @param loopStartTicks 循环起始，TimeSpan ticks（100 纳秒单位）
     * @param loopEndTicks   循环结束，TimeSpan ticks（100 纳秒单位）
     * @return 生成的 ACB 字节
     */
    public static byte[] createAcbWithPreview(String wavPath, byte[] awbBytes,
                                              long loopStartTicks, long loopEndTicks) throws IOException {
        return createAcbWithPreview(wavPath, awbBytes, loopStartTicks, loopEndTicks, null);
    }

    /**
     * 同 {@link #createAcbWithPreview(String, byte[], long, long)}，但允许指定外部模板 ACB。
     *
     * @param templatePath 模板 ACB 路径；为 {@code null} 时使用内置 templateV3.acb
     */
    public static byte[] createAcbWithPreview(String wavPath, byte[] awbBytes,
                                              long loopStartTicks, long loopEndTicks,
                                              String templatePath) throws IOException {
        byte[] template = templatePath == null
                ? CriUtils.loadBundledTemplate()
                : Files.readAllBytes(Path.of(templatePath));
        return CriUtils.createAcbWithPreview(
                Files.readAllBytes(Path.of(wavPath)),
                awbBytes,
                ticksToSeconds(loopStartTicks),
                ticksToSeconds(loopEndTicks),
                template);
    }

    /** TimeSpan ticks（100 纳秒）→ 秒。 */
    private static double ticksToSeconds(long ticks) {
        return ticks / 10_000_000.0;
    }
}