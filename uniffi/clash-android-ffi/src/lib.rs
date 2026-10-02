#[cfg(feature = "jemallocator")]
#[global_allocator]
static GLOBAL: tikv_jemallocator::Jemalloc = tikv_jemallocator::Jemalloc;

#[cfg(feature = "mimalloc")]
#[global_allocator]
static GLOBAL: ::mimalloc::MiMalloc = ::mimalloc::MiMalloc;

use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::{Arc, Once};

use async_compat::set_runtime_builder;
use clash_lib::{
    Config,
    app::{
        dns,
        dns::config::{DNSListenAddr, DNSNetMode, NameServer},
    },
    config::{
        config::Controller,
        def::{Config as ConfigDef, DNSMode, LogLevel, Port},
        internal::config::TunConfig,
    },
    shutdown as clash_shutdown, start,
};
use log::init_logger;
use once_cell::sync::OnceCell;
use tokio::task::JoinHandle;
use tokio_util::sync::CancellationToken;
use tracing::{error, info};
use url::Host;

pub mod controller;
pub mod log;
pub mod util;

type EyreError = eyre::Error;
#[uniffi::remote(Object)]
pub struct EyreError;

#[uniffi::export]
pub fn format_eyre_error(err: &EyreError) -> String {
    err.to_string()
}

#[derive(uniffi::Record)]
pub struct ProfileOverride {
    pub tun_fd: i32,

    #[uniffi(default = false)]
    pub allow_lan: bool,

    #[uniffi(default = 7890)]
    pub mixed_port: u16,
    #[uniffi(default = None)]
    pub http_port: Option<u16>,
    #[uniffi(default = None)]
    pub socks_port: Option<u16>,
    #[uniffi(default = false)]
    pub fake_ip: bool,

    #[uniffi(default = "198.18.0.2/16")]
    pub fake_ip_range: String,

    #[uniffi(default = true)]
    pub ipv6: bool,
}

#[derive(uniffi::Object)]
pub struct ClashInstance {
    mixed_port: u16,
    cancel_token: CancellationToken,
    _handle: Option<JoinHandle<eyre::Result<()>>>,
}

#[uniffi::export]
impl ClashInstance {
    /// The mixed HTTP/SOCKS port the proxy is listening on
    pub fn mixed_port(&self) -> u16 {
        self.mixed_port
    }

    /// Shut down clash-rs and cancel the background task
    pub fn shutdown(&self) {
        self.cancel_token.cancel();
        clash_shutdown();
        info!("clash-rs shutdown");
    }
}

#[unsafe(export_name = "Java_rs_clash_android_MainActivity_javaInit")]
pub extern "system" fn java_init(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    #[cfg_attr(not(target_os = "android"), allow(unused_variables))] context: jni::objects::JObject,
) {
    use jni::{Outcome, errors::Result as JniResult};

    let vm: jni::JavaVM = {
        let outcome = env
            .with_env(|env| -> jni::errors::Result<_> { Ok(env.get_java_vm()?.clone()) })
            .into_outcome();
        match outcome {
            Outcome::Ok(v) => v,
            Outcome::Err(e) => panic!("JNI error: {}", e),
            Outcome::Panic(p) => std::panic::resume_unwind(p),
        }
    };
    static VM: OnceCell<jni::JavaVM> = OnceCell::new();
    _ = VM.set(vm);
    let builder = || {
        let mut builder = tokio::runtime::Builder::new_multi_thread();
        builder
            .on_thread_start(|| {
                let vm = VM.get().expect("init java vm");
                let _ = vm.attach_current_thread(|_| -> JniResult<()> { Ok(()) });
            })
            .enable_all();
        builder
    };
    set_runtime_builder(Box::new(builder));

    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let level = if cfg!(debug_assertions) {
            LogLevel::Debug
        } else {
            LogLevel::Info
        };

        unsafe {
            std::env::set_var("RUST_BACKTRACE", "1");
        }
        init_logger(level.into());
        color_eyre::install().unwrap();

        // Re-route panic backtraces through `tracing` so they reach
        // logcat. color_eyre's default hook prints to stderr, which on
        // Android is /dev/null — without this the tombstone only shows
        // the abort message and raw `pc` offsets.
        let prev = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            let bt = std::backtrace::Backtrace::force_capture();
            error!(target: "panic", "thread panicked: {info}\n{bt}");
            prev(info);
        }));

        // Install aws-lc-rs as the default crypto provider
        if let Err(e) = rustls::crypto::aws_lc_rs::default_provider().install_default() {
            error!("Failed to install default crypto provider: {:?}", e);
        } else {
            info!("Successfully installed aws-lc-rs crypto provider");
        }
        info!("Init logger and crypto provider initialized");
    });

    // Hand Android's certificate verifier to `rustls-platform-verifier` so that
    // every rustls client (e.g. reqwest downloads) validates TLS chains with the
    // OS trust store, including user-installed CAs and revocation checks.
    // Must run before any TLS connection is attempted.
    #[cfg(target_os = "android")]
    {
        match env
            .with_env(|env| -> JniResult<()> {
                rustls_platform_verifier::android::init_with_env(env, context)?;
                Ok(())
            })
            .into_outcome()
        {
            Outcome::Ok(_) => info!("Initialized rustls-platform-verifier"),
            Outcome::Err(e) => error!("rustls-platform-verifier init JNI error: {e:?}"),
            Outcome::Panic(p) => std::panic::resume_unwind(p),
        };
    }
}

#[uniffi::export]
fn verify_config(config_path: &str) -> Result<String, EyreError> {
    let _config = Config::File(config_path.to_string()).try_parse()?;
    Ok("config verified successfully".to_string())
}

