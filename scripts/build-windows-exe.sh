#!/usr/bin/env bash
# =============================================================================
# 在 Linux 上交叉打包 Windows x64 版 MaiAcb（含真正可双击的 MaiAcb.exe）
#
# 原理：jlink/jpackage 不能跨平台直接生成 Windows 产物，因此借助
#   Docker 内的 Wine 运行 Windows JDK 的 jlink + jpackage（--type app-image），
#   JDK 与 JavaFX 均使用 Windows 原生版本（JavaFX 用 -win classifier），
#   最终产出 out/MaiAcb/MaiAcb.exe（自包含运行时 + 启动器），并整目录打包 zip。
#
# 已知坑（均已在本机验证解决）：
#   - 部分发行版（如 Temurin）27 的 Windows 包不再带 jmods，jlink 无法运行
#     → 默认用甲骨文官方 JDK（含 jmods，顶层目录 jdk-27，脚本会自动抹平）。
#   - jpackage 在 wine 下对 Z:\（映射到容器根目录）做可写探测会误报"not writable"
#     → jlink/jpackage 产物写 wine 虚拟 C: 盘（prefix/drive_c），完成后拷回宿主。
#
# 依赖：
#   - docker（当前用户无需 sudo，已加入 docker 组）
#   - curl / unzip / maven / 7z（p7zip，打包 zip）
#
# 用法：
#   scripts/build-windows-exe.sh [工作目录]
#   工作目录默认 /tmp/opencode/xwin；产物在工作目录 out/MaiAcb/ 下（zip 同目录）。
# 可选环境变量：
#   JDK_URL=…            Windows JDK 27 zip（默认甲骨文官方）
#   JFX_VER=27           与 pom.xml 的 javafx.version 配套
#   WINE_IMAGE=…         wine 镜像，默认 scottyhardy/docker-wine
# =============================================================================
set -euo pipefail

JFX_VER="${JFX_VER:-27}"
WINE_IMAGE="${WINE_IMAGE:-scottyhardy/docker-wine}"
JDK_URL="${JDK_URL:-https://download.oracle.com/java/27/latest/jdk-27_windows-x64_bin.zip}"
# 版本号规则：YY.PATCH-LETTER（26=年份 1=补丁号 A=迭代字母）。
# 展示版用于产物名称；jpackage --app-version 仅接受纯数字，故拆为 26.2.0。
APP_VERSION="26.2-A"
JPACKAGE_VERSION="26.2.0"
WORK="${1:-/tmp/opencode/xwin}"
REPO="$(cd "$(dirname "$0")/.." && pwd)"

mkdir -p "$WORK"/{winjdk,jfx-win,app,out,tmp}

echo "==> [1/5] Windows 原生 JDK（Oracle JDK 27 x64）"
if [ ! -d "$WORK/winjdk/jdk" ]; then
  if [ ! -f "$WORK/winjdk.zip" ]; then
    curl -s --http1.1 -C - --retry 3 --retry-all-errors -L -o "$WORK/winjdk.zip" "$JDK_URL"
  fi
  unzip -q -o "$WORK/winjdk.zip" -d "$WORK/winjdk"
  # 解出来一层目录（Oracle 为 jdk-27，Zulu 为 zulu…-ca-jdk…），抹平为 $WORK/winjdk/jdk
  d="$(find "$WORK/winjdk" -maxdepth 2 -type d \( -name 'zulu*' -o -name 'jdk-*' \) | head -1)"
  [ -n "$d" ] && mv "$d" "$WORK/winjdk/jdk"
fi
[ -f "$WORK/winjdk/jdk/jmods/java.base.jmod" ] || { echo "错误：Windows JDK 缺少 jmods，无法 jlink。"; exit 1; }
echo "     $(ls "$WORK/winjdk/jdk/bin/jlink.exe" "$WORK/winjdk/jdk/bin/jpackage.exe")"

