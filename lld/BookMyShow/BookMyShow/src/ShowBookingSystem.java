import Entities.*;
import Entities.Seat.Seat;
import PaymentStrategy.*;
import PriceCalculationStrategy.*;

import java.time.LocalDateTime;
import java.util.*;

public class ShowBookingSystem {

    private static final Scanner in = new Scanner(System.in);

    // showId -> a human-readable label, so the menu can show something meaningful
    private static final Map<String, String> showLabels = new LinkedHashMap<>();

    public static void main(String[] args) {
        Venue venue = buildVenue();
        String userId = promptUserId();

        while (true) {
            System.out.println("""
                    
                    ========= PVR Agra =========
                    1. List shows
                    2. View available seats
                    3. Book seats
                    4. Exit
                    """);
            switch (prompt("Choice: ")) {
                case "1" -> listShows();
                case "2" -> viewSeats(venue);
                case "3" -> bookSeats(venue, userId);
                case "4" -> { System.out.println("Bye."); return; }
                default  -> System.out.println("Invalid choice.");
            }
        }
    }

    /* ---------------- setup ---------------- */

    private static Venue buildVenue() {
        PriceCalculationStrategy pricing = new ShowBasedPriceStrategy(200, 1.50, 2.0);
        PaymentStrategy payment = new MockPaymentStrategy(0.3, 800);   // 30% decline, 800ms latency

        Random random = new Random();
        List<Theatre> theatres = new ArrayList<>();
        for (int t = 0; t < 3; t++) {
            int capacity = random.nextInt(41) + 10;                     // 10..50
            List<Seat> seats = new ArrayList<>();
            for (int s = 0; s < capacity; s++) {
                seats.add(new Seat("S-" + t + "-" + s));
            }
            theatres.add(new Theatre("T-" + t, seats));
        }

        Venue venue = new Venue("PVR", "Agra", theatres, pricing, payment);

        List<String> movies = List.of("SpiderMan", "Odyssey", "Troy", "Good Will Hunting");

        // NON-OVERLAPPING slots: show i in theatre t starts at hour (i*3), runs 2h.
        // Each theatre gets its own showId per movie -- IDs must be unique across the venue.
        for (int t = 0; t < theatres.size(); t++) {
            String theatreId = theatres.get(t).getTheatreId();
            for (int m = 0; m < movies.size(); m++) {
                LocalDateTime start = LocalDateTime.of(2026, 8, 1, 9 + (m * 3), 0);
                TimeSlot slot = new TimeSlot(start, start.plusHours(2));

                Show show = venue.addShow(theatreId,movies.get(m), slot).get();
                System.out.println("Added following show "+show.getShowName()+ " at theater "+theatreId);
                showLabels.put(show.getShowId(), "%-18s %s  %s".formatted(
                        movies.get(m), theatreId, start.toLocalTime()));
            }
        }
        return venue;
    }

    /* ---------------- menu actions ---------------- */

    private static void listShows() {
        System.out.println("\n--- Shows ---");
        int i = 1;
        for (var e : showLabels.entrySet()) {
            System.out.printf("%2d. %s%n", i++, e.getValue());
        }
    }

    private static void viewSeats(Venue venue) {
        String showId = selectShow();
        if (showId == null) return;

        venue.getSeatsForShow(showId).ifPresentOrElse(
                seats -> {
                    System.out.println("\nAvailable (" + seats.size() + "):");
                    printInColumns(seats);
                },
                () -> System.out.println("No such show."));
    }

    private static void bookSeats(Venue venue, String userId) {
        String showId = selectShow();
        if (showId == null) return;

        Optional<List<String>> available = venue.getSeatsForShow(showId);
        if (available.isEmpty() || available.get().isEmpty()) {
            System.out.println("Nothing available.");
            return;
        }
        System.out.println("\nAvailable:");
        printInColumns(available.get());

        String raw = prompt("Seats (comma separated, e.g. S-0-1,S-0-2): ");
        List<String> requested = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        if (requested.isEmpty()) {
            System.out.println("No seats entered.");
            return;
        }

        System.out.println("Starting booking...");
        Optional<Ticket> ticket = venue.bookSeats(showId, requested, userId,System.currentTimeMillis());

        ticket.ifPresentOrElse(
                tk -> System.out.println("BOOKED. Ticket " + tk.ticketId() + " -> " + requested),
                () -> System.out.println("FAILED. Seats unavailable, or payment declined."));
    }

    /* ---------------- helpers ---------------- */

    private static String selectShow() {
        listShows();
        int n = readInt("Show number: ");
        List<String> ids = new ArrayList<>(showLabels.keySet());
        if (n < 1 || n > ids.size()) {
            System.out.println("Out of range.");
            return null;
        }
        return ids.get(n - 1);
    }

    private static void printInColumns(List<String> items) {
        for (int i = 0; i < items.size(); i++) {
            System.out.printf("%-12s", items.get(i));
            if ((i + 1) % 6 == 0) System.out.println();
        }
        System.out.println();
    }

    private static String promptUserId() {
        String id = prompt("User id: ");
        return id.isBlank() ? "guest-" + UUID.randomUUID().toString().substring(0, 6) : id;
    }

    private static String prompt(String label) {
        System.out.print(label);
        return in.nextLine().trim();
    }

    private static int readInt(String label) {
        while (true) {
            try { return Integer.parseInt(prompt(label)); }
            catch (NumberFormatException e) { System.out.println("Enter a number."); }
        }
    }
}