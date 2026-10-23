package sts1solver;
import com.google.gson.*;

public class SearchStatusCheck {
    public static void main(String[] args) {
        long normal = SearchStatus.timeoutMillis(8000), deep = SearchStatus.timeoutMillis(128000);
        if (normal != 160000 || deep != 610000) throw new AssertionError("deadline contract");
        if (SearchStatus.expired(100001, 0, normal) || SearchStatus.expired(150001, 0, normal)
            || !SearchStatus.expired(160001, 0, normal)) throw new AssertionError("premature timeout");
        if (SearchStatus.acknowledgedTimeoutMillis(150) != normal) throw new AssertionError("ack mismatch");
        JsonObject row = new JsonObject();
        row.addProperty("phase", "beam"); row.addProperty("simulations", 8000);
        String beam = SearchStatus.phase(row);
        row.addProperty("phase", "finalizing");
        if (beam.equals(SearchStatus.phase(row))) throw new AssertionError("stage is hidden");
        float previous = SearchStatus.waitingFill(-5);
        if (previous != 0 || Math.abs(SearchStatus.waitingFill(8000) - .5f) > 1e-6) throw new AssertionError("search bar start or midpoint");
        for (long t = 500; t <= 3600000; t *= 2) {
            float fill = SearchStatus.waitingFill(t);
            if (fill < previous || fill > .95f) throw new AssertionError("search bar must only grow and never look finished: " + t);
            previous = fill;
        }
        System.out.println("PASS: shared deadlines, 100/150-second boundary, visible beam/finalization stages and the search bar");
    }
}
