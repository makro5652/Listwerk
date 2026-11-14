package app.listwerk.core

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Parameter einer iCalendar-Zeile, z. B. `TZID=Asia/Riyadh`. Der Wert bleibt roh (mit Anführungszeichen),
 * damit das Zurückschreiben verlustfrei ist.
 */
@ConsistentCopyVisibility
data class ICalParam private constructor(val name: String, val value: String) {
    val unquotedValue: String
        get() = if (value.length >= 2 && value.startsWith("\"") && value.endsWith("\"")) value.substring(1, value.length - 1) else value

    companion object {
        /** Der Parametername wird immer großgeschrieben. */
        operator fun invoke(name: String, value: String): ICalParam = ICalParam(name.uppercase(), value)
    }
}

/** Eine entfaltete iCalendar-Inhaltszeile: `NAME;PARAM=x:WERT`. */
@ConsistentCopyVisibility
data class ContentLine private constructor(
    val name: String,
    val params: List<ICalParam>,
    /** Wert so, wie er in der Datei steht (Text-Escapes unverändert). */
    val value: String,
) {
    fun param(name: String): String? {
        val n = name.uppercase()
        return params.firstOrNull { it.name == n }?.unquotedValue
    }

    /** Die Zeile ohne Faltung. */
    val unfolded: String
        get() {
            val sb = StringBuilder(name)
            for (p in params) {
                sb.append(';').append(p.name)
                if (p.value.isNotEmpty()) sb.append('=').append(p.value)
            }
            return sb.append(':').append(value).toString()
        }

    companion object {
        /** Der Zeilenname wird immer großgeschrieben. */
        operator fun invoke(name: String, params: List<ICalParam> = emptyList(), value: String): ContentLine =
            ContentLine(name.uppercase(), params, value)

        /** Zerlegt eine entfaltete Zeile. Doppelpunkte und Semikolons in Anführungszeichen trennen nicht. */
        fun parse(line: String): ContentLine? {
            var inQuotes = false
            var colon = -1
            for ((i, ch) in line.withIndex()) {
                if (ch == '"') inQuotes = !inQuotes
                if (ch == ':' && !inQuotes) { colon = i; break }
            }
            if (colon <= 0) return null
            val left = line.substring(0, colon)
            val rawValue = line.substring(colon + 1)

            val parts = ArrayList<String>()
            val current = StringBuilder()
            inQuotes = false
            for (ch in left) {
                if (ch == '"') inQuotes = !inQuotes
                if (ch == ';' && !inQuotes) {
                    parts.add(current.toString()); current.setLength(0)
                } else current.append(ch)
            }
            parts.add(current.toString())
            val first = parts.first()
            if (first.isEmpty()) return null

            val params = parts.drop(1).map { p ->
                val eq = p.indexOf('=')
                if (eq >= 0) ICalParam(p.substring(0, eq).uppercase(), p.substring(eq + 1))
                else ICalParam(p.uppercase(), "")
            }
            return ContentLine(first.uppercase(), params, rawValue)
        }
    }
}

// MARK: - Text, Faltung

object ICalText {
    /** Escaping für TEXT-Werte nach RFC 5545 §3.3.11. */
    fun escape(s: String): String {
        val out = StringBuilder(s.length + 8)
        var prevCR = false
        for (c in s) {
            when (c) {
                '\\' -> out.append("\\\\")
                ';' -> out.append("\\;")
                ',' -> out.append("\\,")
                '\n' -> if (!prevCR) out.append("\\n")
                '\r' -> out.append("\\n")
                else -> out.append(c)
            }
            prevCR = c == '\r'
        }
        return out.toString()
    }

    fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                if (i + 1 >= s.length) { out.append(c); break }
                val n = s[i + 1]
                when (n) {
                    'n', 'N' -> out.append('\n')
                    '\\', ';', ',' -> out.append(n)
                    else -> { out.append(c); out.append(n) }
                }
                i += 2
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    /** Teilt an nicht maskierten Kommas (für CATEGORIES) und entfernt die Escapes. */
    fun splitList(s: String): List<String> {
        val items = ArrayList<String>()
        var current = StringBuilder()
        var escaped = false
        for (c in s) {
            if (escaped) {
                current.append('\\').append(c); escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == ',') {
                items.add(unescape(current.toString())); current = StringBuilder()
            } else current.append(c)
        }
        if (escaped) current.append('\\')
        items.add(unescape(current.toString()))
        return items.filter { it.isNotEmpty() }
    }

    /** Entfaltet den Dateitext in logische Zeilen (CRLF oder LF, Fortsetzung mit Leerzeichen oder Tab). */
    fun unfold(text: String): List<String> {
        val normalized = text.replace("\r\n", "\n")
        val lines = ArrayList<String>()
        for (raw in normalized.split('\n')) {
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t') && lines.isNotEmpty()) {
                lines[lines.size - 1] = lines.last() + raw.substring(1)
            } else lines.add(raw)
        }
        while (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.size - 1)
        return lines
    }

    /** Faltet eine Zeile auf höchstens 75 Oktette, ohne UTF-8-Zeichen zu zerteilen. Endet mit CRLF. */
    fun fold(line: String): String {
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size <= 75) return line + "\r\n"
        val out = java.io.ByteArrayOutputStream(bytes.size + bytes.size / 70 * 3 + 2)
        var i = 0
        var limit = 75
        while (i < bytes.size) {
            var end = minOf(i + limit, bytes.size)
            while (end < bytes.size && end > i + 1 && (bytes[end].toInt() and 0xC0) == 0x80) end--
            out.write(bytes, i, end - i)
            i = end
            if (i < bytes.size) {
                out.write(13); out.write(10); out.write(32)
                limit = 74
            }
        }
        out.write(13); out.write(10)
        return out.toString(Charsets.UTF_8)
    }
}

// MARK: - Datum

/**
 * Datum oder Zeitpunkt aus einem iCalendar-Wert. Die Bestandteile bleiben erhalten, damit ein Datum
 * ohne Uhrzeit (`VALUE=DATE`) und ein Zeitpunkt mit `TZID` unverändert zurückgeschrieben werden.
 */
