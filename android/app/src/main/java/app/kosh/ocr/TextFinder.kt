package app.kosh.ocr

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A text region: centre, size (width along [angle]) and angle in radians; [score] is the model's confidence. */
data class TextBox(val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float, val score: Float) {
    fun scaled(sx: Float, sy: Float) = copy(cx = cx / sx, cy = cy / sy, w = w / sx, h = h / sy)
    val top: Float get() = cy - h / 2
    val bottom: Float get() = cy + h / 2
    val left: Float get() = cx - w / 2
    val right: Float get() = cx + w / 2
}

/**
 * Turns the detector's probability map into text boxes, the way PaddleOCR's DB post-processing does:
 * threshold, connected regions, the smallest rotated rectangle around each, then grown a little ("unclip")
 * because the model marks the shrunken core of each word. Pure Kotlin so it can be tested off the phone.
 */
object TextFinder {
    fun find(
        prob: FloatArray,
        width: Int,
        height: Int,
        thresh: Float = 0.3f,
        boxThresh: Float = 0.6f,
        unclip: Float = 1.5f,
        minSize: Float = 3f,
    ): List<TextBox> {
        val seen = BooleanArray(width * height)
        val stack = IntArray(width * height)
        val boxes = ArrayList<TextBox>()
        for (start in 0 until width * height) {
            if (seen[start] || prob[start] <= thresh) continue
            // Flood fill one 4-connected region, keeping each row's left and right ends (enough for its outline).
            var top = 0
            stack[top++] = start
            seen[start] = true
            var count = 0
            var sum = 0.0
            val rowMin = HashMap<Int, Int>()
            val rowMax = HashMap<Int, Int>()
            while (top > 0) {
                val p = stack[--top]
                val x = p % width
                val y = p / width
                count++
                sum += prob[p]
                rowMin[y] = min(rowMin[y] ?: x, x)
                rowMax[y] = max(rowMax[y] ?: x, x)
                if (x > 0) push(p - 1, prob, thresh, seen, stack, top)?.let { top = it }
                if (x < width - 1) push(p + 1, prob, thresh, seen, stack, top)?.let { top = it }
                if (y > 0) push(p - width, prob, thresh, seen, stack, top)?.let { top = it }
                if (y < height - 1) push(p + width, prob, thresh, seen, stack, top)?.let { top = it }
            }
            if (count < 10) continue
            val score = (sum / count).toFloat()
            if (score < boxThresh) continue
            // Pixel squares: each row contributes its outer corners.
            val pts = ArrayList<FloatArray>(rowMin.size * 4)
            for ((y, x0) in rowMin) {
                val x1 = rowMax[y]!! + 1f
                pts += floatArrayOf(x0.toFloat(), y.toFloat()); pts += floatArrayOf(x0.toFloat(), y + 1f)
                pts += floatArrayOf(x1, y.toFloat()); pts += floatArrayOf(x1, y + 1f)
            }
            val r = minAreaRect(hull(pts)) ?: continue
            if (min(r.w, r.h) < minSize) continue
            val d = (r.w * r.h) * unclip / (2 * (r.w + r.h))
            boxes += r.copy(w = r.w + 2 * d, h = r.h + 2 * d, score = score)
        }
        return boxes
    }

    private fun push(q: Int, prob: FloatArray, thresh: Float, seen: BooleanArray, stack: IntArray, top: Int): Int? {
        if (seen[q] || prob[q] <= thresh) return null
        seen[q] = true
        stack[top] = q
        return top + 1
    }

