package evolvia.core;

/**
 * Main loop: fixed-timestep simulation (see {@link Time}) decoupled from rendering,
 * which runs as fast as V-Sync allows and interpolates between the last two ticks.
 */
public final class GameLoop {

    /** Callbacks driven by the loop. */
    public interface Handler {
        /** Called once per rendered frame, before simulation ticks. For UI / non-simulation input. */
        void handleInput();

        /** Advances the simulation by exactly one fixed tick. Must not depend on frame time. */
        void update(long tick);

        /**
         * Renders the current state.
         *
         * @param alpha interpolation factor in [0, 1) between the previous and the current tick
         */
        void render(float alpha);
    }

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Window window;
    private final Input input;
    private final Time time;
    private final LoopStats stats;
    private final Handler handler;

    public GameLoop(Window window, Input input, Time time, LoopStats stats, Handler handler) {
        this.window = window;
        this.input = input;
        this.time = time;
        this.stats = stats;
        this.handler = handler;
    }

    /** Runs until the window is asked to close. */
    public void run() {
        long lastFrame = System.nanoTime();
        long statsWindowStart = lastFrame;
        int frames = 0;
        int ticks = 0;
        long tickNanos = 0;

        while (!window.shouldClose()) {
            window.pollEvents();

            long now = System.nanoTime();
            int ticksToRun = time.advance(now - lastFrame);
            lastFrame = now;

            handler.handleInput();

            for (int i = 0; i < ticksToRun; i++) {
                long tickStart = System.nanoTime();
                handler.update(time.nextTick());
                tickNanos += System.nanoTime() - tickStart;
                ticks++;
            }

            handler.render(time.alpha());
            window.swapBuffers();
            input.endFrame();
            frames++;

            long elapsed = System.nanoTime() - statsWindowStart;
            if (elapsed >= NANOS_PER_SECOND) {
                stats.fps = (int) Math.round(frames * (double) NANOS_PER_SECOND / elapsed);
                stats.tps = (int) Math.round(ticks * (double) NANOS_PER_SECOND / elapsed);
                stats.avgTickMillis = ticks > 0 ? tickNanos / 1e6 / ticks : 0;
                statsWindowStart += elapsed;
                frames = 0;
                ticks = 0;
                tickNanos = 0;
            }
        }
    }
}
