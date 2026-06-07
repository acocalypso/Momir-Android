package com.momir.android.data

import com.momir.android.model.Creature
import com.squareup.moshi.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.buffer
import okio.source
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class AtomicBootstrapper(private val appDir: File) {
    enum class Stage {
        DOWNLOADING,
        PROCESSING,
        LOADING,
    }

    companion object {
        private const val ATOMIC_URL = "https://mtgjson.com/api/v5/AtomicCards.json"
        private const val RAW_FILE = "AtomicCards.json"
        private const val PROCESSED_FILE = "creatures_min.json"
        private const val RETRY_COUNT = 3
    }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(140, TimeUnit.SECONDS)
        .build()

    fun loadOrBootstrap(onStage: (Stage) -> Unit = {}): Map<Int, List<Creature>> {
        val processed = File(appDir, PROCESSED_FILE)
        if (!processed.exists()) {
            val raw = File(appDir, RAW_FILE)
            if (!raw.exists()) {
                onStage(Stage.DOWNLOADING)
                downloadAtomic(raw)
            }
            onStage(Stage.PROCESSING)
            processAtomic(raw, processed)
        }
        onStage(Stage.LOADING)
        return loadProcessed(processed)
    }

    fun forceRefresh(onStage: (Stage) -> Unit = {}): Map<Int, List<Creature>> {
        val raw = File(appDir, RAW_FILE)
        val processed = File(appDir, PROCESSED_FILE)

        onStage(Stage.DOWNLOADING)
        downloadAtomic(raw)
        onStage(Stage.PROCESSING)
        processAtomic(raw, processed)
        onStage(Stage.LOADING)
        return loadProcessed(processed)
    }

    private fun downloadAtomic(target: File) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        var lastError: Throwable? = null

        repeat(RETRY_COUNT) { attempt ->
            val req = Request.Builder().url(ATOMIC_URL).build()
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IOException("Failed AtomicCards download: HTTP ${resp.code}")
                    }
                    resp.body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                }

                if (!tmp.exists() || tmp.length() < 1024L * 1024L) {
                    throw IOException("Downloaded AtomicCards file is unexpectedly small")
                }

                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                }
                tmp.delete()
                return
            } catch (e: Exception) {
                lastError = e
                tmp.delete()

                if (attempt < RETRY_COUNT - 1) {
                    val backoffMs = 1200L * (attempt + 1)
                    Thread.sleep(backoffMs)
                }
            }
        }

        throw IOException("AtomicCards download failed after $RETRY_COUNT attempts: ${lastError?.message}", lastError)
    }

    private fun processAtomic(rawFile: File, outFile: File) {
        val byMv = mutableMapOf<Int, MutableList<Creature>>()

        rawFile.inputStream().source().buffer().use { source ->
            val reader = JsonReader.of(source)
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "data" -> parseDataObject(reader, byMv)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }

        val root = JSONObject()
        byMv.toSortedMap().forEach { (mv, list) ->
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(
                    JSONObject()
                        .put("n", c.name)
                        .put("mv", c.manaValue)
                        .put("t", c.type)
                        .put("p", c.power)
                        .put("h", c.toughness)
                        .put("x", c.text)
                        .put("m", c.manaCost)
                        .put("f", c.isFunny)
                )
            }
            root.put(mv.toString(), arr)
        }

        val tmpOut = File(outFile.parentFile, "${outFile.name}.tmp")
        tmpOut.writeText(root.toString())
        if (!tmpOut.renameTo(outFile)) {
            tmpOut.copyTo(outFile, overwrite = true)
            tmpOut.delete()
        }
    }

    private fun parseDataObject(reader: JsonReader, byMv: MutableMap<Int, MutableList<Creature>>) {
        reader.beginObject()
        while (reader.hasNext()) {
            val cardName = reader.nextName()
            reader.beginArray()
            var firstCreature: Creature? = null

            if (reader.hasNext()) {
                firstCreature = parsePrinting(reader, cardName)
            }
            while (reader.hasNext()) {
                reader.skipValue()
            }
            reader.endArray()

            if (firstCreature != null) {
                byMv.getOrPut(firstCreature.manaValue) { mutableListOf() }.add(firstCreature)
            }
        }
        reader.endObject()
    }

    private fun parsePrinting(reader: JsonReader, fallbackName: String): Creature? {
        var name = fallbackName
        var manaValue = 0
        var type = ""
        var power = ""
        var toughness = ""
        var text = ""
        var manaCost = ""
        var isFunny = false
        var isCreature = false

        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "name" -> name = reader.nextString()
                "manaValue" -> manaValue = reader.nextDouble().toInt()
                "type" -> type = reader.nextString()
                "power" -> power = nextStringOrEmpty(reader)
                "toughness" -> toughness = nextStringOrEmpty(reader)
                "text" -> text = nextStringOrEmpty(reader)
                "manaCost" -> manaCost = nextStringOrEmpty(reader)
                "isFunny" -> isFunny = reader.nextBoolean()
                "types" -> {
                    isCreature = parseTypesArray(reader)
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()

        if (!isCreature) return null
        return Creature(
            name = name,
            manaValue = manaValue,
            type = type,
            power = power,
            toughness = toughness,
            text = text,
            manaCost = manaCost,
            isFunny = isFunny,
        )
    }

    private fun parseTypesArray(reader: JsonReader): Boolean {
        var creature = false
        reader.beginArray()
        while (reader.hasNext()) {
            if (reader.nextString() == "Creature") creature = true
        }
        reader.endArray()
        return creature
    }

    private fun nextStringOrEmpty(reader: JsonReader): String {
        return if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<String>()
            ""
        } else {
            reader.nextString()
        }
    }

    private fun loadProcessed(file: File): Map<Int, List<Creature>> {
        val root = JSONObject(file.readText())
        val out = mutableMapOf<Int, List<Creature>>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val mv = keys.next().toInt()
            val arr = root.getJSONArray(mv.toString())
            val cards = mutableListOf<Creature>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                cards.add(
                    Creature(
                        name = o.optString("n"),
                        manaValue = o.optInt("mv"),
                        type = o.optString("t"),
                        power = o.optString("p"),
                        toughness = o.optString("h"),
                        text = o.optString("x"),
                        manaCost = o.optString("m"),
                        isFunny = o.optBoolean("f"),
                    )
                )
            }
            out[mv] = cards
        }
        return out
    }
}
