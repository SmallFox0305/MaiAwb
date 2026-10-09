# MaiAwb 舞萌音频数据预览片段追加工具

> **本软件全程由 AI 制作**：全部代码、界面与构建脚本均由 AI 编写与维护。
> 采用 [MIT 开源协议](LICENSE) 发布，版权归 Small_Fox 所有。

复刻 maimai 官机 `CreateAcbWithPreview` 的 ACB 预览生成器。
**AWB 为唯一输入**：工具内部从 HCA 头自提取音频参数（采样率 / 声道 / 样本数）驱动 ACB 生成，
用户无需提交任何音频文件，也不内置 HCA→PCM 解码。

- 当前版本：**26.1-A**
- 版本号规则：`YY.PATCH-LETTER` → `26` = 年份，`1` = 补丁号，`A` = 迭代版本字母

---

## 特性

- **AWB 唯一输入**：`提取 WAV` 由工具内部完成（解析 HCA 头采样参数即可驱动 ACB，无需 PCM 解码）
- **官机可用的 ACB 输出**：以内置 `templateV3.acb` 为模板，输出与官机 CRI 二进制布局一致
- **count=1 规范化**：统一规范 AWB 为单一音频流，规避官机卡死
- **双界面**：JavaFX GUI（极简工业风：白底黑字、直角硬边缘）+ CLI
- **文件选择器**：优先 kdialog（KDE/Dolphin 同款），其次 zenity，最后回退 JavaFX

## 快速开始（GUI）

1. 运行 `MaiAwb`（Windows：解压 `MaiAwb-26.1-A-windows-x64.zip`，双击 `MaiAwb.exe`）
2. 选择 AWB 音频容器（AFS2 / AWB，必选）
3. 输入预览循环区间（秒，回车确认）
4. 点击「生成」→ 输出 `.acb` 与配对 `.awb`，部署到官机 `SoundData/`

## 快速开始（CLI）

```
java -m com.maiawb/com.maiawb.cli.Main <awb> <out.acb> [loopStartSec] [loopEndSec] [--verify]
```

- `loopStartSec` / `loopEndSec`：循环区间（秒），默认 0 / 末位
- `--verify`：输出后回读 ACB 校验各表（Cue / TrackEvent / StreamAwbHash）
- 示例：`java -m com.maiawb/com.maiawb.cli.Main music006000.awb out.acb 0 8 --verify`

## 构建

| 目标 | 方式 |
|---|---|
| Linux 本机自包含镜像 | `mvn package` + javafx jlink → `target/image/` |
| Windows x64 exe（在 Linux 交叉打包） | `scripts/build-windows-exe.sh` → `out/MaiAwb/MaiAwb.exe` + zip |

Windows 打包原理：Docker 内 Wine 运行 **Oracle JDK 27**（含 jmods）的 jlink + jpackage
`--type app-image`，JavaFX 使用 `-win` classifier 原生模块。详见 `docs/AI_MAINTENANCE.md`。

## 目录速览

```
src/main/java/com/maiawb/
├── MaiAwbApp.java          GUI 主入口
├── NativeFileDialog.java   kdialog/zenity/JavaFX 文件对话框桥接
├── cli/Main.java           CLI 入口
├── core/CriUtils.java      AWB-only 核心：AudioSpec / extractAudioSpec / createAcbWithPreview
├── core/AwbNormalizer.java count=1 规范化 / HCA 探测
└── core/{cri,io,converter} CRI 二进制读写
scripts/build-windows-exe.sh   Windows exe 交叉打包（可复现）
document 维护说明 → docs/AI_MAINTENANCE.md
```

## 已知限制

- 不产出可播放 PCM wav（无内置 HCA 解码）；如需可对接 vgmstream-cli
- 官机实机验证待部署确认（见 `docs/AI_MAINTENANCE.md` 待办）

## 开源协议

[MIT](LICENSE) © 2026 Small_Fox。本软件全程由 AI 制作，按 MIT 协议自由使用、修改与分发。