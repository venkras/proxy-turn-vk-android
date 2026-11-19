package com.wdtt.client.csqtt

/**
 * Минимальный JSON-парсер только для чтения payload-объектов событий CSQTT.
 *
 * Пакет `csqtt` не должен зависеть от `org.json` (на JVM это заглушка
 * android.jar, а на устройстве — Android API), поэтому здесь компактный
 * рекурсивный разборщик. Поддерживаются объекты, массивы, строки, числа,
 * `true`/`false`/`null`; глубина ограничена, чтобы злой или битый лог не уронил
 * стек.
 */
internal object CsqttJson {
    private const val MAX_DEPTH = 16
    private const val MAX_NUMBER_LENGTH = 32

    /** Разбирает JSON-объект; `null`, если это не объект или JSON битый. */
    fun parseObject(text: String): Map<String, Any?>? {
        if (text.isEmpty() || text.length > 64 * 1024) return null
        return try {
            val reader = Reader(text)
            reader.skipWhitespace()
            val value = reader.readValue(0)
            reader.skipWhitespace()
            if (!reader.isEnd) return null
            @Suppress("UNCHECKED_CAST")
            (value as? Map<String, Any?>)
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: IndexOutOfBoundsException) {
            null
        }
    }

    private class Reader(private val source: String) {
        private var index = 0

        val isEnd: Boolean get() = index >= source.length

        fun skipWhitespace() {
            while (!isEnd && source[index].isWhitespace()) index++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("json too deep")
            skipWhitespace()
            if (isEnd) throw IllegalArgumentException("unexpected end")
            return when (val char = source[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char.isDigit()) readNumber() else
                    throw IllegalArgumentException("unexpected char '$char'")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            expect('{')
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw IllegalArgumentException("expected key")
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected , or }")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            expect('[')
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> {
                        index++
                        return result
                    }
                    else -> throw IllegalArgumentException("expected , or ]")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (isEnd) throw IllegalArgumentException("unterminated string")
                val char = source[index++]
                when (char) {
                    '"' -> return builder.toString()
                    '\\' -> {
                        if (isEnd) throw IllegalArgumentException("bad escape")
                        when (val escape = source[index++]) {
                            '"' -> builder.append('"')
                            '\\' -> builder.append('\\')
                            '/' -> builder.append('/')
                            'b' -> builder.append('\b')
                            'f' -> builder.append('\u000C')
                            'n' -> builder.append('\n')
                            'r' -> builder.append('\r')
                            't' -> builder.append('\t')
                            'u' -> {
                                if (index + 4 > source.length) {
                                    throw IllegalArgumentException("bad unicode escape")
                                }
                                val code = source.substring(index, index + 4).toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape")
                                builder.append(code.toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$escape'")
                        }
                    }
                    else -> builder.append(char)
                }
            }
        }

        private fun readNumber(): Number {
            val start = index
            if (peek() == '-') index++
            while (!isEnd && (source[index].isDigit() || source[index] in ".eE+-")) {
                index++
                if (index - start > MAX_NUMBER_LENGTH) {
                    throw IllegalArgumentException("number too long")
                }
            }
            val raw = source.substring(start, index)
            if (raw.isEmpty() || raw == "-") throw IllegalArgumentException("bad number")
            return if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
                raw.toDoubleOrNull() ?: throw IllegalArgumentException("bad number")
            } else {
                raw.toLongOrNull() ?: throw IllegalArgumentException("bad number")
            }
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!source.startsWith(literal, index)) {
                throw IllegalArgumentException("expected $literal")
            }
            index += literal.length
            return value
        }

        private fun expect(char: Char) {
            if (isEnd || source[index] != char) {
                throw IllegalArgumentException("expected '$char'")
            }
            index++
        }

        private fun peek(): Char {
            if (isEnd) throw IllegalArgumentException("unexpected end")
            return source[index]
        }
    }
}
