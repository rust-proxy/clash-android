use crate::log::init_logger;
use async_compat::set_runtime_builder;
use clash_lib::config::def::LogLevel;
use std::{
    path::PathBuf,
    sync::{Once, OnceLock},
};
use tracing::{error, info};

#[unsafe(export_name = "Java_rs_clash_android_Application_javaInit")]
pub extern "system" fn java_init(
    mut env: jni::EnvUnowned,
    _class: jni::objects::JClass,
    #[cfg_attr(not(target_os = "android"), allow(unused_variables))] context: jni::objects::JObject,
    panic_file: jni::objects::JString,
) -> jni::sys::jboolean {
    use jni::{Outcome, errors::Result as JniResult};

    let vm: jni::JavaVM = {
        let outcome = env
            .with_env(|env| -> jni::errors::Result<_> { Ok(env.get_java_vm()?.clone()) })
            .into_outcome();
        match outcome {
            Outcome::Ok(v) => v,
            Outcome::Err(e) => panic!("JNI error: {}", e),
            Outcome::Panic(p) => std::panic::resume_unwind(p),
        }
    };
    static VM: OnceLock<jni::JavaVM> = OnceLock::new();
    _ = VM.set(vm);
    let builder = || {
        let mut builder = tokio::runtime::Builder::new_multi_thread();
        builder
            .on_thread_start(|| {
                let vm = VM.get().expect("init java vm");
                let _ = vm.attach_current_thread(|_| -> JniResult<()> { Ok(()) });
            })
            .enable_all();
        builder
    };
    set_runtime_builder(Box::new(builder));

    static INIT: Once = Once::new();
    INIT.call_once(|| {
        let level = if cfg!(debug_assertions) {
            LogLevel::Debug
        } else {
            LogLevel::Info
        };

        unsafe {
            std::env::set_var("RUST_BACKTRACE", "1");
        }
        init_logger(level.into());
        color_eyre::install().unwrap();

        // Re-route panic backtraces through `tracing` so they reach
        // logcat. color_eyre's default hook prints to stderr, which on
        // Android is /dev/null — without this the tombstone only shows
        // the abort message and raw `pc` offsets.
        let panic_path = if panic_file.is_null() {
            None
        } else {
            match env
                .with_env(|env| panic_file.try_to_string(env))
                .into_outcome()
            {
                Outcome::Ok(path) => Some(PathBuf::from(path)),
                _ => None,
            }
        };
        let prev = std::panic::take_hook();
        std::panic::set_hook(Box::new(move |info| {
            if let (Some(path), Some(location)) = (&panic_path, info.location()) {
                // Panic payloads and absolute paths may contain profile or subscription data.
                let file = location.file().rsplit(['/', '\\']).next().unwrap_or("rust");
                use std::io::Write;
                if let Some(parent) = path.parent()
                    && let Ok(mut temporary) = tempfile::NamedTempFile::new_in(parent)
                    && write!(temporary, "{}:{}", file, location.line()).is_ok()
                {
                    let _ = temporary.persist(path);
                }
            }
            let bt = std::backtrace::Backtrace::force_capture();
            error!(target: "panic", "thread panicked: {info}\n{bt}");
            prev(info);
        }));

        // Install aws-lc-rs as the default crypto provider
        if let Err(e) = rustls::crypto::aws_lc_rs::default_provider().install_default() {
            error!("Failed to install default crypto provider: {:?}", e);
        } else {
            info!("Successfully installed aws-lc-rs crypto provider");
        }
        info!("Init logger and crypto provider initialized");
    });

    // Hand Android's certificate verifier to `rustls-platform-verifier` so that
    // every rustls client (e.g. reqwest downloads) validates TLS chains with the
    // OS trust store, including user-installed CAs and revocation checks.
    // Must run before any TLS connection is attempted.
    #[cfg(target_os = "android")]
    {
        match env
            .with_env(|env| -> JniResult<()> {
                rustls_platform_verifier::android::init_with_env(env, context)?;
                Ok(())
            })
            .into_outcome()
        {
            Outcome::Ok(_) => {
                info!("Initialized rustls-platform-verifier");
                jni::sys::JNI_TRUE
            }
            Outcome::Err(e) => {
                error!("rustls-platform-verifier init JNI error: {e:?}");
                jni::sys::JNI_FALSE
            }
            Outcome::Panic(p) => std::panic::resume_unwind(p),
        }
    }
    #[cfg(not(target_os = "android"))]
    {
        jni::sys::JNI_FALSE
    }
}
