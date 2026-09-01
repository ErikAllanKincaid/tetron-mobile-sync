// SPDX-License-Identifier: GPL-3.0-only
package xyz.tetron.mobile

import android.os.Parcel
import android.os.Parcelable

/**
 * SYNC-003: wire-compat mirrors of MOBILE-024's cross-process parcel
 * classes. The snapshot leaves tetron-mobile as a Parcelable class that
 * lives only inside that APK; a consumer can never load it, so
 * `Bundle.getParcelable` on it would throw BadParcelableException. The
 * standard cross-app technique: ship classes under the same
 * fully-qualified names here so Parcel's `Class.forName` + reflective
 * `getField("CREATOR")` path resolves to them.
 *
 * These are wire DTOs, not copies of tetron-mobile's code: the layout was
 * verified against the provider's compiled CREATOR bytecode
 * (2026-08-19), zero logic lives here, and this app's own typed models
 * (`xyz.tetron.sync.bridge`) are the only types callers ever see. Any
 * provider-side field change is a contract change and must keep
 * MeshStatusProviderContractTest green with this mirror updated in
 * lockstep.
 *
 * Layout (write = read, in order):
 * - BridgePeer: hostname String?, ip String, connKind Int (0 Direct,
 *   1 Relay, 2 Tor, 3 Unknown)
 * - StatusSnapshot: state (enum name String), network String?, ownMeshIp
 *   String?, subnet String?, peer count Int, N x BridgePeer,
 *   updatedAtMillis Long, ownHostname String?
 *
 * `ownHostname` is LAST on purpose: a provider that predates it (an older
 * tetron-mobile) writes a parcel that ends after `updatedAtMillis`, and
 * `Parcel.readString()` past the end returns null -- so this mirror stays
 * compatible with both provider versions. Never move it earlier.
 */
enum class BridgeTunnelState {
    Active,
    Standby,
    Suspended,
    Reconnecting,
    NotJoined,
    CoreNotRunning,
    /** never produced by the provider; parse-fallback for a future state
     *  name this build does not know yet (never crash on drift). */
    Unknown;

    companion object {
        fun fromName(name: String?): BridgeTunnelState =
            entries.firstOrNull { it.name == name } ?: Unknown
    }
}

class BridgePeer(
    val hostname: String?,
    val ip: String,
    val connKind: Int,
) : Parcelable {
    internal constructor(parcel: Parcel) : this(
        parcel.readString(),
        parcel.readString().orEmpty(),
        parcel.readInt(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(hostname)
        dest.writeString(ip)
        dest.writeInt(connKind)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<BridgePeer> =
            object : Parcelable.Creator<BridgePeer> {
                override fun createFromParcel(source: Parcel): BridgePeer = BridgePeer(source)
                override fun newArray(size: Int): Array<BridgePeer?> = arrayOfNulls(size)
            }
    }
}

class StatusSnapshot(
    val state: BridgeTunnelState,
    val network: String?,
    val ownMeshIp: String?,
    val subnet: String?,
    val peers: List<BridgePeer>,
    val updatedAtMillis: Long,
    // Trailing for Kotlin source-compat with existing call sites; the wire
    // position is mid-record (right after ownMeshIp) to match the
    // provider's @Parcelize declaration order -- see the read/write below.
    val ownHostname: String? = null,
) : Parcelable {
    private constructor(parcel: Parcel) : this(
        state = BridgeTunnelState.fromName(parcel.readString()),
        network = parcel.readString(),
        ownMeshIp = parcel.readString(),
        subnet = parcel.readString(),
        peers = List(maxOf(parcel.readInt(), 0)) { BridgePeer(parcel) },
        updatedAtMillis = parcel.readLong(),
        // Last, and read past-the-end-safe: an older provider's parcel
        // stops after updatedAtMillis and this yields null.
        ownHostname = parcel.readString(),
    )

    override fun writeToParcel(dest: Parcel, flags: Int) {
        dest.writeString(state.name)
        dest.writeString(network)
        dest.writeString(ownMeshIp)
        dest.writeString(subnet)
        dest.writeInt(peers.size)
        peers.forEach { it.writeToParcel(dest, flags) }
        dest.writeLong(updatedAtMillis)
        dest.writeString(ownHostname)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<StatusSnapshot> =
            object : Parcelable.Creator<StatusSnapshot> {
                override fun createFromParcel(source: Parcel): StatusSnapshot = StatusSnapshot(source)
                override fun newArray(size: Int): Array<StatusSnapshot?> = arrayOfNulls(size)
            }
    }
}