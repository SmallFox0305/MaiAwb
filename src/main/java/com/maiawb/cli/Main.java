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
 * 用法：java -m com.maiawb/com.maiawb.cli.Main &lt;wav&gt; &lt;awb&gt; &lt;out.acb&gt; [loopStart秒] [loopEnd秒] [--verify]
 * <p>
 * 行为：
 * <ol>
 *   <li>读取输入 AWB；用 {@link AwbNormalizer} 规范化为官机 count=1 布局（音频位于流 id0），并写出
 *       <b>outBase.awb</b>；</li>
 *   <li>以规范化后的 AWB 生成 ACB（模板固定内置 templateV3.acb，不支持自定义模板），写出
 *       <b>out.acb</b>。ACB 的 StreamAwbHash / StreamAwbAfs2Header 与所写出的 AWB 严格配对。</li>
 * </ol>
 */
public final class Main {

    private static final Pattern ACB_EXT = Pattern.compile("(?i)\\.acb$");

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: Main <wav> <awb> <out.acb> [loopStartSec] [loopEndSec] [--verify]");
            System.exit(2);
        }
        Path wav = Path.of(args[0]);
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

        byte[] wavBytes = Files.readAllBytes(wav);
        byte[] awbBytes = Files.readAllBytes(awb);

        // 1) 规范化 AWB 为官机 count=1 布局并写出配对 .awb
        AwbNormalizer.Result norm = AwbNormalizer.normalize(awbBytes, w -> System.err.println("[警告] " + w));
        System.out.println("[awb] " + norm.note());
        Path awbOut = deriveAwbOut(out);
        Files.write(awbOut, norm.awb());
        System.out.println("OK-awb: " + awbOut + " (" + norm.awb().length + " bytes"
                + (norm.changed() ? ", 已规范化" : "") + ")");

        // 2) 生成 ACB（模板固定内置 templateV3.acb）
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