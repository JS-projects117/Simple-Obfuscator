import java.util.Set;

/** Generates short, uninformative identifiers (a, b, ..., z, aa, ab, ...) skipping reserved names. */
public final class NameGen {
    private final Set<String> reserved;
    private final String prefix;
    private int counter = 0;

    public NameGen(Set<String> reserved, String prefix) {
        this.reserved = reserved;
        this.prefix = prefix == null ? "" : prefix;
    }

    public String next() {
        while (true) {
            String name = prefix + encode(counter++);
            if (reserved.contains(name) || Token.KEYWORDS.contains(name) || Token.CONTEXTUAL_KEYWORDS.contains(name)) continue;
            reserved.add(name);
            return name;
        }
    }

    /** Bijective base-26: 0 -> a, 25 -> z, 26 -> aa, ... */
    static String encode(int n) {
        StringBuilder sb = new StringBuilder();
        n++;
        while (n > 0) {
            n--;
            sb.append((char) ('a' + (n % 26)));
            n /= 26;
        }
        return sb.reverse().toString();
    }
}
