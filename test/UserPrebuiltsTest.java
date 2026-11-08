import com.elitonvita.aibuilder.PrebuiltCatalog;
import com.elitonvita.aibuilder.PrebuiltPreview;
import com.elitonvita.aibuilder.UserPrebuilts;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class UserPrebuiltsTest {
    private static int checks;
    static void check(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }

    /** Stand-in for the in-game rule: everything passes except the materials named here. */
    static UserPrebuilts.Checker allowAllExcept(String... banned) {
        Set<String> no = Set.of(banned);
        return (material, data) -> no.contains(material) ? "material nao permitido: " + material : null;
    }

    static final String VALID = """
        {
          "id": "casa_teste",
          "title": "Casa Teste",
          "description": "3 x 3 x 3 de teste.",
          "parts": [
            {"type":"box","material":"STONE_BRICKS","from":[0,0,0],"to":[2,0,2]},
            {"type":"walls","material":"OAK_PLANKS","from":[0,1,0],"to":[2,1,2],"height":2}
          ]
        }
        """;

    static Path dirWith(Map<String, String> files) throws Exception {
        Path dir = Files.createTempDirectory("aibuilder-user-prebuilts");
        for (var e : files.entrySet()) Files.writeString(dir.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        return dir;
    }

    static UserPrebuilts.Load load(Map<String, String> files) throws Exception {
        return UserPrebuilts.load(dirWith(files), Set.of("casa_campo"), 1500, allowAllExcept());
    }

    static void rejects(String json, String expectedFragment) {
        try {
            UserPrebuilts.parse(json, Set.of("casa_campo"), 1500, allowAllExcept("BEDROCK"));
            throw new AssertionError("accepted invalid JSON, expected: " + expectedFragment);
        } catch (IllegalArgumentException e) {
            check(e.getMessage().contains(expectedFragment), "wrong reason: " + e.getMessage() + " (wanted " + expectedFragment + ")");
            checks++;
        }
    }

    static String withPart(String part) {
        return "{\"id\":\"teste_parte\",\"title\":\"T\",\"description\":\"D\",\"parts\":[" + part + "]}";
    }

    public static void main(String[] args) throws Exception {
        // 1) a valid file loads with its id, title and category default
        var ok = load(Map.of("casa_teste.json", VALID));
        check(ok.errors().isEmpty() && ok.entries().size() == 1, "valid file rejected: " + ok.errors());
        var entry = ok.entries().get(0);
        check(entry.id().equals("casa_teste") && entry.title().equals("Casa Teste"), "id/title not loaded");
        check(entry.category().equals("personalizados"), "default category missing");
        check(entry.blueprint().getAsJsonArray("parts").size() == 2, "parts not kept");

        // 2) the shipped example is valid (it is copied as .json by the user)
        Path example = Path.of("src/prebuilt-kit/exemplo_abrigo.json.example");
        check(Files.exists(example), "kit example missing: " + example.toAbsolutePath());
        var fromKit = UserPrebuilts.parse(Files.readString(example), Set.of("casa_campo"), 1500, allowAllExcept());
        check(fromKit.id().equals("abrigo_madeira") && fromKit.blueprint().getAsJsonArray("parts").size() == 7, "kit example changed");

        // 3) .example files are ignored; missing folder gives an empty result without errors
        var ignored = load(Map.of("exemplo.json.example", VALID, "lixo.txt", "x"));
        check(ignored.entries().isEmpty() && ignored.files() == 0, "non-.json file was loaded");
        var missing = UserPrebuilts.load(Path.of("/nao/existe/prebuilts"), Set.of(), 1500, allowAllExcept());
        check(missing.entries().isEmpty() && missing.errors().isEmpty(), "missing folder reported an error");

        // 4) one bad file does not stop the good ones
        var mixed = load(Map.of("a_bom.json", VALID, "b_ruim.json", "{ nao e json"));
        check(mixed.entries().size() == 1 && mixed.errors().size() == 1 && mixed.errors().get(0).startsWith("b_ruim.json"), "mixed folder not isolated");

        // 5) absolute world coordinates are refused, whatever the spelling
        rejects("{\"id\":\"abc_def\",\"title\":\"T\",\"description\":\"D\",\"x\":10,\"parts\":[]}", "campo 'x' nao e aceito");
        rejects("{\"id\":\"abc_def\",\"title\":\"T\",\"description\":\"D\",\"World\":\"w\",\"parts\":[]}", "nao e aceito");
        rejects("{\"id\":\"abc_def\",\"title\":\"T\",\"description\":\"D\",\"origin\":[1,2,3],\"parts\":[]}", "nao e aceito");
        rejects("{\"id\":\"abc_def\",\"title\":\"T\",\"description\":\"D\",\"extra\":1,\"parts\":[]}", "campo desconhecido 'extra'");

        // 6) ids: format, built-in clash, required fields
        rejects("{\"id\":\"Casa Ruim\",\"title\":\"T\",\"description\":\"D\",\"parts\":[]}", "invalido");
        rejects("{\"id\":\"ab\",\"title\":\"T\",\"description\":\"D\",\"parts\":[]}", "invalido");
        rejects("{\"id\":\"casa_campo\",\"title\":\"T\",\"description\":\"D\",\"parts\":[]}", "ja existe");
        rejects("{\"title\":\"T\",\"description\":\"D\",\"parts\":[]}", "campo obrigatorio 'id'");
        rejects("{\"id\":\"abc_def\",\"description\":\"D\",\"parts\":[]}", "campo obrigatorio 'title'");
        rejects("{\"id\":\"abc_def\",\"title\":\"T\",\"description\":\"D\",\"category\":\"Casas!\",\"parts\":[]}", "category");

        // 7) structure: material, data, bounds, block budget
        rejects(withPart("{\"type\":\"box\",\"material\":\"BEDROCK\",\"from\":[0,0,0],\"to\":[0,0,0]}"), "material nao permitido: BEDROCK");
        rejects(withPart("{\"type\":\"box\",\"material\":\"OAK_DOOR\",\"data\":\"minecraft:stone[facing=south]\",\"from\":[0,0,0],\"to\":[0,0,0]}"), "BlockData nao corresponde");
        rejects(withPart("{\"type\":\"box\",\"material\":\"STONE\",\"from\":[0,0,0],\"to\":[200,0,0]}").replace("teste_parte", "larga"), "footprint");
        rejects(withPart("{\"type\":\"box\",\"material\":\"STONE\",\"from\":[0,0,0],\"to\":[0,100,0]}"), "altura");
        rejects(withPart("{\"type\":\"box\",\"material\":\"STONE\",\"from\":[0,-2,0],\"to\":[0,-2,0]}"), "abaixo do chao");
        rejects(withPart("{\"type\":\"box\",\"material\":\"STONE\",\"from\":[3,0,0],\"to\":[1,0,0]}"), "planta invalida");
        rejects("{\"id\":\"abc_def\",\"title\":\"T\",\"description\":\"D\",\"parts\":[]}", "parts");

        var big = UserPrebuilts.load(dirWith(Map.of("casa_teste.json", VALID)), Set.of(), 5, allowAllExcept());
        check(big.entries().isEmpty() && big.errors().get(0).contains("limite de blocos"), "block budget not enforced");

        var capped = UserPrebuilts.load(dirWith(Map.of(
                "a.json", VALID.replace("casa_teste", "casa_um"),
                "b.json", VALID.replace("casa_teste", "casa_dois"),
                "c.json", VALID.replace("casa_teste", "casa_tres"))), Set.of(),
                com.elitonvita.aibuilder.ConstructionLimits.defaults(1500), 2, allowAllExcept());
        check(capped.entries().size() == 2 && capped.files() == 3
                && capped.errors().stream().anyMatch(error -> error.contains("limite de 2")),
                "configured prebuilt capacity was not enforced or reported");

        // 8) the 7 built-in entries are untouched by the loader
        check(PrebuiltCatalog.builtIn().size() == 7, "built-in count changed");

        // 9) a previa (data.js) traz os blocos compilados e os erros; a assinatura muda quando um JSON muda
        var preview = PrebuiltPreview.render(PrebuiltCatalog.builtIn(), ok.entries(), ok, 1500, 42L);
        check(preview.startsWith("window.AIBUILDER_PREVIEW = ") && preview.contains("\"source\":\"usuario\""), "preview script missing user entry");
        check(preview.contains("\"material\":\"STONE_BRICKS\"") && preview.contains("\"entries\""), "preview has no compiled blocks");
        var broken = load(Map.of("ruim.json", "{ nao e json"));
        check(PrebuiltPreview.render(List.of(), List.of(), broken, 1500, 1L).contains("JSON invalido"), "preview lost the error message");
        Path sigDir = dirWith(Map.of("a.json", VALID));
        String s1 = UserPrebuilts.signature(sigDir);
        Files.writeString(sigDir.resolve("a.json"), VALID + " ", StandardCharsets.UTF_8);
        check(!s1.equals(UserPrebuilts.signature(sigDir)), "edit not detected");
        Files.delete(sigDir.resolve("a.json"));
        check(!UserPrebuilts.signature(sigDir).equals(s1) && UserPrebuilts.signature(sigDir).isEmpty(), "delete not detected");
        check(UserPrebuilts.signature(Path.of("/nao/existe")).isEmpty(), "missing folder signature");

        ConfigurableLimitsTest.main(new String[0]);
        OrientationLimitsTest.main(new String[0]);
        RevisedPrebuiltsTest.main(new String[0]);
        System.out.println("PASS: " + checks + " user pre-built assertions");
    }
}
