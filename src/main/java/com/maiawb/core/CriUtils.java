package com.maiawb.core;

import com.maiawb.core.cri.CriRow;
import com.maiawb.core.cri.CriTable;
import com.maiawb.core.cri.CriTableWriterSettings;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.function.Consumer;

/**
 * ACB 生成工具，逐行复刻 MaiChartManager.Utils.CriUtils.CreateAcbWithPreview。
 */
public final class CriUtils {

    /** 内置 templateV3.acb 的 classpath 资源路径。 */
    public static final String TEMPLATE_RESOURCE = "/templateV3.acb";

    private CriUtils() {
    }

    /**
     * 从 AWB 内音频提取到的参数（等价于 C# 侧解析预览 WAV 得到的采样信息）。
     *
     * @param sampleRate  采样率（Hz），如 44100/48000
     * @param sampleCount 每声道样本数（HCA 解码总样本数，供 Length / NumSamples / 循环截断使用）
     * @param channels    声道数
     */
    public record AudioSpec(int sampleRate, int sampleCount, int channels) {
        /** 音频时长（秒）。 */
        public float duration() {
            return (float) sampleCount / (float) sampleRate;
        }
    }

    /**
     * 从 AWB 内 HCA 载荷提取音频参数（等同于把 AWB 音频“转成 WAV 后解析”得到的采样信息）。
     * <p>
     * HCA 解码总样本数 = frameCount × 1024 − encoderDelay − encoderPadding
     * （对照 vgmstream clhca 的 sample_count 计算，已实测 music006000 → 5,754,864 与解码器一致）。
     * 因 ACB 元数据（Length / NumSamples / 循环截断 / 采样率）只用采样率与样本数，无需实际 PCM 解码。
     *
     * @param awbPayload 规范化 AWB 的首个非空流（HCA 载荷）
     * @return 音频参数
     * @throws IOException 载荷不是可解析的 CRI keyed-HCA 时
     */
    public static AudioSpec extractAudioSpec(byte[] awbPayload) throws IOException {
        AwbNormalizer.HcaParams p = AwbNormalizer.probeHca(awbPayload);
        if (p == null) {
            throw new IOException("AWB 内音频不是可解析的 CRI keyed-HCA（无法提取采样参数）。");
        }
        long samples = p.frameCount() * 1024L - p.encoderDelay() - p.encoderPadding();
        if (samples <= 0) {
            throw new IOException("AWB 内 HCA 样本数无效（frameCount=" + p.frameCount()
                    + ", delay=" + p.encoderDelay() + ", padding=" + p.encoderPadding() + "）。");
        }
        if (samples > Integer.MAX_VALUE) {
            throw new IOException("AWB 内 HCA 音频过长（" + samples + " 样本），超出支持范围。");
        }
        return new AudioSpec(p.sampleRate(), (int) samples, p.channels());
    }

    /**
     * 读取内置模板 templateV3.acb。
     */
    public static byte[] loadBundledTemplate() throws IOException {
        try (InputStream in = CriUtils.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (in == null) {
                throw new IOException("Bundled templateV3.acb not found in classpath: " + TEMPLATE_RESOURCE);
            }
            return in.readAllBytes();
        }
    }

    /**
     * 主转换逻辑，对应 C#:
     * {@code MaiChartManager.Utils.CriUtils.CreateAcbWithPreview(string wavPath, byte[] awbBytes, TimeSpan loopStart, TimeSpan loopEnd)}
     * <p>
     * 模板固定使用内置 templateV3.acb（不提供也不支持自定义模板，与官机工具链一致）。
     *
     * @param wavBytes        预览音频（PCM16 WAV）字节
     * @param awbBytes        目标音频 AFS2/AWB 字节（建议先经 {@link AwbNormalizer#normalize} 规范化）
     * @param loopStartSecond 循环起始秒（对应 TimeSpan）
     * @param loopEndSecond   循环结束秒
     */
    public static byte[] createAcbWithPreview(byte[] wavBytes, byte[] awbBytes,
                                              double loopStartSecond, double loopEndSecond) throws IOException {
        return createAcbWithPreview(wavBytes, awbBytes, loopStartSecond, loopEndSecond,
                w -> System.err.println("[警告] " + w));
    }

