package com.aiattempt.debroid.vm

import org.json.JSONArray
import org.json.JSONObject

enum class VmState { STOPPED, STARTING, RUNNING, ERROR }

data class PortForward(val host: Int, val guest: Int)

/**
 * One virtual machine. Everything here is persisted, so a VM survives an app
 * restart, a crash and a reboot. Sizes are the *reservation*, not the on-disk
 * usage: disk images are sparse.
 */
data class VmInstance(
    val id: String,
    var name: String,
    val distro: String = "debian-trixie",
    var cores: Int,
    var ramMb: Int,
    var diskMb: Int,
    var ports: List<PortForward> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    var state: VmState = VmState.STOPPED,
    var message: String = "Created — not started yet",
    var startedAt: Long? = null,
    var lastExit: Int? = null
) {
    val specLine: String
        get() = "$cores ${if (cores == 1) "core" else "cores"} · ${ramMb} MB · ${formatDisk(diskMb)} · ${distroLabel()}"

    fun distroLabel(): String = when (distro) {
        "debian-trixie" -> "Debian 13 (trixie)"
        "debian-sid" -> "Debian sid"
        else -> distro
    }

    fun uptimeSeconds(): Long = startedAt?.let { (System.currentTimeMillis() - it) / 1000 } ?: 0L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("distro", distro)
        put("cores", cores)
        put("ramMb", ramMb)
        put("diskMb", diskMb)
        put("createdAt", createdAt)
        put("state", state.name)
        put("message", message)
        put("startedAt", startedAt ?: -1L)
        put("lastExit", lastExit ?: Int.MIN_VALUE)
        put("ports", JSONArray().apply {
            ports.forEach { p -> put(JSONObject().put("host", p.host).put("guest", p.guest)) }
        })
    }

    companion object {
        fun fromJson(o: JSONObject): VmInstance {
            val ports = ArrayList<PortForward>()
            o.optJSONArray("ports")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    ports.add(PortForward(p.optInt("host"), p.optInt("guest")))
                }
            }
            val startedAt = o.optLong("startedAt", -1L).takeIf { it > 0 }
            val exit = o.optInt("lastExit", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            return VmInstance(
                id = o.optString("id"),
                name = o.optString("name", "Debian"),
                distro = o.optString("distro", "debian-trixie"),
                cores = o.optInt("cores", 1),
                ramMb = o.optInt("ramMb", 512),
                diskMb = o.optInt("diskMb", 4096),
                ports = ports,
                createdAt = o.optLong("createdAt", System.currentTimeMillis()),
                state = runCatching { VmState.valueOf(o.optString("state", "STOPPED")) }.getOrDefault(VmState.STOPPED),
                message = o.optString("message", ""),
                startedAt = startedAt,
                lastExit = exit
            )
        }

        fun formatDisk(diskMb: Int): String =
            if (diskMb >= 1024) String.format("%.1f GB", diskMb / 1024.0) else "$diskMb MB"
    }
}
