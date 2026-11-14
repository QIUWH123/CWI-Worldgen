package net.quiwh.cwiworldgen.hydrology.current;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.UUID;
/** Bounded work queue; only motion tasks own deduplication markers. */
public final class CurrentWorkQueue {
    public record Task(UUID player,long chunk,long revision) {}
    private final int limit;
    private final ArrayDeque<Task> tasks=new ArrayDeque<>();
    private final HashSet<Long> queued=new HashSet<>();
    public CurrentWorkQueue(int limit) { this.limit=limit; }
    public boolean request(long chunk) { if (!queued.add(chunk)) return false; add(new Task(null,chunk,0)); return true; }
    public void watch(UUID player,long chunk,long revision) { add(new Task(player,chunk,revision)); }
    private void add(Task task) { tasks.addLast(task); while(tasks.size()>limit) release(tasks.removeFirst()); }
    public Task poll() { var t=tasks.pollFirst(); if(t!=null) release(t); return t; }
    private void release(Task task) { if(task.player()==null) queued.remove(task.chunk()); }
    public boolean queued(long chunk) { return queued.contains(chunk); }
    public int size() { return tasks.size(); }
    public int motionCount() { return queued.size(); }
    public void clear() { tasks.clear(); queued.clear(); }
    public void playerLeft(UUID player) { tasks.removeIf(t->player.equals(t.player())); }
}
