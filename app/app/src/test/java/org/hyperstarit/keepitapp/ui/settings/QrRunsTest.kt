package org.hyperstarit.keepitapp.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The QR code is drawn from the server's rows of modules, one rectangle per run of dark ones. These
 * pin the runs — the web's `qrPath` draws the same ones — so a code that scans on one scans on both.
 */
class QrRunsTest {

    @Test
    fun `each run of dark modules is one rectangle, row by row`() {
        assertEquals(
            listOf(QrRun(0, 1, 2), QrRun(1, 0, 1), QrRun(1, 3, 1)),
            qrRuns(listOf("0110", "1001")),
        )
    }

    @Test
    fun `a run reaches the end of its row`() {
        assertEquals(listOf(QrRun(0, 1, 3)), qrRuns(listOf("0111")))
    }

    @Test
    fun `light rows draw nothing`() {
        assertEquals(emptyList<QrRun>(), qrRuns(listOf("000", "000")))
    }
}
