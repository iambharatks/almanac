import java.util.concurrent.atomic.AtomicReference;



public class ConfigManager {
    // 2. Wrap the immutable object in an AtomicReference
    private final AtomicReference<ServerConfig> currentConfig =
            new AtomicReference<>(new ServerConfig("PROD", 100));

    // Fast, lock-free read
    public ServerConfig getConfig() {
        return currentConfig.get();
    }

    // Thread-safe, lock-free update using CAS
    public void updateMaxConnections(int newMax) {
        ServerConfig oldConfig;
        ServerConfig newConfig;

        // 3. The CAS Spin Loop
        do {
            // Read the current state
            oldConfig = currentConfig.get();

            // Create a brand new configuration object based on the old one
            newConfig = new ServerConfig(oldConfig.getEnvironment(), newMax);

            // compareAndSet() checks: Is the reference currently held STILL 'oldConfig'?
            // If YES: Update to 'newConfig' and return true (loop breaks).
            // If NO (another thread changed it): Return false (loop repeats).
        } while (!currentConfig.compareAndSet(oldConfig, newConfig));

        System.out.println(Thread.currentThread().getName() + " successfully updated to: " + newConfig);
    }

    public static void main(String[] args) {
        ConfigManager manager = new ConfigManager();

        // Simulate multiple threads trying to update the config simultaneously
        Runnable updateTask = () -> {
            int randomConnections = (int) (Math.random() * 500) + 100;
            manager.updateMaxConnections(randomConnections);
        };

        Thread t1 = new Thread(updateTask, "Thread-1");
        Thread t2 = new Thread(updateTask, "Thread-2");
        Thread t3 = new Thread(updateTask, "Thread-3");

        t1.start();
        t2.start();
        t3.start();
    }
}
// 1. The object being referenced MUST be immutable.
// If it isn't, threads could modify its internal state without the AtomicReference knowing.
class ServerConfig {
    private final String environment;
    private final int maxConnections;

    public ServerConfig(String environment, int maxConnections) {
        this.environment = environment;
        this.maxConnections = maxConnections;
    }

    public String getEnvironment() { return environment; }
    public int getMaxConnections() { return maxConnections; }

    @Override
    public String toString() {
        return "Config[env=" + environment + ", maxConn=" + maxConnections + "]";
    }
}