#!/bin/bash

# 多平台编译脚本 - 优化体积

set -e

VERSION="1.0.0"
OUTPUT_DIR="build"

# 创建输出目录
mkdir -p $OUTPUT_DIR

# 编译参数（优化体积）
LDFLAGS="-s -w -X main.Version=$VERSION"
GCFLAGS="-trimpath"

# 检查 upx 是否可用
UPX_AVAILABLE=0
if command -v upx &> /dev/null; then
    UPX_AVAILABLE=1
    echo "检测到 UPX，将进行额外压缩..."
else
    echo "未检测到 UPX，跳过额外压缩步骤"
fi

echo "开始编译影视+转发服务..."
echo "版本: $VERSION"
echo ""

# 定义编译目标列表: 名称|GOOS|GOARCH|GOARM|输出文件
declare -a TARGETS=(
    "Linux-AMD64|linux|amd64||ws-proxy-linux-amd64"
    "Windows-AMD64|windows|amd64||ws-proxy-windows-amd64.exe"
    "Android-ARM64|linux|arm64||ws-proxy-android-arm64"
    "Android-ARMv7|linux|arm|7|ws-proxy-android-armv7"
    "Android-x86_64|linux|amd64||ws-proxy-android-x86_64"
    "Android-x86|linux|386||ws-proxy-android-x86"
    "macOS-AMD64|darwin|amd64||ws-proxy-darwin-amd64"
    "macOS-ARM64|darwin|arm64||ws-proxy-darwin-arm64"
)

# 遍历编译目标
for target in "${TARGETS[@]}"; do
    IFS='|' read -r NAME GOOS GOARCH GOARM OUTPUT_FILE <<< "$target"
    
    echo "编译 $NAME..."
    
    # 构建环境变量
    BUILD_ENV="CGO_ENABLED=0 GOOS=$GOOS GOARCH=$GOARCH"
    if [ -n "$GOARM" ]; then
        BUILD_ENV="$BUILD_ENV GOARM=$GOARM"
    fi
    
    # 执行编译
    eval "$BUILD_ENV go build -ldflags=\"$LDFLAGS\" -gcflags=\"$GCFLAGS\" -o $OUTPUT_DIR/$OUTPUT_FILE main.go"
    
    if [ $? -ne 0 ]; then
        echo "  (编译失败)"
        continue
    fi
    
    # UPX 压缩
    if [ $UPX_AVAILABLE -eq 1 ]; then
        echo "  正在使用 UPX 压缩..."
        upx --best --lzma $OUTPUT_DIR/$OUTPUT_FILE >/dev/null 2>&1
        if [ $? -eq 0 ]; then
            echo "  (已使用 UPX 压缩)"
        else
            echo "  (UPX 压缩失败，使用未压缩版本)"
        fi
    fi
done

echo ""
echo "编译完成！输出目录: $OUTPUT_DIR"
echo ""
echo "文件列表:"
ls -lh $OUTPUT_DIR/

echo ""
echo "使用说明:"
echo "  Linux/CentOS:    ./ws-proxy-linux-amd64"
echo "  Windows:         ws-proxy-windows-amd64.exe"
echo "  Termux(ARM64):   ./ws-proxy-android-arm64"
echo "  Termux(ARMv7):   ./ws-proxy-android-armv7"
echo "  Termux(x86_64):  ./ws-proxy-android-x86_64"
echo "  Termux(x86):     ./ws-proxy-android-x86"
echo "  macOS(Intel):    ./ws-proxy-darwin-amd64"
echo "  macOS(Apple):    ./ws-proxy-darwin-arm64"
echo ""
echo "提示: 安装 UPX 可以进一步压缩二进制文件"
echo "      安装方法:"
echo "        Ubuntu/Debian: apt-get install upx-ucl"
echo "        CentOS/RHEL:   yum install upx"
echo "        macOS:         brew install upx"
