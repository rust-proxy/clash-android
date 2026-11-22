use super::{
    HTTPS, Inputs, TransferError, TransferEvent, TransferSession,
    payload::{Offer, subscription_url},
};
use localsend::{
    discovery::{DeviceChannel, DiscoveredDevice, HttpChannel},
    http::server::{
        common::save::FileUploadTarget,
        v2::{PrepareUploadDecisionV2, ServerEventV2},
    },
};
use tokio::sync::oneshot;

type Decision = oneshot::Sender<PrepareUploadDecisionV2>;
pub(super) struct Pending {
    id: String,
    offer: Offer,
    decision: Option<Decision>,
    received_profile: bool,
    received_url: Option<String>,
}

#[uniffi::export(async_runtime = "tokio")]
impl TransferSession {
    pub fn answer(&self, id: String, accept: bool) {
        let mut pending = self.pending.lock().unwrap();
        let Some(p) = pending.as_mut().filter(|p| p.id == id) else {
            return;
        };
        let Some(tx) = p.decision.take() else {
            return;
        };
        if accept {
            let ids = std::iter::once(p.offer.profile_id.clone())
                .chain(p.offer.url_id.clone())
                .collect();
            if tx.send(PrepareUploadDecisionV2::Accept(ids)).is_ok() {
                return;
            }
        } else {
            let _ = tx.send(PrepareUploadDecisionV2::Decline);
        }
        pending.take();
    }

    pub async fn next_event(&self) -> Result<TransferEvent, TransferError> {
        let mut inputs = self.inputs.lock().await;
        let Inputs { server, discovery } = &mut *inputs;
        loop {
            let event = tokio::select! {
                _ = self.stop.cancelled() => return Err(TransferError::Failed),
                Some(_) = discovery.recv() => return Ok(TransferEvent::PeersChanged),
                event = server.recv() => event.ok_or(TransferError::Unavailable)?,
            };
            if let Some(event) = self.receive_event(event).await? {
                return Ok(event);
            }
        }
    }
}

impl TransferSession {
    async fn receive_event(
        &self,
        event: ServerEventV2,
    ) -> Result<Option<TransferEvent>, TransferError> {
        match event {
            ServerEventV2::Register { ip, info }
                if self.on_lan(&ip.to_string()) && info.protocol == HTTPS =>
            {
                self.discovery
                    .observe_inbound(DiscoveredDevice {
                        alias: info.alias,
                        version: info.version,
                        device_model: info.device_model,
                        device_type: info.device_type.clone(),
                        fingerprint: info.fingerprint,
                        download: false,
                        channel: DeviceChannel::Http(HttpChannel {
                            host: ip.to_string(),
                            port: info.port,
                            protocol: HTTPS,
                        }),
                    })
                    .await;
                return Ok(Some(TransferEvent::PeersChanged));
            }
            ServerEventV2::PrepareUpload {
                ip,
                files,
                info,
                session_id,
                decision_tx,
                ..
            } => {
                let mut pending = self.pending.lock().unwrap();
                if self.receive_path.is_some()
                    && self.on_lan(&ip.to_string())
                    && pending.is_none()
                    && let Some(offer) = Offer::parse(&files)
                {
                    let event = TransferEvent::Offer {
                        id: session_id.clone(),
                        alias: info.alias,
                        name: offer.name.clone(),
                        size: offer.size,
                        has_subscription: offer.url_id.is_some(),
                    };
                    *pending = Some(Pending {
                        id: session_id,
                        offer,
                        decision: Some(decision_tx),
                        received_profile: false,
                        received_url: None,
                    });
                    return Ok(Some(event));
                }
                let _ = decision_tx.send(PrepareUploadDecisionV2::Decline);
            }
            ServerEventV2::PrepareUploadAborted { session_id } => {
                self.pending.lock().unwrap().take_if(|p| p.id == session_id);
                return Ok(Some(TransferEvent::Withdrawn { id: session_id }));
            }
            ServerEventV2::FileUpload {
                target_tx,
                file_id,
                session_id,
                ..
            } => {
                let is_url = {
                    let pending = self.pending.lock().unwrap();
                    let Some(p) = pending
                        .as_ref()
                        .filter(|p| p.id == session_id && p.decision.is_none())
                    else {
                        return Ok(None);
                    };
                    if file_id == p.offer.profile_id {
                        false
                    } else if p.offer.url_id.as_ref() == Some(&file_id) {
                        true
                    } else {
                        return Ok(None);
                    }
                };
                let scratch = self.receive_path.as_ref().ok_or(TransferError::Failed)?;
                let url_path = if is_url {
                    let dir = scratch.parent().ok_or(TransferError::Failed)?;
                    Some(
                        tempfile::NamedTempFile::new_in(dir)
                            .map_err(|_| TransferError::Failed)?
                            .into_temp_path(),
                    )
                } else {
                    None
                };
                let path = url_path
                    .as_ref()
                    .map_or_else(|| scratch.clone(), |p| p.to_path_buf());
                let (tx, rx) = oneshot::channel();
                target_tx
                    .send(FileUploadTarget::Path {
                        path,
                        result_tx: tx,
                        progress_tx: None,
                    })
                    .map_err(|_| TransferError::Failed)?;
                tokio::select! {
                    _ = self.stop.cancelled() => return Err(TransferError::Failed),
                    result = rx => result.map_err(|_| TransferError::Failed)?.map_err(|_| TransferError::Failed)?,
                }
                let url = if let Some(path) = url_path {
                    Some(subscription_url(
                        &tokio::fs::read_to_string(&path)
                            .await
                            .map_err(|_| TransferError::Subscription)?,
                    )?)
                } else {
                    None
                };
                let mut pending = self.pending.lock().unwrap();
                let Some(p) = pending.as_mut().filter(|p| p.id == session_id) else {
                    return Ok(None);
                };
                if is_url {
                    p.received_url = url;
                } else {
                    p.received_profile = true;
                }
                if p.received_profile && (p.offer.url_id.is_none() || p.received_url.is_some()) {
                    let completed = pending.take().unwrap();
                    return Ok(Some(TransferEvent::Received {
                        name: completed.offer.name,
                        url: completed.received_url,
                    }));
                }
            }
            ServerEventV2::SessionEnd { session_id, .. } => {
                if self
                    .pending
                    .lock()
                    .unwrap()
                    .take_if(|p| p.id == session_id)
                    .is_some()
                {
                    return Ok(Some(TransferEvent::Withdrawn { id: session_id }));
                }
            }
            ServerEventV2::ListenerFailed { .. } => return Err(TransferError::Unavailable),
            _ => {}
        }
        Ok(None)
    }
}
