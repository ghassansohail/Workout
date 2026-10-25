package com.formcoach.app.coach

/**
 * CSV encoding of [PoseFrame] for debug recordings and replay fixtures.
 * Layout: `t_ms,w,h` then for each landmark 0..32 the fields `x,y,z,p` (p = inFrame).
 * A missing landmark writes four empty fields. Pure Kotlin, locale-independent.
 */
object PoseFrameCsv {
    private const val META_COLUMNS = 3
    private const val FIELDS_PER_LANDMARK = 4
    private const val COLUMN_COUNT = META_COLUMNS + LandmarkId.COUNT * FIELDS_PER_LANDMARK

    fun header(): String {
        val columns = ArrayList<String>(COLUMN_COUNT)
        columns += listOf("t_ms", "w", "h")
        for (i in 0 until LandmarkId.COUNT) {
            columns += "x$i"
            columns += "y$i"
            columns += "z$i"
            columns += "p$i"
        }
        return columns.joinToString(",")
    }

    fun encode(frame: PoseFrame): String {
        val sb = StringBuilder(COLUMN_COUNT * 8)
        sb.append(frame.timestampMs).append(',')
        sb.append(frame.imageWidth).append(',')
        sb.append(frame.imageHeight)
        for (i in 0 until LandmarkId.COUNT) {
            sb.append(',')
            val lm = frame.landmarks[i]
            if (lm == null) {
                sb.append(",,,")
            } else {
                sb.append(lm.x.toString()).append(',')
                sb.append(lm.y.toString()).append(',')
                sb.append(lm.z.toString()).append(',')
                sb.append(lm.inFrame.toString())
            }
        }
        return sb.toString()
    }

    fun decode(line: String): PoseFrame {
        // Kotlin's split keeps trailing empty fields, so a missing last landmark is still counted.
        val fields = line.split(",")
        require(fields.size == COLUMN_COUNT) {
            "Expected $COLUMN_COUNT columns, got ${fields.size}"
        }
        val timestampMs = fields[0].toLong()
        val width = fields[1].toInt()
        val height = fields[2].toInt()
        val landmarks = arrayOfNulls<Landmark>(LandmarkId.COUNT)
        for (i in 0 until LandmarkId.COUNT) {
            val base = META_COLUMNS + i * FIELDS_PER_LANDMARK
            val x = fields[base]
            val y = fields[base + 1]
            val z = fields[base + 2]
            val p = fields[base + 3]
            if (x.isEmpty() && y.isEmpty() && z.isEmpty() && p.isEmpty()) continue
            require(x.isNotEmpty() && y.isNotEmpty() && z.isNotEmpty() && p.isNotEmpty()) {
                "Landmark $i has a partially empty field group"
            }
            landmarks[i] = Landmark(x.toFloat(), y.toFloat(), z.toFloat(), p.toFloat())
        }
        return PoseFrame(timestampMs, width, height, landmarks)
    }

    /** Decodes every data row, skipping the header line and blank lines. */
    fun readAll(lines: Sequence<String>): List<PoseFrame> {
        val header = header()
        return lines
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != header }
            .map { decode(it) }
            .toList()
    }
}