    /**
     * 主转换逻辑，同 {@link #createAcbWithPreview(byte[], byte[], double, double)}，
     * 额外通过 {@code warningSink} 上报不影响输出的提示（可能帮助避免生成官机不可读的文件）。
     *
     * @param warningSink 生成过程中的警告回调；可为 {@code null}
     */
    public static byte[] createAcbWithPreview(byte[] wavBytes, byte[] awbBytes,
                                              double loopStartSecond, double loopEndSecond,
                                              Consumer<String> warningSink) throws IOException {
        return createAcbWithPreview(wavBytes, awbBytes, loopStartSecond, loopEndSecond,
                loadBundledTemplate(), warningSink);
    }

    /**
     * 以采样参数直接生成 ACB（等价于 {@link #createAcbWithPreview(byte[], byte[], double, double)}
     * 中传入一个与 AWB 音频完全一致的 PCM16 WAV）。
     * <p>
     * 参数可经 {@link #extractAudioSpec} 从 AWB 提取：本工具不做 HCA→PCM 解码，ACB 元数据所需的
     * 采样率/样本数直接取自 HCA 头，数值与解码器输出完全一致。
     */
    public static byte[] createAcbWithPreview(int sampleRate, int sampleCount, byte[] awbBytes,
                                              double loopStartSecond, double loopEndSecond,
                                              Consumer<String> warningSink) throws IOException {
        return createAcbWithPreview(sampleRate, sampleCount, awbBytes,
                loopStartSecond, loopEndSecond, loadBundledTemplate(), warningSink);
    }

    /**
     * 核心实现：在给定模板字节上复刻 C# CreateAcbWithPreview 的全部字节语义。
     */
    private static byte[] createAcbWithPreview(byte[] wavBytes, byte[] awbBytes,
                                               double loopStartSecond, double loopEndSecond,
                                               byte[] templateAcb, Consumer<String> warningSink) {
        WaveFile audioData = WaveFile.parse(wavBytes);
        return createAcbWithPreview(audioData.sampleRate(), audioData.sampleCount(), awbBytes,
                loopStartSecond, loopEndSecond, templateAcb, warningSink);
    }

    /**
     * 核心实现：在给定模板字节上复刻 C# CreateAcbWithPreview 的全部字节语义。
     */
    private static byte[] createAcbWithPreview(int sampleRate, int sampleCount, byte[] awbBytes,
                                               double loopStartSecond, double loopEndSecond,
                                               byte[] templateAcb, Consumer<String> warningSink) {
        Consumer<String> warn = warningSink == null ? w -> {
        } : warningSink;
        // SHA1(awb)
        byte[] sha1HashOfAwb = sha1(awbBytes);

        // 已知边界：官机对 NumSamples 字段为 0x00000000 的 ACB 拒绝读取音频并卡死
        // （对照实测：同 AWB 下官方输出 NumSamples 低字节 0xF0 可播、本输出 0x00 卡死）。
        // 按 C# 语义只写入 (byte)sampleCount，不改字节，但明确提示用户规避。
        if ((byte) sampleCount == 0) {
            warn.accept("音频总样本数 " + sampleCount
                    + " 的低字节为 0x00：按 C# 语义 (byte)sampleCount 写出的 WaveformTable.NumSamples"
                    + " 将为 0x00000000。经官机对照实测，该字段为 0 会导致游戏拒读音频并卡死主进程。"
                    + "请对音频末尾裁剪/补零使总样本数 mod 256 != 0 后再生成"
                    + "（例如将 5,755,392 裁为 5,754,864 样本，与官方参考 track_right.acb 逐字节一致）。");
        }

        CriTable criTable = new CriTable();
        criTable.load(templateAcb);

        CriTable cueTable = getTable(criTable.rows().get(0), "CueTable");
        float audioLength = (float) sampleCount / (float) sampleRate;
        cueTable.rows().get(0).set("Length", (int) (audioLength * 1000));
        criTable.setWriterSettings(CriTableWriterSettings.adx2());
        criTable.rows().get(0).set("CueTable", cueTable.save());

        CriTable trackEventTable = getTable(criTable.rows().get(0), "TrackEventTable");
        // 对 loopEnd 予以截断，防止 loopEnd 超过音频结尾
        if (loopEndSecond > audioLength) {
            loopEndSecond = audioLength;
        }
        trackEventTable.rows().get(1).set("Command", makeCommandForPreview(loopStartSecond, loopEndSecond));
        criTable.rows().get(0).set("TrackEventTable", trackEventTable.save());

        CriTable awbHashTable = getTable(criTable.rows().get(0), "StreamAwbHash");
        awbHashTable.rows().get(0).set("Hash", sha1HashOfAwb);
        criTable.rows().get(0).set("StreamAwbHash", awbHashTable.save());

        CriTable waveFormTable = getTable(criTable.rows().get(0), "WaveformTable");
        waveFormTable.rows().get(0).set("SamplingRate", (short) sampleRate);
        waveFormTable.rows().get(0).set("NumSamples", (byte) sampleCount);
        criTable.rows().get(0).set("WaveformTable", waveFormTable.save());

        // 修复歌曲持续时间：从 AFS2 头计算要保留的头部长度
        int count = littleEndianInt32(awbBytes, 8) + 1;
        int headSize = 16 + (awbBytes[5] & 0xFF) * count + (awbBytes[6] & 0xFF) * count + (awbBytes[7] & 0xFF) * count;
        byte[] header = Arrays.copyOf(awbBytes, headSize);
        CriTable streamAwbAfs2Header = getTable(criTable.rows().get(0), "StreamAwbAfs2Header");
        streamAwbAfs2Header.rows().get(0).set("Header", header);
        criTable.rows().get(0).set("StreamAwbAfs2Header", streamAwbAfs2Header.save());

        criTable.setWriterSettings(CriTableWriterSettings.adx2());
        return criTable.save();
    }

