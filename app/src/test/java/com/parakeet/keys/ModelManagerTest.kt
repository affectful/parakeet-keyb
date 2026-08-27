package com.parakeet.keys

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelManagerTest {
    @Test
    fun modelDownloadSizesMatchVerifiedArtifacts() {
        assertEquals(670_478_772, ModelManager.totalBytes(ModelManager.Version.V3))
        assertEquals(661_190_513, ModelManager.totalBytes(ModelManager.Version.V2))
    }
}
