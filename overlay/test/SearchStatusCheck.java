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
        System.out.println("PASS: shared deadlines, 100/150-second boundary and visible beam/finalization stages");
    }
}
