package kotlinx.coroutree.model.tree

/**
 * Append-only list that hands out immutable prefix views in O(1).
 *
 * One thread appends; views may be read from any thread once they have been safely published. Elements never move:
 * storage is a directory of chunks of doubling size, so a view stays valid while the log keeps growing.
 */
internal class AppendLog<T> {
    private var chunks: Array<Array<Any?>?> = arrayOfNulls(4)
    private var size = 0

    fun add(element: T) {
        val chunk = chunkOf(size)
        if (chunk == chunks.size) chunks = chunks.copyOf(chunks.size * 2)
        val array = chunks[chunk] ?: arrayOfNulls<Any?>(FIRST_CHUNK shl chunk).also { chunks[chunk] = it }
        array[size - startOf(chunk)] = element
        size++
    }

    fun view(): List<T> = if (size == 0) emptyList() else View(chunks, size)

    private class View<T>(private val chunks: Array<Array<Any?>?>, override val size: Int) : AbstractList<T>(), RandomAccess {
        override fun get(index: Int): T {
            if (index < 0 || index >= size) throw IndexOutOfBoundsException("index $index, size $size")
            val chunk = chunkOf(index)
            @Suppress("UNCHECKED_CAST")
            return chunks[chunk]!![index - startOf(chunk)] as T
        }
    }

    private companion object {
        const val FIRST_CHUNK = 8

        // Chunk k holds FIRST_CHUNK * 2^k elements and starts at FIRST_CHUNK * (2^k - 1).
        fun chunkOf(index: Int): Int = 31 - Integer.numberOfLeadingZeros(index / FIRST_CHUNK + 1)
        fun startOf(chunk: Int): Int = FIRST_CHUNK * ((1 shl chunk) - 1)
    }
}
