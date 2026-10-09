package top.smallfox.maiacb.converter;

import top.smallfox.maiacb.core.CriUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * AWB → ACB 转换入口（功能性门面），签名与 MaiChartManager 的
 * {@code CreateAcbWithPreview(wavPath, awbBytes, loopStart, loopEnd)} 对齐。
 * 模板固定使用内置 templateV3.acb（不支持自定义模板，与官机工具链一致）。
 * 实际转换逻辑全部委托给 {@link top.smallfox.maiacb.core.CriUtils}。
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
     * 同 {@link #createAcbWithPreview(String, byte[], long, long)}，额外支持警告回调。
     *
     * @param warningSink 生成过程的警告回调；可为 {@code null}
     */
    public static byte[] createAcbWithPreview(String wavPath, byte[] awbBytes,
                                              long loopStartTicks, long loopEndTicks,
                                              Consumer<String> warningSink) throws IOException {
        return CriUtils.createAcbWithPreview(
                Files.readAllBytes(Path.of(wavPath)),
                awbBytes,
                ticksToSeconds(loopStartTicks),
                ticksToSeconds(loopEndTicks),
                warningSink);
    }

    /** TimeSpan ticks（100 纳秒）→ 秒。 */
    private static double ticksToSeconds(long ticks) {
        return ticks / 10_000_000.0;
    }
}