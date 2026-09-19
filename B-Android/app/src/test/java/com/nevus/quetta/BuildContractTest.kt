package com.nevus.quetta

import com.nevus.quetta.security.ReleaseSecurity
import org.junit.Assert.assertThrows
import org.junit.Test

class BuildContractTest {
    @Test
    fun `release signing rejects missing secrets`() {
        assertThrows(IllegalStateException::class.java) {
            ReleaseSecurity.requireReleaseSigning(emptyMap())
        }
    }
}
