package com.aiattempt.debroid.vm

import android.app.ActivityManager
import android.content.Context
import com.aiattempt.debroid.log.Channel
import com.aiattempt.debroid.log.LogStore
import com.aiattempt.debroid.sys.SysInfo
import java.io.RandomAccessFile
import java.util.UUID

/**
 * Single source of truth for VMs. It owns the hard limits (2 VMs, one running at
 * a time), validates every resource request against the *actual* device, persists
 * the list and drives the supervisor.
 *
 * Every public entry point is guarded: a failure becomes a Result or a console
 * line, never an exception escaping into the UI.
 */
object VmManager {

    const val MAX_VMS = 2

    // Resource bounds. The ceilings are computed from the device (see [budget]),
    // these are absolute floors/caps so a form can never ask for the impossible.
    const val MIN_RAM_MB = 256
    const val MAX_RAM_CAP_MB = 4096
    const val RAM_STEP_MB = 128
    const val MIN_DISK_MB = 2048
    const val MAX_DISK_CAP_MB = 32768
    const val DISK_STEP_MB = 512

    /** Left to Android so the phone itself keeps working while a VM runs. */
    const val ANDROID_RESERVE_RAM_MB = 1024
    /** Left free for the app process (JVM, UI, log pump) while a guest exists. */
    const val APP_RESERVE_RAM_MB = 512
    /** Storage the phone keeps for itself / updates / caches. */
    const val DISK_RESERVE_MB = 2048
    /** Below this we refuse to boot at all instead of getting OOM-killed. */
    const val MIN_FREE_RAM_TO_START_MB = 768

    data class Budget(
        val cores: Int,
        val ramTotalMb: Int,
        val ramFreeMb: Int,
        val ramMaxMb: Int,
        val diskFreeMb: Long,
        val diskMaxMb: Int
    ) {
        val reason: String?
            get() = when {
                ramMaxMb < MIN_RAM_MB || ramFreeMb < MIN_FREE_RAM_TO_START_MB ->
                    "Not enough free RAM right now: Android reports $ramFreeMb MB free, Debroid needs at least $MIN_FREE_RAM_TO_START_MB MB."
                diskMaxMb < MIN_DISK_MB || diskFreeMb < (MIN_DISK_MB + DISK_RESERVE_MB) ->
                    "Not enough free storage: ${diskFreeMb / 1024} GB free, Debroid needs at least ${(MIN_DISK_MB + DISK_RESERVE_MB) / 1024} GB."
                else -> null
            }

        val canCreate: Boolean get() = reason == null
    }

    private val lock = Any()
    private val items = ArrayList<VmInstance>()

    @Volatile
    private var loaded = false

    @Volatile
    var runningId: String? = null
        private set

    /** Set while the Debian image is being fetched/cloned for a VM. */
    @Volatile
    private var preparingId: String? = null

    private var process: VmProcess? = null

