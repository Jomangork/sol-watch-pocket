package local.solwatch.pocket

import java.util.Base64

/**
 * A company or alliance logo from the game's share code (HQ › Logo), e.g. "SOL1:EAb///87…".
 *
 * Layout, worked out from a real code: after "SOL1:", unpadded base64 of
 *   [size] [colour count n] [n × RGB] [size × size pixels, 4 bits each, high nibble first] [1 check byte]
 * Pixel 0 is transparent; 1..n index the palette. The check byte's formula is unknown, so it's ignored.
 * Pure Kotlin (no Android types), so JVM tests cover it.
 */
class Logo(val size: Int, private val argb: IntArray) {
    fun pixel(x: Int, y: Int): Int = argb[y * size + x]

    companion object {
        private const val PREFIX = "SOL1:"

        fun decode(code: String?): Logo? {
            val text = code?.trim()?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX) ?: return null
            // Standard base64 ('/' appears in real codes); URL-safe '-' and '_' are accepted too.
            val normal = text.replace('-', '+').replace('_', '/')
            val bytes = runCatching {
                Base64.getDecoder().decode(normal.padEnd((normal.length + 3) / 4 * 4, '='))
            }.getOrNull() ?: return null
            if (bytes.size < 2) return null
            val size = bytes[0].toInt() and 0xff
            val colours = bytes[1].toInt() and 0xff
            val pixelStart = 2 + colours * 3
            val pixelBytes = (size * size + 1) / 2
            if (size !in 1..64 || colours !in 1..15 || bytes.size < pixelStart + pixelBytes) return null
            val palette = IntArray(colours) { i ->
                val r = bytes[2 + i * 3].toInt() and 0xff
                val g = bytes[3 + i * 3].toInt() and 0xff
                val b = bytes[4 + i * 3].toInt() and 0xff
                (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            val argb = IntArray(size * size) { i ->
                val byte = bytes[pixelStart + i / 2].toInt() and 0xff
                val index = if (i % 2 == 0) byte shr 4 else byte and 0x0f
                if (index == 0 || index > colours) 0 else palette[index - 1]
            }
            return Logo(size, argb)
        }
    }
}
