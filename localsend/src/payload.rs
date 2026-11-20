use std::collections::HashMap;

use localsend::{crypto::hash::sha256_hex, model::transfer::FileDto};

use crate::TransferError;

pub const MAX_SIZE: u64 = 16 * 1024 * 1024;
const MAX_URL_SIZE: u64 = 16 * 1024;
const URL_SUFFIX: &str = ".subscription.txt";

pub fn subscription_name(profile: &str) -> String {
    format!(
        "{}{URL_SUFFIX}",
        profile.rsplit_once('.').map_or(profile, |(stem, _)| stem)
    )
}

pub fn subscription_url(text: &str) -> Result<String, TransferError> {
    let text = text.trim_end_matches(['\r', '\n']);
    if text.len() as u64 > MAX_URL_SIZE
        || text.chars().any(char::is_whitespace)
        || text.chars().any(char::is_control)
    {
        return Err(TransferError::Subscription);
    }
    let parsed = url::Url::parse(text).map_err(|_| TransferError::Subscription)?;
    if !matches!(parsed.scheme(), "https" | "http") || !parsed.has_host() {
        return Err(TransferError::Subscription);
    }
    Ok(text.to_owned())
}

pub fn file_info(id: &str, name: String, mime: &str, data: &[u8]) -> FileDto {
    FileDto {
        id: id.into(),
        file_name: name,
        file_type: mime.into(),
        size: data.len() as u64,
        sha256: Some(sha256_hex(data)),
        preview: None,
        metadata: None,
    }
}

pub struct Offer {
    pub name: String,
    pub size: u64,
    pub profile_id: String,
    pub url_id: Option<String>,
}

impl Offer {
    pub fn parse(files: &HashMap<String, FileDto>) -> Option<Self> {
        if !(1..=2).contains(&files.len()) {
            return None;
        }
        let (profile_id, profile) = files
            .iter()
            .find(|(_, f)| !f.file_name.ends_with(URL_SUFFIX))?;
        if !(1..=MAX_SIZE).contains(&profile.size) {
            return None;
        }
        let mut offer = Self {
            name: profile.file_name.clone(),
            size: profile.size,
            profile_id: profile_id.clone(),
            url_id: None,
        };
        if files.len() == 2 {
            let (id, link) = files
                .iter()
                .find(|(_, f)| f.file_name == subscription_name(&profile.file_name))?;
            if !(1..=MAX_URL_SIZE).contains(&link.size) {
                return None;
            }
            offer.url_id = Some(id.clone());
            offer.size += link.size;
        }
        Some(offer)
    }
}
