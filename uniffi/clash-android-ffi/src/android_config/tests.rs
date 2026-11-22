use super::*;

fn vpn() -> ProfileOverride {
    ProfileOverride {
        tun_fd: Some(7),
        allow_lan: false,
        mixed_port: None,
        http_port: None,
        socks_port: None,
        fake_ip: false,
        fake_ip_range: "198.18.0.2/16".into(),
        ipv6: true,
        tproxy_port: None,
    }
}

fn build(profile: &str, over: &ProfileOverride, dns: Option<bool>) -> eyre::Result<AndroidConfig> {
    build_config(profile.parse()?, "/cache", over, dns)
}

#[test]
fn mixed_port_prefers_profile_then_override_then_vpn_default() {
    let mut over = vpn();
    assert_eq!(build("{}", &over, None).unwrap().mixed_port, 7890);
    over.mixed_port = Some(8000);
    assert_eq!(build("{}", &over, None).unwrap().mixed_port, 8000);
    assert_eq!(
        build("mixed-port: 8001", &over, None).unwrap().mixed_port,
        8001
    );
}

#[test]
fn lan_setting_overrides_profile_and_respects_its_bind_address() {
    for (opt_in, profile_lan, expected) in [
        (false, false, false),
        (true, false, true),
        (false, true, false),
    ] {
        let over = ProfileOverride {
            allow_lan: opt_in,
            ..vpn()
        };
        let profile = format!(
            "mixed-port: 8000\nport: 8001\nsocks-port: 8002\nallow-lan: {profile_lan}\nbind-address: 192.0.2.5"
        );
        let config = build(&profile, &over, None).unwrap().core;
        assert_eq!(config.listeners.len(), 3);
        for listener in &config.listeners {
            let opts = listener.common_opts();
            assert_eq!(opts.allow_lan, expected);
            assert_eq!(opts.listen.0, IpAddr::V4(Ipv4Addr::new(192, 0, 2, 5)));
        }
    }
}

#[test]
fn managed_tproxy_is_exclusive_with_tun_and_uses_only_loopback() {
    let mut over = ProfileOverride {
        tproxy_port: Some(17893),
        allow_lan: true,
        ..vpn()
    };
    assert!(
        build("{}", &over, None)
            .err()
            .unwrap()
            .to_string()
            .contains("Cannot combine")
    );
    over.tun_fd = None;
    let config = build("{}", &over, Some(true)).unwrap();
    assert_eq!(config.mixed_port, 0);
    assert_eq!(
        config.tcp_dns,
        Some(SocketAddr::from((Ipv4Addr::LOCALHOST, 53553)))
    );
    assert_eq!(config.core.listeners.len(), 2);
    for listener in &config.core.listeners {
        assert!(matches!(
            listener,
            InboundOpts::TProxy {
                dns_hijack: true,
                ..
            }
        ));
        assert!(listener.common_opts().listen.is_localhost());
    }
}

#[test]
fn dns_keeps_profile_nameservers_and_tcp_listener_while_applying_overrides() {
    let over = ProfileOverride {
        ipv6: false,
        fake_ip: true,
        fake_ip_range: "198.19.0.0/16".into(),
        ..vpn()
    };
    let config = build("dns:\n  default-nameserver: ['192.0.2.1']\n  nameserver: ['192.0.2.2']\n  listen: {tcp: '127.0.0.1:1053'}", &over, Some(true)).unwrap();
    let dns = config.core.dns;
    assert_eq!(
        config.tcp_dns,
        Some(SocketAddr::from((Ipv4Addr::LOCALHOST, 1053)))
    );
    assert_eq!(
        dns.listen.udp,
        Some(SocketAddr::from((Ipv4Addr::LOCALHOST, 53553)))
    );
    assert!(dns.enable && !dns.ipv6 && dns.store_fake_ip);
    assert!(matches!(dns.enhance_mode, DNSMode::FakeIp));
    assert_eq!(
        dns.nameserver[0].host,
        Host::<String>::Ipv4(Ipv4Addr::new(192, 0, 2, 2))
    );
    assert_eq!(
        dns.default_nameserver[0].host,
        Host::<String>::Ipv4(Ipv4Addr::new(192, 0, 2, 1))
    );
    assert_eq!(
        dns.default_nameserver
            .iter()
            .filter(|ns| matches!(ns.host, Host::Ipv6(_)))
            .count(),
        3
    );
}

#[test]
fn invalid_fake_ip_range_is_reported_only_when_fake_ip_is_enabled() {
    let mut over = ProfileOverride {
        fake_ip_range: "invalid".into(),
        ..vpn()
    };
    assert!(build("{}", &over, None).is_ok());
    over.fake_ip = true;
    assert!(build("{}", &over, None).is_err());
}
