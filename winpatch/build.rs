use std::env;
use std::fs;
use std::path::{Path, PathBuf};

fn main() {
    println!("cargo:rerun-if-env-changed=APATCH_WINPATCH_KPIMG");
    println!("cargo:rerun-if-env-changed=APATCH_WINPATCH_KPTOOLS");
    println!("cargo:rerun-if-env-changed=APATCH_WINPATCH_MSYS_2_0_DLL");
    println!("cargo:rerun-if-env-changed=APATCH_WINPATCH_MSYS_Z_DLL");
    println!("cargo:rerun-if-changed=vendor");

    let manifest_dir = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("missing manifest dir"));
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("missing out dir"));

    let kpimg_path = resolve_asset_path(
        "APATCH_WINPATCH_KPIMG",
        manifest_dir.join("vendor").join("kpimg-android"),
    );
    let kptools_path = resolve_asset_path(
        "APATCH_WINPATCH_KPTOOLS",
        manifest_dir.join("vendor").join("kptools.exe"),
    );
    let msys_2_0_dll_path = resolve_asset_path(
        "APATCH_WINPATCH_MSYS_2_0_DLL",
        manifest_dir.join("vendor").join("msys-2.0.dll"),
    );
    let msys_z_dll_path = resolve_asset_path(
        "APATCH_WINPATCH_MSYS_Z_DLL",
        manifest_dir.join("vendor").join("msys-z.dll"),
    );

    let generated = format!(
        "pub const EMBEDDED_KPIMG: Option<&'static [u8]> = {kpimg};\n\
         pub const EMBEDDED_KPTOOLS: Option<&'static [u8]> = {kptools};\n\
         pub const EMBEDDED_MSYS_2_0_DLL: Option<&'static [u8]> = {msys_2_0_dll};\n\
         pub const EMBEDDED_MSYS_Z_DLL: Option<&'static [u8]> = {msys_z_dll};\n",
        kpimg = asset_expr(kpimg_path.as_deref()),
        kptools = asset_expr(kptools_path.as_deref()),
        msys_2_0_dll = asset_expr(msys_2_0_dll_path.as_deref()),
        msys_z_dll = asset_expr(msys_z_dll_path.as_deref()),
    );

    fs::write(out_dir.join("embedded_assets.rs"), generated).expect("write embedded assets");
}

fn resolve_asset_path(env_key: &str, fallback: PathBuf) -> Option<PathBuf> {
    if let Some(value) = env::var_os(env_key) {
        let path = PathBuf::from(value);
        if path.is_file() {
            return Some(path);
        }
        panic!("{env_key} points to a non-file path: {}", path.display());
    }

    if fallback.is_file() {
        Some(fallback)
    } else {
        None
    }
}

fn asset_expr(path: Option<&Path>) -> String {
    match path {
        Some(path) => {
            let path = path.to_string_lossy();
            format!("Some(include_bytes!(r#\"{path}\"#))")
        }
        None => "None".to_string(),
    }
}
