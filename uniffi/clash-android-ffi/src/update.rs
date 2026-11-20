use std::time::Duration;

use bytes::Bytes;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncWrite, AsyncWriteExt};
use tokio_stream::{Stream, StreamExt};

use crate::EyreError;
use crate::util::{DownloadProgress, DownloadProgressCallback, download_client};

const MANIFEST_LIMIT: usize = 64 * 1024;

async fn fetch_update_asset(
    url: String,
    proxy_url: Option<String>,
) -> Result<reqwest::Response, EyreError> {
    let response = download_client(None, proxy_url)?
        .https_only(true)
        .redirect(reqwest::redirect::Policy::limited(5))
        .connect_timeout(Duration::from_secs(15))
        .read_timeout(Duration::from_secs(30))
        .build()?
        .get(url)
        .send()
        .await?;
    eyre::ensure!(
        response.status() == reqwest::StatusCode::OK,
        "Update request returned {}",
        response.status()
    );
    Ok(response)
}

/// None indicates invalid metadata. Transport and HTTP errors are retryable failures.
#[uniffi::export(async_runtime = "tokio")]
async fn fetch_update_manifest(
    url: String,
    proxy_url: Option<String>,
) -> Result<Option<String>, EyreError> {
    let response = fetch_update_asset(url, proxy_url).await?;
    if response
        .content_length()
        .is_some_and(|n| n >= MANIFEST_LIMIT as u64)
    {
        return Ok(None);
    }
    read_manifest(response.bytes_stream()).await
}

async fn read_manifest(
    mut body: impl Stream<Item = Result<Bytes, reqwest::Error>> + Unpin,
) -> Result<Option<String>, EyreError> {
    let mut bytes = Vec::new();
    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        if chunk.len() >= MANIFEST_LIMIT - bytes.len() {
            return Ok(None);
        }
        bytes.extend_from_slice(&chunk);
    }
    Ok(String::from_utf8(bytes).ok())
}

/// Write only to the caller's private, pre-created temporary APK. The caller
/// removes it on cancellation/failure and checks its Android identity before use.
#[uniffi::export(async_runtime = "tokio")]
async fn download_apk(
    url: String,
    temporary_path: String,
    size: u64,
    sha256: String,
    proxy_url: Option<String>,
    progress_callback: Box<dyn DownloadProgressCallback>,
) -> Result<bool, EyreError> {
    let response = fetch_update_asset(url, proxy_url).await?;
    if size == 0
        || response
            .content_length()
            .is_some_and(|length| length != size)
    {
        return Ok(false);
    }
    let mut output = tokio::fs::OpenOptions::new()
        .write(true)
        .truncate(true)
        .open(temporary_path)
        .await?;
    write_verified_apk(
        response.bytes_stream(),
        &mut output,
        size,
        &sha256,
        |downloaded| {
            progress_callback.on_progress(DownloadProgress {
                downloaded,
                total: size,
            });
        },
    )
    .await
}

async fn write_verified_apk(
    mut body: impl Stream<Item = Result<Bytes, reqwest::Error>> + Unpin,
    output: &mut (impl AsyncWrite + Unpin),
    size: u64,
    sha256: &str,
    mut progress: impl FnMut(u64),
) -> Result<bool, EyreError> {
    let mut digest = Sha256::new();
    let mut downloaded = 0u64;
    let mut last_percent = 0;
    while let Some(chunk) = body.next().await {
        let chunk = chunk?;
        if chunk.len() as u64 > size - downloaded {
            return Ok(false);
        }
        output.write_all(&chunk).await?;
        digest.update(&chunk);
        downloaded += chunk.len() as u64;
        let percent = (u128::from(downloaded) * 100) / u128::from(size);
        if percent != last_percent {
            progress(downloaded);
            last_percent = percent;
        }
    }
    output.flush().await?;
    Ok(downloaded == size && format!("{:x}", digest.finalize()) == sha256)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn manifest_limit_applies_across_chunks_and_utf8_is_decoded_after_reading() {
        let prefix = Bytes::from(vec![b'a'; MANIFEST_LIMIT - 4]);
        let suffix = "新".as_bytes();
        let chunks = [
            prefix.clone(),
            Bytes::from_static(&suffix[..1]),
            Bytes::from_static(&suffix[1..]),
        ];
        let manifest = read_manifest(tokio_stream::iter(chunks.map(Ok)))
            .await
            .unwrap()
            .unwrap();
        assert_eq!(manifest.len(), MANIFEST_LIMIT - 1);
        assert!(manifest.ends_with('新'));
        for tail in [
            Bytes::from_static(b"abcd"),
            Bytes::from_static(b"abcde"),
            Bytes::from_static(b"\xff"),
        ] {
            let chunks = [prefix.clone(), tail];
            assert!(
                read_manifest(tokio_stream::iter(chunks.map(Ok)))
                    .await
                    .unwrap()
                    .is_none()
            );
        }
    }

    #[tokio::test]
    async fn rejects_bad_hash_and_short_or_oversized_streams() {
        let hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        for (tail, expected_output, valid) in [
            ("c", "abc", true),
            ("d", "abd", false),
            ("", "ab", false),
            ("cd", "ab", false),
        ] {
            let chunks = [
                Bytes::from_static(b"ab"),
                Bytes::from_static(tail.as_bytes()),
            ];
            let mut output = Vec::new();
            let mut progress = Vec::new();
            let verified = write_verified_apk(
                tokio_stream::iter(chunks.map(Ok)),
                &mut output,
                3,
                hash,
                |n| progress.push(n),
            )
            .await
            .unwrap();
            assert_eq!(verified, valid);
            assert_eq!(output, expected_output.as_bytes());
            assert_eq!(progress.last().copied(), Some(output.len() as u64));
        }
    }
}
