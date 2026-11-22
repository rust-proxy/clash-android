#[cfg(feature = "jemallocator")]
#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

#[cfg(feature = "mimalloc")]
#[global_allocator]
static GLOBAL: ::mimalloc::MiMalloc = ::mimalloc::MiMalloc;

mod android_config;
mod controller;
mod download;
mod initialization;
mod instance;
mod log;
mod profile_transfer;
#[cfg(target_os = "android")]
mod tproxy;
mod update;

type EyreError = eyre::Error;
#[uniffi::remote(Object)]
pub struct EyreError;

#[uniffi::export]
pub fn format_eyre_error(err: &EyreError) -> String {
    err.to_string()
}

uniffi::setup_scaffolding!("clash_android_ffi");
