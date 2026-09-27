package evolvia.ui;

import java.util.ArrayDeque;
import java.util.Deque;

/** Short messages below the top bar ("Uloženo", errors) that fade after a few seconds (real time). */
public final class Notifications {

    private static final long SHOW_NANOS = 4_000_000_000L;
    private static final int MAX = 4;

    private record Note(String text, boolean error, long time) {
    }

    private final Deque<Note> notes = new ArrayDeque<>();

    public synchronized void info(String text) {
        add(new Note(text, false, System.nanoTime()));
    }

    /** Thread-safe: background save threads report here too. */
    public synchronized void error(String text) {
        add(new Note(text, true, System.nanoTime()));
    }

    private void add(Note note) {
        if (notes.size() == MAX) {
            notes.removeFirst();
        }
        notes.addLast(note);
    }

    public synchronized void build(Ui ui, float top) {
        long now = System.nanoTime();
        notes.removeIf(n -> now - n.time() > SHOW_NANOS);
        float y = top + 10f;
        for (Note note : notes) {
            float w = ui.bold.width(note.text()) + 24f;
            float x = (ui.width() - w) / 2f;
            float h = ui.bold.lineHeight() + 10f;
            ui.draw().rect(x, y, w, h, 0xE8181B20);
            ui.draw().outline(x, y, w, h, 1f, note.error() ? 0xFFC0473A : 0xFF5FAF6A);
            ui.text(ui.bold, note.text(), x + 12f, y + 5f, note.error() ? 0xFFE08A7A : Ui.TEXT);
            y += h + 6f;
        }
    }
}