    /** Called on any state change. Must be cheap; the UI refreshes from [list]. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    fun init(ctx: Context) = synchronized(lock) {
        if (loaded) return
        items.clear()
        items.addAll(VmStore.load(ctx))
        // Nothing is running after a process restart, whatever the file said.
        items.forEach { vm ->
            if (vm.state == VmState.RUNNING || vm.state == VmState.STARTING) {
                vm.state = VmState.STOPPED
                vm.message = "App restarted — VM is stopped"
                vm.startedAt = null
            }
        }
        loaded = true
        runningId = null
        preparingId = null
        VmStore.save(ctx, items)
        LogStore.log(Channel.APP, "VM store loaded: ${items.size} of $MAX_VMS")
        LogStore.log(Channel.ENGINE, "UML image on device: ${VmImage.isReady(ctx)}")
    }

    private fun ensure(ctx: Context) {
        if (!loaded) init(ctx)
    }

    fun list(ctx: Context): List<VmInstance> {
        ensure(ctx)
        return synchronized(lock) { items.toList() }
    }

    fun find(ctx: Context, id: String): VmInstance? {
        ensure(ctx)
        return synchronized(lock) { items.firstOrNull { it.id == id } }
    }

    fun running(ctx: Context): VmInstance? {
        ensure(ctx)
        val id = runningId ?: return null
        return synchronized(lock) { items.firstOrNull { it.id == id } }
    }

    // ---- limits -----------------------------------------------------------

    fun budget(ctx: Context): Budget {
        val cores = try {
            Runtime.getRuntime().availableProcessors().coerceIn(1, 16)
        } catch (_: Throwable) {
            1
        }
        val am = ctx.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo()
        val totalMb: Int
        val freeMb: Int
        try {
            am?.getMemoryInfo(mem)
            totalMb = (mem.totalMem / (1024 * 1024)).toInt()
            freeMb = (mem.availMem / (1024 * 1024)).toInt()
        } catch (_: Throwable) {
            return Budget(cores, 0, 0, MIN_RAM_MB, -1, MIN_DISK_MB)
        }
        val usable = minOf(totalMb - ANDROID_RESERVE_RAM_MB, freeMb - APP_RESERVE_RAM_MB, MAX_RAM_CAP_MB)
        val ramMax = alignDown(usable, RAM_STEP_MB).coerceAtLeast(MIN_RAM_MB)

        val freeStorage = SysInfo.freeStorageMb(ctx)
        val diskUsable = if (freeStorage <= 0) 0L else freeStorage - DISK_RESERVE_MB
        val diskMax = alignDown(diskUsable, DISK_STEP_MB.toLong())
            .coerceAtMost(MAX_DISK_CAP_MB.toLong())
            .coerceAtLeast(MIN_DISK_MB.toLong())
            .toInt()

        return Budget(cores, totalMb, freeMb, ramMax, freeStorage, diskMax)
    }

    private fun alignDown(value: Int, step: Int): Int = if (value <= 0) 0 else (value / step) * step

    private fun alignDown(value: Long, step: Long): Long = if (value <= 0L) 0L else (value / step) * step

    /** Clamps a requested value into the device's real range. */
    fun clampRam(ctx: Context, requested: Int): Int {
        val b = budget(ctx)
        return alignDown(requested.coerceIn(MIN_RAM_MB, b.ramMaxMb).coerceAtLeast(MIN_RAM_MB), RAM_STEP_MB)
            .coerceAtLeast(MIN_RAM_MB)
    }

    fun clampDisk(ctx: Context, requested: Int): Int {
        val b = budget(ctx)
        return alignDown(requested.coerceIn(MIN_DISK_MB, b.diskMaxMb).coerceAtLeast(MIN_DISK_MB), DISK_STEP_MB)
            .coerceAtLeast(MIN_DISK_MB)
    }

    fun clampCores(ctx: Context, requested: Int): Int = requested.coerceIn(1, budget(ctx).cores)

    // ---- CRUD -------------------------------------------------------------