echo "==> [2/5] JavaFX ${JFX_VER} Windows 原生模块（-win classifier）"
for a in base graphics controls; do
  f="$WORK/jfx-win/javafx-$a-${JFX_VER}-win.jar"
  if [ ! -f "$f" ]; then
    curl -sfL -o "$f" \
      "https://repo1.maven.org/maven2/org/openjfx/javafx-$a/${JFX_VER}/javafx-$a-${JFX_VER}-win.jar"
  fi
done
ls "$WORK/jfx-win/"*.jar >/dev/null

echo "==> [3/5] 打包模块 jar（Linux 编译，字节码平台无关）"
mvn -q -f "$REPO/pom.xml" package -DskipTests
cp "$REPO/target/MaiAcb.jar" "$WORK/app/"

echo "==> [4/5] Docker 内 Wine 执行 jlink + jpackage（--type app-image）"
cat > "$WORK/winebuild.sh" <<'WINEBUILD'
#!/usr/bin/env bash
# 容器内执行：路径用单引号按原样传入 wine（Z:\ 映射容器根 /，C:\ 为 wine 虚拟盘）
set -e
export HOME=/work
export WINEPREFIX=/work/.wine
export WINEDEBUG=-all
cd /work

if [ ! -f "$WINEPREFIX/system.reg" ]; then
  echo "[wine] 初始化 prefix..."
  wineboot -u >/dev/null 2>&1 || true
fi

MP='Z:\work\winjdk\jdk\jmods;Z:\work\jfx-win;Z:\work\app'

# 清理 wine 虚拟 C: 盘（wine 的 C:\ 对应 $WINEPREFIX/drive_c；bash 的
# rm 只认 Linux 路径，所以直接用 /work/.wine/drive_c 清理）
rm -rf /work/.wine/drive_c/winimg /work/.wine/drive_c/out /work/.wine/drive_c/tmp

echo "[wine] jlink -> C:\winimg"
wine 'Z:\work\winjdk\jdk\bin\jlink.exe' \
  --module-path "$MP" \
  --add-modules 'top.smallfox.maiacb,javafx.controls' \
  --output 'C:\winimg'

echo "[wine] jpackage app-image -> C:\out\MaiAcb (+MaiAcb.exe)"
wine 'Z:\work\winjdk\jdk\bin\jpackage.exe' \
  --type app-image \
  --runtime-image 'C:\winimg' \
  --module-path 'Z:\work\app;Z:\work\jfx-win' \
  --module 'top.smallfox.maiacb/top.smallfox.maiacb.MaiAcbApp' \
  --name MaiAcb --app-version "$JPACKAGE_VERSION" \
  --dest 'C:\out' --temp 'C:\tmp'

echo WINE_BUNDLE_OK
WINEBUILD

docker run --rm \
  -u "$(id -u):$(id -g)" \
  -v "$WORK:/work" \
  -e HOME=/work \
  -e WINEPREFIX=/work/.wine \
  -e WINEDEBUG=-all \
  -e JPACKAGE_VERSION="$JPACKAGE_VERSION" \
  --entrypoint /bin/bash \
  "$WINE_IMAGE" /work/winebuild.sh 2>&1 \
  | grep -v "Attempt to"

# 构建产物在 wine C: 盘，拷回宿主
rm -rf "$WORK/out/MaiAcb"
cp -r "$WORK/.wine/drive_c/out/MaiAcb" "$WORK/out/"

echo "==> [5/5] 打包 zip（7z）"
ZIP_NAME="MaiAcb-${APP_VERSION}-windows-x64.zip"
rm -f "$WORK/out/$ZIP_NAME"
( cd "$WORK/out" && 7z a -tzip -mx=5 -bso0 -bsp0 "$ZIP_NAME" MaiAcb >/dev/null )
echo "完成：$WORK/out/$ZIP_NAME"
ls -la "$WORK/out/MaiAcb/MaiAcb.exe" "$WORK/out/$ZIP_NAME"