    /**
     * 读取嵌套表的字节内容生成子 CriTable，对应 C# 扩展方法 GetTable(row, name)。
     */
    public static CriTable getTable(CriRow row, String name) {
        CriTable table = new CriTable();
        table.load((byte[]) row.get(name));
        return table;
    }

    /**
     * 构造预览轨道事件命令字节，对应 C# MakeCommandForPreview：
     * <pre>
     * [3,231,4] BE(startMs) [7,208,4,0,2,0,1,7,209,4] BE(endMs) [15,160,0,0,0,0]
     * </pre>
     */
    public static byte[] makeCommandForPreview(double loopStartSecond, double loopEndSecond) {
        long startMs = (long) (loopStartSecond * 1000.0);
        long endMs = (long) (loopEndSecond * 1000.0);

        byte[] cmd = new byte[27];
        int p = 0;
        cmd[p++] = 3;
        cmd[p++] = (byte) 231;
        cmd[p++] = 4;
        p += writeUInt32BE(cmd, p, (int) (startMs & 0xFFFFFFFFL));
        cmd[p++] = 7;
        cmd[p++] = (byte) 208;
        cmd[p++] = 4;
        cmd[p++] = 0;
        cmd[p++] = 2;
        cmd[p++] = 0;
        cmd[p++] = 1;
        cmd[p++] = 7;
        cmd[p++] = (byte) 209;
        cmd[p++] = 4;
        p += writeUInt32BE(cmd, p, (int) (endMs & 0xFFFFFFFFL));
        cmd[p++] = 15;
        cmd[p++] = (byte) 160;
        cmd[p++] = 0;
        cmd[p++] = 0;
        cmd[p++] = 0;
        cmd[p++] = 0;
        return cmd;
    }

    /** 大端写入 4 字节，返回写入字节数。 */
    private static int writeUInt32BE(byte[] b, int off, int value) {
        b[off] = (byte) (value >>> 24);
        b[off + 1] = (byte) (value >>> 16);
        b[off + 2] = (byte) (value >>> 8);
        b[off + 3] = (byte) value;
        return 4;
    }

    /** 小端 int32，对应 C# BitConverter.ToInt32(bytes, 8)。 */
    private static int littleEndianInt32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | (b[off + 1] & 0xFF) << 8
                | (b[off + 2] & 0xFF) << 16
                | (b[off + 3] & 0xFF) << 24;
    }

    private static byte[] sha1(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return md.digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 not available", e);
        }
    }
}