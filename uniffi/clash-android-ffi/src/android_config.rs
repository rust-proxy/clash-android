use crate::EyreError;
use clash_lib::{
    Config,
    app::dns::{
        self,
        config::{DNSNetMode, NameServer},
    },
    config::{
        RuntimeConfig,
        config::{BindAddress, Controller},
        def::{Config as ConfigDef, DNSMode, Port},
        internal::config::TunConfig,
        listener::{CommonInboundOpts, InboundOpts},
    },
};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use url::Host;

#[derive(uniffi::Record)]
pub struct ProfileOverride {
    /// The VPN service's detached TUN fd. Omit it to use the profile's TPROXY listener.
    #[uniffi(default = None)]
    pub tun_fd: Option<i32>,

    /// Allow LAN clients on the profile's top-level proxy ports, subject to its bind-address.
    #[uniffi(default = false)]
    pub allow_lan: bool,

    /// Fallback when the profile has no mixed port. VPN mode defaults to 7890.
    #[uniffi(default = None)]
    pub mixed_port: Option<u16>,
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

    /// Install app-managed, loopback-only TPROXY listeners for this session.
    #[uniffi(default = None)]
    pub tproxy_port: Option<u16>,
}

#[uniffi::export]
pub(crate) fn verify_config(config_path: &str) -> Result<String, EyreError> {
    let _config = Config::File(config_path.to_string()).try_parse()?;
    Ok("config verified successfully".to_string())
}

pub(crate) struct AndroidConfig {
    pub core: RuntimeConfig,
    pub mixed_port: u16,
    pub tcp_dns: Option<SocketAddr>,
}

pub(crate) fn build_config(
    mut config_def: ConfigDef,
    work_dir: &str,
    over: &ProfileOverride,
    tproxy_dns: Option<bool>,
) -> Result<AndroidConfig, EyreError> {
    let mixed_port = config_def
        .mixed_port
        .or(over.mixed_port.map(Port))
        .or_else(|| over.tun_fd.map(|_| Port(7890)));
    config_def.mixed_port = mixed_port;
    config_def.allow_lan = Some(over.allow_lan);
    config_def.port = config_def.port.or_else(|| over.http_port.map(Port));
    config_def.socks_port = config_def.socks_port.or_else(|| over.socks_port.map(Port));

    if over.tun_fd.is_none() {
        // TPROXY owns no TUN device, even if the shared profile enables one.
        config_def.tun = None;
    }
    if over.tproxy_port.is_some() {
        eyre::ensure!(
            over.tun_fd.is_none(),
            "Cannot combine TUN and managed TPROXY"
        );
        config_def.tproxy_port = None;
    }
    let mut config = Config::Def(config_def).try_parse()?;
    configure_inbounds(&mut config, over, tproxy_dns)?;
    config.general.geosite = Some("geosite.dat".to_string());
    config.general.mmdb = Some("Country.mmdb".to_string());
    config.general.asn_mmdb = None;

    config.general.controller = Controller {
        external_controller_ipc: Some(format!("{work_dir}/clash.sock")),
        ..Default::default()
    };

    config.general.ipv6 = over.ipv6;
    let tcp_dns = configure_dns(&mut config.dns, over, tproxy_dns)?;
    Ok(AndroidConfig {
        core: config,
        mixed_port: mixed_port.map_or(0, |p| p.0),
        tcp_dns,
    })
}

fn configure_inbounds(
    config: &mut RuntimeConfig,
    over: &ProfileOverride,
    tproxy_dns: Option<bool>,
) -> Result<(), EyreError> {
    if let Some(port) = over.tproxy_port {
        eyre::ensure!(port != 0, "Invalid TPROXY port");
        config
            .listeners
            .retain(|opts| !matches!(opts, InboundOpts::TProxy { .. }));
        for addr in [
            IpAddr::V4(Ipv4Addr::LOCALHOST),
            IpAddr::V6(Ipv6Addr::LOCALHOST),
        ] {
            if addr.is_ipv6() && !over.ipv6 {
                continue;
            }
            config.listeners.insert(InboundOpts::TProxy {
                common_opts: CommonInboundOpts {
                    name: format!("ANDROID-TPROXY-{addr}"),
                    listen: BindAddress(addr),
                    port,
                    allow_lan: true,
                    fw_mark: None,
                },
                udp: true,
                dns_hijack: tproxy_dns.unwrap_or(true),
            });
        }
    }
    if let Some(fd) = over.tun_fd {
        eyre::ensure!(fd >= 0, "VPN mode requires a valid TUN fd");
        config.tun = TunConfig {
            enable: true,
            device_id: format!("fd://{fd}"),
            route_all: false,
            routes: Vec::new(),
            gateway: ipnet::Ipv4Net::new(Ipv4Addr::new(10, 0, 0, 1), 30)?,
            gateway_v6: None,
            mtu: None,
            so_mark: None,
            route_table: 0,
            dns_hijack: true,
        };
    } else {
        eyre::ensure!(
            config
                .listeners
                .iter()
                .any(|listener| matches!(listener, InboundOpts::TProxy { .. }))
                || !config.inbound_providers.is_empty(),
            "TPROXY mode requires tproxy-port or a TPROXY listener in the profile or an inbound provider"
        );
    }

    Ok(())
}

fn configure_dns(
    dns: &mut dns::Config,
    over: &ProfileOverride,
    tproxy_dns: Option<bool>,
) -> Result<Option<SocketAddr>, EyreError> {
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
    let default_nameserver = &mut dns.default_nameserver;
    if default_nameserver.is_empty() {
        default_nameserver.extend([
            udp_bootstrap(Host::Ipv4(Ipv4Addr::new(223, 5, 5, 5))), // AliDNS
            udp_bootstrap(Host::Ipv4(Ipv4Addr::new(223, 6, 6, 6))), // AliDNS
            udp_bootstrap(Host::Ipv4(Ipv4Addr::new(8, 8, 8, 8))),   // Google
        ]);
    }
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
    if dns.nameserver.is_empty() {
        dns.nameserver.extend(
            [
                Ipv4Addr::new(223, 5, 5, 5),
                Ipv4Addr::new(223, 6, 6, 6),
                Ipv4Addr::new(1, 1, 1, 1),
            ]
            .map(|addr| NameServer {
                net: DNSNetMode::DoT,
                host: Host::Ipv4(addr),
                port: 853,
                interface: None,
                proxy: None,
            }),
        );
    }

    // Keep an explicit profile TCP listener. Otherwise share the managed
    // loopback address with the UDP DNS service (separate transport).
    let loopback_dns = SocketAddr::from((Ipv4Addr::LOCALHOST, 53553));
    let tcp_dns = (tproxy_dns == Some(true)).then(|| *dns.listen.tcp.get_or_insert(loopback_dns));
    dns.enable = true;
    dns.ipv6 = over.ipv6;
    dns.listen.udp = Some(loopback_dns);
    if over.fake_ip {
        dns.enhance_mode = DNSMode::FakeIp;
        dns.fake_ip_range = over.fake_ip_range.parse()?;
        dns.store_fake_ip = true;
    } else {
        dns.enhance_mode = DNSMode::Normal;
    }

    Ok(tcp_dns)
}

#[cfg(test)]
mod tests;