data class ICalDate(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int = 0,
    val minute: Int = 0,
    val second: Int = 0,
    val kind: Kind,
) {
    sealed interface Kind {
        data object Day : Kind
        data object Floating : Kind
        data object Utc : Kind
        data class Zoned(val tzid: String) : Kind
    }

    val isAllDay: Boolean get() = kind == Kind.Day

    private fun pad(v: Int, n: Int) = v.toString().padStart(n, '0')

    val valueString: String
        get() {
            val date = pad(year, 4) + pad(month, 2) + pad(day, 2)
            return when (kind) {
                Kind.Day -> date
                Kind.Utc -> date + "T" + pad(hour, 2) + pad(minute, 2) + pad(second, 2) + "Z"
                else -> date + "T" + pad(hour, 2) + pad(minute, 2) + pad(second, 2)
            }
        }

    val params: List<ICalParam>
        get() = when (kind) {
            Kind.Day -> listOf(ICalParam("VALUE", "DATE"))
            is Kind.Zoned -> listOf(ICalParam("TZID", kind.tzid))
            else -> emptyList()
        }

    fun line(named: String): ContentLine = ContentLine(named.uppercase(), params, valueString)

    /** Zeitpunkt; ohne eigene Zeitzone (Tag, schwebend) gilt `fallback`. */
    fun instant(fallback: ZoneId = ZoneId.systemDefault()): Instant {
        val zone = when (kind) {
            Kind.Utc -> ZoneOffset.UTC
            is Kind.Zoned -> try { ZoneId.of(kind.tzid) } catch (_: DateTimeException) { fallback }
            else -> fallback
        }
        return try {
            ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute, second), zone).toInstant()
        } catch (_: DateTimeException) {
            Instant.EPOCH
        }
    }

    /** Sortierschlüssel für die Datenbank: Tage vor Zeitpunkten desselben Tages, sonst chronologisch. */
    fun sortKey(zone: ZoneId = ZoneId.systemDefault()): String = when (kind) {
        Kind.Day -> pad(year, 4) + "-" + pad(month, 2) + "-" + pad(day, 2)
        else -> {
            val d = instant(zone).atZone(ZoneOffset.UTC)
            pad(d.year, 4) + "-" + pad(d.monthValue, 2) + "-" + pad(d.dayOfMonth, 2) + "T" +
                pad(d.hour, 2) + ":" + pad(d.minute, 2) + ":" + pad(d.second, 2) + "Z"
        }
    }

    /** Kalendertag in der Ortszeit des Geräts (für „Heute“, „Überfällig“). */
    fun localDay(zone: ZoneId = ZoneId.systemDefault()): java.time.LocalDate {
        if (kind == Kind.Day) return java.time.LocalDate.of(year, month, day)
        return instant(zone).atZone(zone).toLocalDate()
    }

    companion object {
        fun day(y: Int, m: Int, d: Int) = ICalDate(y, m, d, kind = Kind.Day)

        /** `value` ist der Rohwert (`20261120`, `20261120T140000`, `20261120T140000Z`), `params` die Zeilenparameter. */
        fun parse(value: String, params: List<ICalParam>): ICalDate? {
            val v = value.trim()
            val tzid = params.firstOrNull { it.name == "TZID" }?.unquotedValue
            val isDateType = params.firstOrNull { it.name == "VALUE" }?.unquotedValue?.uppercase() == "DATE"
            val bytes = v.toByteArray(Charsets.UTF_8)

            fun num(from: Int, len: Int): Int? {
                if (from + len > bytes.size) return null
                var n = 0
                for (i in from until from + len) {
                    val b = bytes[i].toInt()
                    if (b < 48 || b > 57) return null
                    n = n * 10 + (b - 48)
                }
                return n
            }

            val y = num(0, 4) ?: return null
            val mo = num(4, 2) ?: return null
            val d = num(6, 2) ?: return null
            if (bytes.size == 8 || isDateType) return ICalDate(y, mo, d, kind = Kind.Day)
            if (bytes.size < 15 || bytes[8].toInt() != 84) return null
            val h = num(9, 2) ?: return null
            val mi = num(11, 2) ?: return null
            val s = num(13, 2) ?: return null
            val isUtc = bytes.size >= 16 && bytes[15].toInt() == 90
            val kind: Kind = if (isUtc) Kind.Utc else if (tzid != null) Kind.Zoned(tzid) else Kind.Floating
            return ICalDate(y, mo, d, h, mi, s, kind)
        }

        /** Ein Zeitpunkt aus der Oberfläche. Mit Uhrzeit wird UTC geschrieben, das ist für alle Clients eindeutig. */
        fun of(instant: Instant, allDay: Boolean, zone: ZoneId = ZoneId.systemDefault()): ICalDate =
            if (allDay) {
                val d = instant.atZone(zone).toLocalDate()
                ICalDate(d.year, d.monthValue, d.dayOfMonth, kind = Kind.Day)
            } else {
                val d = instant.atZone(ZoneOffset.UTC)
                ICalDate(d.year, d.monthValue, d.dayOfMonth, d.hour, d.minute, d.second, Kind.Utc)
            }
    }
}

// MARK: - UTC-Zeitstempel, Dauer

object ICalStamp {
    fun string(date: Instant): String {
        val d = date.atZone(ZoneOffset.UTC)
        fun p(v: Int, n: Int) = v.toString().padStart(n, '0')
        return p(d.year, 4) + p(d.monthValue, 2) + p(d.dayOfMonth, 2) + "T" + p(d.hour, 2) + p(d.minute, 2) + p(d.second, 2) + "Z"
    }

    fun parse(s: String): Instant? {
        val d = ICalDate.parse(s, emptyList()) ?: return null
        if (d.kind == ICalDate.Kind.Day) return null
        return d.instant()
    }
}