    fun create(
        ctx: Context,
        name: String,
        cores: Int,
        ramMb: Int,
        diskMb: Int,
        ports: List<PortForward>
    ): Result<VmInstance> {
        ensure(ctx)
        val budget = budget(ctx)
        budget.reason?.let { return Result.failure(IllegalStateException(it)) }

        synchronized(lock) {
            if (items.size >= MAX_VMS) {
                return Result.failure(
                    IllegalStateException("You already have $MAX_VMS VMs — delete one first, then create another.")
                )
            }
            val safeCores = cores.coerceIn(1, budget.cores)
            val safeRam = alignDown(ramMb.coerceIn(MIN_RAM_MB, budget.ramMaxMb).coerceAtLeast(MIN_RAM_MB), RAM_STEP_MB)
                .coerceAtLeast(MIN_RAM_MB)
            val safeDisk = alignDown(diskMb.coerceIn(MIN_DISK_MB, budget.diskMaxMb).coerceAtLeast(MIN_DISK_MB), DISK_STEP_MB)
                .coerceAtLeast(MIN_DISK_MB)
            val cleanPorts = cleanPorts(ports)

            val id = UUID.randomUUID().toString()
            val finalName = uniqueName(name, null)
            val vm = VmInstance(
                id = id,
                name = finalName,
                cores = safeCores,
                ramMb = safeRam,
                diskMb = safeDisk,
                ports = cleanPorts,
                state = VmState.STOPPED,
                message = "Created — press Start to boot"
            )

            runCatching { VmStore.vmDir(ctx, id) }
            appendConsole(ctx, vm, "creating VM '$finalName'")
            appendConsole(ctx, vm, "requested: $cores cores / $ramMb MB RAM / ${VmInstance.formatDisk(diskMb)}")
            if (safeCores != cores || safeRam != ramMb || safeDisk != diskMb) {
                appendConsole(
                    ctx, vm,
                    "clamped to device limits: $safeCores cores / $safeRam MB / ${VmInstance.formatDisk(safeDisk)}"
                )
            }

            // The disk itself is materialised on the first Start, from the shared
            // Debian image, so creating a VM stays instant and costs no storage.
            appendConsole(ctx, vm, "disk: ${VmInstance.formatDisk(safeDisk)} reserved, created on first start")
            appendConsole(ctx, vm, "Debian suite: ${vm.distroLabel()}")
            cleanPorts.forEach { p -> appendConsole(ctx, vm, "port forward: host ${p.host} -> guest ${p.guest}") }

            items.add(vm)
            VmStore.save(ctx, items)
            LogStore.log(Channel.ENGINE, "VM created: ${vm.name} (${vm.id}) ${vm.specLine}")
            notifyChanged()
            return Result.success(vm)
        }
    }

    /**
     * Edits an existing VM. Resources are clamped to the device as usual; the disk
     * can only grow, because the image already holds this guest's filesystem.
     * Changes are persisted immediately and used on the next boot.
     */
    fun update(
        ctx: Context,
        id: String,
        name: String,
        cores: Int,
        ramMb: Int,
        diskMb: Int,
        ports: List<PortForward>
    ): Result<VmInstance> {
        ensure(ctx)
        val budget = budget(ctx)
        synchronized(lock) {
            val vm = items.firstOrNull { it.id == id }
                ?: return Result.failure(IllegalStateException("That VM no longer exists."))
            val safeCores = cores.coerceIn(1, budget.cores)
            val safeRam = alignDown(ramMb.coerceIn(MIN_RAM_MB, budget.ramMaxMb).coerceAtLeast(MIN_RAM_MB), RAM_STEP_MB)
                .coerceAtLeast(MIN_RAM_MB)
            val onDiskMb = runCatching {
                (VmStore.diskImage(ctx, id).length() / (1024 * 1024)).toInt()
            }.getOrDefault(0)
            val diskMin = maxOf(MIN_DISK_MB, onDiskMb)
            val diskCeil = maxOf(budget.diskMaxMb, diskMin)
            val safeDisk = alignDown(diskMb.coerceIn(diskMin, diskCeil), DISK_STEP_MB).coerceAtLeast(diskMin)
            val clean = cleanPorts(ports)

            // Growing the disk is real: the sparse image is extended right here so
            // the guest's next boot finds a bigger /dev/ubda and resize2fs fills it.
            // A running VM is left alone — its block device cannot change size.
            if (safeDisk > onDiskMb && runningId != id && preparingId != id) {
                val diskFile = VmStore.diskImage(ctx, id)
                val grown = runCatching {
                    RandomAccessFile(diskFile, "rw").use { it.setLength(safeDisk.toLong() * 1024L * 1024L) }
                }.isSuccess
                appendConsole(
                    ctx, vm,
                    if (grown)
                        "disk extended: ${VmInstance.formatDisk(onDiskMb)} -> ${VmInstance.formatDisk(safeDisk)} " +
                            "(sparse — only what the guest writes costs storage; the filesystem grows on boot)"
                    else "could not extend the disk image to ${VmInstance.formatDisk(safeDisk)}"
                )
                if (grown) LogStore.log(Channel.ENGINE, "disk ${vm.name}: $onDiskMb MB -> $safeDisk MB")
            } else if (safeDisk > onDiskMb) {
                appendConsole(
                    ctx, vm,
                    "disk will grow to ${VmInstance.formatDisk(safeDisk)} once this VM is stopped"
                )
            }

            val resourcesChanged = safeCores != vm.cores || safeRam != vm.ramMb || safeDisk != vm.diskMb
            vm.name = uniqueName(name, id)
            vm.cores = safeCores
            vm.ramMb = safeRam
            vm.diskMb = safeDisk
            vm.ports = clean

            val running = runningId == id
            if (resourcesChanged) {
                vm.message = if (running) "Resource changes apply after a restart" else "Updated — press Start"
                appendConsole(
                    ctx, vm,
                    "edited: $safeCores cores / $safeRam MB RAM / ${VmInstance.formatDisk(safeDisk)}" +
                        if (running) " (applies after a restart)" else ""
                )
            } else {
                appendConsole(ctx, vm, "edited: name and port forwards updated")
            }
            appendConsole(ctx, vm, "port forwards: " + if (clean.isEmpty()) "none" else clean.joinToString(", ") { "host ${it.host} -> ${it.guest}" })
            VmStore.save(ctx, items)
            LogStore.log(Channel.ENGINE, "VM edited: ${vm.name} (${vm.id}) ${vm.specLine}")
            notifyChanged()
            return Result.success(vm)
        }
    }

