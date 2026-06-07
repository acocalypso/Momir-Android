package com.momir.android.ui

import android.Manifest
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.momir.android.ble.BlePrinterManager
import com.momir.android.data.AtomicBootstrapper
import com.momir.android.data.CardRepository
import com.momir.android.model.Creature
import com.momir.android.print.ThermalRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import androidx.core.graphics.scale
import androidx.core.graphics.createBitmap

class MainViewModel(app: Application) : AndroidViewModel(app) {
    enum class BootstrapMode {
        INITIAL,
        REFRESH,
    }

    data class UiState(
        val loading: Boolean = true,
        val message: String = "Preparing card database...",
        val bootstrapping: Boolean = true,
        val bootstrapMode: BootstrapMode = BootstrapMode.INITIAL,
        val bootstrapStage: AtomicBootstrapper.Stage = AtomicBootstrapper.Stage.DOWNLOADING,
        val totalCreatures: Int = 0,
        val selectedMv: Int = 0,
        val includeFunny: Boolean = false,
        val showPreviewImage: Boolean = true,
        val card: Creature? = null,
        val imageUrl: String? = null,
        val previewBitmap: Bitmap? = null,
        val printerState: BlePrinterManager.State = BlePrinterManager.State.DISCONNECTED,
        val printerName: String? = null,
        val printWidth: Int = 576,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val ble = BlePrinterManager(app.applicationContext)
    private val repo = CardRepository(AtomicBootstrapper(app.filesDir))
    private val http = OkHttpClient()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val total = repo.ensureLoaded { stage ->
                    _ui.value = _ui.value.copy(
                        bootstrapping = true,
                        bootstrapMode = BootstrapMode.INITIAL,
                        bootstrapStage = stage,
                        message = stageMessage(stage),
                    )
                }
                _ui.value = _ui.value.copy(loading = false, bootstrapping = false, message = "Ready", totalCreatures = total)
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(loading = false, bootstrapping = false, message = "Bootstrap failed: ${e.message}")
            }
        }
    }

    fun refreshAtomicJson() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _ui.value = _ui.value.copy(
                    loading = true,
                    bootstrapping = true,
                    bootstrapMode = BootstrapMode.REFRESH,
                    bootstrapStage = AtomicBootstrapper.Stage.DOWNLOADING,
                    message = stageMessage(AtomicBootstrapper.Stage.DOWNLOADING),
                )
                val total = repo.refreshData { stage ->
                    _ui.value = _ui.value.copy(
                        bootstrapping = true,
                        bootstrapMode = BootstrapMode.REFRESH,
                        bootstrapStage = stage,
                        message = stageMessage(stage),
                    )
                }
                _ui.value = _ui.value.copy(
                    loading = false,
                    bootstrapping = false,
                    totalCreatures = total,
                    message = "Atomic JSON updated",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    bootstrapping = false,
                    message = "Atomic update failed: ${e.message}",
                )
            }
        }
    }

    fun setMv(mv: Int) {
        _ui.value = _ui.value.copy(selectedMv = mv)
    }

    fun setIncludeFunny(v: Boolean) {
        _ui.value = _ui.value.copy(includeFunny = v)
    }

    fun setShowPreviewImage(v: Boolean) {
        val current = _ui.value
        _ui.value = current.copy(showPreviewImage = v, previewBitmap = if (v) current.previewBitmap else null)
        if (v && current.card != null && current.previewBitmap == null) {
            generatePreview(current.card)
        }
    }

    fun roll() {
        val state = _ui.value
        val c = repo.getRandomCreature(state.selectedMv, state.includeFunny)
        if (c == null) {
            _ui.value = state.copy(card = null, imageUrl = null, previewBitmap = null, message = "No creatures at MV ${state.selectedMv}")
            return
        }
        val image = buildScryfallImageUrl(c.name, "normal")
        _ui.value = state.copy(card = c, imageUrl = image, previewBitmap = null, message = "Rolled ${c.name}")

        if (state.showPreviewImage) {
            generatePreview(c)
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT])
    fun connectPrinter() {
        _ui.value = _ui.value.copy(message = "Scanning for printer...", printerState = BlePrinterManager.State.CONNECTING)
        ble.connect { ok ->
            val ps = ble.state
            val failure = ble.lastError ?: "Printer connect failed"
            _ui.value = _ui.value.copy(
                printerState = ps,
                printerName = ble.deviceName,
                printWidth = ble.profile.printWidth,
                message = if (ok) "Connected to ${ble.deviceName}" else failure,
            )
        }
    }

    fun syncPrinterState() {
        _ui.value = _ui.value.copy(
            printerState = ble.state,
            printerName = ble.deviceName,
            printWidth = ble.profile.printWidth,
        )
    }

    fun printCurrentCard() {
        val state = _ui.value
        val card = state.card ?: return
        if (ble.state != BlePrinterManager.State.READY) {
            _ui.value = state.copy(message = "Printer not connected")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(message = "Preparing print...", printerState = BlePrinterManager.State.PRINTING)
            val rendered = state.previewBitmap ?: ThermalRenderer.renderCardBitmap(card, fetchArtCrop(card.name), printWidth = 576)
            val toPrint = if (ble.profile.printWidth > 576) rotateAndCenter(rendered, ble.profile.printWidth) else resizeToWidth(rendered, ble.profile.printWidth)
            val commands = ThermalRenderer.buildPrintCommands(
                toPrint,
                init = ble.profile.initCommands,
                finalize = ble.profile.finalizeCommands,
            )
            val ok = ble.sendCommands(commands)
            _ui.value = _ui.value.copy(
                printerState = ble.state,
                message = if (ok) "Printed ${card.name}" else (ble.lastError ?: "Print failed"),
            )
        }
    }

    private fun fetchArtCrop(name: String): Bitmap? {
        val network = try {
            val req = Request.Builder()
                .url(buildScryfallImageUrl(name, "art_crop"))
                .header("User-Agent", "momir-android/1.0")
                .header("Accept", "image/*")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val bytes = resp.body.bytes()
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        } catch (_: Exception) {
            null
        }
        return network ?: loadBundledDummyArt() ?: createGeneratedDummyArt(name)
    }

    private fun loadBundledDummyArt(): Bitmap? {
        val app = getApplication<Application>()

        // Preferred: place file at app/src/main/assets/dummy.png
        try {
            app.assets.open("dummy.png").use { input ->
                return BitmapFactory.decodeStream(input)
            }
        } catch (_: Exception) {
        }

        // Alternative: place file in res/drawable as "dummy"
        return try {
            val resId = app.resources.getIdentifier("dummy", "drawable", app.packageName)
            if (resId != 0) BitmapFactory.decodeResource(app.resources, resId) else null
        } catch (_: Exception) {
            null
        }
    }

    private fun createGeneratedDummyArt(name: String): Bitmap {
        val width = 1024
        val height = 1024
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)

        canvas.drawColor(Color.rgb(242, 244, 248))

        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            style = Paint.Style.STROKE
            strokeWidth = 10f
        }
        canvas.drawRect(24f, 24f, (width - 24).toFloat(), (height - 24).toFloat(), border)

        val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(45, 55, 72)
            textSize = 52f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val subtitle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(99, 115, 129)
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }
        val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(75, 85, 99)
            textSize = 260f
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }

        canvas.drawText("NO ART AVAILABLE", width / 2f, 150f, title)
        canvas.drawText("offline fallback", width / 2f, 210f, subtitle)
        canvas.drawText("?", width / 2f, 590f, mark)

        val maxName = if (name.length > 40) name.take(37) + "..." else name
        canvas.drawText(maxName, width / 2f, 910f, subtitle)

        return bmp
    }

    private fun generatePreview(card: Creature) {
        viewModelScope.launch(Dispatchers.IO) {
            val art = fetchArtCrop(card.name)
            val preview = ThermalRenderer.renderCardBitmap(card, art, printWidth = 576)
            _ui.value = _ui.value.copy(previewBitmap = preview)
        }
    }

    private fun buildScryfallImageUrl(name: String, version: String): String {
        return "https://api.scryfall.com/cards/named".toHttpUrl().newBuilder()
            .addQueryParameter("exact", name)
            .addQueryParameter("format", "image")
            .addQueryParameter("version", version)
            .build()
            .toString()
    }

    private fun stageMessage(stage: AtomicBootstrapper.Stage): String {
        return when (stage) {
            AtomicBootstrapper.Stage.DOWNLOADING -> "Downloading AtomicCards JSON..."
            AtomicBootstrapper.Stage.PROCESSING -> "Processing creatures index..."
            AtomicBootstrapper.Stage.LOADING -> "Loading local creature database..."
        }
    }

    private fun resizeToWidth(bitmap: Bitmap, width: Int): Bitmap {
        if (bitmap.width == width) return bitmap
        val h = (bitmap.height.toFloat() * (width.toFloat() / bitmap.width.toFloat())).toInt()
        return bitmap.scale(width, h.coerceAtLeast(1))
    }

    private fun rotateAndCenter(bitmap: Bitmap, printWidth: Int): Bitmap {
        val rotated = createBitmap(bitmap.height, bitmap.width)
        val canvas = android.graphics.Canvas(rotated)
        canvas.rotate(90f)
        canvas.translate(0f, -bitmap.height.toFloat())
        canvas.drawBitmap(bitmap, 0f, 0f, null)

        val out = createBitmap(printWidth, rotated.height)
        val c = android.graphics.Canvas(out)
        c.drawColor(android.graphics.Color.WHITE)
        val x = (printWidth - rotated.width) / 2f
        c.drawBitmap(rotated, x, 0f, null)
        return out
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
    }
}
