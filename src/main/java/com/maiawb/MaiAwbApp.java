package com.maiawb;

import com.maiawb.core.AwbNormalizer;
import com.maiawb.core.CriUtils;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;

/**
 * ACB 预览生成器 —— 极简工业风图形界面（白底黑字、直角硬边缘，尽量少装饰）。
 * 用法一致地复刻 MaiChartManager 的 CreateAcbWithPreview：
 * 用户选择自己做好的 AWB → 从 AWB 内 HCA 提取音频参数（等价 WAV）→ templateV3.acb
 * -> 输出 ACB + 配对规范化 AWB。无需也不接受音频文件输入。
 * 文件选择优先走系统原生对话框（Linux 下 kdialog=KDE/Dolphin 同款，其次 zenity），
 * 无原生工具时回退 JavaFX 默认选择器。
 */
public class MaiAwbApp extends Application {

    private final TextField awbField = new TextField();
    private final TextField loopStartField = new TextField("10.0");
    private final TextField loopEndField = new TextField("15.0");
    private final Label loopStateLabel = new Label();
    private final TextArea logArea = new TextArea();
    private final Label statusLabel = new Label();
    private final Button generateButton = new Button("生成并导出 ACB");

    private File lastDir;

    @Override
    public void start(Stage stage) {
        awbField.setPromptText("选择 AFS2 / AWB 音频容器（必选）");
        loopStartField.setPrefColumnCount(6);
        loopEndField.setPrefColumnCount(6);
        // 回车即确认，修改数值后重新标记
        loopStartField.setOnAction(e -> onConfirmLoop());
        loopEndField.setOnAction(e -> onConfirmLoop());
        loopStartField.textProperty().addListener((obs, a, b) -> markLoopDirty());
        loopEndField.textProperty().addListener((obs, a, b) -> markLoopDirty());

        stage.setTitle("MaiAwb 舞萌音频数据预览片段追加工具 v26.1-A");
        stage.setMinWidth(760);
        stage.setMinHeight(560);

        BorderPane root = new BorderPane();
        root.setTop(buildTitleBar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 820, 620);
        scene.getStylesheets().add(getClass().getResource("/tob.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        statusLabel.setText("就绪。");
        generateButton.setOnAction(e -> onGenerate());
        log("就绪。选择 AWB 音频文件与循环区间后生成 .acb + 配对 .awb。");
    }

    // ------------------------------------------------------------------

    private Region buildTitleBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(10, 14, 8, 14));
        Label brand = new Label("MaiAwb 舞萌音频数据预览片段追加工具");
        brand.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);
        Label ver = new Label("v26.1-A");
        ver.setStyle("-fx-text-fill: #444444;");
        bar.getChildren().addAll(brand, filler, ver);
        return bar;
    }

    private Region buildCenter() {
        VBox center = new VBox(12);
        center.setPadding(new Insets(14));

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(10);
        form.setAlignment(Pos.CENTER_LEFT);

        int row = 0;
        form.add(rowLabel("AWB 文件"), 0, row);
        form.add(awbField, 1, row);
        GridPane.setHgrow(awbField, Priority.ALWAYS);
        Button awbBrowse = browseButton("浏览…", () -> {
            File f = pickOpenRaw("选择 AFS2/AWB 音频", "AWB 文件(*.awb)", List.of("*.awb"));
            if (f != null) {
                awbField.setText(f.getAbsolutePath());
                lastDir = f.getParentFile();
            }
        });
        form.add(awbBrowse, 2, row);

        row++;
        form.add(rowLabel("循环区间"), 0, row);
        HBox loopBox = new HBox(6);
        loopBox.setAlignment(Pos.CENTER_LEFT);
        loopBox.getChildren().addAll(
                new Label("开始(秒)"), loopStartField,
                new Label("结束(秒)"), loopEndField);

        Button confirmLoopBtn = new Button("确认");
        confirmLoopBtn.setTooltip(new javafx.scene.control.Tooltip("校验并应用当前循环区间（回车亦可）"));
        confirmLoopBtn.setOnAction(e -> onConfirmLoop());
        loopStateLabel.setText("点击「确认」提交循环区间");
        loopStateLabel.getStyleClass().add("loop-pending");
        // 确认按钮与状态文字交换顺序，间距与行内其他控件一致（6px）：…结束(秒) [确认] [状态文字]
        loopBox.getChildren().addAll(confirmLoopBtn, loopStateLabel);
        form.add(loopBox, 1, row);
        GridPane.setHgrow(loopBox, Priority.ALWAYS);
        form.add(generateButton, 2, row);

        VBox logPanel = new VBox(6);
        logPanel.getChildren().add(new Label("日志"));
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.setPrefRowCount(10);
        // 清空日志按钮堆叠到日志框右下角
        StackPane logStack = new StackPane(logArea);
        Button clearLogBtn = new Button("清空日志");
        clearLogBtn.setOnAction(e -> logArea.clear());
        StackPane.setAlignment(clearLogBtn, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(clearLogBtn, new Insets(0, 8, 8, 0));
        logStack.getChildren().add(clearLogBtn);
        VBox.setVgrow(logStack, Priority.ALWAYS);
        logPanel.getChildren().add(logStack);

        center.getChildren().addAll(form, logPanel);
        VBox.setVgrow(logPanel, Priority.ALWAYS);
        return center;
    }

    private Stage stage() {
        return (Stage) logArea.getScene().getWindow();
    }

    private File lastDir() {
        return lastDir != null ? lastDir : new File(System.getProperty("user.dir"));
    }

    private Region buildStatusBar() {
        HBox bar = new HBox(8);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 14, 8, 14));
        statusLabel.getStyleClass().add("status-ok");
        bar.getChildren().add(statusLabel);
        return bar;
    }

    // ------------------------------------------------------------------

    private void onGenerate() {
        String awbPath = awbField.getText().trim();

        if (awbPath.isEmpty()) {
            flash("请先选择 AWB 音频文件", true);
            log("[错误] 请先选择 AWB 音频文件。", true);
            return;
        }

        double startSec;
        double endSec;
        try {
            startSec = Double.parseDouble(loopStartField.getText().trim());
            endSec = Double.parseDouble(loopEndField.getText().trim());
        } catch (NumberFormatException ex) {
            flash("循环区间必须是数字（秒）", true);
            log("[错误] 循环区间必须是数字（秒）。", true);
            return;
        }
        if (startSec < 0 || endSec <= startSec) {
            flash("循环区间无效：需要 0 <= 开始 < 结束", true);
            log("[错误] 循环区间无效：需要 0 ≤ 开始 < 结束。", true);
            return;
        }

        byte[] awbBytes;
        try {
            awbBytes = Files.readAllBytes(Path.of(awbPath));
        } catch (Exception ex) {
            flash("读取 AWB 失败", true);
            log("[错误] 读取 AWB 失败：" + ex.getMessage(), true);
            return;
        }

        flash("正在生成…", false);
        setBusy(true);

        Task<GenResult> task = new Task<>() {
            @Override
            protected GenResult call() throws Exception {
                // 1) 规范化为官机 count=1 布局（音频位于流 id0），保证与模板 ACB 的 StreamAwbId=0 配对自洽
                AwbNormalizer.Result norm = AwbNormalizer.normalize(awbBytes, w -> log("[警告] " + w, true));

                // 2) 从 AWB 内 HCA 提取音频参数（等价“提取 WAV”：采样率/样本数/时长，无需 PCM 解码）
                CriUtils.AudioSpec spec = CriUtils.extractAudioSpec(norm.payload());
                log(String.format("[音频] 已从 AWB 提取：%d Hz / %d 声道 / %d 样本 / %.3f s",
                        spec.sampleRate(), spec.channels(), spec.sampleCount(), spec.duration()), false);

                // 3) 生成 ACB（模板固定内置 templateV3.acb）
                byte[] acb = CriUtils.createAcbWithPreview(spec.sampleRate(), spec.sampleCount(), norm.awb(),
                        startSec, endSec, w -> log("[警告] " + w, true));
                return new GenResult(acb, norm);
            }
        };

        task.setOnSucceeded(e -> {
            setBusy(false);
            GenResult result = task.getValue();
            byte[] acb = result.acb();
            AwbNormalizer.Result norm = result.norm();
            log("[信息] AWB 处理：" + norm.note(), false);
            String suggested = suggestOutputName(awbPath);
            File out = pickSaveRaw("导出 ACB 文件", suggested, "ACB 文件(*.acb)", List.of("*.acb"));
            if (out == null) {
                flash("已生成，已取消保存", false);
                log("[提示] 转换完成（" + acb.length + " 字节），用户取消了保存。");
                return;
            }
            try {
                Files.write(out.toPath(), acb);
                // 连同规范化后的配对 AWB 一并导出（同基名 .awb），保证 ACB↔AWB 自洽
                Path awbOut = deriveAwbOut(out.toPath());
                Files.write(awbOut, norm.awb());
                lastDir = out.getParentFile();
                log("[完成] 已导出 " + out.getAbsolutePath());
                log("      大小 " + acb.length + " 字节，SHA1(" + HexFormat.of().formatHex(sha1(acb)) + ")");
                log("      配对 AWB（已规范化为官机 count=1 布局）→ " + awbOut);
                flash("导出完成 → " + out.getName() + "（含配对 .awb）", false);
            } catch (Exception ex) {
                flash("写入文件失败", true);
                log("[错误] 写入输出文件失败：" + ex.getMessage(), true);
            }
        });

        task.setOnFailed(e -> {
            setBusy(false);
            Throwable t = task.getException();
            flash("生成失败", true);
            log("[错误] 生成失败：" + rootMessage(t), true);
            log(stackTrace(t));
        });

        Thread th = new Thread(task, "acb-generator");
        th.setDaemon(true);
        th.start();
    }

    // ------------------------------------------------------------------

    private String suggestOutputName(String awbPath) {
        String name = Path.of(awbPath).getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return name + ".acb";
    }

    /** 由保存的 out.acb 推导配对 AWB 路径（outBase.awb）。 */
    private static Path deriveAwbOut(Path out) {
        String name = out.getFileName().toString();
        String base = name.replaceFirst("(?i)\\.acb$", "");
        return out.resolveSibling(base + ".awb");
    }

    /** 生成任务的结果：ACB 字节 + AWB 规范化结果（用于导出配对 .awb）。 */
    private record GenResult(byte[] acb, AwbNormalizer.Result norm) {
    }

    /** 确认（应用）循环区间：校验数值并给出状态反馈。超过音频结尾的截断由 CriUtils 按 C# 语义内部处理。 */
    private void onConfirmLoop() {
        double startSec;
        double endSec;
        try {
            startSec = Double.parseDouble(loopStartField.getText().trim());
            endSec = Double.parseDouble(loopEndField.getText().trim());
        } catch (NumberFormatException ex) {
            setLoopState("error", "循环区间必须是数字（秒）");
            flash("循环区间必须是数字（秒）", true);
            log("[错误] 循环区间必须是数字（秒）。", true);
            return;
        }
        if (startSec < 0 || endSec <= startSec) {
            setLoopState("error", "需要 0 ≤ 开始 < 结束");
            flash("循环区间无效：需要 0 ≤ 开始 < 结束", true);
            log("[错误] 循环区间无效：需要 0 ≤ 开始 < 结束。", true);
            return;
        }

        loopStartField.setText(formatSec(startSec));
        loopEndField.setText(formatSec(endSec));
        setLoopState("confirmed", "已确认  " + formatSec(startSec) + " → " + formatSec(endSec) + " s");
        flash("循环区间已确认", false);
        log(String.format("[确认] 循环区间  %s → %s 秒（总长 %.3f s）。",
                formatSec(startSec), formatSec(endSec), endSec - startSec));
    }

    private void setLoopState(String kind, String text) {
        loopStateLabel.getStyleClass().removeAll("loop-pending", "loop-confirmed", "loop-error");
        loopStateLabel.getStyleClass().add("loop-" + kind);
        loopStateLabel.setText(text);
    }

    /** 数值被修改后，撤销已确认状态。 */
    private void markLoopDirty() {
        if (loopStateLabel.getText() != null && loopStateLabel.getText().startsWith("已确认")) {
            setLoopState("pending", "值已变更，请重新确认");
        }
    }

    private static String formatSec(double v) {
        String s = String.format("%.3f", v);
        return s.replaceFirst("\\.?0+$", "");
    }

    // ------------------------------------------------------------------

    private void log(String msg) {
        log(msg, false);
    }

    private void log(String msg, boolean error) {
        Platform.runLater(() -> {
            logArea.appendText(msg + "\n");
        });
    }

    private void flash(String msg, boolean error) {
        Platform.runLater(() -> {
            statusLabel.getStyleClass().removeAll("status-ok", "status-err");
            statusLabel.getStyleClass().add(error ? "status-err" : "status-ok");
            statusLabel.setText(msg);
        });
    }

    private void setBusy(boolean busy) {
        generateButton.setDisable(busy);
        generateButton.setText(busy ? "生成中…" : "生成并导出 ACB");
    }

    // ------------------------------------------------------------------

    /** 打开选择器：优先原生（Linux 下 kdialog=KDE/Dolphin 同款，其次 zenity），无原生工具或异常时回退 JavaFX。 */
    private File pickOpenRaw(String title, String desc, List<String> patterns) {
        NativeFileDialog.NativeResult r = NativeFileDialog.open(lastDir(), title, desc, patterns,
                w -> log("[提示] " + w, false));
        if (r.supported()) {
            return r.file();
        }
        return pickOpenJavaFx(title, desc, patterns);
    }

    /** 另存为选择器：同上，优先原生。 */
    private File pickSaveRaw(String title, String initialName, String desc, List<String> patterns) {
        NativeFileDialog.NativeResult r = NativeFileDialog.save(lastDir(), initialName, title, desc, patterns,
                w -> log("[提示] " + w, false));
        if (r.supported()) {
            return r.file();
        }
        return pickSaveJavaFx(title, initialName, desc, patterns);
    }

    private File pickOpenJavaFx(String title, String desc, List<String> patterns) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        File dir = lastDir();
        if (dir.isDirectory()) {
            fc.setInitialDirectory(dir);
        }
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, String.join(";", patterns)));
        return fc.showOpenDialog(stage());
    }

    private File pickSaveJavaFx(String title, String initialName, String desc, List<String> patterns) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        File dir = lastDir();
        if (dir.isDirectory()) {
            fc.setInitialDirectory(dir);
        }
        fc.setInitialFileName(initialName);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, String.join(";", patterns)));
        return fc.showSaveDialog(stage());
    }

    private static Button browseButton(String text, Runnable action) {
        Button b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Label rowLabel(String text) {
        return new Label(text);
    }

    private static byte[] sha1(byte[] data) {
        try {
            return java.security.MessageDigest.getInstance("SHA-1").digest(data);
        } catch (Exception ex) {
            return new byte[0];
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m == null ? cur.getClass().getSimpleName() : m;
    }

    private static String stackTrace(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) {
        launch(args);
    }
}