    /** Monotone-chain convex hull. */
    fun hull(points: List<FloatArray>): List<FloatArray> {
        val pts = points.distinctBy { (it[0] * 4096 + it[1]).toDouble() }.sortedWith(compareBy({ it[0] }, { it[1] }))
        if (pts.size < 3) return pts
        fun cross(o: FloatArray, a: FloatArray, b: FloatArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val lower = ArrayList<FloatArray>()
        for (p in pts) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], p) <= 0) lower.removeAt(lower.size - 1)
            lower += p
        }
        val upper = ArrayList<FloatArray>()
        for (p in pts.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], p) <= 0) upper.removeAt(upper.size - 1)
            upper += p
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * Smallest-area rotated rectangle around a convex hull (one side lies on a hull edge). Width is the side that
     * reads as the text direction: the long side for a word or line, the one nearest horizontal for a lone character.
     */
    fun minAreaRect(h: List<FloatArray>): TextBox? {
        if (h.size < 3) return null
        var best = Double.MAX_VALUE
        var out: TextBox? = null
        for (i in h.indices) {
            val a = h[i]
            val b = h[(i + 1) % h.size]
            val ex = (b[0] - a[0]).toDouble()
            val ey = (b[1] - a[1]).toDouble()
            val len = hypot(ex, ey)
            if (len == 0.0) continue
            val ux = ex / len; val uy = ey / len
            val vx = -uy; val vy = ux
            var minU = Double.MAX_VALUE; var maxU = -Double.MAX_VALUE; var minV = Double.MAX_VALUE; var maxV = -Double.MAX_VALUE
            for (p in h) {
                val u = p[0] * ux + p[1] * uy
                val v = p[0] * vx + p[1] * vy
                minU = min(minU, u); maxU = max(maxU, u); minV = min(minV, v); maxV = max(maxV, v)
            }
            val area = (maxU - minU) * (maxV - minV)
            if (area < best) {
                best = area
                val cu = (minU + maxU) / 2
                val cv = (minV + maxV) / 2
                out = orient((ux * cu + vx * cv).toFloat(), (uy * cu + vy * cv).toFloat(), (maxU - minU).toFloat(), (maxV - minV).toFloat(), atan2(uy, ux))
            }
        }
        return out
    }

    private fun orient(cx: Float, cy: Float, w0: Float, h0: Float, a0: Double): TextBox {
        var w = w0; var h = h0; var a = a0
        // Bring the angle to (-90°, 90°].
        fun norm() { while (a > PI / 2) a -= PI; while (a <= -PI / 2) a += PI }
        norm()
        val long = max(w, h); val short = min(w, h)
        val swap = if (long / short >= 1.5f) h > w else abs(a) > PI / 4
        if (swap) { val t = w; w = h; h = t; a += PI / 2; norm() }
        return TextBox(cx, cy, w, h, a.toFloat(), 0f)
    }

    /**
     * Recognised boxes on the same printed line, left to right, so "TOTAL" and "₹450.00" end up together.
     * A photo taken at a slant tilts every line the same way, so the boxes are first turned back by the
     * page's tilt (the median angle of the wide boxes); otherwise a word at the right end of a tilted line
     * would sit nearer the line above it.
     */
    fun <T> lines(items: List<T>, box: (T) -> TextBox): List<List<T>> {
        val tilt = tilt(items.map(box))
        val cos = kotlin.math.cos(tilt); val sin = kotlin.math.sin(tilt)
        // Each box's position along the line (x) and across it (y), in the page's own frame.
        fun x(b: TextBox) = b.cx * cos + b.cy * sin
        fun y(b: TextBox) = -b.cx * sin + b.cy * cos
        val sorted = items.sortedBy { y(box(it)) }
        val out = ArrayList<MutableList<T>>()
        for (it in sorted) {
            val b = box(it)
            val line = out.lastOrNull()
            val sameLine = line != null && line.any { o ->
                val r = box(o)
                val overlap = min(y(r) + r.h / 2, y(b) + b.h / 2) - max(y(r) - r.h / 2, y(b) - b.h / 2)
                overlap > 0.5f * min(r.h, b.h)
            }
            if (sameLine) line!!.add(it) else out += mutableListOf(it)
        }
        return out.map { l -> l.sortedBy { x(box(it)) } }
    }

    /** The page's slant in radians: the median angle of boxes clearly wider than tall (short boxes have no reliable angle). */
    fun tilt(boxes: List<TextBox>): Float {
        val angles = boxes.filter { it.w >= it.h * 2 }.map { it.angle }.sorted()
        if (angles.isEmpty()) return 0f
        val a = angles[angles.size / 2]
        // Beyond about 30 degrees the photo is sideways or the boxes are wrong; don't guess.
        return if (abs(a) <= (PI / 6).toFloat()) a else 0f
    }
}
