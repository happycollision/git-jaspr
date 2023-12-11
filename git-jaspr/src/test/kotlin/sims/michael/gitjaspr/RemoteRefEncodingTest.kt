package sims.michael.gitjaspr

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import sims.michael.gitjaspr.RemoteRefEncoding.RemoteRefParts
import sims.michael.gitjaspr.RemoteRefEncoding.getRemoteRefParts

class RemoteRefEncodingTest {
    @Test
    fun `getRemoteRefParts - no revision number`() {
        assertEquals(
            RemoteRefParts("main", "12345", null),
            getRemoteRefParts("jaspr/main/12345", "jaspr"),
        )
    }

    @Test
    fun `getRemoteRefParts - with revision number`() {
        assertEquals(
            RemoteRefParts("main", "12345", 1),
            getRemoteRefParts("jaspr/main/12345_01", "jaspr"),
        )
    }
}
