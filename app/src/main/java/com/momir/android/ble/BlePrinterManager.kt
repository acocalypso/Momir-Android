package com.momir.android.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanSettings
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.util.UUID

class BlePrinterManager(private val context: Context) {
    enum class State { DISCONNECTED, CONNECTING, READY, PRINTING }

    data class Profile(
        val name: String,
        val printWidth: Int,
        val initCommands: ByteArray,
        val finalizeCommands: ByteArray,
        val chunkSize: Int,
        val chunksPerBurst: Int,
        val burstDelayMs: Long,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as Profile

            if (printWidth != other.printWidth) return false
            if (chunkSize != other.chunkSize) return false
            if (chunksPerBurst != other.chunksPerBurst) return false
            if (burstDelayMs != other.burstDelayMs) return false
            if (name != other.name) return false
            if (!initCommands.contentEquals(other.initCommands)) return false
            if (!finalizeCommands.contentEquals(other.finalizeCommands)) return false

            return true
        }

        override fun hashCode(): Int {
            var result = printWidth
            result = 31 * result + chunkSize
            result = 31 * result + chunksPerBurst
            result = 31 * result + burstDelayMs.hashCode()
            result = 31 * result + name.hashCode()
            result = 31 * result + initCommands.contentHashCode()
            result = 31 * result + finalizeCommands.contentHashCode()
            return result
        }
    }

    companion object {
        private const val TAG = "MomirBle"
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val CONNECT_RETRY_DELAY_MS = 650L
        private const val MAX_CONNECT_ATTEMPTS = 2

        private val SERVICE_UUID: UUID = UUID.fromString("0000ff00-0000-1000-8000-00805f9b34fb")
        private val WRITE_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")
        private val NAME_PREFIXES = listOf("M04", "M02", "T02", "Mr.in_")
        private val NAME_CONTAINS = listOf("M04S", "M04AS", "M02S", "M02 PRO", "M02", "T02", "MR.IN_")

        val PROFILE_M02 = Profile(
            name = "M02",
            printWidth = 384,
            initCommands = byteArrayOf(0x1b, 0x40, 0x1f, 0x11, 0x02, 0x04, 0x1b, 0x61, 0x01, 0x1f, 0x11, 0x24, 0x00),
            finalizeCommands = byteArrayOf(0x1b, 0x64, 0x02, 0x1b, 0x64, 0x02, 0x1f, 0x11, 0x08, 0x1f, 0x11, 0x0e, 0x1f, 0x11, 0x07, 0x1f, 0x11, 0x09),
            chunkSize = 512,
            chunksPerBurst = 1,
            burstDelayMs = 50,
        )

        val PROFILE_M02S = PROFILE_M02.copy(name = "M02S", printWidth = 576, chunksPerBurst = 2)

        val PROFILE_M04S = Profile(
            name = "M04S",
            printWidth = 1232,
            initCommands = byteArrayOf(0x1f, 0x11, 0x02, 0x04, 0x1f, 0x11, 0x37, 0x96.toByte(), 0x1f, 0x11, 0x0b, 0x1f, 0x11, 0x35, 0x00),
            finalizeCommands = byteArrayOf(0x1b, 0x64, 0x02),
            chunkSize = 205,
            chunksPerBurst = 3,
            burstDelayMs = 50,
        )
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        manager.adapter
    }

    private var scanner: BluetoothLeScanner? = null
    private var scanCallback: ScanCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var scanTimeoutTask: Runnable? = null
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var negotiatedMtu: Int = 23
    private var lastGattStatus: Int = 0

    var state: State = State.DISCONNECTED
        private set

    var profile: Profile = PROFILE_M02S
        private set

    var deviceName: String? = null
        private set

    var lastError: String? = null
        private set

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val scanGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
            val connectGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            scanGranted && connectGranted
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connect(onDone: (Boolean) -> Unit) {
        fun fail(msg: String) {
            Log.w(TAG, msg)
            lastError = msg
            state = State.DISCONNECTED
            onDone(false)
        }

        if (!hasBluetoothPermissions()) {
            fail("Bluetooth permissions missing")
            return
        }
        if (state == State.CONNECTING || state == State.PRINTING) {
            fail("Printer is busy ($state)")
            return
        }

        val adapter = bluetoothAdapter ?: run {
            fail("Bluetooth adapter unavailable")
            return
        }
        if (!adapter.isEnabled) {
            fail("Bluetooth is turned off")
            return
        }
        val sc = adapter.bluetoothLeScanner ?: run {
            fail("BLE scanner unavailable")
            return
        }

        // Some OEMs still gate BLE scan delivery behind system Location being enabled.
        if (!isLocationEnabled()) {
            Log.w(TAG, "Location services appear OFF; some devices may suppress BLE scan results")
        }

        lastError = null
        state = State.CONNECTING
        scanner = sc
        scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        scanCallback?.let {
            try {
                scanner?.stopScan(it)
            } catch (_: SecurityException) {
            }
        }

        findBondedPrinter(adapter)?.let { bonded ->
            Log.i(TAG, "Using bonded printer fallback: ${bonded.address}")
            connectGatt(bonded, onDone)
            return
        }

        var sawAnyScanResult = false

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                sawAnyScanResult = true
                processScanResult(result, this, onDone)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (results.isNotEmpty()) {
                    sawAnyScanResult = true
                }
                for (r in results) {
                    processScanResult(r, this, onDone)
                }
            }

            private fun processScanResult(result: ScanResult, callback: ScanCallback, done: (Boolean) -> Unit) {
                val d = result.device ?: return
                val deviceName = try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        d.name
                    } else {
                        null
                    }
                } catch (_: SecurityException) {
                    null
                }
                val advName = result.scanRecord?.deviceName
                val name = deviceName ?: advName

                Log.d(TAG, "Scan hit: name=${name ?: "<none>"}, mac=${d.address}, rssi=${result.rssi}")
                if (name == null) return
                if (matchesPrinterName(name)) {
                    try {
                        scanner?.stopScan(callback)
                    } catch (_: SecurityException) {
                    }
                    scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
                    scanCallback = null
                    Log.i(TAG, "Printer matched: $name (${d.address})")
                    connectGatt(d, done)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                fail("BLE scan failed with code $errorCode")
            }
        }

        scanCallback = cb
        val timeoutTask = Runnable {
            try {
                scanner?.stopScan(cb)
            } catch (_: SecurityException) {
            }
            scanCallback = null
            if (!sawAnyScanResult) {
                fail("No BLE advertisements received in ${SCAN_TIMEOUT_MS / 1000}s")
            } else {
                fail("No supported printer found within ${SCAN_TIMEOUT_MS / 1000}s")
            }
        }
        scanTimeoutTask = timeoutTask
        mainHandler.postDelayed(timeoutTask, SCAN_TIMEOUT_MS)

        try {
            Log.i(TAG, "Starting BLE scan")
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setReportDelay(0L)
                .build()
            sc.startScan(emptyList(), settings, cb)
        } catch (e: SecurityException) {
            scanCallback = null
            mainHandler.removeCallbacks(timeoutTask)
            fail("Missing permission while starting scan")
        } catch (e: Exception) {
            scanCallback = null
            mainHandler.removeCallbacks(timeoutTask)
            fail("Unable to start BLE scan: ${e.message}")
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun connectGatt(device: BluetoothDevice, onDone: (Boolean) -> Unit) {
        Log.i(TAG, "Connecting GATT to ${device.address}")
        deviceName = try { device.name } catch (e: SecurityException) { null }
        profile = detectProfile(deviceName.orEmpty())
        lastError = null
        negotiatedMtu = 23
        lastGattStatus = 0
        var connectResultDelivered = false

        safelyCloseGatt(gatt)
        gatt = null

        fun completeConnectOnce(ok: Boolean) {
            if (!connectResultDelivered) {
                connectResultDelivered = true
                onDone(ok)
            }
        }

        fun connectAttempt(attempt: Int) {
            Log.i(TAG, "Connecting GATT attempt $attempt/$MAX_CONNECT_ATTEMPTS to ${device.address}")
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    Log.d(TAG, "GATT state changed: status=$status newState=$newState")
                    lastGattStatus = status
                    if (newState == BluetoothGatt.STATE_CONNECTED) {
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                            try {
                                // Improve link stability under sustained write load.
                                g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            } catch (_: SecurityException) {
                            }
                            try {
                                g.requestMtu(247)
                            } catch (_: SecurityException) {
                            }
                            try {
                                g.discoverServices()
                            } catch (_: SecurityException) {
                            }
                        }
                    } else {
                        if (!connectResultDelivered && status == 133 && attempt < MAX_CONNECT_ATTEMPTS) {
                            Log.w(TAG, "GATT 133 on attempt $attempt/$MAX_CONNECT_ATTEMPTS, retrying...")
                            safelyCloseGatt(g)
                            if (g === gatt) {
                                gatt = null
                            }
                            mainHandler.postDelayed({ connectAttempt(attempt + 1) }, CONNECT_RETRY_DELAY_MS)
                            return
                        }

                        state = State.DISCONNECTED
                        writeChar = null
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            lastError = "GATT disconnected (status=$status). Check printer battery/temperature and retry."
                        } else {
                            lastError = "GATT disconnected"
                        }
                        safelyCloseGatt(g)
                        if (g === gatt) {
                            gatt = null
                        }
                        completeConnectOnce(false)
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        negotiatedMtu = mtu
                        Log.d(TAG, "MTU negotiated: $mtu")
                    } else {
                        Log.w(TAG, "MTU negotiation failed: status=$status")
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    Log.d(TAG, "Services discovered: status=$status")
                    val service: BluetoothGattService? = g.getService(SERVICE_UUID)
                    writeChar = service?.getCharacteristic(WRITE_UUID)
                    state = if (writeChar != null) State.READY else State.DISCONNECTED
                    if (writeChar == null) {
                        lastError = "Printer write characteristic not found"
                        Log.w(TAG, lastError ?: "Printer write characteristic not found")
                    } else {
                        lastError = null
                        Log.i(TAG, "Printer is ready: profile=${profile.name} width=${profile.printWidth}")
                    }
                    completeConnectOnce(writeChar != null)
                }
            }

            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                device.connectGatt(context, false, callback)
            }
        }

        connectAttempt(1)
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    suspend fun sendCommands(commands: ByteArray): Boolean {
        val g = gatt ?: return false
        val ch = writeChar ?: return false
        if (state != State.READY) return false

        state = State.PRINTING
        try {
            val linkPayload = (negotiatedMtu - 3).coerceAtLeast(20)
            val conservative = profile.name == "M02" || profile.name == "M02S"
            val effectiveChunkSize = if (conservative) {
                minOf(profile.chunkSize, linkPayload, 205)
            } else {
                minOf(profile.chunkSize, linkPayload)
            }
            val effectiveChunksPerBurst = if (conservative) 1 else profile.chunksPerBurst
            val effectiveBurstDelayMs = if (conservative) maxOf(profile.burstDelayMs, 70L) else profile.burstDelayMs
            val interChunkDelayMs = if (conservative) 12L else 8L

            Log.i(
                TAG,
                "Sending ${commands.size} bytes in chunks of $effectiveChunkSize (mtu=$negotiatedMtu, burst=$effectiveChunksPerBurst)",
            )
            val chunks = commands.asList().chunked(effectiveChunkSize).map { it.toByteArray() }
            for ((i, chunk) in chunks.withIndex()) {
                if (state == State.DISCONNECTED) {
                    lastError = lastError ?: "Disconnected during BLE transfer (status=$lastGattStatus)"
                    Log.w(TAG, lastError ?: "Disconnected during BLE transfer")
                    return false
                }

                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                } else {
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    // On older APIs, we must use setValue and writeCharacteristic(characteristic)
                    // These are deprecated in newer APIs but there's no non-deprecated alternative for < 33
                    @Suppress("DEPRECATION")
                    ch.value = chunk
                    @Suppress("DEPRECATION")
                    val result = g.writeCharacteristic(ch)
                    result
                }
                if (!ok) {
                    lastError = "BLE write failed at chunk ${i + 1}/${chunks.size} (status=$lastGattStatus)"
                    Log.w(TAG, lastError ?: "BLE write failed")
                    state = State.DISCONNECTED
                    return false
                }
                if ((i + 1) % effectiveChunksPerBurst == 0) {
                    delay(effectiveBurstDelayMs)
                }
                delay(interChunkDelayMs)
            }
            delay(2000)
            state = State.READY
            lastError = null
            return true
        } catch (_: Exception) {
            lastError = "BLE write threw exception"
            Log.e(TAG, lastError ?: "BLE write exception")
            state = State.DISCONNECTED
            return false
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun disconnect() {
        scanTimeoutTask?.let { mainHandler.removeCallbacks(it) }
        scanCallback?.let {
            try {
                scanner?.stopScan(it)
            } catch (_: SecurityException) {
            }
        }
        scanCallback = null

        try {
            gatt?.disconnect()
            safelyCloseGatt(gatt)
        } catch (_: SecurityException) {
        }
        gatt = null
        writeChar = null
        negotiatedMtu = 23
        state = State.DISCONNECTED
    }

    private fun safelyCloseGatt(gattRef: BluetoothGatt?) {
        if (gattRef == null) return
        try {
            gattRef.close()
        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }
    }

    private fun matchesPrinterName(name: String): Boolean {
        val upper = name.uppercase()
        return NAME_PREFIXES.any { upper.startsWith(it.uppercase()) } || NAME_CONTAINS.any { upper.contains(it) }
    }

    private fun findBondedPrinter(adapter: BluetoothAdapter): BluetoothDevice? {
        return try {
            val devices = adapter.bondedDevices ?: emptySet()
            devices.firstOrNull { d ->
                val n = try {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
                        d.name
                    } else {
                        null
                    }
                } catch (_: SecurityException) {
                    null
                }
                n != null && matchesPrinterName(n)
            }
        } catch (_: SecurityException) {
            null
        }
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lm.isLocationEnabled
            } else {
                val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val network = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                gps || network
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun detectProfile(name: String): Profile {
        val upper = name.uppercase()
        return when {
            upper.contains("M04") -> PROFILE_M04S
            upper.contains("M02S") || upper.contains("M02 PRO") -> PROFILE_M02S
            upper.contains("M02") || upper.contains("T02") -> PROFILE_M02
            else -> PROFILE_M02S
        }
    }
}
