package hacktrack.logic;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class ReminderScheduler {
    private static ScheduledExecutorService scheduler;
    private static final List<Consumer<String>> notificationListeners = new ArrayList<>();
    private static boolean running = false;

    public static void addNotificationListener(Consumer<String> listener) {
        synchronized (notificationListeners) {
            notificationListeners.add(listener);
        }
    }

    public static void removeNotificationListener(Consumer<String> listener) {
        synchronized (notificationListeners) {
            notificationListeners.remove(listener);
        }
    }

    public static void start() {
        if (running) return;
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ReminderScheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(() -> {
            try {
                int count = ReminderLogic.checkAndFireReminders();
                if (count > 0) {
                    String msg = count + " reminder(s) fired at " + java.time.LocalTime.now().withNano(0);
                    notifyListeners(msg);
                }
            } catch (Exception e) {
                notifyListeners("Reminder check error: " + e.getMessage());
            }
        }, 0, 1, TimeUnit.MINUTES);
        running = true;
        notifyListeners("Reminder scheduler started (checks every minute)");
    }

    public static void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
        running = false;
        notifyListeners("Reminder scheduler stopped");
    }

    public static boolean isRunning() {
        return running;
    }

    private static void notifyListeners(String message) {
        synchronized (notificationListeners) {
            for (Consumer<String> listener : notificationListeners) {
                try {
                    listener.accept(message);
                } catch (Exception e) {
                    // swallow listener errors
                }
            }
        }
    }
}