#[uniffi::export(async_runtime = "tokio")]
async fn run_clash(
    config_path: String,
    work_dir: String,
    over: ProfileOverride,
) -> Result<Arc<ClashInstance>, EyreError> {
    std::env::set_current_dir(&work_dir)?;
    let mut config_def = ConfigDef::try_from(PathBuf::from(config_path.clone()))?;
    let mixed_port = config_def.mixed_port.get_or_insert(Port(over.mixed_port)).0;
    config_def.port = config_def.port.or_else(|| over.http_port.map(Port));
    config_def.socks_port = config_def.socks_port.or_else(|| over.socks_port.map(Port));

    let mut config = Config::Def(config_def).try_parse()?;
    config.tun = TunConfig {
        enable: true,
        device_id: format!("fd://{}", over.tun_fd),
        route_all: false,
        routes: Vec::new(),
        gateway: ipnet::Ipv4Net::new(Ipv4Addr::new(10, 0, 0, 1), 30)?,
        gateway_v6: None,
        mtu: None,
        so_mark: None,
        route_table: 0,
        dns_hijack: true,
    };

    config.general.geosite = Some("geosite.dat".to_string());
    config.general.mmdb = Some("Country.mmdb".to_string());
    config.general.asn_mmdb = None;

    config.general.controller = Controller {
        external_controller_ipc: Some(format!("{work_dir}/clash.sock")),
        ..Default::default()
    };

    config.general.ipv6 = over.ipv6;
    // Bootstrap nameservers. These are built with no parent resolver, so the
    // host must be a literal IP (a `Host::Domain` would hit the resolve path
    // and fail).
    let udp_bootstrap = |host: Host| NameServer {
        net: DNSNetMode::Udp,
        host,
        port: 53,
        interface: None,
        proxy: None,
    };
    let mut default_nameserver = if config.dns.default_nameserver.is_empty() {
        vec![
            udp_bootstrap(Host::Ipv4(Ipv4Addr::new(223, 5, 5, 5))), // AliDNS
            udp_bootstrap(Host::Ipv4(Ipv4Addr::new(223, 6, 6, 6))), // AliDNS
            udp_bootstrap(Host::Ipv4(Ipv4Addr::new(8, 8, 8, 8))),   // Google
        ]
    } else {
        config.dns.default_nameserver.clone()
    };
    // Always append IPv6 bootstrap servers so resolution works on IPv6-only
    // uplinks (e.g. 464XLAT cellular), where the interface has no IPv4 address
    // and the IPv4 servers are unreachable. We append unconditionally — even
    // when the profile supplies its own `default-nameserver` — because such
    // profiles are almost always IPv4-only and would otherwise have no usable
    // bootstrap on an IPv6-only network.
    let ipv6_bootstrap = [
        Host::Ipv6(Ipv6Addr::new(0x2400, 0x3200, 0, 0, 0, 0, 0, 1)), // AliDNS
        Host::Ipv6(Ipv6Addr::new(0x2400, 0x3200, 0xbaba, 0, 0, 0, 0, 1)), // AliDNS
        Host::Ipv6(Ipv6Addr::new(0x2001, 0x4860, 0x4860, 0, 0, 0, 0, 0x8888)), // Google
    ];
    for host in ipv6_bootstrap {
        if !default_nameserver.iter().any(|ns| ns.host == host) {
            default_nameserver.push(udp_bootstrap(host));
        }
    }
    let nameserver = if config.dns.nameserver.is_empty() {
        vec![
            NameServer {
                net: DNSNetMode::DoT,
                host: Host::Ipv4(Ipv4Addr::new(223, 5, 5, 5)),
                port: 853,
                interface: None,
                proxy: None,
            },
            NameServer {
                net: DNSNetMode::DoT,
                host: Host::Ipv4(Ipv4Addr::new(223, 6, 6, 6)),
                port: 853,
                interface: None,
                proxy: None,
            },
            NameServer {
                net: DNSNetMode::DoT,
                host: Host::Ipv4(Ipv4Addr::new(1, 1, 1, 1)),
                port: 853,
                interface: None,
                proxy: None,
            },
        ]
    } else {
        config.dns.nameserver.clone()
    };

    config.dns = dns::Config {
        enable: true,
        ipv6: over.ipv6,
        listen: DNSListenAddr {
            udp: Some(SocketAddr::new(
                IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)),
                53553,
            )),
            ..config.dns.listen
        },
        nameserver,
        default_nameserver,
        ..config.dns
    };
    if over.fake_ip {
        config.dns.enhance_mode = DNSMode::FakeIp;
        config.dns.fake_ip_range = over.fake_ip_range.parse()?;
        config.dns.store_fake_ip = true;
    } else {
        config.dns.enhance_mode = DNSMode::Normal;
    }

    info!("Config path: {config_path}\n\tTUN fd: {}", over.tun_fd);

    let cancel_token = CancellationToken::new();
    let token = cancel_token.clone();
    let handle: JoinHandle<eyre::Result<()>> = tokio::spawn(async move {
        let (log_tx, _) = tokio::sync::broadcast::channel(100);
        info!("Starting clash-rs");
        let start_result = start(config, work_dir, log_tx, token.child_token());
        tokio::select! {
            result = start_result => {
                if let Err(err) = result {
                    error!("clash-rs start error: {:#}", eyre::eyre!(err));
                }
            }
            _ = token.cancelled() => {
                info!("clash-rs cancelled");
            }
        }
        info!("Quitting clash-rs");
        Ok(())
    });

    Ok(Arc::new(ClashInstance {
        mixed_port,
        cancel_token,
        _handle: Some(handle),
    }))
}

uniffi::setup_scaffolding!("clash_android_ffi");
