package com.maiawb.cli;

import com.maiawb.core.AwbNormalizer;
import com.maiawb.core.CriUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 命令行入口（调试/脚本化使用）。
 * <p>
 * 用法：java -m com.maiawb/com.maiawb.cli.Main &lt;awb&gt; &lt;out.acb&gt; [loopStart秒] [loopEnd秒] [--verify]
 * <p>
 * 行为：
 * <ol>
 *   <li>读取输入 AWB；用 {@link AwbNormalizer} 规范化为官机 count=1 布局（音频位于流 id0），并写出
 *       <b>outBase.awb</b>；</li>
 *   <li>从 AWB 内 HCA 头提取音频参数（采样率 / HCA 解码总样本数 / 声道，见
 *       {@link CriUtils#extractAudioSpec}）——这就是“从 AWB 提取 WAV”，因 ACB 元数据（Length /
 *       NumSamples / 循环截断）只用采样率与样本数，无需实际 PCM 解码；</li>
 *   <li>以提取的采样参数 + 规范化 AWB 生成 ACB（模板固定内置 templateV3.acb），写出 <b>out.acb</b>。
 *       ACB 的 StreamAwbHash / StreamAwbAfs2Header 与所写出的 AWB 严格配对。</li>
 * </ol>
 * 不需要也不提供音频文件输入。
 */
public final class Main {

    private static final Pattern ACB_EXT = Pattern.compile("(?i)\\.acb$");

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.err.println("用法: Main <awb> <out.acb> [loopStartSec] [loopEndSec] [--verify]");
            System.exit(2);
        }
        Path awb = Path.of(args[0]);
        Path out = Path.of(args[1]);
        boolean verify = false;
        List<String> rest = new ArrayList<>();
        for (int i = 2; i < args.length; i++) {
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

        // 2) 从 AWB 提取音频参数（等价 WAV 头信息）
        CriUtils.AudioSpec spec = CriUtils.extractAudioSpec(norm.payload());
        System.out.printf("[audio] 已从 AWB 提取音频：%d Hz / %d 声道 / %d 样本 / %.3f s%n",
                spec.sampleRate(), spec.channels(), spec.sampleCount(), spec.duration());

        // 3) 生成 ACB（模板固定内置 templateV3.acb）
        long t0 = System.currentTimeMillis();
        byte[] acb = CriUtils.createAcbWithPreview(spec.sampleRate(), spec.sampleCount(), norm.awb(),
                start, end, w -> System.err.println("[警告] " + w));
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