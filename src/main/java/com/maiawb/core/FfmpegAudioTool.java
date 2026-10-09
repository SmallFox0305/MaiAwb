package com.maiawb.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 系统 ffmpeg 集成：
 * <ol>
 *   <li>{@link #findFfmpeg()}：在 PATH 与常见安装路径中定位 ffmpeg；</li>
 *   <li>{@link #installGuidance()}：未安装时的分平台安装指引；</li>
 *   <li>{@link #toWav(String, Path, int, int, Path, Consumer)}：把任意 ffmpeg 可解码格式
 *       转码为 PCM16 WAV（强制声道数与采样率，与 AWB 内 HCA 对齐）。</li>
 * </ol>
 */
public final class FfmpegAudioTool {

    private static final long DETECT_TIMEOUT_SECONDS = 8;
    private static final long CONVERT_TIMEOUT_SECONDS = 600;

    private FfmpegAudioTool() {
    }

    /** 在 PATH 或常见安装路径中查找 ffmpeg；均找不到返回 {@code null}。 */
    public static String findFfmpeg() {
        if (probe("ffmpeg", "-version")) {
            return "ffmpeg";
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        List<String> candidates = new ArrayList<>();
        if (os.contains("win")) {
            String prog = System.getenv("ProgramFiles");
            String prog86 = System.getenv("ProgramFiles(x86)");
            String local = System.getenv("LOCALAPPDATA");
            String user = System.getProperty("user.home");
            if (prog != null) candidates.add(prog + "\\ffmpeg\\bin\\ffmpeg.exe");
            if (prog86 != null) candidates.add(prog86 + "\\ffmpeg\\bin\\ffmpeg.exe");
            if (local != null) candidates.add(local + "\\Microsoft\\WinGet\\Links\\ffmpeg.exe");
            if (user != null) {
                candidates.add(user + "\\scoop\\shims\\ffmpeg.exe");
                candidates.add(user + "\\AppData\\Local\\Microsoft\\WinGet\\Links\\ffmpeg.exe");
            }
            candidates.add("C:\\ffmpeg\\bin\\ffmpeg.exe");
            candidates.add("C:\\ProgramData\\chocolatey\\bin\\ffmpeg.exe");
        } else {
            candidates.add("/usr/bin/ffmpeg");
            candidates.add("/usr/local/bin/ffmpeg");
            if (os.contains("mac")) {
                candidates.add("/opt/homebrew/bin/ffmpeg");
            }
        }
        for (String c : candidates) {
            Path p = Path.of(c);
            if (Files.isRegularFile(p) && Files.isExecutable(p)) {
                return c;
            }
        }
        return null;
    }

    /** 尝试运行 {@code cmd arg} 并在 8 秒内成功退出 0。 */
    private static boolean probe(String cmd, String arg) {
        try {
            Process p = new ProcessBuilder(cmd, arg).redirectErrorStream(true).start();
            if (p.waitFor(DETECT_TIMEOUT_SECONDS, TimeUnit.SECONDS) && p.exitValue() == 0) {
                return true;
            }
            p.destroy();
        } catch (Exception ignored) {
            // 未安装 / 无权运行等：视为未找到
        }
        return false;
    }

    /** 分平台的安装引导文案（供 GUI 对话框 / CLI stderr 直接展示）。 */
    public static String installGuidance() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return "未检测到系统 ffmpeg。\n"
                    + "安装方式（任选其一）：\n"
                    + "  1) winget install ffmpeg      （Windows 10/11 自带 winget 可直接执行）\n"
                    + "  2) 前往 https://www.gyan.dev/ffmpeg/builds/ 下载 release 完整包，\n"
                    + "     解压后把 bin 目录加入 PATH 并重启终端\n"
                    + "  3) 或下载 compatible 精简包同样解压配置\n"
                    + "完成后重新运行本工具即可。";
        }
        if (os.contains("mac")) {
            return "未检测到系统 ffmpeg。\n"
                    + "安装方式：brew install ffmpeg   （已装 Homebrew 时）";
        }
        if (os.contains("linux")) {
            return "未检测到系统 ffmpeg。\n"
                    + "安装方式：sudo apt install ffmpeg   （Debian/Ubuntu）或\n"
                    + "           sudo dnf install ffmpeg   （Fedora）";
        }
        return "未检测到系统 ffmpeg，请安装后重试。";
    }

    /**
     * 把任意音频转码为 PCM16 WAV，强制声道数与采样率（与 AWB 内 HCA 对齐）。
     *
     * @param ffmpeg     {@link #findFfmpeg()} 找到的 ffmpeg 可执行路径
     * @param input      源音频（任意 ffmpeg 支持的格式）
     * @param sampleRate 目标采样率（HCA 采样率）
     * @param channels   目标声道数（HCA 声道数）
     * @param outWav     输出 WAV 路径（调用方创建临时文件）
     * @param log        进度日志；可为 {@code null}
     * @return 输出 WAV 路径
     * @throws IOException ffmpeg 缺失、转码失败时抛出（消息含安装引导）
     */
    public static Path toWav(String ffmpeg, Path input, int sampleRate, int channels, Path outWav,
                             Consumer<String> log) throws IOException, InterruptedException {
        if (ffmpeg == null || ffmpeg.isBlank()) {
            throw new IOException("未检测到系统 ffmpeg。\n" + installGuidance());
        }
        if (!Files.isRegularFile(input)) {
            throw new IOException("输入音频文件不存在：" + input);
        }
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y", "-hide_banner", "-loglevel", "error",
                "-i", input.toString(),
                "-map_metadata", "-1",
                "-vn",
                "-ac", Integer.toString(channels),
                "-ar", Integer.toString(sampleRate),
                "-c:a", "pcm_s16le",
                "-f", "wav",
                outWav.toString()));

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (!p.waitFor(CONVERT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IOException("ffmpeg 转码超时（>10 分钟）。");
        }
        if (p.exitValue() != 0) {
            throw new IOException("ffmpeg 转码失败（exit=" + p.exitValue() + "）。\n"
                    + tail(output) + "\n" + installGuidance());
        }
        if (log != null) {
            log.accept("ffmpeg 已转码：" + input.getFileName() + " → " + sampleRate + " Hz / "
                    + channels + " 声道 / PCM16 WAV。");
        }
        return outWav;
    }

    private static String tail(String s) {
        String t = s == null ? "" : s.trim();
        if (t.isEmpty()) {
            return "（ffmpeg 无输出）";
        }
        String[] lines = t.split("\\R");
        int from = Math.max(0, lines.length - 6);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < lines.length; i++) {
            sb.append("  ").append(lines[i]).append('\n');
        }
        return sb.toString();
    }
}