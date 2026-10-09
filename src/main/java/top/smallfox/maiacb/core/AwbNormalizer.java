package top.smallfox.maiacb.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * AWB/AFS2 规范化器。
 * <p>
 * 按 CRI AFS2 语义（对照 SonicAudioTools/CriAfs2Archive 与 XV2-Tools/AFS2_File）解析输入的
 * AWB，提取首个非空流，输出与官机模板（music001451，count=1）逐字节同构的 AWB：
 * <pre>
 *   0x00 "AFS2" | 02 04 02 00 | 01 00 00 00 | 20 00 00 00 | 00 00 | 1a 00 00 00 | filesize | 6 个 00 | HCA 载荷
 * </pre>
 * 其中音频流位于流 id 0（WaveformTable.StreamAwbId=0 可直接命中）。
 * <p>
 * 背景：第三方工具（ACE/XV2-Tools）用 count=2 结构打包 AWB（流 0 空、流 1 为音频），而官机 ACB
 * 模板的 WaveformTable.StreamAwbId=0 指向流 0 → 官机读到空流即无音频（实测卡死）。本类把任何
 * AWB 规范化为官机主流的单流布局，保证 ACB/文件系统配对自洽。
 */
public final class AwbNormalizer {

    /** 规范 AFS2 头部长度（数据区从 0x20 对齐开始）。 */
    public static final int HEADER_SIZE = 0x20;

    /** 掩码后仍为 "HCA\0" 的 CRI keyed-HCA 魔数。 */
    private static final int MASKED_HCA_MAGIC = 0x48434100; // 'H'|'C'|'A'|0x00
    private static final int MAGIC_MASK = 0x7F7F7F7F;

    /** 规范 AFS2 头信息字节（类型=02、指针宽 4、id 宽 2），与官机模板一致。 */
    private static final byte INFO_TYPE = 0x02;
    private static final byte INFO_POINTER_SIZE = 0x04;
    private static final byte INFO_ID_SIZE = 0x02;

    private AwbNormalizer() {
    }

    public record Result(byte[] awb, boolean changed, String note, byte[] payload) {
    }

    /** HCA fmt 头关键参数（对照 vgmstream/clhca.c 的位读取顺序）。 */
    public record HcaParams(int channels, int sampleRate, long frameCount, int encoderDelay, int encoderPadding) {
    }

    /**
     * 解析并规范化 AWB。
     *
     * @param awb   原始 AFS2/AWB 字节
     * @param warn  警告回调；可为 {@code null}
     * @return 规范化后的 AWB（音频流位于 id0，count=1）+ 是否发生写改 + 说明
     */
    public static Result normalize(byte[] awb, Consumer<String> warn) {
        Consumer<String> sink = warn == null ? w -> {
        } : warn;
        try {
            Parse p = parse(awb);
            if (p.streams.isEmpty()) {
                sink.accept("AFS2 中没有任何非空流，未做规范化，将原样参与 ACB 生成（官机大概率无法播放）。");
                return new Result(awb, false, "AFS2 为空（无非空流）", new byte[0]);
            }

            StringBuilder detail = new StringBuilder();
            for (int i = 0; i < p.streams.size(); i++) {
                boolean hca = isMaskedHca(p.streams.get(i));
                if (i > 0) {
                    detail.append(", ");
                }
                detail.append("流").append(i).append(":").append(p.streams.get(i).length)
                        .append("B").append(hca ? " HCA" : " 非HCA魔数");
                if (!hca) {
                    sink.accept("AFS2 非空流 " + i + " 不以掩码 HCA 头起始（" + p.streams.get(i).length
                            + "B）：可能不是 CRI keyed-HCA，官机能否解码未知，本工具按原流搬运。");
                }
            }
            if (p.streams.size() > 1) {
                sink.accept("AFS2 含 " + p.streams.size() + " 个非空流，将仅提取首个非空流（其余忽略）。");
            }

            byte[] out = buildCountOne(p.streams.get(0));
            boolean changed = !Arrays.equals(out, awb);
            String note = (changed
                    ? "AWB 已规范化为官机 count=1 布局（音频位于流 id0；输入为 " + p.count + " 流结构）"
                    : "AWB 已是规范 count=1 布局，原样使用")
                    + "；非空流量=" + p.streams.size() + "（" + detail + "）";
            return new Result(out, changed, note, p.streams.get(0));
        } catch (Exception ex) {
            sink.accept("AFS2 解析失败（" + ex.getMessage() + "），未做规范化，将原样参与 ACB 生成。");
            return new Result(awb, false, "AFS2 解析失败：" + ex.getMessage(), new byte[0]);
        }
    }

    /**
     * 从 HCA 载荷解析 fmt 头关键参数（供 ffmpeg 转码对齐采样率/声道）。
     * <p>
     * 偏移严格对照 vgmstream clhca.c：@0x04 version(u16BE)、@0x06 header_size(u16BE)、
     * @0x08 "fmt\\0"（掩码）、@0x0C channels(u8)、@0x0D sample_rate(u24BE)、
     * @0x10 frame_count(u32BE)、@0x14 encoder_delay(u16BE)、@0x16 encoder_padding(u16BE)。
     *
     * @return 解析成功返回参数；非 HCA 或字段越界返回 {@code null}
     */
    public static HcaParams probeHca(byte[] payload) {
        if (!isMaskedHca(payload) || payload.length < 0x18) {
            return null;
        }
        int version = readBe16(payload, 4);
        int headerSize = readBe16(payload, 6);
        if (headerSize <= 0 || headerSize > payload.length || (version & 0xFF00) == 0) {
            return null;
        }
        int channels = payload[0x0C] & 0xFF;
        int rate = readBe24(payload, 0x0D);
        long frames = readBe32(payload, 0x10);
        int delay = readBe16(payload, 0x14);
        int pad = readBe16(payload, 0x16);
        if (channels < 1 || channels > 8 || rate < 8000 || rate > 192000 || frames <= 0) {
            return null;
        }
        return new HcaParams(channels, rate, frames, delay, pad);
    }

