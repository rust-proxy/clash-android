use std::time::Duration;
use std::{collections::HashMap, sync::Arc};

use bytes::Bytes;
use eyre::Context;
#[cfg(unix)]
use once_cell::sync::OnceCell;
use reqwest::Method;
use serde::{Deserialize, Serialize};
use urlencoding::encode;

use crate::EyreError;

const STATS_REQUEST_TIMEOUT: Duration = Duration::from_secs(10);

fn proxy_path(name: &str) -> Result<String, EyreError> {
    if matches!(name, "." | "..") {
        eyre::bail!(
            "Cannot address proxy or group named '{name}': reqwest normalizes dot path segments"
        );
    }
    Ok(format!("/proxies/{}", encode(name)))
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, uniffi::Enum)]
#[serde(rename_all = "lowercase")]
pub enum Mode {
    Rule,
    Global,
    Direct,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Proxy {
    /// Proxy name
    pub name: String,
    /// Proxy type (e.g., Selector, URLTest, Fallback, Direct, Reject, etc.)
    #[serde(rename = "type")]
    pub proxy_type: String,
    /// All proxy node names contained in the proxy group (only for proxy
    /// groups)
    #[serde(default)]
    pub all: Vec<String>,
    /// Currently selected proxy node name (only for proxy groups)
    pub now: Option<String>,
    /// Delay test history records
    #[serde(default)]
    pub history: Vec<DelayHistory>,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct DelayHistory {
    pub time: String,
    pub delay: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct DelayResponse {
    pub delay: i32,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct MemoryResponse {
    pub inuse: i64,
    pub oslimit: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Connection {
    pub id: String,
    pub metadata: Metadata,
    pub upload: i64,
    pub download: i64,
    pub start: String,
    pub chains: Vec<String>,
    pub rule: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct Metadata {
    pub network: String,
    #[serde(rename = "type")]
    pub metadata_type: String,
    #[serde(rename = "sourceIP")]
    pub source_ip: String,
    #[serde(rename = "destinationIP")]
    pub destination_ip: String,
    #[serde(rename = "destinationPort")]
    pub destination_port: u16,
    pub host: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct ConnectionsResponse {
    #[serde(rename = "downloadTotal")]
    pub download_total: i64,
    #[serde(rename = "uploadTotal")]
    pub upload_total: i64,
    pub connections: Vec<Connection>,
}

/// Only the fields displayed by the home screen cross the FFI boundary.
#[derive(Debug, Clone, uniffi::Record)]
pub struct OverviewResponse {
    pub memory: MemoryResponse,
    pub connection_count: u64,
    pub download_total: i64,
    pub upload_total: i64,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct OverviewSnapshot {
    memory: i64,
    download_total: i64,
    upload_total: i64,
    #[serde(rename = "connections", deserialize_with = "count_connections")]
    connection_count: u64,
}

fn count_connections<'de, D: serde::Deserializer<'de>>(deserializer: D) -> Result<u64, D::Error> {
    struct Count;

    impl<'de> serde::de::Visitor<'de> for Count {
        type Value = u64;

        fn expecting(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
            formatter.write_str("an array of connections")
        }

        fn visit_seq<A: serde::de::SeqAccess<'de>>(self, mut seq: A) -> Result<u64, A::Error> {
            let mut count = 0;
            while seq.next_element::<serde::de::IgnoredAny>()?.is_some() {
                count += 1;
            }
            Ok(count)
        }
    }

    deserializer.deserialize_seq(Count)
}

impl From<OverviewSnapshot> for OverviewResponse {
    fn from(snapshot: OverviewSnapshot) -> Self {
        Self {
            memory: MemoryResponse {
                inuse: snapshot.memory,
                // The core's /memory endpoint also reports no OS limit.
                oslimit: 0,
            },
            connection_count: snapshot.connection_count,
            download_total: snapshot.download_total,
            upload_total: snapshot.upload_total,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, uniffi::Record)]
pub struct ConfigResponse {
    #[serde(rename = "external-controller")]
    pub external_controller: Option<String>,
    pub secret: Option<String>,
    pub mode: Option<Mode>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct ProxiesResponse {
    pub proxies: HashMap<String, Proxy>,
}

/// Clash HTTP API client using Unix domain socket
#[derive(uniffi::Object)]
pub struct ClashController {
    #[allow(dead_code)]
    socket_path: String,
    // Constructed in the first async request, inside the Tokio runtime. Reusing
    // the client also reuses its HTTP connection pool between polls.
    #[cfg(unix)]
    client: OnceCell<reqwest::Client>,
}

#[uniffi::export(async_runtime = "tokio")]
impl ClashController {
    /// Create a new HTTP client that connects via Unix domain socket
    #[uniffi::constructor]
    pub fn new(socket_path: String) -> Arc<Self> {
        Arc::new(Self {
            socket_path,
            #[cfg(unix)]
            client: OnceCell::new(),
        })
    }

    /// Get all proxies
    pub async fn get_proxies(&self) -> Result<Vec<Proxy>, EyreError> {
        let mode = self.get_mode().await?.unwrap_or(Mode::Rule);

        // If in direct mode, return a single DIRECT proxy
        if matches!(mode, Mode::Direct) {
            return Ok(vec![Proxy {
                name: "DIRECT".to_string(),
                proxy_type: "Direct".to_string(),
                all: Vec::new(),
                now: None,
                history: Vec::new(),
            }]);
        }

        let mut response: ProxiesResponse = self.request(Method::GET, "/proxies", None).await?;

        // Get the order from GLOBAL proxy group's 'all' field
        if let Some(global_group) = response.proxies.remove("GLOBAL") {
            let mut sorted_proxies = Vec::new();

            // First add proxies in GLOBAL's 'all' order
            for name in &global_group.all {
                if let Some(proxy) = response.proxies.get(name) {
                    sorted_proxies.push(proxy.clone());
                }
            }

            // Then add any remaining proxies not in GLOBAL's 'all'
            for (name, proxy) in &response.proxies {
                if !global_group.all.contains(name) {
                    sorted_proxies.push(proxy.clone());
                }
            }

            // Add GLOBAL group at the front when GLOBAL mode is active
            if matches!(mode, Mode::Global) {
                sorted_proxies.insert(0, global_group);
            }

            Ok(sorted_proxies)
        } else {
            // If no GLOBAL group, return proxies as a vec
            Ok(response.proxies.values().cloned().collect())
        }
    }

    /// Select a proxy for a group
    pub async fn select_proxy(
        &self,
        group_name: String,
        proxy_name: String,
    ) -> Result<(), EyreError> {
        let body = serde_json::json!(
            {
                "name": proxy_name
            }
        );

        let path = proxy_path(&group_name)?;
        self.request_no_response(Method::PUT, &path, Some(serde_json::to_vec(&body)?))
            .await
    }

    /// Get proxy delay
    pub async fn get_proxy_delay(
        &self,
        name: String,
        url: Option<String>,
        timeout: Option<i32>,
    ) -> Result<DelayResponse, EyreError> {
        let test_url = url.unwrap_or_else(|| "http://www.gstatic.com/generate_204".to_string());
        let timeout_ms = timeout.unwrap_or(5000);

        let path = format!(
            "{}/delay?url={}&timeout={}",
            proxy_path(&name)?,
            encode(&test_url),
            timeout_ms
        );
        self.request(Method::GET, &path, None).await
    }

    /// Get memory statistics.
    pub async fn get_memory(&self) -> Result<MemoryResponse, EyreError> {
        self.request(Method::GET, "/memory", None).await
    }

    /// Get active connections
    pub async fn get_connections(&self) -> Result<ConnectionsResponse, EyreError> {
        tokio::time::timeout(
            STATS_REQUEST_TIMEOUT,
            self.request(Method::GET, "/connections", None),
        )
        .await
        .wrap_err("Connection statistics request timed out")?
    }

    /// Get home statistics without allocating or transferring connection records.
    /// The pinned core still generates a full /connections response; this avoids
    /// the additional Rust/FFI/Kotlin object graphs, not the HTTP response body.
    /// Its memory field also eliminates the separate /memory request.
    pub async fn get_overview(&self) -> Result<OverviewResponse, EyreError> {
        let snapshot: OverviewSnapshot = tokio::time::timeout(
            STATS_REQUEST_TIMEOUT,
            self.request(Method::GET, "/connections", None),
        )
        .await
        .wrap_err("Overview statistics request timed out")??;
        Ok(snapshot.into())
    }

    /// Get current configuration
    pub async fn get_configs(&self) -> Result<ConfigResponse, EyreError> {
        self.request(Method::GET, "/configs", None).await
    }

    /// Update configuration
    pub async fn update_config(&self, config: HashMap<String, String>) -> Result<(), EyreError> {
        let body_bytes = serde_json::to_vec(&config).wrap_err("Failed to serialize config")?;

        self.request_no_response(Method::PATCH, "/configs", Some(body_bytes))
            .await
    }

    /// Set proxy mode (rule, global, direct)
    pub async fn set_mode(&self, mode: Mode) -> Result<(), EyreError> {
        let mode_str = match mode {
            Mode::Rule => "rule",
            Mode::Global => "global",
            Mode::Direct => "direct",
        };
        let mut config = HashMap::new();
        config.insert("mode".to_string(), mode_str.to_string());
        self.update_config(config).await
    }

    /// Get current proxy mode
    pub async fn get_mode(&self) -> Result<Option<Mode>, EyreError> {
        let config = self.get_configs().await?;
        Ok(config.mode)
    }
}

impl ClashController {
    #[cfg(unix)]
    async fn do_request(
        &self,
        method: Method,
        path: &str,
        body: Option<Vec<u8>>,
    ) -> Result<Bytes, EyreError> {
        let client = self.client.get_or_try_init(|| {
            reqwest::Client::builder()
                .unix_socket(self.socket_path.as_str())
                .http1_only()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .retry(reqwest::retry::never())
                .pool_idle_timeout(Duration::from_secs(15))
                .pool_max_idle_per_host(1)
                .build()
                .wrap_err("Failed to build controller HTTP client")
        })?;
        // localhost supplies the HTTP authority. The client connects only to the configured socket.
        let mut request = client
            .request(method, format!("http://localhost{path}"))
            .header(reqwest::header::CONTENT_TYPE, "application/json");
        if let Some(body) = body {
            request = request.body(body);
        }
        let response = request.send().await.wrap_err("HTTP request failed")?;
        if !response.status().is_success() {
            eyre::bail!("HTTP status error: {}", response.status());
        }
        response
            .bytes()
            .await
            .wrap_err("Failed to read response body")
    }

    #[cfg(not(unix))]
    async fn do_request(
        &self,
        _method: Method,
        _path: &str,
        _body: Option<Vec<u8>>,
    ) -> Result<Bytes, EyreError> {
        Err(eyre::eyre!(
            "ClashController requires Unix domain sockets, which are not available on this platform"
        ))
    }

    async fn request_no_response(
        &self,
        method: Method,
        path: &str,
        body: Option<Vec<u8>>,
    ) -> Result<(), EyreError> {
        self.do_request(method, path, body).await.map(|_| ())
    }

    async fn request<T>(
        &self,
        method: Method,
        path: &str,
        body: Option<Vec<u8>>,
    ) -> Result<T, EyreError>
    where
        T: serde::de::DeserializeOwned,
    {
        let body_bytes = self.do_request(method, path, body).await?;
        serde_json::from_slice(&body_bytes).wrap_err_with(|| {
            format!(
                "Failed to parse JSON response: {}",
                String::from_utf8_lossy(&body_bytes)
            )
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn overview_preserves_totals_and_counts_without_connection_schema() {
        // Overview does not need the detail fields or their schema. Nested and
        // escaped values must be skipped without mistaking them for records.
        let snapshot: OverviewSnapshot = serde_json::from_str(
            r#"{
                "downloadTotal": 4294967296,
                "uploadTotal": 123,
                "memory": 987654,
                "connections": [
                    {"metadata": {"host": "a,]\"b", "extra": [1, {"x": true}]}},
                    {"id": "second", "chains": ["DIRECT"]}
                ],
                "futureField": {"ignored": true}
            }"#,
        )
        .unwrap();
        let overview: OverviewResponse = snapshot.into();
        assert_eq!(overview.connection_count, 2);
        assert_eq!(overview.download_total, 4294967296);
        assert_eq!(overview.upload_total, 123);
        assert_eq!(overview.memory.inuse, 987654);
        assert_eq!(overview.memory.oslimit, 0);
    }

    #[test]
    fn overview_handles_empty_and_large_connection_lists() {
        for count in [0, 10_000] {
            let entries = std::iter::repeat_n(r#"{"metadata":{"host":"example.com"}}"#, count)
                .collect::<Vec<_>>()
                .join(",");
            let json = format!(
                r#"{{"downloadTotal":0,"uploadTotal":0,"memory":0,"connections":[{entries}]}}"#,
            );
            let snapshot: OverviewSnapshot = serde_json::from_str(&json).unwrap();
            assert_eq!(snapshot.connection_count, count as u64);
        }
    }

    #[test]
    fn overview_rejects_missing_or_malformed_statistics() {
        for json in [
            r#"{"downloadTotal":0,"uploadTotal":0,"memory":0}"#,
            r#"{"downloadTotal":0,"uploadTotal":0,"connections":[]}"#,
            r#"{"downloadTotal":0,"uploadTotal":0,"memory":0,"connections":null}"#,
            r#"{"downloadTotal":0,"uploadTotal":0,"memory":0,"connections":{}}"#,
            r#"{"downloadTotal":0,"uploadTotal":0,"memory":0,"connections":[{"broken":]}"#,
        ] {
            assert!(serde_json::from_str::<OverviewSnapshot>(json).is_err());
        }
    }
}

#[cfg(all(test, unix))]
mod unix_tests;