    fun delete(ctx: Context, id: String): Result<Unit> {
        ensure(ctx)
        synchronized(lock) {
            val vm = items.firstOrNull { it.id == id }
                ?: return Result.failure(IllegalStateException("That VM no longer exists."))
            if (runningId == id) {
                stopLocked(ctx, vm, "Stopped because the VM was deleted")
            }
            if (preparingId == id) preparingId = null
            items.remove(vm)
            VmStore.deleteVm(ctx, id)
            VmStore.save(ctx, items)
            LogStore.log(Channel.ENGINE, "VM deleted: ${vm.name} (${vm.id})")
        }
        notifyChanged()
        return Result.success(Unit)
    }

    // ---- lifecycle --------------------------------------------------------

    fun start(ctx: Context, id: String): Result<Unit> {
        ensure(ctx)
        var failure: Result<Unit>? = null
        synchronized(lock) {
            val vm = items.firstOrNull { it.id == id } ?: return Result.failure(
                IllegalStateException("That VM no longer exists.")
            )
            if (preparingId == id) return Result.success(Unit)
            if (runningId == id && process?.isAlive == true) return Result.success(Unit)

            // One VM at a time: stop the other one first, on purpose.
            items.firstOrNull { it.id == runningId && it.id != id }?.let { other ->
                stopLocked(ctx, other, "Stopped so '${vm.name}' could start (only one VM runs at a time)")
            }
            process?.let { it.terminate() }
            process = null
            runningId = null

            val available = budget(ctx).ramFreeMb
            if (available < MIN_FREE_RAM_TO_START_MB || vm.ramMb > available - APP_RESERVE_RAM_MB) {
                val msg = "Not enough free RAM to start: the VM asks for ${vm.ramMb} MB, Android reports " +
                    "$available MB free. Close some apps, or edit the VM and give it less RAM."
                vm.state = VmState.STOPPED
                vm.message = msg
                appendConsole(ctx, vm, msg)
                VmStore.save(ctx, items)
                failure = Result.failure(IllegalStateException(msg))
                return@synchronized
            }

            vm.state = VmState.STARTING
            appendConsole(ctx, vm, "start requested ($available MB free RAM)")
            LogStore.log(Channel.ENGINE, "start ${vm.name}: ${available} MB free RAM")

            if (!VmImage.hasBootableDisk(ctx, vm)) {
                vm.message = "Fetching the Debian image…"
                appendConsole(ctx, vm, "the Debian trixie arm64 disk image is not on this device yet — fetching it now")
                preparingId = id
                // Own the console while the image is fetching, so the Terminal tab
                // shows the progress instead of "No VM is running".
                runningId = id
                VmStore.save(ctx, items)
                Thread({ prepareAndLaunch(ctx, vm) }, "debroid-image-${id.take(6)}").apply {
                    isDaemon = true
                    start()
                }
            } else {
                failure = launchLocked(ctx, vm)
            }
            VmStore.save(ctx, items)
        }
        notifyChanged()
        return failure ?: Result.success(Unit)
    }

