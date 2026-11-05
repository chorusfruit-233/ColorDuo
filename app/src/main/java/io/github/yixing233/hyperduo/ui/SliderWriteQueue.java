package io.github.yixing233.hyperduo.ui;

import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Main-thread bounded-rate writes; flush when an editor leaves composition. */
public final class SliderWriteQueue {
    private final Consumer<Runnable> schedule, cancel;
    private final IntConsumer write;
    private Integer pending;
    private boolean scheduled;
    private final Runnable tick = this::flush;
    public SliderWriteQueue(Consumer<Runnable> schedule, Consumer<Runnable> cancel, IntConsumer write) {
        this.schedule=schedule; this.cancel=cancel; this.write=write;
    }
    public void submit(int value) {
        pending=value;
        if(!scheduled) { scheduled=true; schedule.accept(tick); }
    }
    public boolean hasPending() { return pending!=null; }
    public void flush() {
        cancel.accept(tick); scheduled=false;
        Integer value=pending; pending=null;
        if(value!=null) write.accept(value);
    }
}
