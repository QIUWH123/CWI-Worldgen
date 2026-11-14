package net.quiwh.cwiworldgen.terrain.reference.concurrent;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ThreadPools {
	public static final ExecutorService WORLD_GEN = Executors.newFixedThreadPool(Math.min(6, availableProcessors()), task -> {
        Thread thread = new Thread(task, "CWI Reference Terrain");
        thread.setDaemon(true);
        return thread;
    });
	
	public static int availableProcessors() {
		return Math.max(2, Runtime.getRuntime().availableProcessors());
	}
}