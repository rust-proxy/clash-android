use std::path::Path;

use eyre::Context;
use tempfile::{NamedTempFile, TempPath};
use tokio::io::AsyncWriteExt;
use tokio_stream::StreamExt;
use tracing::info;

use crate::EyreError;

#[derive(uniffi::Record)]
pub struct DownloadResult {
    pub success: bool,
    pub file_size: u64,
    pub error_message: Option<String>,
}

#[derive(uniffi::Record)]
pub struct DownloadProgress {
    pub downloaded: u64,
    pub total: u64,
}

#[uniffi::export(callback_interface)]
pub trait DownloadProgressCallback: Send + Sync {
    fn on_progress(&self, progress: DownloadProgress);
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn download_file(
    url: String,
    output_path: String,
    user_agent: Option<String>,
    proxy_url: Option<String>,
) -> Result<DownloadResult, EyreError> {
    download_file_with_progress(url, output_path, user_agent, proxy_url, None).await
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn download_file_with_progress(
    url: String,
    output_path: String,
    user_agent: Option<String>,
    proxy_url: Option<String>,
    progress_callback: Option<Box<dyn DownloadProgressCallback>>,
) -> Result<DownloadResult, EyreError> {
    let response = fetch(&url, user_agent, proxy_url).await?;
    if let Some(message) = http_error(&response) {
        return Ok(DownloadResult {
            success: false,
            file_size: 0,
            error_message: Some(message),
        });
    }
    let destination = Path::new(&output_path);
    let (staged, file_size) = stage_download(response, destination, progress_callback).await?;
    staged
        .persist(destination)
        .map_err(std::io::Error::from)
        .wrap_err("Failed to install download")?;
    Ok(DownloadResult {
        success: true,
        file_size,
        error_message: None,
    })
}

#[derive(uniffi::Enum)]
pub enum SubscriptionUpdate {
    Updated { file_size: u64 },
    InvalidConfig { detail: String },
}

#[uniffi::export(async_runtime = "tokio")]
pub async fn update_subscription(
    url: String,
    output_path: String,
    user_agent: Option<String>,
    proxy_url: Option<String>,
    progress_callback: Option<Box<dyn DownloadProgressCallback>>,
) -> Result<SubscriptionUpdate, EyreError> {
    let response = fetch(&url, user_agent, proxy_url).await?;
    install_subscription(response, Path::new(&output_path), progress_callback).await
}

async fn install_subscription(
    response: reqwest::Response,
    destination: &Path,
    progress_callback: Option<Box<dyn DownloadProgressCallback>>,
) -> Result<SubscriptionUpdate, EyreError> {
    if let Some(message) = http_error(&response) {
        eyre::bail!(message);
    }
    let (staged, file_size) = stage_download(response, destination, progress_callback).await?;
    let path = staged
        .to_str()
        .ok_or_else(|| eyre::eyre!("Invalid subscription path"))?;
    if let Err(error) = crate::verify_config(path) {
        return Ok(SubscriptionUpdate::InvalidConfig {
            detail: error.to_string(),
        });
    }
    // Drop the failed TempPath here instead of retaining it in a Kotlin exception.
    staged
        .persist(destination)
        .map_err(std::io::Error::from)
        .wrap_err("Failed to replace subscription")?;
    Ok(SubscriptionUpdate::Updated { file_size })
}

fn http_error(response: &reqwest::Response) -> Option<String> {
    let status = response.status();
    (!status.is_success()).then(|| {
        format!(
            "HTTP {} - {}",
            status.as_u16(),
            status.canonical_reason().unwrap_or("Unknown")
        )
    })
}

async fn fetch(
    url: &str,
    user_agent: Option<String>,
    proxy_url: Option<String>,
) -> Result<reqwest::Response, EyreError> {
    let ua = user_agent.unwrap_or_else(|| "clash-android/1.0".to_string());

    // Build reqwest client. Its `rustls` feature verifies certificates with
    // `rustls-platform-verifier`, i.e. the OS trust store (the Android verifier
    // is initialised in `java_init`), instead of bundled webpki roots.
    let mut client_builder = reqwest::Client::builder()
        .user_agent(&ua)
        .redirect(reqwest::redirect::Policy::limited(10));

    // Add proxy if provided
    if let Some(proxy) = proxy_url {
        let proxy =
            reqwest::Proxy::all(proxy).map_err(|e| eyre::eyre!("Invalid proxy URL: {}", e))?;
        client_builder = client_builder.proxy(proxy);
    }

    let client = client_builder
        .build()
        .map_err(|e| eyre::eyre!("Failed to build HTTP client: {}", e))?;

    client
        .get(url)
        .send()
        .await
        .wrap_err("Failed to send download request")
}

async fn stage_download(
    response: reqwest::Response,
    destination: &Path,
    progress_callback: Option<Box<dyn DownloadProgressCallback>>,
) -> Result<(TempPath, u64), EyreError> {
    // Get content length
    let total_size = response.content_length().unwrap_or(0);
    info!("Content length: {} bytes", total_size);

    // Report initial progress
    if let Some(ref callback) = progress_callback {
        info!("Reporting initial progress: 0/{}", total_size);
        callback.on_progress(DownloadProgress {
            downloaded: 0,
            total: total_size,
        });
    }

    let parent = destination
        .parent()
        .ok_or_else(|| eyre::eyre!("Invalid output path"))?;
    let (file, staged) = NamedTempFile::new_in(parent)
        .wrap_err("Failed to create temporary download")?
        .into_parts();
    let mut file = tokio::fs::File::from_std(file);
    let mut stream = response.bytes_stream();
    let mut downloaded = 0;
    while let Some(chunk) = stream.next().await {
        let chunk = chunk.wrap_err("Failed to read download")?;
        file.write_all(&chunk)
            .await
            .wrap_err("Failed to write download")?;
        downloaded += chunk.len() as u64;
        if let Some(ref callback) = progress_callback {
            callback.on_progress(DownloadProgress {
                downloaded,
                total: total_size,
            });
        }
    }
    file.flush().await.wrap_err("Failed to flush download")?;
    file.sync_all().await.wrap_err("Failed to sync download")?;
    drop(file);
    Ok((staged, downloaded))
}
