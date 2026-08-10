use anyhow::{Context, Result, bail};
use clap::Parser;
use std::ffi::OsString;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::{Command, Output};

include!(concat!(env!("OUT_DIR"), "/embedded_assets.rs"));

#[derive(Parser, Debug)]
#[command(
    author,
    version,
    about = "Windows offline APatch boot.img patcher",
    long_about = None
)]
struct Args {
    /// Path to the source boot.img
    #[arg(long, value_name = "PATH")]
    boot: PathBuf,

    /// Path to kpimg-android, optional when embedded at build time
    #[arg(long, value_name = "PATH")]
    kpimg: Option<PathBuf>,

    /// Path to kptools executable from kptools-msys2-win, optional when embedded at build time
    #[arg(long, value_name = "PATH")]
    kptools: Option<PathBuf>,

    /// Output path for the patched boot image
    #[arg(long, value_name = "PATH")]
    output: Option<PathBuf>,

    /// SuperKey used by KernelPatch, defaults to signature mode
    #[arg(long, default_value = "su", value_name = "KEY")]
    superkey: String,

    /// Extra raw arguments forwarded to kptools unpack and patch steps
    #[arg(long = "kptools-arg", value_name = "ARG")]
    kptools_args: Vec<OsString>,
}

fn main() -> Result<()> {
    let args = Args::parse();
    patch_boot_image(args)
}

fn patch_boot_image(args: Args) -> Result<()> {
    ensure_file(&args.boot, "boot image")?;

    let boot_path = fs::canonicalize(&args.boot).context("Failed to resolve boot.img path")?;
    let output_path = args
        .output
        .unwrap_or_else(|| default_output_path(&boot_path))
        .to_path_buf();

    if output_path == boot_path {
        bail!("Output path cannot be the same as the input boot.img");
    }

    if let Some(parent) = output_path.parent() {
        fs::create_dir_all(parent).with_context(|| {
            format!("Failed to create output directory: {}", parent.display())
        })?;
    }

    let workdir_root = exe_directory().context("Failed to locate the exe directory")?;
    let workdir = tempfile::Builder::new()
        .prefix("apatch-winpatch-")
        .tempdir_in(&workdir_root)
        .with_context(|| format!("Failed to create a working directory under {}", workdir_root.display()))?;

    let kpimg_path = stage_runtime_file(
        args.kpimg.as_deref(),
        EMBEDDED_KPIMG,
        workdir.path().join("kpimg"),
        "kpimg",
    )?;
    let kptools_path = stage_runtime_file(
        args.kptools.as_deref(),
        EMBEDDED_KPTOOLS,
        workdir.path().join("kptools.exe"),
        "kptools",
    )?;
    let msys2_path = stage_runtime_file(
        None,
        EMBEDDED_MSYS_2_0_DLL,
        workdir.path().join("msys-2.0.dll"),
        "msys-2.0.dll",
    )?;
    let msysz_path = stage_runtime_file(
        None,
        EMBEDDED_MSYS_Z_DLL,
        workdir.path().join("msys-z.dll"),
        "msys-z.dll",
    )?;

    let work_boot = workdir.path().join("boot.img");
    fs::copy(&boot_path, &work_boot)
        .with_context(|| format!("Failed to copy boot.img into the working directory: {}", work_boot.display()))?;

    println!("Working directory: {}", workdir.path().display());
    println!("Source image: {}", boot_path.display());
    println!("Output image: {}", output_path.display());
    println!("kpimg source: {}", kpimg_path.display());
    println!("kptools source: {}", kptools_path.display());
    println!("msys-2.0.dll source: {}", msys2_path.display());
    println!("msys-z.dll source: {}", msysz_path.display());

    let mut unpack_args = vec![os("unpack"), work_boot.as_os_str().to_os_string()];
    unpack_args.extend(args.kptools_args.iter().cloned());
    run_kptools(&kptools_path, workdir.path(), &unpack_args, "Unpack boot.img", true)?;

    let feature_output = run_kptools(
        &kptools_path,
        workdir.path(),
        &[os("-i"), os("kernel"), os("-f")],
        "Check kernel config",
        false,
    )?;
    let feature_text = collect_output(&feature_output);
    if !feature_text.contains("CONFIG_KALLSYMS=y") {
        bail!("CONFIG_KALLSYMS is not enabled; APatch patching cannot continue");
    }

    let layout_output = run_kptools(
        &kptools_path,
        workdir.path(),
        &[os("-i"), os("kernel"), os("-l")],
        "Read kernel patch metadata",
        true,
    )?;
    let layout_text = collect_output(&layout_output);
    if layout_text.contains("patched=false") {
        fs::copy(&work_boot, workdir.path().join("ori.img"))
            .context("Failed to back up the original boot.img")?;
    }

    let kernel_path = workdir.path().join("kernel");
    let kernel_ori_path = workdir.path().join("kernel.ori");
    fs::rename(&kernel_path, &kernel_ori_path).with_context(|| {
        format!(
            "Failed to rename kernel file: {} -> {}",
            kernel_path.display(),
            kernel_ori_path.display()
        )
    })?;

    let mut patch_args = vec![
        os("-p"),
        os("-i"),
        os("kernel.ori"),
        os("-k"),
        os("kpimg"),
        os("-o"),
        os("kernel"),
    ];
    if args.superkey != "su" {
        patch_args.push(os("-S"));
        patch_args.push(OsString::from(args.superkey));
    }
    patch_args.extend(args.kptools_args);

    run_kptools(&kptools_path, workdir.path(), &patch_args, "Inject KernelPatch", true)?;
    run_kptools(
        &kptools_path,
        workdir.path(),
        &[os("repack"), work_boot.as_os_str().to_os_string()],
        "Repack boot.img",
        true,
    )?;

    if !feature_text.contains("CONFIG_KALLSYMS_ALL=y") {
        println!("Warning: CONFIG_KALLSYMS_ALL is not enabled; the patched image may not boot.");
    }

    let new_boot_path = workdir.path().join("new-boot.img");
    ensure_file(&new_boot_path, "patched boot image")?;
    fs::copy(&new_boot_path, &output_path).with_context(|| {
        format!(
            "Failed to copy new-boot.img to the output path: {}",
            output_path.display()
        )
    })?;

    println!("Patch completed: {}", output_path.display());
    Ok(())
}

