# apatch-winpatch

只负责 **Windows 离线修补 `boot.img`**。

支持两种资源提供方式：

- **外部传参**：运行时通过 `--kpimg` 和 `--kptools` 指定
- **编译内置**：构建时把 `kpimg-android`、`kptools.exe`、`msys-2.0.dll`、`msys-z.dll` 打进 `exe`

## 构建

```bash
cargo build --manifest-path winpatch/Cargo.toml --release
```

生成文件：

```text
winpatch/target/release/apatch-winpatch.exe
```

如果你想强制清理旧的构建产物，再重新生成最新 `exe`，可以执行：

```bash
cargo clean --manifest-path winpatch/Cargo.toml
cargo build --manifest-path winpatch/Cargo.toml --release
```

## 内置资源构建

### 方式一：放到固定目录

将下面两个文件放到 `winpatch/vendor/`：

- `winpatch/vendor/kpimg-android`
- `winpatch/vendor/kptools.exe`
- `winpatch/vendor/msys-2.0.dll`
- `winpatch/vendor/msys-z.dll`

然后直接构建：

```bash
cargo build --manifest-path winpatch/Cargo.toml --release
```

### 方式二：通过环境变量指定

```powershell
$env:APATCH_WINPATCH_KPIMG="D:\kernelpatch\kpimg-android"
$env:APATCH_WINPATCH_KPTOOLS="D:\kernelpatch\kptools.exe"
cargo build --manifest-path winpatch/Cargo.toml --release
```

构建完成后，这些文件会被编译进 `apatch-winpatch.exe`，运行时自动释放到 `exe` 同目录下的临时工作目录。

## 输入

- `--boot`：原始 `boot.img`
- `--kpimg`：`kpimg-android`，如果已内置可省略
- `--kptools`：从 `kptools-msys2-win.7z` 解压出的 `kptools.exe`，如果已内置可省略
- `--superkey`：可选，默认 `su`
- `--output`：可选，默认输出到源镜像同目录下的 `new-boot.img`

## 示例

```bash
apatch-winpatch.exe ^
  --boot D:\images\boot.img ^
  --kpimg D:\kernelpatch\kpimg-android ^
  --kptools D:\kernelpatch\kptools.exe ^
  --output D:\images\new-boot.img
```

如果你已经把资源编译进 `exe`，最短可以这样运行：

```bash
apatch-winpatch.exe --boot D:\images\boot.img --output D:\images\new-boot.img
```

## 清理缓存

在 `apatch-winpatch.exe` 所在目录执行下面的 PowerShell 命令，可以清理程序生成的临时工作目录：

```powershell
Get-ChildItem . -Directory -Filter "apatch-winpatch-*" | Remove-Item -Recurse -Force
```

## 说明

- 这个工具只做 `unpack -> patch -> repack`
- 不负责提取手机分区
- 不负责刷回设备
- 底层仍然调用官方 `kptools`
