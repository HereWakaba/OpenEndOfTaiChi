import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class AuditCheck {
    static int spread(String s) {
        int h = s.hashCode();
        return h ^ (h >>> 16);
    }
    static int k(String s) { return Integer.numberOfTrailingZeros(spread(s)); }
    static int bucket(String s, int size) { return spread(s) & (size - 1); }

    public static void main(String[] args) throws Exception {
        // ---- Part 1: hash/spread/k/buckets for module names ----
        String[] names = {"P","p","z","AA","Aa","Cc","Gg","Oo","aA","aa","bb","reflection","aom"};
        System.out.println("=== hash/spread/k/bucket ===");
        System.out.printf("%-12s %-12s %-12s %-4s %-6s %-6s %-6s %-6s%n",
            "name","hash","spread","k","b4","b8","b16","b64");
        for (String n : names) {
            int h = n.hashCode();
            int sp = spread(n);
            System.out.printf("%-12s 0x%08X 0x%08X %-4d %-6d %-6d %-6d %-6d%n",
                n, h, sp, k(n), bucket(n,4), bucket(n,8), bucket(n,16), bucket(n,64));
        }

        // ---- Part 2: floor names search ----
        System.out.println();
        System.out.println("=== floor name search (len 1..4, alphabet A-Za-z) ===");
        int[] ts = {8,16,32,64,128,256,512,1024};
        for (int t : ts) {
            int need = Integer.numberOfTrailingZeros(t); // log2(t)
            String best = null; int bestSpread = Integer.MAX_VALUE;
            for (int len = 1; len <= 4; len++) {
                for (char c1 = 'A'; c1 <= 'z'; c1++) {
                    if (c1 > 'Z' && c1 < 'a') continue;
                    StringBuilder sb = new StringBuilder(); sb.append(c1);
                    if (len == 1) {
                        int sp = spread(sb.toString());
                        if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) {
                            bestSpread = sp; best = sb.toString();
                        }
                    } else if (len == 2) {
                        for (char c2 = 'A'; c2 <= 'z'; c2++) {
                            if (c2 > 'Z' && c2 < 'a') continue;
                            sb.append(c2);
                            int sp = spread(sb.toString());
                            if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) {
                                bestSpread = sp; best = sb.toString();
                            }
                            sb.deleteCharAt(1);
                        }
                    } else if (len == 3) {
                        for (char c2 = 'A'; c2 <= 'z'; c2++) {
                            if (c2 > 'Z' && c2 < 'a') continue;
                            sb.append(c2);
                            for (char c3 = 'A'; c3 <= 'z'; c3++) {
                                if (c3 > 'Z' && c3 < 'a') continue;
                                sb.append(c3);
                                int sp = spread(sb.toString());
                                if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) {
                                    bestSpread = sp; best = sb.toString();
                                }
                                sb.deleteCharAt(2);
                            }
                            sb.deleteCharAt(1);
                        }
                    } else {
                        for (char c2 = 'A'; c2 <= 'z'; c2++) {
                            if (c2 > 'Z' && c2 < 'a') continue;
                            sb.append(c2);
                            for (char c3 = 'A'; c3 <= 'z'; c3++) {
                                if (c3 > 'Z' && c3 < 'a') continue;
                                sb.append(c3);
                                for (char c4 = 'A'; c4 <= 'z'; c4++) {
                                    if (c4 > 'Z' && c4 < 'a') continue;
                                    sb.append(c4);
                                    int sp = spread(sb.toString());
                                    if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) {
                                        bestSpread = sp; best = sb.toString();
                                    }
                                    sb.deleteCharAt(3);
                                }
                                sb.deleteCharAt(2);
                            }
                            sb.deleteCharAt(1);
                        }
                    }
                }
            }
            System.out.printf("T=%-5d need_k=%-2d -> floor=%-6s spread=%d k=%d%n",
                t, need, best, bestSpread, best==null?-1:k(best));
        }

        // ---- Part 3: floor name search with digits too ----
        System.out.println();
        System.out.println("=== floor name search (len 1..4, alphabet A-Za-z0-9) ===");
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        for (int t : ts) {
            int need = Integer.numberOfTrailingZeros(t);
            String best = null; int bestSpread = Integer.MAX_VALUE;
            for (int len = 1; len <= 4; len++) {
                for (char c1 : alpha.toCharArray()) {
                    StringBuilder sb = new StringBuilder(); sb.append(c1);
                    if (len == 1) {
                        int sp = spread(sb.toString());
                        if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) { bestSpread = sp; best = sb.toString(); }
                    } else if (len == 2) {
                        for (char c2 : alpha.toCharArray()) {
                            sb.append(c2);
                            int sp = spread(sb.toString());
                            if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) { bestSpread = sp; best = sb.toString(); }
                            sb.deleteCharAt(1);
                        }
                    } else if (len == 3) {
                        for (char c2 : alpha.toCharArray()) {
                            sb.append(c2);
                            for (char c3 : alpha.toCharArray()) {
                                sb.append(c3);
                                int sp = spread(sb.toString());
                                if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) { bestSpread = sp; best = sb.toString(); }
                                sb.deleteCharAt(2);
                            }
                            sb.deleteCharAt(1);
                        }
                    } else {
                        for (char c2 : alpha.toCharArray()) {
                            sb.append(c2);
                            for (char c3 : alpha.toCharArray()) {
                                sb.append(c3);
                                for (char c4 : alpha.toCharArray()) {
                                    sb.append(c4);
                                    int sp = spread(sb.toString());
                                    if (sp != 0 && Integer.numberOfTrailingZeros(sp) >= need && sp < bestSpread) { bestSpread = sp; best = sb.toString(); }
                                    sb.deleteCharAt(3);
                                }
                                sb.deleteCharAt(2);
                            }
                            sb.deleteCharAt(1);
                        }
                    }
                }
            }
            System.out.printf("T=%-5d need_k=%-2d -> floor=%-6s spread=%d k=%d%n",
                t, need, best, bestSpread, best==null?-1:k(best));
        }

        // ---- Part 4: winner log stats ----
        System.out.println();
        for (String f : new String[]{"e1","e2","e3"}) {
            List<String> lines = Files.readAllLines(Path.of("E:\\Minecraft\\Reflection\\exp\\" + f + ".winner.log"));
            Map<String,Integer> counts = new LinkedHashMap<>();
            List<String> seq = new ArrayList<>();
            for (String l : lines) {
                String name = l.split(" ")[0];
                if (name.isEmpty()) continue;
                counts.merge(name, 1, Integer::sum);
                seq.add(name);
            }
            System.out.println("=== " + f + ".winner.log: " + seq.size() + " runs ===");
            for (Map.Entry<String,Integer> e : counts.entrySet())
                System.out.printf("  %s: %d (%.1f%%)%n", e.getKey(), e.getValue(), 100.0*e.getValue()/seq.size());
            int best=0; String bn=""; int cur=0; String cn="";
            for (String n : seq) { if (n.equals(cn)) cur++; else {cn=n;cur=1;} if (cur>best){best=cur;bn=n;} }
            System.out.println("  longest streak: " + bn + " x" + best);
        }

        // ---- Part 5: e4.log agent stats ----
        List<String> elines = Files.readAllLines(Path.of("E:\\Minecraft\\Reflection\\exp\\e4.log"));
        List<List<String>> rounds = new ArrayList<>();
        List<String> curRound = null;
        for (String l : elines) {
            if (l.contains("run start")) {
                if (curRound != null && !curRound.isEmpty()) rounds.add(curRound);
                curRound = new ArrayList<>();
                continue;
            }
            if (l.contains("[prepare]")) {
                int i = l.indexOf("module=");
                if (i >= 0 && curRound != null) curRound.add(l.substring(i+7).trim());
            }
        }
        if (curRound != null && !curRound.isEmpty()) rounds.add(curRound);
        int n = rounds.size();
        System.out.println("=== e4.log: " + n + " rounds ===");
        Map<String,Integer> firstCounts = new LinkedHashMap<>();
        Map<String,Integer> orderCounts = new LinkedHashMap<>();
        Map<String,Integer> posSum = new LinkedHashMap<>();
        for (List<String> r : rounds) {
            if (!r.isEmpty()) firstCounts.merge(r.get(0), 1, Integer::sum);
            orderCounts.merge(String.join(" ", r), 1, Integer::sum);
            for (int i=0;i<r.size();i++) posSum.merge(r.get(i), i, Integer::sum);
        }
        System.out.println("  first-name distribution:");
        for (Map.Entry<String,Integer> e : firstCounts.entrySet())
            System.out.printf("    %-4s %3d (%.1f%%)%n", e.getKey(), e.getValue(), 100.0*e.getValue()/n);
        System.out.println("  unique orders: " + orderCounts.size());
        System.out.println("  avg position per module (0=first):");
        posSum.entrySet().stream()
            .sorted((a,b)->Double.compare((double)a.getValue()/n,(double)b.getValue()/n))
            .forEach(e->System.out.printf("    %-4s %.2f%n", e.getKey(), (double)e.getValue()/n));
        // also check: does every round have exactly 8 entries and all 8 distinct names?
        Set<String> all8 = new HashSet<>(Arrays.asList("P","p","AA","Aa","Cc","Gg","Oo","aA"));
        int bad = 0;
        for (List<String> r : rounds) {
            if (r.size() != 8 || !new HashSet<>(r).equals(all8)) bad++;
        }
        System.out.println("  rounds with size!=8 or not-all-8-distinct: " + bad);
    }
}