    /** Runs off the main thread: fetch/clone the image, then boot. */
    private fun prepareAndLaunch(ctx: Context, vm: VmInstance) {
        val result = VmImage.ensureDiskImage(ctx, vm) { line -> appendConsole(ctx, vm, line) }
        var cancelled = false
        synchronized(lock) {
            // The user may have pressed Stop (or deleted the VM) while the image was
            // downloading; in that case the guest must not boot behind their back.
            cancelled = preparingId != vm.id || items.none { it.id == vm.id }
            if (preparingId == vm.id) preparingId = null
            if (cancelled && runningId == vm.id) runningId = null
            VmStore.save(ctx, items)
        }
        if (cancelled) {
            appendConsole(ctx, vm, "image preparation cancelled — nothing was started")
            notifyChanged()
            return
        }
        result.onSuccess {
            synchronized(lock) {
                launchLocked(ctx, vm)
                VmStore.save(ctx, items)
            }
        }.onFailure {
            synchronized(lock) {
                vm.state = VmState.ERROR
                if (runningId == vm.id) runningId = null
                vm.message = "Could not fetch the Debian image: ${it.message ?: it.javaClass.simpleName}"
                appendConsole(ctx, vm, vm.message)
                appendConsole(ctx, vm, "check the connection, then press Start again — a partial download is discarded")
                VmStore.save(ctx, items)
            }
        }
        notifyChanged()
    }

    /** Must be called with [lock] held. */
    private fun launchLocked(ctx: Context, vm: VmInstance): Result<Unit> {
        vm.state = VmState.STARTING
        vm.message = "Starting…"
        appendConsole(ctx, vm, "booting the UML kernel with ${vm.ramMb} MB RAM")
        val launch = VmProcess.launch(
            ctx = ctx,
            vm = vm,
            onLine = { line -> appendConsole(ctx, vm, line) },
            onExit = { code ->
                synchronized(lock) {
                    vm.state = if (code == 0) VmState.STOPPED else VmState.ERROR
                    vm.message = if (code == 0) "Exited cleanly" else "Guest exited with code $code"
                    vm.lastExit = code
                    vm.startedAt = null
                    if (runningId == vm.id) {
                        runningId = null
                        process = null
                    }
                    VmStore.save(ctx, items)
                }
                appendConsole(ctx, vm, "guest process ended, exit code $code")
                // Read the tombstone now, while it is still in the crash buffer.
                if (code != 0) GuestExit.captureTombstones()
                GuestExit.explain(code)?.let { appendConsole(ctx, vm, it) }
                GuestExit.blockedSyscall()?.let { (nr, name) ->
                    appendConsole(ctx, vm, "Android refused $name (arm64 syscall $nr) and killed the kernel process")
                }
                val traps = GuestExit.trapLines()
                if (traps.isNotEmpty()) {
                    appendConsole(ctx, vm, "seccomp / signal lines seen in logcat:")
                    traps.forEach { line -> appendConsole(ctx, vm, "  $line") }
                }
                notifyChanged()
            }
        )

        return when (launch) {
            is VmProcess.LaunchResult.Missing -> {
                vm.state = VmState.STOPPED
                vm.message = launch.reason
                appendConsole(ctx, vm, launch.reason)
                appendConsole(ctx, vm, "missing: " + VmProcess.missingArtefacts(ctx, vm).joinToString(", "))
                Result.failure(IllegalStateException(launch.reason))
            }
            is VmProcess.LaunchResult.Started -> {
                process = launch.process
                runningId = vm.id
                vm.state = VmState.RUNNING
                vm.startedAt = System.currentTimeMillis()
                vm.message = "Running"
                appendConsole(ctx, vm, "kernel launched, console attached")
                Result.success(Unit)
            }
        }
    }

