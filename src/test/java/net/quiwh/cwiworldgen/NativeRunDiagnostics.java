package net.quiwh.cwiworldgen;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.TestFunction;
import net.neoforged.neoforge.gametest.GameTestHolder;

/** Opt-in diagnostics for this verification JVM only; adds no tests or production hooks. */
@GameTestHolder(CwiWorldgen.ID)
public final class NativeRunDiagnostics {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static final long INTERVAL_MILLIS = 60_000L;

    @GameTestGenerator
    public static List<TestFunction> startOptionalDiagnostics() {
        if (Boolean.getBoolean("cwi.worldgen.verifyDiagnostics") && STARTED.compareAndSet(false, true)) {
            long pid = ProcessHandle.current().pid();
            System.out.println("CWI_VERIFY_DIAGNOSTICS_START=pid:" + pid + ",interval_seconds:60,time:" + Instant.now());
            Thread reporter = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(INTERVAL_MILLIS);
                        reportOwnThreads(pid);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }, "CWI verification diagnostics");
            reporter.setDaemon(true);
            reporter.start();
        }
        return List.of();
    }

    private static void reportOwnThreads(long pid) {
        // Thread.getAllStackTraces observes only the current JVM. No process attachment,
        // subprocess, filesystem inspection, server task or world mutation is involved.
        Map<Thread, StackTraceElement[]> snapshot = Thread.getAllStackTraces();
        StringBuilder report = new StringBuilder("CWI_VERIFY_DIAGNOSTICS_SNAPSHOT_BEGIN=pid:")
                .append(pid).append(",time:").append(Instant.now()).append('\n');
        Runtime runtime = Runtime.getRuntime();
        report.append("CWI_VERIFY_HEAP=used_bytes:").append(runtime.totalMemory() - runtime.freeMemory())
                .append(",committed_bytes:").append(runtime.totalMemory())
                .append(",max_bytes:").append(runtime.maxMemory()).append('\n');
        ManagementFactory.getGarbageCollectorMXBeans().forEach(collector -> report
                .append("CWI_VERIFY_GC=name:").append(collector.getName())
                .append(",collections:").append(collector.getCollectionCount())
                .append(",cumulative_millis:").append(collector.getCollectionTime()).append('\n'));
        int[] count = {0};
        snapshot.entrySet().stream().filter(entry -> relevant(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
                .forEach(entry -> {
                    Thread thread = entry.getKey();
                    report.append("CWI_VERIFY_THREAD=name:").append(thread.getName())
                            .append(",id:").append(thread.threadId()).append(",state:")
                            .append(thread.getState()).append('\n');
                    for (StackTraceElement frame : entry.getValue()) report.append("    at ").append(frame).append('\n');
                    count[0]++;
                });
        report.append("CWI_VERIFY_DIAGNOSTICS_SNAPSHOT_END=pid:").append(pid)
                .append(",threads:").append(count[0]);
        System.out.println(report);
    }

    private static boolean relevant(Thread thread, StackTraceElement[] stack) {
        if (thread == Thread.currentThread()) return false;
        String name = thread.getName();
        if (name.equals("Server thread") || name.equals("CWI Reference Terrain")
                || name.startsWith("Worker-") || name.startsWith("ForkJoinPool-")) return true;
        for (StackTraceElement frame : stack) {
            String type = frame.getClassName();
            if (type.startsWith("net.minecraft.server.MinecraftServer")
                    || type.startsWith("net.minecraft.world.level.levelgen.")
                    || type.startsWith("net.quiwh.cwiworldgen.terrain.")
                    || type.startsWith("net.quiwh.cwiworldgen.hydrology.")) return true;
        }
        return false;
    }

    private NativeRunDiagnostics() {}
}
