package top.smallfox.maiacb;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Linux 原生文件对话框桥接（ui/toB 工业风的一部分）。
 * <p>
 * 优先级：
 * <ol>
 *   <li><b>kdialog</b>：KDE 系，Dolphin/KFileWidget 同款文件对话框（KDE Plasma 桌面下的首选）；</li>
 *   <li><b>zenity</b>：GTK 系文件对话框；</li>
 *   <li>都没有 ── {@code supported()=false}，由调用方回退 JavaFX 默认选择器。</li>
 * </ol>
 * 仅在 Linux 上探测；Windows / macOS 直接回退 JavaFX。对话框本身是模态的，调用方阻塞等待结果即可。
 */
final class NativeFileDialog {

    private enum Tool { NONE, KDIALOG, ZENITY }

    private static volatile Tool cached;

    private NativeFileDialog() {
    }

    /**
     * 原生选择结果。
     *
     * @param supported 是否真的有原生工具可用（即使本次取消）；{@code false} 时应回退 JavaFX 选择器
     * @param file      选中的文件；用户取消时为 {@code null}
     */
    record NativeResult(boolean supported, File file) {
        static NativeResult none() {
            return new NativeResult(false, null);
        }
    }

    /** 选择单个已存在的文件（打开用）。 */
    static NativeResult open(File dir, String title, String desc, List<String> patterns, Consumer<String> info) {
        Tool t = detect();
        if (t == Tool.NONE) {
            if (info != null) info.accept("未检测到 kdialog/zenity，使用 JavaFX 默认选择器。");
            return NativeResult.none();
        }
        List<String> cmd = new ArrayList<>();
        String start = startDir(dir);
        if (t == Tool.KDIALOG) {
            cmd.add("kdialog");
            cmd.add("--title"); cmd.add(title);
            cmd.add("--getopenfilename");
            cmd.add(start);
            cmd.add(String.join(" ", patterns) + "|" + desc);
        } else {
            cmd.add("zenity");
            cmd.add("--file-selection");
            cmd.add("--title=" + title);
            cmd.add("--filename=" + start + "/");
            for (String p : patterns) {
                cmd.add("--file-filter=" + desc + " | " + p);
            }
        }
        return run(cmd, t, info);
    }

    /** 选择保存路径（另存用）。 */
    static NativeResult save(File dir, String initialName, String title, String desc,
                             List<String> patterns, Consumer<String> info) {
        Tool t = detect();
        if (t == Tool.NONE) {
            if (info != null) info.accept("未检测到 kdialog/zenity，使用 JavaFX 默认选择器。");
            return NativeResult.none();
        }
        List<String> cmd = new ArrayList<>();
        String start = startDir(dir);
        if (t == Tool.KDIALOG) {
            cmd.add("kdialog");
            cmd.add("--title"); cmd.add(title);
            cmd.add("--getsavefilename");
            cmd.add(start);
            cmd.add(String.join(" ", patterns) + "|" + desc);
        } else {
            String f = (initialName == null || initialName.isEmpty())
                    ? start + "/untitled.acb"
                    : start + "/" + initialName;
            cmd.add("zenity");
            cmd.add("--file-selection");
            cmd.add("--save");
            cmd.add("--confirm-overwrite");
            cmd.add("--title=" + title);
            cmd.add("--filename=" + f);
            for (String p : patterns) {
                cmd.add("--file-filter=" + desc + " | " + p);
            }
        }
        return run(cmd, t, info);
    }

    // ------------------------------------------------------------------

    private static String startDir(File dir) {
        if (dir != null) {
            return dir.getAbsolutePath();
        }
        return System.getProperty("user.dir", ".");
    }

    private static NativeResult run(List<String> cmd, Tool t, Consumer<String> info) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // zenity/kdialog 的 GTK/Qt 警告（如 "Failed to measure available space"）打 stderr，
            // 若与 stdout 合并会把警告串进选中路径；这里直接丢弃 stderr。
            pb.redirectError(ProcessBuilder.Redirect.DISCARD);
            Process p = pb.start();
            boolean done = p.waitFor(180, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                if (info != null) info.accept(toolName(t) + " 无响应（已强制结束），回退 JavaFX 选择器。");
                return NativeResult.none();
            }
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int code = p.exitValue();
            String path = lastLine(out);
            if (code == 0 && !path.isEmpty()) {
                if (info != null) info.accept("已使用 " + toolName(t) + " 原生选择器。");
                return new NativeResult(true, new File(path));
            }
            if (code == 0 || code == 1) {
                // 用户取消：不再回退 JavaFX，避免连弹两个对话框
                return new NativeResult(true, null);
            }
            if (info != null) info.accept(toolName(t) + " 异常退出（exit=" + code + "），回退 JavaFX 选择器。");
            return NativeResult.none();
        } catch (Exception ex) {
            if (info != null) info.accept(toolName(t) + " 不可用（" + ex.getMessage() + "），回退 JavaFX 选择器。");
            return NativeResult.none();
        }
    }

    /** 取最后一行非空内容作为对话框的返回值：防御外部程序把启动警告等噪音打进 stdout 的情形。 */
    private static String lastLine(String out) {
        String[] lines = out.split("\\r?\\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String s = lines[i].trim();
            if (!s.isEmpty()) {
                return s;
            }
        }
        return "";
    }

    private static String toolName(Tool t) {
        return t == Tool.KDIALOG ? "kdialog(KDE/Dolphin)" : "zenity(GTK)";
    }

    private static Tool detect() {
        Tool v = cached;
        if (v != null) {
            return v;
        }
        Tool found = Tool.NONE;
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("linux")) {
            if (available("kdialog")) {
                found = Tool.KDIALOG;
            } else if (available("zenity")) {
                found = Tool.ZENITY;
            }
        }
        cached = found;
        return found;
    }

    private static boolean available(String cmd) {
        try {
            Process p = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }
}