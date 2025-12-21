@echo off
chcp 65001 >nul 2>&1
REM 多平台编译脚本 - Windows 版本

setlocal EnableDelayedExpansion

set VERSION=1.0.0
set OUTPUT_DIR=build

REM 创建输出目录
if not exist %OUTPUT_DIR% mkdir %OUTPUT_DIR%

REM 编译参数（优化体积）
set LDFLAGS=-s -w -X main.Version=%VERSION%
set GCFLAGS=-trimpath

REM 检查 upx.exe 是否存在
set UPX_AVAILABLE=0
if exist "upx.exe" (
    set UPX_AVAILABLE=1
    echo 检测到 UPX，将进行额外压缩...
) else (
    echo 未检测到 UPX，跳过额外压缩步骤
)

echo 开始编译影视+转发服务...
echo 版本: %VERSION%
echo.

set CGO_ENABLED=0

REM 编译目标列表
REM Linux AMD64
call :build_target "Linux-AMD64" linux amd64 "" "ws-proxy-linux-amd64"

REM Windows AMD64
call :build_target "Windows-AMD64" windows amd64 "" "ws-proxy-windows-amd64.exe"

REM Android ARM64
call :build_target "Android-ARM64" linux arm64 "" "ws-proxy-android-arm64"

REM Android ARMv7
call :build_target "Android-ARMv7" linux arm 7 "ws-proxy-android-armv7"

REM Android x86_64
call :build_target "Android-x86_64" linux amd64 "" "ws-proxy-android-x86_64"

REM Android x86
call :build_target "Android-x86" linux 386 "" "ws-proxy-android-x86"

REM macOS AMD64
call :build_target "macOS-AMD64" darwin amd64 "" "ws-proxy-darwin-amd64"

REM macOS ARM64
call :build_target "macOS-ARM64" darwin arm64 "" "ws-proxy-darwin-arm64"

echo.
echo 编译完成！输出目录: %OUTPUT_DIR%
echo.
echo 文件列表:
dir /B %OUTPUT_DIR%

echo.
echo 使用说明:
echo   Linux/CentOS:    ./ws-proxy-linux-amd64
echo   Windows:         ws-proxy-windows-amd64.exe
echo   Termux(ARM64):   ./ws-proxy-android-arm64
echo   Termux(ARMv7):   ./ws-proxy-android-armv7
echo   Termux(x86_64):  ./ws-proxy-android-x86_64
echo   Termux(x86):     ./ws-proxy-android-x86
echo   macOS(Intel):    ./ws-proxy-darwin-amd64
echo   macOS(Apple):    ./ws-proxy-darwin-arm64
echo.
echo 提示: 安装 UPX 可以进一步压缩二进制文件
echo       下载地址: https://upx.github.io/
echo.
pause
exit /b 0

:build_target
setlocal enabledelayedexpansion
set NAME=%~1
set GOOS=%~2
set GOARCH=%~3
set GOARM=%~4
set OUTPUT_FILE=%~5

echo 编译 %NAME%...
set GOOS=%GOOS%
set GOARCH=%GOARCH%
if not "%GOARM%"=="" (
    set GOARM=%GOARM%
)
set OUTPUT_PATH=%OUTPUT_DIR%\%OUTPUT_FILE%

go build -ldflags="%LDFLAGS%" -gcflags="%GCFLAGS%" -o !OUTPUT_PATH! main.go

if errorlevel 1 (
    echo   ^(编译失败^)
    exit /b 1
)

if %UPX_AVAILABLE%==1 (
    echo   正在使用 UPX 压缩...
    upx.exe --best --lzma !OUTPUT_PATH! >nul 2>&1
    if errorlevel 1 (
        echo   ^(UPX 压缩失败，使用未压缩版本^)
    ) else (
        echo   ^(已使用 UPX 压缩^)
    )
)

endlocal
exit /b 0
