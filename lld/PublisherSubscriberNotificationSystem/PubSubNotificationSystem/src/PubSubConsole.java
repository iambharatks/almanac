import Entities.*;
import Entities.Subscriber.ISubscriber;
import Entities.Subscriber.SimpleSubscriber;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Two modes:
 *   1. Menu   -- type commands, watch subscribers react in real time.
 *   2. Demo   -- scripted concurrent publishers. Shows the properties a menu can't:
 *                fan-out, slow-consumer isolation, per-subscription ordering.
 *
 * Adjust method names to match your API. Assumed:
 *   broker.createTopic(name) -> Optional<Topic>
 *   broker.publish(publisher, topicName, message) -> boolean
 *   broker.subscribe(topicName, ISubscriber) -> boolean
 *   broker.unsubscribe(topicName, ISubscriber) -> boolean
 *   broker.shutdown()
 *   new SimpleSubscriber(MessageHandler)
 *   new Message(String payload)
 */
public class PubSubConsole {

    private static final Scanner in = new Scanner(System.in);
    private static final Broker broker = new Broker();

    // named subscribers so the menu can refer to them
    private static final Map<String, ISubscriber> subscribers = new LinkedHashMap<>();

    public static void main(String[] args) {
        System.out.println("""
                pub-sub console
                  demo                     run the scripted concurrency demo
                  menu                     interactive mode
                """);
        String mode = prompt("mode: ");
        if (mode.equalsIgnoreCase("demo")) runDemo();
        else menuLoop();
    }

    /* ================= interactive ================= */

    private static void menuLoop() {
        printHelp();
        while (true) {
            String[] p = prompt("> ").split("\\s+", 4);      // cap at 4 so payloads keep spaces
            try {
                switch (p[0].toLowerCase()) {
                    case "topic"  -> cmdTopic(p);
                    case "sub"    -> cmdSub(p);
                    case "unsub"  -> cmdUnsub(p);
                    case "pub"    -> cmdPub(p);
                    case "list"   -> cmdList();
                    case "help"   -> printHelp();
                    case "quit"   -> { broker.shutdown(); System.out.println("bye"); return; }
                    case ""       -> { }
                    default       -> System.out.println("unknown command -- try 'help'");
                }
            } catch (ArrayIndexOutOfBoundsException e) {
                System.out.println("missing argument -- try 'help'");
            } catch (RuntimeException e) {
                System.out.println("error: " + e.getMessage());
            }
        }
    }

    private static void cmdTopic(String[] p) {
        broker.createTopic(p[1]).ifPresentOrElse(
                t -> System.out.println("created topic " + p[1]),
                () -> System.out.println("topic already exists"));
    }

    /** sub <topic> <name> [slowMillis] */
    private static void cmdSub(String[] p) {
        String topic = p[1], name = p[2];
        long slow = (p.length > 3) ? Long.parseLong(p[3]) : 0;
        ISubscriber s = subscribers.computeIfAbsent(name,
                k -> new SimpleSubscriber(name , m -> {
                    if (slow > 0) sleep(slow);                 // simulate a slow handler
                    System.out.printf("    [%s] <- %s%n", name, m);
                }));

        System.out.println(broker.subscribe(topic, s)
                ? name + " subscribed to " + topic + (slow > 0 ? " (slow: " + slow + "ms)" : "")
                : "subscribe failed");
    }

    private static void cmdUnsub(String[] p) {
        ISubscriber s = subscribers.get(p[2]);
        if (s == null) { System.out.println("no such subscriber"); return; }
        System.out.println(broker.unsubscribe(p[1], s) ? "unsubscribed" : "unsubscribe failed");
    }

    /** pub <topic> <payload...> */
    private static void cmdPub(String[] p) {
        String payload = String.join(" ", Arrays.copyOfRange(p, 2, p.length));
        System.out.println(broker.publish(null, p[1], new Message(UUID.randomUUID().toString(), payload))
                ? "published" : "publish failed");
    }

    private static void cmdList() {
        System.out.println("subscribers: " + subscribers.keySet());
    }

    private static void printHelp() {
        System.out.println("""
                
                  topic <name>                      create a topic
                  sub   <topic> <name> [slowMs]     subscribe (slowMs simulates a slow handler)
                  unsub <topic> <name>              stop that subscription
                  pub   <topic> <payload...>        publish a message
                  list                              show known subscribers
                  quit                              shut down cleanly
                """);
    }

    /* ================= scripted demo ================= */

    private static void runDemo() {
        final int PUBLISHERS = 2, PER_PUBLISHER = 8;
        broker.createTopic("orders");

        AtomicInteger fastCount  = new AtomicInteger();
        AtomicInteger slowCount  = new AtomicInteger();
        AtomicInteger auditCount = new AtomicInteger();

        ISubscriber fast  = new SimpleSubscriber("fast", m -> {
            System.out.printf("  [fast ] %s%n", m); fastCount.incrementAndGet(); });
        ISubscriber slow  = new SimpleSubscriber("slow",m -> {
            sleep(200); System.out.printf("  [slow ] %s%n", m); slowCount.incrementAndGet(); });
        ISubscriber audit = new SimpleSubscriber("audit",m -> {
            System.out.printf("  [audit] %s%n", m); auditCount.incrementAndGet(); });

        broker.subscribe("orders", fast);
        broker.subscribe("orders", slow);
        broker.subscribe("orders", audit);

        System.out.println("\n--- 2 publishers x 8 messages, 3 subscribers (one slow) ---\n");

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(PUBLISHERS);

        for (int p = 0; p < PUBLISHERS; p++) {
            final int id = p;
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    for (int i = 0; i < PER_PUBLISHER; i++) {
                        broker.publish(null, "orders", new Message(UUID.randomUUID().toString(),"P" + id + "-msg" + i));
                        sleep(30);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally { done.countDown(); }
            });
            t.start();
        }

        try {
            startGate.countDown();
            done.await();

            sleep(500);                                  // let fast subscribers drain
            System.out.println("\n--- unsubscribing 'audit' mid-stream ---\n");
            broker.unsubscribe("orders", audit);

            for (int i = 0; i < 3; i++) {
                broker.publish(null, "orders", new Message(UUID.randomUUID().toString(), "after-unsub-" + i));
                sleep(50);
            }

            sleep(3000);                                 // let the slow subscriber catch up
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        int total = PUBLISHERS * PER_PUBLISHER + 3;
        System.out.printf("""
                
                --- results ---
                published : %d
                fast      : %d   (expect %d)
                slow      : %d   (expect %d)
                audit     : %d   (expect %d -- stopped early)
                """, total, fastCount.get(), total, slowCount.get(), total,
                auditCount.get(), PUBLISHERS * PER_PUBLISHER);

        broker.shutdown();
        System.out.println("shutdown complete -- JVM should exit now");
    }

    /* ================= helpers ================= */

    private static String prompt(String label) {
        System.out.print(label);
        return in.nextLine().trim();
    }

    private static void sleep(long millis) {
        try { Thread.sleep(millis); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}