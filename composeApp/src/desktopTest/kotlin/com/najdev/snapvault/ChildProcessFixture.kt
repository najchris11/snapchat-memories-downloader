package com.najdev.snapvault

import kotlin.system.exitProcess

/**
 * A child process with the behaviours [ProcessUtilTest] needs, launched as a real second JVM.
 *
 * A shell script would be shorter and would not run on Windows, which SnapVault ships to. This
 * costs a JVM start per test and works everywhere the build does.
 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        // Holds its stdout pipe open and never exits: a read on that pipe blocks forever, and
        // a blocking read on a process pipe does not answer to Thread.interrupt() (D06).
        "sleep" -> Thread.sleep(Long.MAX_VALUE)

        // Refuses to die on the gentle signal. destroy() sends SIGTERM, which runs shutdown
        // hooks; this one never returns, so only destroyForcibly() can end the process.
        "ignore-term" -> {
            Runtime.getRuntime().addShutdownHook(Thread { Thread.sleep(Long.MAX_VALUE) })
            println(READY)
            System.out.flush()
            Thread.sleep(Long.MAX_VALUE)
        }

        // Fills both pipe buffers before exiting. A parent that waits before draining
        // deadlocks here: the child blocks on a full pipe, the parent blocks on waitFor.
        "flood" -> {
            val block = "x".repeat(64 * 1024)
            repeat(16) { System.err.print(block) }
            repeat(16) { print(block) }
            System.err.println(STDERR_END)
            println(STDOUT_END)
        }

        "echo" -> {
            println(args[1])
            exitProcess(args[2].toInt())
        }
    }
}

const val READY = "FIXTURE-READY"
const val STDOUT_END = "STDOUT-END"
const val STDERR_END = "STDERR-END"
