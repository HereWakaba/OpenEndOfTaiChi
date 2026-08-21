import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AnalyzeWinners - compact stats for probe experiment logs.
 *
 * Usage:
 *   java AnalyzeWinners <file> winner     (lines: "name timestamp" - one winner per run)
 *   java AnalyzeWinners <file> agent      (agent log: "run start" blocks, "[prepare] ... module=X" lines)
 */
public class AnalyzeWinners {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Usage: java AnalyzeWinners <file> (winner|agent)");
            return;
        }
        List<String> lines = Files.readAllLines(Path.of(args[0]));
        if ("winner".equals(args[1])) {
            analyzeWinner(lines);
        } else {
            analyzeAgent(lines);
        }
    }

    static void analyzeWinner(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List<String> seq = new ArrayList<>();
        for (String l : lines) {
            String name = l.split(" ")[0];
            if (name.isEmpty() || name.startsWith("[")) continue;
            counts.merge(name, 1, Integer::sum);
            seq.add(name);
        }
        int total = seq.size();
        System.out.println("== winner log: " + total + " runs ==");
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            System.out.printf("  %-6s %3d  (%.1f%%)%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / total);
        }
        // longest streak
        int best = 0; String bestName = ""; int cur = 0; String curName = "";
        for (String n : seq) {
            if (n.equals(curName)) cur++; else { curName = n; cur = 1; }
            if (cur > best) { best = cur; bestName = n; }
        }
        System.out.printf("  longest same-winner streak: %s x%d%n", bestName, best);
    }

    static void analyzeAgent(List<String> lines) {
        List<List<String>> rounds = new ArrayList<>();
        List<String> cur = null;
        for (String l : lines) {
            if (l.contains("run start")) {
                if (cur != null && !cur.isEmpty()) rounds.add(cur);
                cur = new ArrayList<>();
                continue;
            }
            if (l.contains("[prepare]")) {
                int i = l.indexOf("module=");
                if (i >= 0 && cur != null) {
                    String m = l.substring(i + 7).trim();
                    cur.add(m);
                }
            }
        }
        if (cur != null && !cur.isEmpty()) rounds.add(cur);

        int n = rounds.size();
        System.out.println("== agent log: " + n + " rounds ==");
        if (n == 0) return;

        Map<String, Integer> firstCounts = new LinkedHashMap<>();
        Map<String, Integer> orderCounts = new LinkedHashMap<>();
        Map<String, Integer> posSum = new LinkedHashMap<>();
        for (List<String> r : rounds) {
            if (!r.isEmpty()) firstCounts.merge(r.get(0), 1, Integer::sum);
            orderCounts.merge(String.join(" ", r), 1, Integer::sum);
            for (int i = 0; i < r.size(); i++) {
                posSum.merge(r.get(i), i, Integer::sum);
            }
        }
        System.out.println("  first-name distribution:");
        for (Map.Entry<String, Integer> e : firstCounts.entrySet()) {
            System.out.printf("    %-6s %3d  (%.1f%%)%n", e.getKey(), e.getValue(), 100.0 * e.getValue() / n);
        }
        System.out.println("  unique orders: " + orderCounts.size());
        System.out.println("  top orders:");
        orderCounts.entrySet().stream()
            .sorted((a, b) -> b.getValue() - a.getValue())
            .limit(5)
            .forEach(e -> System.out.printf("    %-40s x%d%n", e.getKey(), e.getValue()));
        System.out.println("  avg position per module (0=first):");
        posSum.entrySet().stream()
            .sorted((a, b) -> Double.compare((double) a.getValue() / n, (double) b.getValue() / n))
            .forEach(e -> System.out.printf("    %-6s %.2f%n", e.getKey(), (double) e.getValue() / n));
    }
}
