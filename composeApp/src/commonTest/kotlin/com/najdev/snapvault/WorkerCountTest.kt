package com.najdev.snapvault

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerCountTest {

    // D13: workers scaled with 75% of the CPUs and nothing else. Each worker can hold a fully
    // decoded image during combination, so on a 64-core workstation that was 48 full-resolution
    // decodes at once — memory, not CPU, was the budget that ran out, and the machine went
    // unresponsive before the run did.
    @Test
    fun workersAreCappedHoweverManyCpusThereAre() {
        assertEquals(MAX_WORKERS, workerCountFor(64))
        assertEquals(MAX_WORKERS, workerCountFor(256))
        assertTrue(MAX_WORKERS <= 8, "the cap is a memory budget; raising it needs a reason")
    }

    @Test
    fun smallMachinesStillScaleWithTheirCpus() {
        assertEquals(1, workerCountFor(1))
        assertEquals(1, workerCountFor(2))
        assertEquals(3, workerCountFor(4))
        assertEquals(6, workerCountFor(8))
    }
}
