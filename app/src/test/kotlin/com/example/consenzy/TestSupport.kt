package com.example.consenzy

import com.example.consenzy.demo.DemoBackend
import com.example.consenzy.demo.DemoFixtures
import com.example.consenzy.demo.DemoStore
import java.io.File

/** A controllable clock, so request status progression and session expiry can be tested without waiting. */
class FakeClock(var nowMs: Long = 1_790_000_000_000L) {
    fun now(): Long = nowMs

    fun advanceSeconds(seconds: Long) {
        nowMs += seconds * 1000
    }
}

object TestSupport {
    /** The same asset the app ships (`app/src/main/assets/demo-fixtures.json`). */
    fun fixtures(): DemoFixtures {
        val file = listOf("src/main/assets/demo-fixtures.json", "app/src/main/assets/demo-fixtures.json")
            .map(::File)
            .firstOrNull { it.isFile }
            ?: error("demo-fixtures.json not found from ${File(".").absolutePath}")
        return DemoFixtures.parse(file.readText())
    }

    fun demoBackend(clock: FakeClock = FakeClock()): DemoBackend = DemoBackend(DemoStore(fixtures(), clock::now))

    const val ADULT = "CUST-DEMO-0001"
    const val CHILD = "CUST-DEMO-CHILD-7"
}
