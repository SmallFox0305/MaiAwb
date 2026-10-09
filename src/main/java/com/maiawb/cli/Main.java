package com.maiawb.cli;

import com.maiawb.core.CriUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 命令行入口（调试/脚本化使用）。
 * <p>
 * 用法：java -m com.maiawb/com.maiawb.cli.Main &lt;wav&gt; &lt;awb&gt; &lt;out.acb&gt; [loopStart秒] [loopEnd秒] [template.acb?]
 */
public final class Main {

    public static void main(String[] args) throws IOException {
        if (args.length < 3) {
            System.err.println("用法: Main <wav> <awb> <out.acb> [loopStartSec] [loopEndSec] [template.acb]");
            System.exit(2);
        }
        Path wav = Path.of(args[0]);
        Path awb = Path.of(args[1]);
        Path out = Path.of(args[2]);
        boolean verify = false;
        java.util.List<String> rest = new java.util.ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if ("--verify".equals(args[i])) {
                verify = true;
            } else {
                rest.add(args[i]);
            }
        }
        double start = rest.size() > 0 ? Double.parseDouble(rest.get(0)) : 10.0;
        double end = rest.size() > 1 ? Double.parseDouble(rest.get(1)) : 15.0;
        Path template = rest.size() > 2 ? Path.of(rest.get(2)) : null;

        byte[] wavBytes = Files.readAllBytes(wav);
        byte[] awbBytes = Files.readAllBytes(awb);

        long t0 = System.currentTimeMillis();
        byte[] acb = CriUtils.createAcbWithPreview(wavBytes, awbBytes, start, end, template,
                w -> System.err.println("[警告] " + w));
        long ms = System.currentTimeMillis() - t0;

        Files.write(out, acb);
        System.out.println("OK: " + out + " (" + acb.length + " bytes, " + ms + " ms)");

        if (verify) {
            verify(out);
        }
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