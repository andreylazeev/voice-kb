package ly.anoma.hex.typing

import android.content.Context
import android.util.AtomicFile
import org.pytorch.executorch.EValue
import org.pytorch.executorch.Module
import org.pytorch.executorch.Tensor
import java.io.File

/** The FUTO Swipe encoder predicts letter and blank emissions over 32 time steps. */
internal class FutoSwipeModel(context: Context) : AutoCloseable {
    private val module: Module
    init {
        val file = AtomicFile(File(context.codeCacheDir, "futo-encoder-fp32-0a9a44.pte"))
        if (!file.baseFile.isFile) {
            val output = file.startWrite()
            try {
                context.assets.open("futo/encoder-fp32.pte").use { it.copyTo(output) }
                file.finishWrite(output)
            } catch (failure: Exception) {
                file.failWrite(output)
                throw failure
            }
        }
        val loaded = Module.load(file.baseFile.absolutePath, Module.LOAD_MODE_MMAP)
        try {
            loaded.loadMethod("forward")
            module = loaded
        } catch (failure: Exception) {
            loaded.close()
            throw failure
        }
    }

    fun emissions(path: List<KeyPoint>, layout: FutoLayout): FloatArray {
        val coordinates = EValue.from(Tensor.fromBlob(layout.coordinates, longArrayOf(1, FutoLayout.KEYS.toLong(), 2)))
        val mask = EValue.from(FutoKeyMask(FutoLayout.KEYS, layout.letters.length))
        val trajectory = EValue.from(Tensor.fromBlob(layout.features(path), longArrayOf(1, 2, FutoLayout.SAMPLES.toLong())))
        return module.forward(trajectory, coordinates, mask).first().toTensor().dataAsFloatArray
    }

    override fun close() = module.close()
}

internal class FutoLayout(grid: KeyGrid) {
    companion object {
        const val KEYS = 64
        const val SAMPLES = 64
        const val STEPS = 32
        const val CLASSES = KEYS + 1
    }

    val letters = grid.centres.keys.sorted().joinToString("")
    private val pitchX = kotlin.math.abs(grid.pitchX)
    private val pitchY = kotlin.math.abs(grid.pitchY)
    private val left = grid.centres.values.minOf { it.x } - pitchX / 2
    private val top = grid.centres.values.minOf { it.y } - pitchY / 2
    private val width = grid.centres.values.maxOf { it.x } + pitchX / 2 - left
    private val height = grid.centres.values.maxOf { it.y } + pitchY / 2 - top
    private fun x(point: KeyPoint) = ((point.x - left) / width).toFloat()
    private fun y(point: KeyPoint) = ((point.y - top) / height).toFloat()
    val coordinates = FloatArray(2 * KEYS).apply {
        require(letters.length <= KEYS)
        letters.forEachIndexed { i, letter ->
            val center = grid.centres.getValue(letter)
            this[i * 2] = x(center)
            this[i * 2 + 1] = y(center)
        }
    }

    fun features(path: List<KeyPoint>): FloatArray {
        require(path.size >= 2)
        val times = DoubleArray(path.size)
        val hasTime = path.first().timeMillis < path.last().timeMillis
        for (index in 1 until path.size) {
            times[index] = if (hasTime)
                maxOf((path[index].timeMillis - path.first().timeMillis).toDouble(), times[index - 1] + .001)
            else index.toDouble()
        }
        var end = 1
        return FloatArray(2 * SAMPLES).apply {
            for (index in 0 until SAMPLES) {
                val at = times.last() * index / (SAMPLES - 1)
                while (end < path.lastIndex && times[end] < at) end++
                val start = end - 1
                val mix = ((at - times[start]) / (times[end] - times[start])).toFloat()
                this[index] = x(path[start]) + (x(path[end]) - x(path[start])) * mix
                this[SAMPLES + index] = y(path[start]) + (y(path[end]) - y(path[start])) * mix
            }
        }
    }
}
