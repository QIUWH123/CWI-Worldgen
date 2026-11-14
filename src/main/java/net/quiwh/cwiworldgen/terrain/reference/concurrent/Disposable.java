package net.quiwh.cwiworldgen.terrain.reference.concurrent;

public interface Disposable {
    void dispose();
    
    public interface Listener<T> {
        void onDispose(T ctx);
    }
}