object ICalDuration {
    /** `-P1D`, `PT15M`, `P1W`, `-P1DT2H30M` → Sekunden (negativ = vor dem Bezugspunkt). */
    fun parse(s: String): Double? {
        var t = s.trim()
        var sign = 1.0
        if (t.startsWith("-")) { sign = -1.0; t = t.substring(1) } else if (t.startsWith("+")) t = t.substring(1)
        if (!t.startsWith("P")) return null
        t = t.substring(1)
        var total = 0.0
        var inTime = false
        val number = StringBuilder()
        for (ch in t) {
            if (ch == 'T') { inTime = true; continue }
            if (ch.isDigit()) { number.append(ch); continue }
            val n = number.toString().toDoubleOrNull() ?: return null
            number.setLength(0)
            total += when {
                ch == 'W' && !inTime -> n * 604_800
                ch == 'D' && !inTime -> n * 86_400
                ch == 'H' && inTime -> n * 3_600
                ch == 'M' && inTime -> n * 60
                ch == 'S' && inTime -> n
                else -> return null
            }
        }
        return if (number.isEmpty()) sign * total else null
    }

    fun format(seconds: Double): String {
        val neg = seconds < 0
        var rest = Math.round(Math.abs(seconds)).toInt()
        if (rest == 0) return "PT0S"
        val days = rest / 86_400; rest %= 86_400
        val hours = rest / 3_600; rest %= 3_600
        val minutes = rest / 60; rest %= 60
        val out = StringBuilder(if (neg) "-P" else "P")
        if (days > 0) out.append(days).append('D')
        if (hours > 0 || minutes > 0 || rest > 0) {
            out.append('T')
            if (hours > 0) out.append(hours).append('H')
            if (minutes > 0) out.append(minutes).append('M')
            if (rest > 0) out.append(rest).append('S')
        }
        return out.toString()
    }
}

// MARK: - CSS-Farbnamen (RFC 7986, Property COLOR)

object CSSColorNames {
    private val table: List<Pair<String, Triple<Int, Int, Int>>> = listOf(
        "black" to Triple(0, 0, 0), "white" to Triple(255, 255, 255), "red" to Triple(255, 0, 0),
        "orange" to Triple(255, 165, 0), "yellow" to Triple(255, 255, 0), "green" to Triple(0, 128, 0),
        "lime" to Triple(0, 255, 0), "teal" to Triple(0, 128, 128), "cyan" to Triple(0, 255, 255),
        "blue" to Triple(0, 0, 255), "navy" to Triple(0, 0, 128), "purple" to Triple(128, 0, 128),
        "magenta" to Triple(255, 0, 255), "pink" to Triple(255, 192, 203), "brown" to Triple(165, 42, 42),
        "gray" to Triple(128, 128, 128), "silver" to Triple(192, 192, 192), "gold" to Triple(255, 215, 0),
        "coral" to Triple(255, 127, 80), "salmon" to Triple(250, 128, 114), "turquoise" to Triple(64, 224, 208),
        "violet" to Triple(238, 130, 238), "indigo" to Triple(75, 0, 130), "crimson" to Triple(220, 20, 60),
        "olive" to Triple(128, 128, 0), "maroon" to Triple(128, 0, 0), "skyblue" to Triple(135, 206, 235),
        "tomato" to Triple(255, 99, 71),
    )

    fun nearestName(hex: String): String? {
        val c = rgb(hex) ?: return null
        return table.minByOrNull { dist(it.second, c) }?.first
    }

    fun rgb(hex: String): Triple<Int, Int, Int>? {
        var s = hex.trim()
        if (s.startsWith("#")) s = s.substring(1)
        if (s.length != 6) return null
        val v = s.toIntOrNull(16) ?: return null
        return Triple((v shr 16) and 255, (v shr 8) and 255, v and 255)
    }

    private fun dist(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        val r = a.first - b.first; val g = a.second - b.second; val bl = a.third - b.third
        return r * r + g * g + bl * bl
    }
}
