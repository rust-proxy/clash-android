use super::*;

use tempfile::TempDir;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::{UnixListener, UnixStream};

fn endpoint() -> (TempDir, Arc<ClashController>, UnixListener) {
    let directory = tempfile::tempdir().unwrap();
    let path = directory.path().join("controller.sock");
    let listener = UnixListener::bind(&path).unwrap();
    let controller = ClashController::new(path.to_str().unwrap().to_owned());
    (directory, controller, listener)
}

async fn read_request(socket: &mut UnixStream) -> (String, Vec<u8>) {
    let mut reader = BufReader::new(socket);
    let mut headers = String::new();
    while !headers.ends_with("\r\n\r\n") {
        assert_ne!(reader.read_line(&mut headers).await.unwrap(), 0);
    }
    let length = headers
        .lines()
        .filter_map(|line| line.split_once(':'))
        .find(|(name, _)| name.eq_ignore_ascii_case("content-length"))
        .map_or(0, |(_, value)| value.trim().parse().unwrap());
    let mut body = vec![0; length];
    reader.read_exact(&mut body).await.unwrap();
    (headers, body)
}

async fn reply_once(listener: UnixListener, response: &str) -> (String, Vec<u8>) {
    let (mut socket, _) = tokio::time::timeout(Duration::from_secs(5), listener.accept())
        .await
        .unwrap()
        .unwrap();
    let request = read_request(&mut socket).await;
    socket.write_all(response.as_bytes()).await.unwrap();
    request
}

#[tokio::test]
async fn overview_reuses_the_unix_http_connection() {
    let (_dir, controller, listener) = endpoint();
    let server = async {
        // Accept once: both polls must use the same open socket.
        let (mut socket, _) = listener.accept().await.unwrap();
        let body = r#"{"downloadTotal":7,"uploadTotal":3,"memory":12,"connections":[]}"#;
        for _ in 0..2 {
            let (headers, _) = read_request(&mut socket).await;
            assert!(headers.starts_with("GET /connections HTTP/1.1\r\n"));
            socket
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n{body}",
                        body.len()
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
        }
    };
    let polls = async {
        for _ in 0..2 {
            let response = controller.get_overview().await.unwrap();
            assert_eq!(response.download_total, 7);
            assert_eq!(response.connection_count, 0);
        }
    };
    tokio::time::timeout(Duration::from_secs(5), async {
        tokio::join!(server, polls);
    })
    .await
    .expect("successive polls must reuse the same connection");
}

#[tokio::test]
async fn selection_preserves_encoded_path_json_body_and_empty_success() {
    let (_dir, controller, listener) = endpoint();
    let (result, (headers, body)) = tokio::join!(
        controller.select_proxy("组 /?#%".into(), "node \"A\"".into()),
        reply_once(listener, "HTTP/1.1 204 No Content\r\n\r\n"),
    );
    result.unwrap();
    assert!(headers.starts_with("PUT /proxies/%E7%BB%84%20%2F%3F%23%25 HTTP/1.1\r\n"));
    assert!(
        headers
            .to_ascii_lowercase()
            .contains("\r\ncontent-type: application/json\r\n")
    );
    assert_eq!(
        serde_json::from_slice::<serde_json::Value>(&body).unwrap(),
        serde_json::json!({"name": "node \"A\""})
    );
}

#[tokio::test]
async fn rejects_redirects_and_http_errors_without_parsing_the_body() {
    for status in ["302 Found", "404 Not Found", "503 Service Unavailable"] {
        let (_dir, controller, listener) = endpoint();
        let response =
            format!("HTTP/1.1 {status}\r\nLocation: /other\r\nContent-Length: 3\r\n\r\nbad");
        let (result, _) = tokio::join!(controller.get_mode(), reply_once(listener, &response));
        assert_eq!(
            result.unwrap_err().to_string(),
            format!("HTTP status error: {status}")
        );
    }
}

#[tokio::test]
async fn distinguishes_invalid_json_from_a_truncated_body() {
    for (length, expected) in [
        (3, "Failed to parse JSON response: bad"),
        (10, "Failed to read response body"),
    ] {
        let (_dir, controller, listener) = endpoint();
        let response = format!("HTTP/1.1 200 OK\r\nContent-Length: {length}\r\n\r\nbad");
        let (result, _) = tokio::join!(controller.get_mode(), reply_once(listener, &response));
        assert_eq!(result.unwrap_err().to_string(), expected);
    }
}

#[tokio::test]
async fn missing_socket_is_a_transport_error() {
    let directory = tempfile::tempdir().unwrap();
    let controller = ClashController::new(
        directory
            .path()
            .join("missing.sock")
            .to_str()
            .unwrap()
            .into(),
    );
    assert_eq!(
        controller.get_mode().await.unwrap_err().to_string(),
        "HTTP request failed"
    );
}

#[tokio::test]
async fn both_statistics_deadlines_cover_a_stalled_response_body() {
    let (_dir, controller, listener) = endpoint();
    let server = async {
        let mut sockets = Vec::new();
        for _ in 0..2 {
            let (mut socket, _) = listener.accept().await.unwrap();
            read_request(&mut socket).await;
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 20\r\n\r\n{")
                .await
                .unwrap();
            sockets.push(socket);
        }
        sockets // Keep both incomplete responses open until the clients time out.
    };
    let start = tokio::time::Instant::now();
    let (_, overview, connections) = tokio::time::timeout(Duration::from_secs(15), async {
        tokio::join!(
            server,
            controller.get_overview(),
            controller.get_connections()
        )
    })
    .await
    .expect("statistics calls must not hang on a partial response");
    assert!(start.elapsed() >= Duration::from_secs(10));
    assert_eq!(
        overview.unwrap_err().to_string(),
        "Overview statistics request timed out"
    );
    assert_eq!(
        connections.unwrap_err().to_string(),
        "Connection statistics request timed out"
    );
}

#[tokio::test]
async fn dot_names_fail_before_a_request_can_target_a_different_route() {
    let (_dir, controller, listener) = endpoint();
    drop(listener); // A request sent by mistake would return a transport error.
    for name in [".", ".."] {
        let error = format!(
            "Cannot address proxy or group named '{name}': reqwest normalizes dot path segments"
        );
        assert_eq!(
            controller
                .select_proxy(name.into(), "DIRECT".into())
                .await
                .unwrap_err()
                .to_string(),
            error
        );
        assert_eq!(
            controller
                .get_proxy_delay(name.into(), None, None)
                .await
                .unwrap_err()
                .to_string(),
            error
        );
    }
}

#[tokio::test]
async fn delay_preserves_the_query_and_can_outlast_the_statistics_deadline() {
    let (_dir, controller, listener) = endpoint();
    let server = async {
        let (mut socket, _) = listener.accept().await.unwrap();
        let (headers, _) = read_request(&mut socket).await;
        assert!(headers.starts_with("GET /proxies/node.example/delay?url=https%3A%2F%2Fexample.com%2Fa%3Fx%3D1%26y%3D2&timeout=12000 HTTP/1.1\r\n"));
        tokio::time::sleep(STATS_REQUEST_TIMEOUT + Duration::from_millis(100)).await;
        socket
            .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\n{\"delay\":7}")
            .await
            .unwrap();
    };
    let delay = controller.get_proxy_delay(
        "node.example".into(),
        Some("https://example.com/a?x=1&y=2".into()),
        Some(12000),
    );
    let (_, result) = tokio::time::timeout(Duration::from_secs(15), async {
        tokio::join!(server, delay)
    })
    .await
    .unwrap();
    assert_eq!(result.unwrap().delay, 7);
}
