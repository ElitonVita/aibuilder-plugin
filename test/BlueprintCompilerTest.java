import com.elitonvita.aibuilder.BlueprintCompiler;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/** Standalone regression suite; explicit assertions run even without -ea. */
public class BlueprintCompilerTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static JsonObject plan(String part) {
        return JsonParser.parseString("{\"parts\":[" + part + "]}").getAsJsonObject();
    }
    private static String box(String to) {
        return "{\"type\":\"box\",\"from\":[0,0,0],\"to\":" + to + ",\"material\":\"STONE\"}";
    }
    private static void rejected(JsonObject bp) {
        BlueprintCompiler.Result result = BlueprintCompiler.compile(bp, 0, 0, 0);
        check(!result.isValid(), "invalid plan accepted: " + bp);
        check(result.blocks.isEmpty(), "rejection leaked partial blocks");
        check(!result.errors.isEmpty(), "rejection missing explanation");
    }
    public static void main(String[] args) throws Exception {
        Path sample = Path.of(args.length == 0 ? "test/sample_house.json" : args[0]);
        JsonObject bp = JsonParser.parseString(Files.readString(sample)).getAsJsonObject();
        BlueprintCompiler.Result r = BlueprintCompiler.compile(bp, 0, 0, 0);
        Map<String, Integer> counts = new TreeMap<>();
        for (BlueprintCompiler.Block b : r.blocks) counts.merge(b.material(), 1, Integer::sum);
        check(r.isValid(), "sample invalid: " + r.errors);
        check(r.blocks.size() == 221, "sample block count changed");
        check(counts.equals(Map.of("COBBLESTONE", 49, "OAK_PLANKS", 172)), "sample materials changed");
        // walls/roof take their height from height/layers: a "to" with another y is normalized, not rejected.
        int withMismatch = BlueprintCompiler.compile(plan("{\"type\":\"walls\",\"from\":[0,1,0],\"to\":[2,0,2],\"height\":2,\"material\":\"STONE\"}"), 0, 0, 0).blocks.size();
        int withMatch = BlueprintCompiler.compile(plan("{\"type\":\"walls\",\"from\":[0,1,0],\"to\":[2,1,2],\"height\":2,\"material\":\"STONE\"}"), 0, 0, 0).blocks.size();
        check(withMismatch == withMatch && withMatch > 0, "walls with a mismatched to.y were not normalized");
        rejected(plan(box("[1000000,1000000,1000000]")));
        rejected(plan(box("[-1,0,0]")));
        rejected(plan(box("[1.5,0,0]")));
        rejected(plan(box("[\"1\",0,0]")));
        rejected(plan(box("[2147483648,0,0]")));
        rejected(plan(box("[1,0,0]").replace("STONE", "")));
        rejected(plan("{\"type\":\"unknown\",\"from\":[0,0,0],\"to\":[0,0,0],\"material\":\"STONE\"}"));
        rejected(plan(box("[0,0,0]") + "," + box("[-1,0,0]")));
        rejected(plan("{\"type\":\"walls\",\"from\":[0,0,0],\"to\":[2,0,2],\"height\":0,\"material\":\"STONE\"}"));
        rejected(plan("{\"type\":\"roof_steps\",\"from\":[0,0,0],\"to\":[2,0,2],\"layers\":1000000,\"material\":\"STONE\"}"));
        BlueprintCompiler.Result limited = BlueprintCompiler.compile(bp, 0, 0, 0, 100);
        check(!limited.isValid() && limited.blocks.isEmpty(), "block limit failed");
        BlueprintCompiler.Result overflow = BlueprintCompiler.compile(plan(box("[1,0,0]")), Integer.MAX_VALUE, 0, 0);
        check(!overflow.isValid() && overflow.blocks.isEmpty(), "origin overflow accepted");
        String opening = "{\"side\":\"north\",\"at\":1,\"width\":1,\"height\":1}";
        String wall = "{\"type\":\"walls\",\"from\":[0,0,0],\"to\":[2,0,2],\"height\":1,\"material\":\"STONE\",\"openings\":[" + opening + "," + opening + "]}";
        BlueprintCompiler.Result overlap = BlueprintCompiler.compile(plan(wall), 0, 0, 0);
        check(overlap.isValid() && overlap.blocks.size() == 7, "overlapping openings changed geometry");
        check(overlap.blocks.stream().noneMatch(b -> b.x() == 1 && b.z() == 0), "opening not removed");
        rejected(plan(wall.replace("\"at\":1", "\"at\":3")));
        JsonObject many = plan(box("[0,0,0]"));
        for (int i = 0; i < 4096; i++) many.getAsJsonArray("parts").add(many.getAsJsonArray("parts").get(0).deepCopy());
        rejected(many);
        rejected(JsonParser.parseString("{\"parts\":[false]}").getAsJsonObject());
        rejected(plan(box("[0,0,0]").replace("[0,0,0]", "[true,0,0]")));
        rejected(plan(wall.replace("north", "up")));
        BlueprintCompiler.Result translated = BlueprintCompiler.compile(plan(box("[1,0,0]")), -10, 64, 8, 2);
        check(translated.isValid() && translated.blocks.get(0).x() == -10
                && translated.blocks.get(1).x() == -9 && translated.blocks.get(0).y() == 64,
                "origin translation changed");
        check(BlueprintCompiler.compile(plan(box("[0,0,0]")), 0, 0, 0, 1).isValid(), "exact limit rejected");
        JsonObject stateful=plan(box("[0,0,0]"));
        stateful.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("data","minecraft:stone[waterlogged=false]");
        var stateResult=BlueprintCompiler.compile(stateful,2,64,3);
        check(stateResult.isValid()&&stateResult.blocks.getFirst().data().equals("minecraft:stone[waterlogged=false]"),"compiler discarded part state");
        stateful.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("data","minecraft:oak_door[half=lower]");
        rejected(stateful);
        stateful.getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("data","minecraft:stone[bad=\"value\"]");
        rejected(stateful);
        System.out.println("PASS: " + checks + " compiler assertions; sample 221 blocks (49 stone, 172 wood)");
    }
}
