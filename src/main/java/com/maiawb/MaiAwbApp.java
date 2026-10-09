package com.maiawb;

import com.maiawb.core.AwbNormalizer;
import com.maiawb.core.CriUtils;
import com.maiawb.core.WaveFile;
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
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;

/**
 * ACB 预览生成器 —— 工业风格图形界面。
 * 用法一致地复刻 MaiChartManager 的 CreateAcbWithPreview：
 * AWB + WAV + 循环区间 -> templateV3.acb -> 输出 ACB。
 */
public class MaiAwbApp extends Application {

    private final TextField awbField = new TextField();
    private final TextField wavField = new TextField();
    private final TextField loopStartField = new TextField("10.0");
    private final TextField loopEndField = new TextField("15.0");
    private final Label loopStateLabel = new Label();
    private final TextArea logArea = new TextArea();
    private final Label statusLabel = new Label();
    private final Label led = new Label();
    private final Button generateButton = new Button("生成并导出 ACB");

    private File lastDir;

    @Override
    public void start(Stage stage) {
        awbField.setPromptText("选择 AFS2 / AWB 音频容器（必选）");
        awbField.getStyleClass().add("field");
        wavField.setPromptText("选择 PCM16 WAV 预览音频（必选）");
        wavField.getStyleClass().add("field");
        loopStartField.getStyleClass().addAll("field", "field-num");
        loopEndField.getStyleClass().addAll("field", "field-num");
        // 回车即确认，修改数值后重新标记
        loopStartField.setOnAction(e -> onConfirmLoop());
        loopEndField.setOnAction(e -> onConfirmLoop());
        loopStartField.textProperty().addListener((obs, a, b) -> markLoopDirty());
        loopEndField.textProperty().addListener((obs, a, b) -> markLoopDirty());

        stage.setTitle("MaiAwb — ACB Preview Generator v1.0.0");
        stage.setMinWidth(760);
        stage.setMinHeight(560);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root");
        root.setTop(buildTitleBar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 820, 620);
        scene.getStylesheets().add(getClass().getResource("/osc_theme.css").toExternalForm());
        stage.setScene(scene);
        stage.show();

        generateButton.setOnAction(e -> onGenerate(stage));
        log("就绪。等待输入 AWB 与 WAV 文件。");
    }

    // ------------------------------------------------------------------

    private Region buildTitleBar() {
        HBox bar = new HBox(10);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("title-bar");

        led.setStyle("-fx-background-color: #6fcf97;");
        led.getStyleClass().add("led");
        Label brand = new Label("MAI AWB · ACB PREVIEW GENERATOR");
        brand.getStyleClass().add("title-brand");
        Label sub = new Label("复刻 MaiChartManager::CriUtils.CreateAcbWithPreview");
        sub.getStyleClass().add("title-sub");
        Region filler = new Region();
        HBox.setHgrow(filler, Priority.ALWAYS);
        Label ver = new Label("v1.0.0 · JDK27 · JavaFX");
        ver.getStyleClass().add("title-sub");

        bar.getChildren().addAll(led, brand, filler, sub, ver);
        return bar;
    }