    fun stop(ctx: Context, id: String): Result<Unit> {
        ensure(ctx)
        synchronized(lock) {
            val vm = items.firstOrNull { it.id == id }
                ?: return Result.failure(IllegalStateException("That VM no longer exists."))
            if (vm.state == VmState.STOPPED) return Result.success(Unit)
            stopLocked(ctx, vm, "Stopped")
            VmStore.save(ctx, items)
        }
        notifyChanged()
        return Result.success(Unit)
    }

    fun restart(ctx: Context, id: String): Result<Unit> {
        stop(ctx, id)
        return start(ctx, id)
    }

    private fun stopLocked(ctx: Context, vm: VmInstance, reason: String) {
        runCatching { process?.terminate() }
        process = null
        if (preparingId == vm.id) {
            preparingId = null
            appendConsole(ctx, vm, "image fetch cancelled")
        }
        if (runningId == vm.id) runningId = null
        vm.state = VmState.STOPPED
        vm.startedAt = null
        vm.message = reason
        appendConsole(ctx, vm, reason)
        LogStore.log(Channel.ENGINE, "stop ${vm.name}: $reason")
    }

    fun sendInput(ctx: Context, id: String, line: String): Boolean {
        val p = process ?: return false
        if (runningId != id) return false
        val ok = p.sendLine(line)
        appendConsole(ctx, find(ctx, id), "> $line")
        return ok
    }

    fun isRunning(ctx: Context, id: String): Boolean = runningId == id && process?.isAlive == true

    // ---- guest disk trim ----------------------------------------------------

    data class DiskTrim(
        val ran: Boolean,
        val output: String,
        val hostBefore: Long,
        val hostAfter: Long
    ) {
        val hostFreed: Long get() = (hostBefore - hostAfter).coerceAtLeast(0L)
    }

    /**
     * Runs `fstrim /` **inside the guest**, over its console.
     *
     * Trimming Android's own filesystem would never help: deleting 1 GB inside
     * the VM leaves 1 GB of blocks marked used in `disk.img`. UML's `ubd` driver
     * enables discard by default and turns each guest discard into a hole punch on
     * that file, so this is the call that actually gives the space back to
     * Android. It has to run in the guest, so the VM must be running.
     */
    fun trimGuestDisk(ctx: Context, id: String, timeoutMs: Long = 45_000): DiskTrim {
        ensure(ctx)
        val vm = find(ctx, id) ?: return DiskTrim(false, "That VM no longer exists.", 0, 0)
        val diskFile = VmStore.diskImage(ctx, id)
        val before = VmImage.allocatedBytes(diskFile)
        if (!isRunning(ctx, id)) {
            return DiskTrim(
                false,
                "This VM is not running. The trim happens inside the guest, so start it first, " +
                    "then run this again. Nothing was changed.",
                before, before
            )
        }

        val marker = "__DEBROID_TRIM_DONE_${System.currentTimeMillis()}__"
        val consoleFile = VmStore.consoleFile(ctx, id)
        val startOffset = runCatching { consoleFile.length() }.getOrDefault(0L)
        val command = "fstrim -v / 2>&1; df -h / 2>&1; sync; echo $marker"
        if (!sendInput(ctx, id, command)) {
            return DiskTrim(false, "The console of '${vm.name}' refused input; is it still running?", before, before)
        }

        var seen = ""
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            seen = readFrom(consoleFile, startOffset)
            if (seen.contains(marker)) break
            runCatching { Thread.sleep(250) }
        }

        val cleaned = seen.lineSequence()
            .filter { line -> !line.contains(marker) && !line.trimStart().startsWith("> fstrim") }
            .joinToString("\n")
            .trim()

