package evolvia.launcher;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;

/**
 * Launcher window (Swing, part of the JDK). All launcher work runs on a background thread;
 * Swing components are only touched on the event dispatch thread via {@link #ui(Runnable)}.
 */
public final class LauncherWindow {

    private final LauncherCore core;
    private final JFrame frame = new JFrame("Evolvia Launcher");
    private final JLabel status = new JLabel(" ");
    private final JLabel detail = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar(0, 1000);
    private final JButton play = new JButton("Hrát");

    // Written by the worker thread, read when the button is clicked (worker is idle then).
    private volatile String installed;
    private volatile Release.Asset pendingAsset;
    private volatile String pendingTag;

    private LauncherWindow(LauncherCore core) {
        this.core = core;
    }

    /** Opens the window and starts the update check. */
    public static void open(LauncherCore core) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
            // default look and feel is fine
        }
        SwingUtilities.invokeLater(() -> new LauncherWindow(core).show());
    }

    private void show() {
        JLabel title = new JLabel("Evolvia");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 28f));
        detail.setFont(detail.getFont().deriveFont(Font.PLAIN, 11f));
        progress.setStringPainted(false);
        progress.setVisible(false);
        play.setEnabled(false);
        play.addActionListener(e -> onPlay());
        play.setFont(play.getFont().deriveFont(Font.BOLD, 14f));

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        for (Component c : new Component[]{title, status, detail, progress, play}) {
            ((JComponent) c).setAlignmentX(Component.LEFT_ALIGNMENT);
        }
        progress.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
        panel.add(title);
        panel.add(Box.createVerticalStrut(10));
        panel.add(status);
        panel.add(Box.createVerticalStrut(4));
        panel.add(detail);
        panel.add(Box.createVerticalStrut(10));
        panel.add(progress);
        panel.add(Box.createVerticalStrut(12));
        panel.add(play);

        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(panel);
        frame.getRootPane().setDefaultButton(play); // Enter = play
        frame.setSize(460, 250);
        frame.setResizable(false);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        runInBackground(this::checkForUpdates);
    }

    // ---------------------------------------------------------------- worker thread

    private void checkForUpdates() {
        installed = core.installedVersion();
        pendingAsset = null;
        pendingTag = null;
        ui(() -> {
            status.setText("Kontroluji aktualizace…");
            detail.setText("Data: " + core.dataRoot());
            play.setEnabled(false);
        });

        Release latest;
        try {
            latest = core.latest();
        } catch (IOException e) {
            offline(e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }

        Optional<Release.Asset> asset = LauncherCore.assetFor(latest, core.platform());
        if (!LauncherCore.isNewer(latest, installed)) {
            ready("Nainstalovaná verze " + installed + " je aktuální.", "Hrát");
        } else if (asset.isEmpty()) {
            if (installed != null) {
                ready("Verze " + latest.tag() + " nemá balíček pro " + core.platform().key()
                        + ", spustí se " + installed + ".", "Hrát");
            } else {
                failed("Verze " + latest.tag() + " nemá balíček pro " + core.platform().key() + ".");
            }
        } else {
            pendingAsset = asset.get();
            pendingTag = latest.tag();
            ready(installed == null
                            ? "K dispozici je verze " + latest.tag() + " (" + megabytes(asset.get().size()) + ")."
                            : "Nová verze " + latest.tag() + " (nainstalováno " + installed + ").",
                    installed == null ? "Stáhnout a hrát" : "Aktualizovat a hrát");
        }
    }

    private void offline(String reason) {
        if (installed != null) {
            ready("Offline: spustí se nainstalovaná verze " + installed + ".", "Hrát");
            ui(() -> detail.setText(shorten(reason)));
        } else {
            failed("Nelze zjistit aktualizace a žádná verze není nainstalována. " + shorten(reason));
        }
    }

    private void onPlay() {
        play.setEnabled(false);
        if (pendingAsset == null && installed == null) {
            runInBackground(this::checkForUpdates); // button acts as "retry" after a failure
            return;
        }
        runInBackground(this::installAndLaunch);
    }

    private void installAndLaunch() {
        try {
            if (pendingAsset != null) {
                Release.Asset asset = pendingAsset;
                String tag = pendingTag;
                ui(() -> {
                    status.setText("Stahuji " + tag + "…");
                    progress.setValue(0);
                    progress.setIndeterminate(false);
                    progress.setVisible(true);
                });
                Path archive = core.download(asset, (done, total) -> ui(() -> {
                    if (total > 0) {
                        progress.setValue((int) (done * 1000 / total));
                        detail.setText(megabytes(done) + " / " + megabytes(total));
                    } else {
                        progress.setIndeterminate(true);
                        detail.setText(megabytes(done));
                    }
                }));
                ui(() -> {
                    status.setText("Instaluji " + tag + "…");
                    progress.setIndeterminate(true);
                });
                try {
                    core.install(archive, tag);
                } finally {
                    Files.deleteIfExists(archive);
                }
                installed = tag;
                pendingAsset = null;
                pendingTag = null;
            }

            String version = installed;
            ui(() -> {
                status.setText("Spouštím " + version + "…");
                progress.setIndeterminate(true);
                progress.setVisible(true);
            });
            Process game = core.launch(version);
            Thread.sleep(LauncherCore.LAUNCH_GRACE.toMillis());
            if (!game.isAlive() && game.exitValue() != 0) {
                failed("Hra skončila s kódem " + game.exitValue() + ". Log: " + core.gameLog());
                return;
            }
            System.exit(0);
        } catch (IOException e) {
            failed("Chyba: " + shorten(e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- UI helpers

    private void ready(String message, String buttonText) {
        ui(() -> {
            status.setText(html(message));
            progress.setVisible(false);
            play.setText(buttonText);
            play.setEnabled(true);
            play.requestFocusInWindow();
        });
    }

    private void failed(String message) {
        ui(() -> {
            status.setText(html(message));
            progress.setVisible(false);
            // Pending update or nothing installed: the button retries; otherwise it plays the installed version.
            boolean retry = pendingAsset != null || installed == null;
            play.setText(retry ? "Zkusit znovu" : "Hrát");
            play.setEnabled(true);
            play.requestFocusInWindow();
        });
    }

    private static void ui(Runnable action) {
        SwingUtilities.invokeLater(action);
    }

    private static void runInBackground(Runnable task) {
        Thread worker = new Thread(task, "launcher-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private static String shorten(String text) {
        if (text == null) {
            return "";
        }
        return text.length() > 160 ? text.substring(0, 157) + "…" : text;
    }

    /** Wrapping label text. Swing scales CSS px (72 dpi based), so 290px fills the ~400 px wide window. */
    private static String html(String text) {
        return "<html><body style='width: 290px'>" + escape(text) + "</body></html>";
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
