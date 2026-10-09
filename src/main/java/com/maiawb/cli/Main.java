package com.maiawb.cli;

import com.maiawb.core.AwbNormalizer;
import com.maiawb.core.CriUtils;
import com.maiawb.core.FfmpegAudioTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令行入口（调试/脚本化使用）。
 * <p>
 * 用法：java -m com.maiawb/com.maiawb.cli.Main &lt;音频&gt; &lt;awb&gt; &lt;out.acb&gt; [loopStart秒] [loopEnd秒] [--verify]
 * <p>
 * 行为：
 * <ol>
 *   <li>读取输入 AWB；用 {@link AwbNormalizer} 规范化为官机 count=1 布局（音频位于流 id0），并写出
 *       <b>outBase.awb</b>；</li>
 *   <li>用系统 ffmpeg 把任意格式音频（wav/mp3/flac/ogg/m4a/opus…）转码为 PCM16 WAV（采样率/声道
 *       与 AWB 内 HCA 对齐）；未检测到 ffmpeg 时给出安装引导并退出；</li>
 *   <li>以转码后的 WAV + 规范化 AWB 生成 ACB（模板固定内置 templateV3.acb，不支持自定义模板），写出
 *       <b>out.acb</b>。ACB 的 StreamAwbHash / StreamAwbAfs2Header 与所写出的 AWB 严格配对。</li>
 * </ol>
 */
public final class Main {

    private static final Pattern ACB_EXT = Pattern.compile("(?i)\\.acb$");

    public static void main(String[] args) throws IOException, InterruptedException {
        if (args.length < 3) {
            System.err.println("用法: Main <音频> <awb> <out.acb> [loopStartSec] [loopEndSec] [--verify]");
            System.err.println("      音频可为任意 ffmpeg 支持的格式（需系统 ffmpeg）");
            System.exit(2);
        }
        Path audio = Path.of(args[0]);
        Path awb = Path.of(args[1]);
        Path out = Path.of(args[2]);
        boolean verify = false;
        List<String> rest = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if ("--verify".equals(args[i])) {
                verify = true;
            } else {
                rest.add(args[i]);
            }
        }
        double start = rest.size() > 0 ? Double.parseDouble(rest.get(0)) : 10.0;
        double end = rest.size() > 1 ? Double.parseDouble(rest.get(1)) : 15.0;

        byte[] awbBytes = Files.readAllBytes(awb);

        // 1) 规范化 AWB 为官机 count=1 布局并写出配对 .awb
        AwbNormalizer.Result norm = AwbNormalizer.normalize(awbBytes, w -> System.err.println("[警告] " + w));
        System.out.println("[awb] " + norm.note());
        Path awbOut = deriveAwbOut(out);
        Files.write(awbOut, norm.awb());
        System.out.println("OK-awb: " + awbOut + " (" + norm.awb().length + " bytes"
                + (norm.changed() ? ", 已规范化" : "") + ")");

        // 2) 系统 ffmpeg 转码任意音频 → PCM16 WAV（采样率/声道与 AWB 内 HCA 对齐）
        String ffmpeg = FfmpegAudioTool.findFfmpeg();
        if (ffmpeg == null) {
            System.err.println("[错误] " + FfmpegAudioTool.installGuidance());
            System.exit(3);
        }
        AwbNormalizer.HcaParams hca = AwbNormalizer.probeHca(norm.payload());
        int sampleRate = hca != null ? hca.sampleRate() : 44100;
        int channels = hca != null ? hca.channels() : 2;
        if (hca == null) {
            System.err.println("[警告] 无法从 AWB 读取 HCA 采样参数，按 44100 Hz / 立体声转码（官机模板默认）。");
        }
        Path tempWav = Files.createTempFile("maiawb_", ".wav");
        byte[] wavBytes;
        try {
            FfmpegAudioTool.toWav(ffmpeg, audio, sampleRate, channels, tempWav,
                    w -> System.out.println("[ffmpeg] " + w));
            wavBytes = Files.readAllBytes(tempWav);
        } finally {
            Files.deleteIfExists(tempWav);
        }

        // 3) 生成 ACB（模板固定内置 templateV3.acb）
        long t0 = System.currentTimeMillis();
        byte[] acb = CriUtils.createAcbWithPreview(wavBytes, norm.awb(), start, end,
                w -> System.err.println("[警告] " + w));
        long ms = System.currentTimeMillis() - t0;

        Files.write(out, acb);
        System.out.println("OK: " + out + " (" + acb.length + " bytes, " + ms + " ms)");

        if (verify) {
            verify(out);
        }
    }

    /** 由 out.acb 推导配对 AWB 路径（outBase.awb）。 */
    static Path deriveAwbOut(Path out) {
        String name = out.getFileName().toString();
        String base = ACB_EXT.matcher(name).replaceFirst("");
        return out.resolveSibling(base + ".awb");
    }

    /** 用本工具的 CriTable 读写回环校验输出 ACB 的关键字段。 */
    private static void verify(Path out) throws IOException {
        com.maiawb.core.cri.CriTable root = new com.maiawb.core.cri.CriTable();
        root.load(Files.readAllBytes(out));
        System.out.println("[verify] root tableName=" + root.tableName()
                + " fields=" + root.fields().size()
                + " rows=" + root.rowCount());

        var row0 = root.rows().get(0);
        System.out.println("[verify]   CueTable.Length = "
                + ((com.maiawb.core.cri.CriTable) com.maiawb.core.CriUtils.getTable(row0, "CueTable"))
                        .rows().get(0).get("Length"));
        var tet = com.maiawb.core.CriUtils.getTable(row0, "TrackEventTable");
        byte[] cmd = (byte[]) tet.rows().get(1).get("Command");
        System.out.println("[verify]   TrackEventTable.Command[" + cmd.length + "] = "
                + java.util.HexFormat.of().formatHex(cmd));
        System.out.println("[verify]   StreamAwbHash.Hash = "
                + java.util.HexFormat.of().formatHex(
                        (byte[]) com.maiawb.core.CriUtils.getTable(row0, "StreamAwbHash").rows().get(0).get("Hash")));
        var wft = com.maiawb.core.CriUtils.getTable(row0, "WaveformTable");
        short sr = (Short) wft.rows().get(0).get("SamplingRate");
        System.out.println("[verify]   WaveformTable.SamplingRate(bytes) = "
                + String.format("0x%04X", sr & 0xFFFF) + " (" + (sr & 0xFFFF) + " Hz)");
        int numSamples = (Integer) wft.rows().get(0).get("NumSamples");
        System.out.println("[verify]   WaveformTable.NumSamples   = "
                + String.format("0x%08X", numSamples) + "  <- 只有低字节由 (byte)sampleCount 写入，语义符合 C#");
        byte[] hdr = (byte[]) com.maiawb.core.CriUtils.getTable(row0, "StreamAwbAfs2Header").rows().get(0).get("Header");
        System.out.println("[verify]   StreamAwbAfs2Header.Header = " + hdr.length + " bytes");
    }
}