        val after = VmImage.allocatedBytes(diskFile)
        val finished = seen.contains(marker)
        val summary = buildString {
            appendLine("trim inside '${vm.name}' — ${LogStore.stamp()}")
            appendLine(if (finished) cleaned.ifBlank { "(no output)" }
            else "the guest did not answer within ${timeoutMs / 1000} s; output so far:\n" +
                cleaned.ifBlank { "(none)" })
            appendLine()
            appendLine("android storage used by this disk: ${mbText(before)} -> ${mbText(after)}")
            appendLine(
                if (after < before) "released ${mbText(before - after)} back to Android"
                else "no blocks were released: the filesystem had nothing discarded, or trim was refused"
            )
        }
        LogStore.log(Channel.ENGINE, "guest trim ${vm.name}: ${mbText(before)} -> ${mbText(after)}")
        appendConsole(ctx, vm, "guest disk trim: ${mbText(before)} -> ${mbText(after)} used on Android")
        return DiskTrim(true, summary.trimEnd(), before, after)
    }

    private fun readFrom(file: java.io.File, offset: Long): String {
        return try {
            if (!file.exists()) {
                ""
            } else {
                RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() <= offset) {
                        ""
                    } else {
                        raf.seek(offset)
                        val buf = ByteArray((raf.length() - offset).coerceAtMost(64L * 1024L).toInt())
                        val n = raf.read(buf)
                        if (n <= 0) "" else String(buf, 0, n)
                    }
                }
            }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun mbText(bytes: Long): String = String.format(java.util.Locale.US, "%.0f MB", bytes / (1024.0 * 1024.0))

    fun isPreparing(id: String): Boolean = preparingId == id

    // ---- console ----------------------------------------------------------

    fun appendConsole(ctx: Context, vm: VmInstance?, line: String) {
        val target = vm ?: return
        if (line.isBlank()) {
            // Still record blank kernel lines: boot output uses them as separators.
            try {
                VmStore.consoleFile(ctx, target.id).appendText("\n")
            } catch (_: Throwable) {
            }
            return
        }
        val stamped = "${LogStore.stamp()}  $line"
        try {
            val f = VmStore.consoleFile(ctx, target.id)
            if (f.length() > 2L * 1024 * 1024) {
                val tail = runCatching { f.readLines().takeLast(4000).joinToString("\n") }.getOrDefault("")
                f.writeText(tail + "\n")
            }
            f.appendText(stamped + "\n")
        } catch (_: Throwable) {
        }
        LogStore.log(Channel.CONSOLE, "[${target.name}] $line")
    }

    fun consoleTail(ctx: Context, id: String, maxLines: Int = 400): String = try {
        val f = VmStore.consoleFile(ctx, id)
        if (!f.exists()) "" else f.readLines().takeLast(maxLines).joinToString("\n")
    } catch (_: Throwable) {
        ""
    }

    /** Every VM console, for the Debug tab's copy-everything report. */
    fun consoleDump(ctx: Context, maxLinesEach: Int = 250): String = buildString {
        list(ctx).forEach { vm ->
            val text = consoleTail(ctx, vm.id, maxLinesEach)
            if (text.isNotBlank()) {
                appendLine("===== VM console: ${vm.name} (${vm.state}) =====")
                appendLine(text)
                appendLine()
            }
        }
    }

    /** Human readable budget line used by the form and the self-test. */
    fun budgetLine(ctx: Context): String {
        val b = budget(ctx)
        return "cores 1-${b.cores} · RAM up to ${b.ramMaxMb} MB (${b.ramFreeMb} MB free of ${b.ramTotalMb} MB) · " +
            "disk up to ${VmInstance.formatDisk(b.diskMaxMb)} (${b.diskFreeMb / 1024} GB free)"
    }

    fun notifyChanged() {
        runCatching { onChanged?.invoke() }
    }

    private fun cleanPorts(ports: List<PortForward>): List<PortForward> =
        ports.filter { it.host in 1024..65535 && it.guest in 1..65535 }
            .distinctBy { it.host }
            .take(8)

    private fun uniqueName(requested: String, exceptId: String?): String {
        val base = requested.trim().ifEmpty { "Debian" }.take(24)
        val others = items.filter { it.id != exceptId }
        if (others.none { it.name.equals(base, ignoreCase = true) }) return base
        var n = 2
        while (others.any { it.name.equals("$base $n", ignoreCase = true) }) n++
        return "$base $n"
    }
}