    private Region buildCenter() {
        VBox center = new VBox(12);
        center.setPadding(new Insets(14));

        // 输入区
        VBox inputPanel = new VBox(8);
        inputPanel.getStyleClass().add("panel");
        Label inputTitle = new Label("▸ 输入");
        inputTitle.getStyleClass().add("section-title");

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(8);
        form.setPadding(new Insets(4, 0, 0, 0));

        int row = 0;
        form.add(rowLabel("AWB 文件"), 0, row);
        form.add(awbField, 1, row);
        GridPane.setHgrow(awbField, Priority.ALWAYS);
        Button awbBrowse = browseButton("浏览…", () -> {
            File f = pickFile(stage(), "选择 AFS2/AWB 音频", "AWB 文件(*.awb)", "*.awb", lastDir());
            if (f != null) {
                awbField.setText(f.getAbsolutePath());
                lastDir = f.getParentFile();
            }
        });
        form.add(awbBrowse, 2, row);
        awbField.setEditable(true);

        row++;
        form.add(rowLabel("WAV 文件"), 0, row);
        form.add(wavField, 1, row);
        GridPane.setHgrow(wavField, Priority.ALWAYS);
        Button wavBrowse = browseButton("浏览…", () -> {
            File f = pickFile(stage(), "选择 PCM16 WAV 预览音频", "WAV 音频(*.wav)", "*.wav", lastDir());
            if (f != null) {
                wavField.setText(f.getAbsolutePath());
                lastDir = f.getParentFile();
                inspectWav(f);
            }
        });
        form.add(wavBrowse, 2, row);

        row++;
        form.add(rowLabel("循环区间"), 0, row);
        HBox loopBox = new HBox(6);
        loopBox.setAlignment(Pos.CENTER_LEFT);
        loopBox.getChildren().add(new Label("开始(秒)"));
        loopBox.getChildren().add(loopStartField);
        loopBox.getChildren().add(new Label("结束(秒)"));
        loopBox.getChildren().add(loopEndField);
        for (var n : loopBox.getChildren()) {
            if (n instanceof Label l) {
                l.getStyleClass().add("form-label");
            }
        }
        loopStateLabel.setText("点击「确认」提交循环区间");
        loopStateLabel.getStyleClass().add("loop-pending");
        loopBox.getChildren().add(loopStateLabel);
        form.add(loopBox, 1, row);
        GridPane.setHgrow(loopBox, Priority.ALWAYS);

        HBox loopActions = new HBox(6);
        loopActions.setAlignment(Pos.CENTER_LEFT);
        Button confirmLoopBtn = new Button("确认");
        confirmLoopBtn.getStyleClass().add("confirm-button");
        confirmLoopBtn.setTooltip(new javafx.scene.control.Tooltip("校验并应用当前循环区间（回车亦可）"));
        confirmLoopBtn.setOnAction(e -> onConfirmLoop());
        loopActions.getChildren().add(confirmLoopBtn);
        Button resumeBtn = browseButton("从 WAV 提取时长", () -> useWavDuration());
        loopActions.getChildren().add(resumeBtn);
        form.add(loopActions, 2, row);

        inputPanel.getChildren().addAll(inputTitle, form);

        // 按钮区
        HBox actionBar = new HBox(10);
        actionBar.setAlignment(Pos.CENTER_LEFT);
        actionBar.getChildren().add(generateButton);
        generateButton.getStyleClass().add("accent-button");
        Button clearLogBtn = new Button("清空日志");
        clearLogBtn.getStyleClass().add("action-button");
        clearLogBtn.setOnAction(e -> logArea.clear());
        actionBar.getChildren().add(clearLogBtn);

        // 日志区
        VBox logPanel = new VBox(6);
        logPanel.getStyleClass().add("panel");
        Label logTitle = new Label("▸ 日志");
        logTitle.getStyleClass().add("section-title");
        logArea.setEditable(false);
        logArea.setWrapText(true);
        logArea.getStyleClass().add("log-area");
        logArea.setPrefRowCount(10);
        VBox.setVgrow(logArea, Priority.ALWAYS);
        logPanel.getChildren().addAll(logTitle, logArea);

        center.getChildren().addAll(inputPanel, actionBar, logPanel);
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
        bar.getStyleClass().add("status-bar");
        statusLabel.getStyleClass().add("status-text");
        bar.getChildren().add(statusLabel);
        return bar;
    }

    // ------------------------------------------------------------------

