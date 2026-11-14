package com.happ.proxyfinder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ExportPayloadValidationTest {
    @Test
    fun acceptsExactNonEmptyBytesWrittenToDocumentProvider() {
        val expected = """{"configs":[{"uri":"vless://example"}]}""".toByteArray(Charsets.UTF_8)
        assertEquals(expected.size, validateExportBytes(expected, expected.copyOf()))
    }

    @Test
    fun rejectsDocumentProviderReturningAnEmptyFile() {
        val expected = """{"configs":[{"uri":"vless://example"}]}""".toByteArray(Charsets.UTF_8)
        val error = assertThrows(IllegalStateException::class.java) {
            validateExportBytes(expected, ByteArray(0))
        }
        assertEquals("После сохранения файл оказался пустым (0 байт). Выбери папку «Загрузки» и повтори экспорт.", error.message)
    }

    @Test
    fun rejectsTruncatedOrChangedDocumentContent() {
        val expected = """{"configs":[{"uri":"vless://example"}]}""".toByteArray(Charsets.UTF_8)
        assertThrows(IllegalStateException::class.java) {
            validateExportBytes(expected, expected.copyOf(expected.size - 1))
        }
    }

    @Test
    fun rejectsEmptyPayloadBeforeWriting() {
        assertThrows(IllegalArgumentException::class.java) {
            validateExportBytes(ByteArray(0), ByteArray(0))
        }
    }
}
