package ly.anoma.hex

import android.content.Context
import ly.anoma.hex.typing.FutoLayout
import ly.anoma.hex.typing.FutoSwipeModel
import ly.anoma.hex.typing.KeyGrid
import ly.anoma.hex.typing.KeyPoint
import org.json.JSONArray
import java.io.DataOutputStream
import java.io.File

/** Produce encoder scores for a public Yandex Cup replay; the same scores can be reused by host experiments. */
internal object RussianSwipeReplay {
    fun encode(context: Context): Int {
        val directory = checkNotNull(context.getExternalFilesDir("swipe-benchmark"))
        val destination = File(directory, "emissions.bin")
        val partial = File(directory, "emissions.part")
        var count = 0
        File(directory, "gestures.jsonl").bufferedReader().use { input ->
            val source = JSONArray(checkNotNull(input.readLine()))
            val layouts = List(source.length()) { index ->
                val row = source.getJSONArray(index)
                val points = row.getJSONObject(0)
                val keys = points.keys().asSequence().associate { key ->
                    val point = points.getJSONArray(key)
                    key.single() to KeyPoint(point.getDouble(0), point.getDouble(1))
                }
                KeyGrid(keys, row.getDouble(1), row.getDouble(2))
            }
            val encoders = layouts.map(::FutoLayout)
            FutoSwipeModel(context).use { model ->
                DataOutputStream(partial.outputStream().buffered(512 * 1024)).use { output ->
                    for (line in input.lineSequence()) {
                        val row = JSONArray(line)
                        val layout = encoders[row.getInt(1)]
                        val x = row.getJSONArray(3)
                        val y = row.getJSONArray(4)
                        val t = row.getJSONArray(5)
                        val path = List(x.length()) { index -> KeyPoint(x.getDouble(index), y.getDouble(index), t.getLong(index)) }
                        val word = row.getString(2).toByteArray()
                        val letters = layout.letters.toByteArray()
                        val scores = model.emissions(path, layout)
                        output.writeInt(row.getInt(0))
                        output.writeShort(letters.size)
                        output.writeShort(word.size)
                        output.write(letters)
                        output.write(word)
                        for (score in scores) output.writeFloat(score)
                        count++
                    }
                }
            }
        }
        check(partial.renameTo(destination)) { "Cannot publish emissions" }
        return count
    }
}
