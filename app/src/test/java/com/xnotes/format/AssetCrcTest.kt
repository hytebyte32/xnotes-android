package com.xnotes.format

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.CRC32

class AssetCrcTest {

    private fun crc(b: ByteArray) = CRC32().also { it.update(b) }.value

    private fun tmp(bytes: ByteArray): File =
        Files.createTempFile("assetcrc", ".bin").toFile().also { it.writeBytes(bytes); it.deleteOnExit() }

    @Test fun matchesCrc32OfTheContents() {
        val data = ByteArray(200_000) { (it * 31).toByte() } // spans several read buffers
        assertEquals(crc(data), AssetCrc.of(tmp(data)))
    }

    @Test fun emptyFile() = assertEquals(0L, AssetCrc.of(tmp(ByteArray(0))))

    @Test fun repeatedCallsAgree() {
        val f = tmp("hello".toByteArray())
        assertEquals(AssetCrc.of(f), AssetCrc.of(f))
    }

    @Test fun aFileReplacedWithADifferentLengthIsNoticed() {
        val f = tmp("first".toByteArray())
        val a = AssetCrc.of(f)
        f.writeBytes("second, longer".toByteArray())
        val b = AssetCrc.of(f)
        assertNotEquals(a, b)
        assertEquals(crc("second, longer".toByteArray()), b)
    }
}
