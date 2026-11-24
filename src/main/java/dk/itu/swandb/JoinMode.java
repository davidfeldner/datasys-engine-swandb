package dk.itu.swandb;

import java.util.Locale;

/**
 * Which join order the planner chooses, the {@code -j} flag of Exercise 11.
 *
 * <ul>
 *   <li>{@link #OPTIMIZED} orders joins by the smallest estimated
 *       intermediate result, the default.</li>
 *   <li>{@link #SYNTAX} folds the {@code FROM} list left to right, exactly
 *       the Exercise 10 plan. It is how week 12 forces a specific order: put
 *       the tables in the wanted order and run this mode.</li>
 * </ul>
 *
 * <p>The choice never changes the rows a query returns, only the plan that
 * produces them and the order the rows come out in; both modes emit columns
 * in {@code FROM}-list order.
 */
public enum JoinMode {

    OPTIMIZED("optimized"),
    SYNTAX("syntax");

    private final String spelling;

    JoinMode(String spelling) {
        this.spelling = spelling;
    }

    /** The value the flag and the log line use. */
    public String spelling() {
        return spelling;
    }

    /**
     * Parses the {@code -j} value. Unknown values are rejected with the two
     * accepted ones, because a typo that silently planned a different order
     * would invalidate a benchmark run.
     *
     * @throws IllegalArgumentException if {@code text} is not a mode name
     */
    public static JoinMode parse(String text) {
        String normalized = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        for (JoinMode mode : values()) {
            if (mode.spelling.equals(normalized)) return mode;
        }
        throw new IllegalArgumentException(
                "unknown join mode: " + text + "; expected optimized or syntax");
    }
}
