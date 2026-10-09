# MaiAwb 维护说明（面向日后的 AI / 开发者）

> 本文档是项目唯一的维护指南。修改任何源码前通读一遍，
> 尤其「关键不变量」与「血泪坑」两节——它们直接决定产物是否能在 maimai 官机运行。

---

## 1. 项目定位

复刻 maimai 官机 `CreateAcbWithPreview` 的 ACB 预览生成器。
**AWB 是唯一输入**：工具从 HCA 头解析采样参数驱动 ACB 生成，不内置 HCA→PCM 解码。
用户不提交音频文件，「提取 WAV」由工具内部完成（即从 HCA 头提取 `AudioSpec`）。

项目元信息：**本软件全程由 AI 制作**；采用 [MIT 协议](../../LICENSE)，版权归 Small_Fox。
版本规则见 §4。

## 2. 目录与职责

```
pom.xml                     Maven 工程；release=27，JavaFX 27，javafx-maven-plugin(jlink)
README.md                   用户文档
docs/AI_MAINTENANCE.md      本文档
scripts/build-windows-exe.sh Windows exe 交叉打包（Docker + Wine + Oracle JDK 27）
src/main/java/module-info.java 模块 com.maiawb，需 JavaFX，opens com.maiawb to javafx.graphics
src/main/java/com/maiawb/
├── MaiAwbApp.java          GUI：标题/布局/按钮/日志；仅展示版本号
├── NativeFileDialog.java   kdialog → zenity → JavaFX 文件对话框桥接
├── cli/Main.java           CLI：Main <awb> <out.acb> [start] [end] [--verify]
├── core/CriUtils.java      ★核心：AudioSpec / extractAudioSpec / createAcbWithPreview(int,int,…)
├── core/AwbNormalizer.java ★count=1 规范化：probeHca / normalize / buildCountOne
├── core/WaveFile.java      仅测试/调试用，不参与主流程
├── core/cri/               CRI 二进制表读写（CriTable/Rows/Reader/Writer/Masker）
├── core/io/                ByteWriter / DataStream
└── core/…                  （com.awb.converter.AwbConverter 历史遗留，勿用）
src/main/resources/
├── templateV3.acb          内置 ACB 模板（唯一模板源，勿从外部再引入）
└── tob.css                 工业风样式（白底黑字、直角、无圆角）
```

## 3. 核心数据流（AWB → 官机可用 ACB）

```
AWB 输入
  → probeHca ：读取 HCA 头（version/channelCount/samplingRate/frameCount/
                encoderDelay/encoderPadding/ath 等）
  → extractAudioSpec：sampleCount = frameCount×1024 − encoderDelay − encoderPadding
  → normalize（count=1）：确保 AWB 内仅 1 段音频流且 metadata/aux 对齐
  → createAcbWithPreview(loopStartSec, loopEndSec, spec)：
      以 templateV3.acb 为底，重写
        NumSamples（低字节不得为 0x00，否则官机卡死）
        SamplingRate
        CueTable / TrackEventTable（含循环区间）
        AwbHash（指向配对的 .awb）
  → 输出 <out>.acb + 规范化 AWB
```

CLI 输出 OK 的字节流应与「仅输入 AWB」时的 wav 路径逐字节一致（回归基准，见 §6）。

## 4. 版本号规则（重要）

- 显示版本：`YY.PATCH-LETTER`，例如 `26.1-A`
  - `YY`　= 年份（2026 → 26）
  - `PATCH` = 补丁号（从 1 递增）
  - `LETTER` = 迭代版本字母（A、B、C…）
- 版本号三处保持一致，规则：
  1. `pom.xml  <version>` 　　　→ 显示格式 `26.1-A`
  2. GUI（`MaiAwbApp.java` 标题 + 右上角 Label）→ 显示格式 `26.1-A`
  3. `scripts/build-windows-exe.sh` 顶部的 `APP_VERSION`（显示/产物名）与
     `JPACKAGE_VERSION`（**纯数字** `26.1.0`：jpackage `--app-version` 只接受
     `major.minor[.patch]`，字母会让 Windows 打包报错）
- 产物命名：`MaiAwb-${APP_VERSION}-windows-x64.zip`
- 每次发版：改版本号 → 重建 → 打 tag `v${APP_VERSION}`

## 5. 关键不变量（改代码前逐条过）

