package dev.peytonli.tapdrop.core

import dev.peytonli.tapdrop.service.deleteCachedShare
import dev.peytonli.tapdrop.service.uniqueShareFile
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareCacheTests {
    @Test
    fun repeatedNamesUseDifferentDirectoriesAndKeepTheFilename() {
        val cacheDir = Files.createTempDirectory("tapdrop-share-cache").toFile()
        try {
            val first = uniqueShareFile(cacheDir, "release-gcm.txt")
            val second = uniqueShareFile(cacheDir, "release-gcm.txt")

            assertEquals("release-gcm.txt", first.name)
            assertEquals("release-gcm.txt", second.name)
            assertNotEquals(first.parentFile, second.parentFile)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun spacesAndUnicodeArePreserved() {
        val cacheDir = Files.createTempDirectory("tapdrop-share-cache").toFile()
        val displayName = "Résumé release – final.txt"
        try {
            assertEquals(displayName, uniqueShareFile(cacheDir, displayName).name)
        } finally {
            cacheDir.deleteRecursively()
        }
    }

    @Test
    fun deletingShareRemovesOnlyItsEmptyShareDirectory() {
        val cacheDir = Files.createTempDirectory("tapdrop-share-cache").toFile()
        try {
            val cachedShare = uniqueShareFile(cacheDir, "share.txt")
            cachedShare.writeText("cached")
            val shareDirectory = cachedShare.parentFile!!

            deleteCachedShare(cachedShare, cacheDir)

            assertFalse(cachedShare.exists())
            assertFalse(shareDirectory.exists())

            val ordinaryDirectory = File(cacheDir, "ordinary").apply { mkdir() }
            val ordinaryFile = File(ordinaryDirectory, "keep-parent.txt").apply { writeText("cached") }
            deleteCachedShare(ordinaryFile, cacheDir)
            assertTrue(ordinaryDirectory.isDirectory)
        } finally {
            cacheDir.deleteRecursively()
        }
    }
}
