use super::{
    HTTPS, IDENTITY, TransferError, TransferSession,
    payload::{MAX_SIZE, file_info, subscription_name, subscription_url},
};
use localsend::http::{client::LsHttpClientV2, dto_v2::PrepareUploadRequestDtoV2};
use std::time::Duration;
use tokio::io::AsyncReadExt;

#[uniffi::export(async_runtime = "tokio")]
impl TransferSession {
    pub async fn send(
        &self,
        fingerprint: String,
        path: String,
        name: String,
        pin: Option<String>,
        url: Option<String>,
    ) -> Result<(), TransferError> {
        let peer = self
            .discovery
            .device_by_fingerprint(&fingerprint)
            .filter(|p| p.confirmed)
            .ok_or(TransferError::Unavailable)?;
        let h = peer
            .device
            .http()
            .filter(|h| h.protocol == HTTPS && self.on_lan(&h.host))
            .ok_or(TransferError::Unavailable)?;
        let file = tokio::fs::File::open(path)
            .await
            .map_err(|_| TransferError::File)?;
        let mut bytes = Vec::new();
        file.take(MAX_SIZE + 1)
            .read_to_end(&mut bytes)
            .await
            .map_err(|_| TransferError::File)?;
        if bytes.is_empty() || bytes.len() as u64 > MAX_SIZE {
            return Err(TransferError::File);
        }
        let cert = IDENTITY.get().ok_or(TransferError::Unavailable)?;
        let client = LsHttpClientV2::try_new(
            &cert.private_key_pem,
            &cert.certificate_pem,
            Some(fingerprint),
            Some(Duration::from_secs(120)),
        )?;
        let mut parts = Vec::with_capacity(2);
        if let Some(url) = url {
            let data = subscription_url(&url)?.into_bytes();
            parts.push((
                file_info(
                    "subscription",
                    subscription_name(&name),
                    "text/plain",
                    &data,
                ),
                data,
            ));
        }
        parts.push((
            file_info("profile", name, "application/yaml", &bytes),
            bytes,
        ));
        let request = PrepareUploadRequestDtoV2 {
            info: self.info.clone(),
            files: parts
                .iter()
                .map(|(f, _)| (f.id.clone(), f.clone()))
                .collect(),
        };
        let prepared = client
            .prepare_upload(
                HTTPS,
                &h.host,
                h.port,
                None,
                request,
                pin.as_deref(),
                self.stop.clone(),
            )
            .await?
            .response
            .ok_or(TransferError::Declined)?;
        if !parts
            .iter()
            .all(|(file, _)| prepared.files.contains_key(&file.id))
        {
            let _ = client
                .cancel(HTTPS, &h.host, h.port, &prepared.session_id)
                .await;
            return Err(TransferError::Declined);
        }
        for (file, bytes) in parts {
            let upload = client
                .upload(
                    HTTPS,
                    &h.host,
                    h.port,
                    None,
                    &prepared.session_id,
                    &file.id,
                    &prepared.files[&file.id],
                    bytes.into(),
                    self.stop.clone(),
                )
                .await;
            if let Err(error) = upload {
                let _ = client
                    .cancel(HTTPS, &h.host, h.port, &prepared.session_id)
                    .await;
                return Err(error.into());
            }
        }
        Ok(())
    }
}
