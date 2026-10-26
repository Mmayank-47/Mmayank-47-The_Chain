import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

public class RoundCheck {
    static double pythonRound(double x, int ndigits) {
        if (!Double.isFinite(x)) return x;
        return new BigDecimal(x).setScale(ndigits, RoundingMode.HALF_EVEN).doubleValue();
    }
    static final Map<String, Double> W = new LinkedHashMap<>();
    static final Map<String, Double> B = new LinkedHashMap<>();
    static {
        W.put("not_breathing", 0.50);
        W.put("severe_bleeding", 0.40);
        W.put("unconscious", 0.40);
        W.put("trapped", 0.25);
        W.put("not_walking", 0.15);
        W.put("elderly", 0.10);
        W.put("child", 0.15);
        W.put("pregnant", 0.15);
        W.put("medication_dependent", 0.20);
        B.put("need", 0.30);
        B.put("unknown", 0.20);
        B.put("offer", 0.0);
    }
    static double urg(String kind, String[] feats) {
        if (kind.equals("offer")) return 0.0;
        double miss = 1.0 - B.getOrDefault(kind, 0.2);
        for (String f : feats) miss *= 1.0 - W.getOrDefault(f, 0.0);
        return pythonRound(1.0 - miss, 4);
    }
    public static void main(String[] args) {
        System.out.println(urg("need", new String[]{"child","trapped"}));
        System.out.println(urg("need", new String[]{"trapped","child"}));
        System.out.println(urg("need", new String[]{"not_breathing"}));
        System.out.println(urg("need", new String[]{"elderly","not_breathing","severe_bleeding","trapped"}));
        System.out.println(urg("unknown", new String[]{}));
        System.out.println(pythonRound(0.55375, 4));
        System.out.println(pythonRound(2.675, 2));
        double c = 0.35; c+=0.20; c+=0.20; c+=0.10; c+=0.10;
        System.out.println(c);
        System.out.println(Math.min(c, 0.85));
        System.out.println(pythonRound(Math.min(1.0, Math.max(0.0, Math.min(c, 0.85))), 4));
    }
}
