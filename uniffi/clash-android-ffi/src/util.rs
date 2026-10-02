use eyre::Context;
use tokio_stream::StreamExt;
use tracing::{error, info};

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
    info!("Starting download from: {}", url);

    let ua = user_agent.unwrap_or_else(|| "clash-android/1.0".to_string());
    info!("Using User-Agent: {}", ua);

    // Build reqwest client. Its `rustls` feature verifies certificates with
    // `rustls-platform-verifier`, i.e. the OS trust store (the Android verifier
    // is initialised in `java_init`), instead of bundled webpki roots.
    let mut client_builder = reqwest::Client::builder()
        .user_agent(&ua)
        .redirect(reqwest::redirect::Policy::limited(10));

    // Add proxy if provided
    if let Some(proxy) = proxy_url {
        info!("Using proxy: {}", proxy);
        let proxy =
            reqwest::Proxy::all(&proxy).map_err(|e| eyre::eyre!("Invalid proxy URL: {}", e))?;
        client_builder = client_builder.proxy(proxy);
    }

    let client = client_builder
        .build()
        .map_err(|e| eyre::eyre!("Failed to build HTTP client: {}", e))?;

    // Send request
    info!("Sending request to: {}", url);
    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| eyre::eyre!("Failed to send request: {}", e))?;

    let status = response.status();
    if !status.is_success() {
        error!(
            "HTTP request failed with status: {} for URL: {}",
            status, url
        );
        return Ok(DownloadResult {
            success: false,
            file_size: 0,
            error_message: Some(format!(
                "HTTP {} - {}",
                status.as_u16(),
                status.canonical_reason().unwrap_or("Unknown")
            )),
        });
    }

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

    // Download with progress tracking
    let mut stream = response.bytes_stream();
    let mut downloaded: u64 = 0;
    let mut buffer = Vec::new();

    while let Some(chunk) = stream.next().await {
        let chunk = chunk.map_err(|e| eyre::eyre!("Failed to read chunk: {}", e))?;
        buffer.extend_from_slice(&chunk);
        downloaded += chunk.len() as u64;

        // Report progress
        if let Some(ref callback) = progress_callback {
            info!("Progress: {}/{} bytes", downloaded, total_size);
            callback.on_progress(DownloadProgress {
                downloaded,
                total: total_size,
            });
        }
    }

    // Create output file and write
    _ = tokio::fs::File::create(&output_path)
        .await
        .context(format!("Failed to create file: {output_path}"))?;
    tokio::fs::write(&output_path, &buffer)
        .await
        .context(format!("Failed to write to file: {output_path}"))?;

    let file_size = buffer.len() as u64;
    info!(
        "Download completed: {} bytes written to {}",
        file_size, output_path
    );

    Ok(DownloadResult {
        success: true,
        file_size,
        error_message: None,
    })
}