fn ensure_file(path: &Path, label: &str) -> Result<()> {
    let metadata = fs::metadata(path)
        .with_context(|| format!("{} does not exist: {}", label, path.display()))?;
    if !metadata.is_file() {
        bail!("{} is not a file: {}", label, path.display());
    }
    Ok(())
}

fn default_output_path(boot_path: &Path) -> PathBuf {
    let parent = boot_path.parent().unwrap_or_else(|| Path::new("."));
    parent.join("new-boot.img")
}

fn exe_directory() -> Result<PathBuf> {
    let exe = std::env::current_exe().context("Failed to get the current exe path")?;
    Ok(exe.parent().unwrap_or_else(|| Path::new(".")).to_path_buf())
}

fn stage_runtime_file(
    provided_path: Option<&Path>,
    embedded_bytes: Option<&'static [u8]>,
    extracted_path: PathBuf,
    label: &str,
) -> Result<PathBuf> {
    if let Some(path) = provided_path {
        ensure_file(path, label)?;
        fs::copy(path, &extracted_path)
            .with_context(|| format!("Failed to copy {} into the working directory: {}", label, extracted_path.display()))?;
        return Ok(extracted_path);
    }

    let bytes = embedded_bytes.with_context(|| {
        format!(
            "Missing --{} and this apatch-winpatch.exe does not embed that resource",
            label
        )
    })?;

    fs::write(&extracted_path, bytes)
        .with_context(|| format!("Failed to extract embedded {}: {}", label, extracted_path.display()))?;

    Ok(extracted_path)
}

fn run_kptools(
    kptools: &Path,
    workdir: &Path,
    args: &[OsString],
    step: &str,
    echo_stdout: bool,
) -> Result<Output> {
    println!("==> {step}");
    let output = Command::new(kptools)
        .current_dir(workdir)
        .args(args)
        .output()
        .with_context(|| format!("Failed to execute kptools: {}", kptools.display()))?;

    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    if echo_stdout && !stdout.trim().is_empty() {
        print!("{stdout}");
    }
    if !stderr.trim().is_empty() {
        eprint!("{stderr}");
    }

    if !output.status.success() {
        bail!(
            "{} failed, exit code: {}",
            step,
            output
                .status
                .code()
                .map_or_else(|| "unknown".to_string(), |code| code.to_string())
        );
    }

    Ok(output)
}

fn collect_output(output: &Output) -> String {
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    format!("{stdout}\n{stderr}")
}

fn os(value: &str) -> OsString {
    OsString::from(value)
}
