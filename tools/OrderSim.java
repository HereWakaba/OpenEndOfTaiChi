import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OrderSim - deterministic simulator of the SERVICE-layer IWP enumeration order.
 *
 * Mirrors the JDK 17 chain exactly:
 *   cap   = (int)(n / 0.75f + 1.0f)          (Module.defineModules)
 *   table = smallest power of two >= cap     (HashMap tableSizeFor)
 *   spread = h ^ (h >>> 16)                  (HashMap.hash)
 *   bucket = spread & (table - 1)
 *   per bucket: if node count >= 8 -> treeified -> order by (spread, name.compareTo)
 *               else -> list order (insertion order; per-run random in the real
 *               JVM because insertion follows cf.modules().toArray() identity order)
 *
 * Usage: java OrderSim <moduleName1> [moduleName2] ...
 */
public class OrderSim {

    record M(String name, int spread) {}

    static int spread(String s) {
        int h = s.hashCode();
        return h ^ (h >>> 16);
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java OrderSim <moduleName1> [moduleName2] ...");
            return;
        }
        int n = args.length;
        int cap = (int) (n / 0.75f + 1.0f);
        int table = Integer.highestOneBit(cap - 1) << 1;
        System.out.printf("n=%d cap=%d table=%d (bucket = spread & %d)%n", n, cap, table, table - 1);

        Map<Integer, List<M>> bins = new LinkedHashMap<>();
        for (String name : args) {
            int sp = spread(name);
            int bucket = sp & (table - 1);
            bins.computeIfAbsent(bucket, k -> new ArrayList<>()).add(new M(name, sp));
            System.out.printf("  %-12s spread=%-10d bucket=%d%n", name, sp, bucket);
        }

        System.out.println();
        System.out.println("--- 预测实例化顺序（桶号升序；桶内 >=8 节点树化=确定序，否则同桶=彩票） ---");
        List<M> order = new ArrayList<>();
        List<Integer> bucketIds = new ArrayList<>(bins.keySet());
        bucketIds.sort(Integer::compareTo);
        for (int b : bucketIds) {
            List<M> list = bins.get(b);
            boolean tree = list.size() >= 8;
            if (tree) {
                list.sort(Comparator.comparingInt(M::spread).thenComparing(M::name));
            }
            System.out.printf("  bucket %d (%d 节点, %s):%n", b, list.size(), tree ? "TREE 确定" : (list.size() > 1 ? "LIST 彩票" : "单节点确定"));
            for (M m : list) {
                System.out.printf("    %-12s spread=%d%n", m.name(), m.spread());
            }
            order.addAll(list);
        }
        System.out.println();
        System.out.print("  SERVICE 层枚举序: ");
        for (M m : order) System.out.print(m.name() + " ");
        System.out.println();
        System.out.println("  （BOOT 层 fmlloader 的 IWP 排在其后，命中 'fmlearlywindow' 即 break）");
    }
}