    private static int readBe16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readBe24(byte[] b, int off) {
        return ((b[off] & 0xFF) << 16) | ((b[off + 1] & 0xFF) << 8) | (b[off + 2] & 0xFF);
    }

    private static long readBe32(byte[] b, int off) {
        return ((long) (b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    /** 判断载荷是否以掩码 HCA 魔数起始（CRI keyed-HCA）。 */
    public static boolean isMaskedHca(byte[] data) {
        if (data.length < 8) {
            return false;
        }
        return (readBe32(data, 0) & MAGIC_MASK) == MASKED_HCA_MAGIC;
    }

    /**
     * 构建官机 count=1 布局 AWB：
     * <pre>
     *   AFS2 | 02 04 02 00 | 01 00 00 00 | 20 00 00 00 | 00 00 | 1a 00 00 00 | filesize(u32le) | 6×00
     *   + payload（音频从 0x20 对齐起始）
     * </pre>
     * 与官机模板 music001451 AWB 头逐字节同构（仅文件大小随载荷变化）。
     */
    static byte[] buildCountOne(byte[] payload) {
        int fileSize = HEADER_SIZE + payload.length;
        byte[] out = new byte[fileSize];
        out[0] = 'A';
        out[1] = 'F';
        out[2] = 'S';
        out[3] = '2';
        out[4] = INFO_TYPE;
        out[5] = INFO_POINTER_SIZE;
        out[6] = INFO_ID_SIZE;
        out[7] = 0x00;
        writeLe32(out, 0x08, 1);          // count = 1
        writeLe32(out, 0x0C, 0x20);       // offset_alignment
        out[0x10] = 0x00;                 // id0 低字节
        out[0x11] = 0x00;                 // id0 高字节
        writeLe32(out, 0x12, 0x1A);       // 数据偏移（0x1A，向上对齐到 0x20）
        writeLe32(out, 0x16, fileSize);   // 末指针 = 文件大小
        // 0x1A..0x1F 为 0（对齐填充）
        System.arraycopy(payload, 0, out, HEADER_SIZE, payload.length);
        return out;
    }

    /** 按 CRI AFS2 布局解析 AWB，提取各流（长度 &gt; 0 的条目）。 */
    private static Parse parse(byte[] b) {
        if (b.length < 0x20 || b[0] != 'A' || b[1] != 'F' || b[2] != 'S' || b[3] != '2') {
            throw new IllegalArgumentException("不是 AFS2 魔数");
        }
        int idSize = b[6] & 0xFF;
        int pointerSize = b[5] & 0xFF;
        if (idSize != 2 && idSize != 4) {
            throw new IllegalArgumentException("不支持的 AFS2 id 宽 " + idSize);
        }
        if (pointerSize != 2 && pointerSize != 4 && pointerSize != 8) {
            throw new IllegalArgumentException("不支持的 AFS2 指针宽 " + pointerSize);
        }
        int count = readLe32(b, 0x08);
        int alignment = readLe32(b, 0x0C);
        if (count < 0 || count > 256) {
            throw new IllegalArgumentException("非法流数量 " + count);
        }
        if (alignment <= 0) {
            alignment = 1;
        }

        List<byte[]> streams = new ArrayList<>();
        int pointerList = 0x10 + count * idSize;
        long[] pointers = new long[count + 1];
        for (int i = 0; i < count + 1; i++) {
            pointers[i] = readLeInt(b, pointerList + i * pointerSize, pointerSize);
        }
        for (int i = 0; i < count; i++) {
            long start = alignUp(pointers[i], alignment);
            long end = pointers[i + 1];          // 与 XV2/SonicAudioTools 一致：末尾用原始值
            long len = end - start;
            if (len <= 0 || start >= b.length) {
                continue;
            }
            len = Math.min(len, b.length - start);
            streams.add(Arrays.copyOfRange(b, (int) start, (int) (start + len)));
        }
        return new Parse(count, streams);
    }

    private record Parse(int count, List<byte[]> streams) {
    }

    private static long alignUp(long v, long align) {
        long r = (v + align - 1) / align * align;
        return Math.max(r, v);
    }

    private static int readLe32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static long readLeInt(byte[] b, int off, int width) {
        long v = 0;
        for (int i = 0; i < width; i++) {
            v |= (long) (b[off + i] & 0xFF) << (8 * i);
        }
        return v;
    }

    private static void writeLe32(byte[] b, int off, int v) {
        b[off] = (byte) (v & 0xFF);
        b[off + 1] = (byte) ((v >>> 8) & 0xFF);
        b[off + 2] = (byte) ((v >>> 16) & 0xFF);
        b[off + 3] = (byte) ((v >>> 24) & 0xFF);
    }
}