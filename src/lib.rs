//! SYNC-001 scaffold: UniFFI cdylib for the tetron-mobile-sync Android
//! addon. See AGENTS.md and spec/sync.py for the requirement this crate
//! implements.
//!
//! Out of scope here (follow-up requirements): the embedded oc-rsync
//! transfer engine (SYNC-002), the mesh bridge client (SYNC-003, Kotlin
//! side), and all gate/pipeline/UI logic. The surface below exists only to
//! prove the whole FFI chain end to end.

use std::sync::Arc;

uniffi::setup_scaffolding!();

/// SYNC-001: placeholder engine object. Deliberately trivial -- the real
/// engine (oc-rsync embedding, SYNC-002) will add methods here; the object
/// shape is the point, so the Kotlin side already calls across the FFI the
/// way it will for real transfers.
#[derive(uniffi::Object)]
pub struct SyncEngine {
    app_version: String,
}

#[uniffi::export]
impl SyncEngine {
    /// SYNC-001: constructs the engine. No arguments yet (the config-dir
    /// parameter tetron-mobile's `Node::new` needs (MOBILE-005) does not
    /// apply here: there is no core daemon and no config dir to point at).
    #[uniffi::constructor]
    pub fn new() -> Arc<Self> {
        Arc::new(Self {
            app_version: env!("CARGO_PKG_VERSION").to_string(),
        })
    }

    /// SYNC-001: the crate's own version, mechanically derived at compile
    /// time. The scaffold's only behavior -- proves constructor + method
    /// across the UniFFI boundary.
    pub fn version(&self) -> String {
        self.app_version.clone()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn engine_version_is_crate_version() {
        assert_eq!(SyncEngine::new().version(), env!("CARGO_PKG_VERSION"));
    }
}