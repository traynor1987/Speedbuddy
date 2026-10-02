package uk.co.traynor.speedbuddy

import java.io.InputStream

object BoundedIo {
    fun bytes(input: InputStream, maximum: Int): ByteArray {
        val result = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(result.size() + count <= maximum) { "File exceeds its safe size limit" }
            result.write(buffer, 0, count)
        }
        return result.toByteArray()
    }
    fun text(input: InputStream, maximum: Int): String = bytes(input, maximum).toString(Charsets.UTF_8)
}
