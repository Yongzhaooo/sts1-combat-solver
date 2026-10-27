package sts1solver;

/** The unfolded panel draws exactly one body; a step being executed keeps the plan body. */
public final class PanelBodyCheck {
    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        require(SolverMod.bodyView(false,false,false,false,false,false,false) == SolverMod.Body.OUTSIDE, "outside a fight");
        require(SolverMod.bodyView(true,false,false,false,false,false,false) == SolverMod.Body.UNSUPPORTED, "unsupported fight");
        require(SolverMod.bodyView(true,true,true,false,false,true,false) == SolverMod.Body.ERROR, "an error replaces the plan");
        require(SolverMod.bodyView(true,true,false,true,false,true,false) == SolverMod.Body.PICKER, "route picker");
        require(SolverMod.bodyView(true,true,false,true,false,false,false) == SolverMod.Body.IDLE, "a picker without a plan draws nothing else");
        require(SolverMod.bodyView(true,true,false,false,true,false,true) == SolverMod.Body.PLAN, "executing keeps the plan body, never the search body");
        require(SolverMod.bodyView(true,true,false,false,false,false,true) == SolverMod.Body.PLAN, "the step stays on screen while the backend confirms it");
        require(SolverMod.bodyView(true,true,false,false,false,true,false) == SolverMod.Body.PLAN, "a plan beats the search body");
        require(SolverMod.bodyView(true,true,false,false,true,false,false) == SolverMod.Body.SEARCH, "searching");
        require(SolverMod.bodyView(true,true,false,false,false,false,false) == SolverMod.Body.IDLE, "idle");
        com.google.gson.JsonParser json = new com.google.gson.JsonParser();
        com.google.gson.JsonObject labelled = json.parse("{\"route\":[{\"label\":\"play 1 0\",\"turn\":1}]}").getAsJsonObject();
        require(SolverMod.routeLabel(labelled).equals("play 1 0"), "the executed step's label comes from the route");
        com.google.gson.JsonObject textOnly = json.parse("{\"route\":[{\"text\":\"end\",\"turn\":1}]}").getAsJsonObject();
        require(SolverMod.routeLabel(textOnly).equals("end"), "a route row without a label falls back to its text");
        require(SolverMod.routeLabel(json.parse("{\"route\":[]}").getAsJsonObject()).isEmpty(), "empty route");
        require(SolverMod.routeLabel(json.parse("{}").getAsJsonObject()).isEmpty(), "no route");
        require(SolverMod.routeLabel(null).isEmpty(), "no plan");
        System.out.println("PASS: panel body while executing, route label");
    }
}