    private void onGenerate(Stage stage) {
        String awbPath = awbField.getText().trim();
        String wavPath = wavField.getText().trim();

        if (awbPath.isEmpty() || wavPath.isEmpty()) {
            flash("请先选择 AWB 与 WAV 文件", true);
            log("[错误] AWB 与 WAV 均为必选输入。", true);
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
        byte[] wavBytes;
        try {
            awbBytes = Files.readAllBytes(Path.of(awbPath));
            wavBytes = Files.readAllBytes(Path.of(wavPath));
        } catch (Exception ex) {
            flash("读取文件失败", true);
            log("[错误] 读取输入文件失败：" + ex.getMessage(), true);
            return;
        }

        flash("正在生成…", false);
        setBusy(true);

        Task<GenResult> task = new Task<>() {
            @Override
            protected GenResult call() throws Exception {
                // 1) 规范化为官机 count=1 布局（音频位于流 id0），保证与模板 ACB 的 StreamAwbId=0 配对自洽
                AwbNormalizer.Result norm = AwbNormalizer.normalize(awbBytes, w -> log("[警告] " + w, true));
                // 2) 生成 ACB（模板固定内置 templateV3.acb）
                byte[] acb = CriUtils.createAcbWithPreview(wavBytes, norm.awb(), startSec, endSec,
                        w -> log("[警告] " + w, true));
                return new GenResult(acb, norm);
            }
        };

        task.setOnSucceeded(e -> {
            setBusy(false);
            GenResult result = task.getValue();
            byte[] acb = result.acb();
            AwbNormalizer.Result norm = result.norm();
            log("[信息] AWB 处理：" + norm.note(), false);
            String suggested = suggestOutputName(wavPath);

            FileChooser fc = new FileChooser();
            fc.setTitle("导出 ACB 文件");
            fc.setInitialDirectory(lastDir());
            fc.setInitialFileName(suggested);
            fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("ACB 文件(*.acb)", "*.acb"));
            fxIgnored(fc);
            File out = fc.showSaveDialog(stage);
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

    private String suggestOutputName(String wavPath) {
        String name = Path.of(wavPath).getFileName().toString();
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

    private void inspectWav(File f) {
        try {
            WaveFile wav = WaveFile.parse(Files.readAllBytes(f.toPath()));
            double seconds = (double) wav.sampleCount() / wav.sampleRate();
            log(String.format("[WAV] %s — %d Hz / %d 声道采样 / %.3f s",
                    f.getName(), wav.sampleRate(), wav.sampleCount(), seconds));
            // 若 end 超出音频时长则自动修正
            try {
                double end = Double.parseDouble(loopEndField.getText().trim());
                if (end > seconds) {
                    loopEndField.setText(String.format("%.3f", seconds));
                    log(String.format("[提示] loopEnd 已自动截断为音频时长 %.3f s", seconds));
                }
            } catch (NumberFormatException ignored) {
                // 保持用户输入
            }
        } catch (Exception ex) {
            log("[警告] WAV 解析失败：" + ex.getMessage(), true);
        }
    }

    private void useWavDuration() {
        String wavPath = wavField.getText().trim();
        if (wavPath.isEmpty()) {
            log("[提示] 请先选择 WAV 文件。");
            return;
        }
        try {
            WaveFile wav = WaveFile.parse(Files.readAllBytes(Path.of(wavPath)));
            double seconds = (double) wav.sampleCount() / wav.sampleRate();
            loopStartField.setText("0");
            loopEndField.setText(String.format("%.3f", seconds));
            log(String.format("[提示] 已设置循环区间 0 → %.3f s（全长预览）。", seconds));
            onConfirmLoop();
        } catch (Exception ex) {
            log("[错误] 无法读取 WAV：" + ex.getMessage(), true);
        }
    }

    /** 确认（应用）循环区间：校验数值，必要时按 WAV 时长截断 end，并给出状态反馈。 */
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

        // 已选 WAV 时，end 超出音频时长自动截断（与 CriUtils 内部逻辑一致）
        String wavPath = wavField.getText().trim();
        if (!wavPath.isEmpty()) {
            try {
                WaveFile wav = WaveFile.parse(Files.readAllBytes(Path.of(wavPath)));
                double duration = (double) wav.sampleCount() / wav.sampleRate();
                if (endSec > duration) {
                    log(String.format("[提示] loopEnd %.3f s 超过音频时长 %.3f s，已自动截断。", endSec, duration));
                    endSec = duration;
                }
                if (startSec >= endSec) {
                    log("[提示] 截断后开始时间不小于结束时间，已重置开始时间为 0。");
                    startSec = 0;
                }
            } catch (Exception ex) {
                log("[警告] WAV 解析失败，未做时长截断：" + ex.getMessage(), true);
            }
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
        led.setStyle(busy
                ? "-fx-background-color: #ffb000;"
                : "-fx-background-color: #6fcf97;");
    }

    // ------------------------------------------------------------------

    private static File pickFile(Stage owner, String title, String desc, String ext, File dir) {
        FileChooser fc = new FileChooser();
        fc.setTitle(title);
        if (dir != null && dir.isDirectory()) {
            fc.setInitialDirectory(dir);
        }
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(desc, ext));
        fxIgnored(fc);
        return fc.showOpenDialog(owner);
    }

    private static Button browseButton(String text, Runnable action) {
        Button b = new Button(text);
        b.getStyleClass().add("action-button");
        b.setOnAction(e -> action.run());
        return b;
    }

    private static Label rowLabel(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("form-label");
        return l;
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

    // 让某些静态方法避免不可达代码警告（保留 JDK 兼容占位）
    private static void fxIgnored(Object o) {
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) {
        launch(args);
    }
}