1. **AWB 唯一输入，绝不要求用户波形/音频路径**。`extractAudioSpec` 只从 HCA 头取参数。
2. **count=1 规范化**：`AwbNormalizer` 统一把 AWB 规整为 count=1（官机卡死根因之一已修）。
3. **NumSamples 低字节 ≠ 0x00**：写 ACB 时低字节若落 0x00 要抬位，避免官机读取卡死。
4. **模板唯一**：`templateV3.acb` 是唯一模板；不要在别处再引入 ACB 模板。
5. **HCA 采样公式**：`sampleCount = frameCount × 1024 − encoderDelay − encoderPadding`。
6. **tob.css 风格**：白底黑字、直角硬边缘（圆角一律 0）、少装饰、元素等高。
7. **文件对话框顺序**：kdialog（Dolphin 同款）→ zenity → JavaFX；丢弃 stderr，只取 stdout 最后一行。
8. **日志行为**：GUI 日志区只反映信息，`清空日志` 按钮在右下角，生成按钮在行尾。

## 6. 验证方法

Linux 本机构建：
```
mvn -q package -DskipTests   # → target/MaiAwb.jar
```
JavaFX jlink 镜像：`mvn javafx:jlink` → `target/image/`（GUI 与 CLI 都在内）。

CLI 回归（样例 AWB）：`/mnt/TOMORE_GMINI_1T/Public/maiawb/music006000.awb`
```
java --module-path target/classes:$(cat /tmp/opencode/mai-cp.txt) \
     --module com.maiawb/com.maiawb.cli.Main <awb> <out.acb> [start] [end] [--verify]
```
- 回归基准：**仅 AWB 输入** 与 **wav 路径输入** 的产物逐字节一致（已验：`ae30975b`）。
- `--verify` 输出 CueTable / TrackEventTable / StreamAwbHash 供人工核对。
- 官机实测样例：`/mnt/TOMORE_GMINI_1T/Public/maiawb/deploy_music006000/`（`music006000.{acb,awb}` 待部署验证）。

## 7. Windows exe 交叉打包（Linux 上）

脚本：`scripts/build-windows-exe.sh [工作目录]`，可重复执行（缓存 JDK/jars）。

原理与坑（都已踩过并解决，勿回退）：
1. **jlink/jpackage 不可跨平台**；用 Docker 内 Wine 运行 Windows 版工具链。
2. **JDK 必须带 jmods**：Temurin 27 的 Windows 包已不含 jmods；
   默认 **Oracle JDK 27**（`download.oracle.com/java/27/latest/…`）。换 JDK 前先确认 jmods。
3. **JavaFX 必须用 `-win` classifier jar**（base/graphics/controls），否则打进 Linux 原生库。
4. **wine 下 jpackage 对 Z:\ 盘写探测误报**：jlink/jpackage 产物一律写 wine 虚拟 C 盘
   （`$WINEPREFIX/drive_c`），构建完拷回宿主。
5. **容器内 bash 的 rm 不认 wine 盘符**：清理用 Linux 路径 `/work/.wine/drive_c/…`。
6. 容器内脚本用单引号原样传 `Z:\…` / `C:\…` 路径，避免多层转义损坏。
7. 打包 zip 用系统 `7z`；产物 zip：`MaiAwb-${APP_VERSION}-windows-x64.zip`。

验证产物：`file …/MaiAwb.exe` 应为 `PE32+ GUI x86-64`；`runtime/bin/server/jvm.dll` 应为 Windows DLL。

## 8. 已知限制与待办

- 不内置 HCA→PCM 解码，不产出可播放 wav；如需导出再对接 vgmstream-cli。
- 官机实机验证：`deploy_music006000/` 待部署，若卡死复现按 §5 不变量排查。
- 全部 commit 均未推送远端；`gh` CLI 未安装，发 GitHub Release 前需先配置 remote/认证。
- 工作区遗留 `.idea/vcs.xml`（未跟踪，无关紧要）。

## 9. 常规改动检查清单

- [ ] 改核心逻辑：跑 §6 回归（逐字节一致 + `--verify`）
- [ ] 改版本号：§4 三处同步（pom / GUI / 脚本）
- [ ] 改 UI：用 CssProbeApp 之类程序化探针验证（模型看不了截图），用后删除
- [ ] 改打包：跑 `scripts/build-windows-exe.sh` 全流程，确认 `file` 类型与 zip 产物
- [ ] 改文档：README 与本文档同步