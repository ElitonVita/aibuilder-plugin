package com.elitonvita.aibuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Pre-moldados criados pelo usuario: um JSON por arquivo na pasta prebuilts/ do plugin.
 * Sem dependencia do Bukkit: a checagem de materiais e injetada (Checker) para poder testar fora do servidor.
 * As coordenadas sao relativas ao canto minimo da area marcada; a posicao real e escolhida pelo usuario.
 */
public final class UserPrebuilts {
    /** Devolve null se o material/BlockData pode ser colocado pelo construtor, ou o motivo. */
    public interface Checker { String problem(String material, String data); }

    public record Load(List<PrebuiltCatalog.Entry> entries, List<String> errors, int files) {}

    public static final String FOLDER = "prebuilts";
    public static final String DEFAULT_CATEGORY = "personalizados";
    public static final int HARD_MAX_USER_PREBUILTS = 256;
    /** Limites de seguranca do arquivo: footprint e altura maximos, independentes do limite de blocos. */
    static final int DEFAULT_HEIGHT = 100, MAX_ERRORS = 20;
    private static final java.util.regex.Pattern ID = java.util.regex.Pattern.compile("[a-z][a-z0-9_]{2,39}");
    private static final java.util.regex.Pattern CATEGORY = java.util.regex.Pattern.compile("[a-z_]{2,30}");
    private static final Set<String> ROOT_KEYS = Set.of("id", "title", "description", "category", "entrance", "parts", "_comment");
    private static final Set<String> ABSOLUTE_KEYS = Set.of("x", "y", "z", "world", "origin", "area", "location", "pos");

    private UserPrebuilts() {}

    public static Load load(Path dir, Set<String> reservedIds, int maxBlocks, Checker checker) {
        return load(dir, reservedIds, maxBlocks, DEFAULT_HEIGHT, checker);
    }

    public static Load load(Path dir, Set<String> reservedIds, int maxBlocks, int maxHeight, Checker checker) {
        return load(dir, reservedIds, ConstructionLimits.defaults(maxBlocks).withHeight(maxHeight), checker);
    }

    public static Load load(Path dir, Set<String> reservedIds, ConstructionLimits limits, Checker checker) {
        return load(dir, reservedIds, limits, HARD_MAX_USER_PREBUILTS, checker);
    }

    public static Load load(Path dir, Set<String> reservedIds, ConstructionLimits limits, int configuredMaxEntries, Checker checker) {
        int maxEntries = Math.max(1, Math.min(HARD_MAX_USER_PREBUILTS, configuredMaxEntries));
        List<PrebuiltCatalog.Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        int files = 0;
        if (dir == null || !Files.isDirectory(dir)) return new Load(entries, errors, 0);
        Set<String> seen = new HashSet<>(reservedIds);
        List<Path> jsons;
        try (Stream<Path> stream = Files.list(dir)) {
            jsons = stream.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p))
                    .sorted().toList();
        } catch (IOException e) {
            return new Load(entries, List.of("nao consegui ler a pasta " + dir.getFileName() + ": " + e.getMessage()), 0);
        }
        files = jsons.size();
        for (Path file : jsons) {
            if (entries.size() >= maxEntries) {
                errors.add("limite de " + maxEntries + " pre-moldados do usuario atingido; arquivos excedentes foram ignorados");
                break;
            }
            String name = file.getFileName().toString();
            try {
                require(Files.size(file) <= limits.fileBytes(), "arquivo maior que construction.max-file-bytes");
                PrebuiltCatalog.Entry entry = parse(Files.readString(file, StandardCharsets.UTF_8), seen, limits, checker);
                seen.add(entry.id());
                entries.add(entry);
            } catch (IllegalArgumentException | IOException | com.google.gson.JsonParseException e) {
                if (errors.size() < MAX_ERRORS) errors.add(name + ": " + e.getMessage());
            }
        }
        return new Load(List.copyOf(entries), List.copyOf(errors), files);
    }

    /** Valida um JSON de pre-moldado. Lanca IllegalArgumentException com a primeira falha encontrada. */
    public static PrebuiltCatalog.Entry parse(String text, Set<String> taken, int maxBlocks, Checker checker) {
        return parse(text, taken, maxBlocks, DEFAULT_HEIGHT, checker);
    }

    public static PrebuiltCatalog.Entry parse(String text, Set<String> taken, int maxBlocks, int maxHeight, Checker checker) {
        return parse(text, taken, ConstructionLimits.defaults(maxBlocks).withHeight(maxHeight), checker);
    }

    public static PrebuiltCatalog.Entry parse(String text, Set<String> taken, ConstructionLimits limits, Checker checker) {
        int maxHeight = limits.height();
        JsonElement root;
        try { root = JsonParser.parseString(text); }
        catch (RuntimeException e) { throw new IllegalArgumentException("JSON invalido (" + e.getMessage() + ")"); }
        require(root != null && root.isJsonObject(), "a raiz precisa ser um objeto JSON");
        JsonObject o = root.getAsJsonObject();
        for (String key : o.keySet()) {
            require(!ABSOLUTE_KEYS.contains(key.toLowerCase(Locale.ROOT)),
                    "campo '" + key + "' nao e aceito: a posicao no mundo e escolhida pelo jogador com a area marcada");
            require(key.startsWith("_") || ROOT_KEYS.contains(key), "campo desconhecido '" + key + "'");
        }
        String id = text(o, "id", true, 40);
        require(ID.matcher(id).matches(), "id '" + id + "' invalido: use 3 a 40 letras minusculas, numeros ou _ (comecando por letra)");
        require(!taken.contains(id), "id '" + id + "' ja existe (builtin ou outro arquivo)");
        String title = text(o, "title", true, 60);
        String description = text(o, "description", true, 240);
        String category = text(o, "category", false, 30);
        if (category == null) category = DEFAULT_CATEGORY;
        require(CATEGORY.matcher(category).matches(), "category '" + category + "' invalida: use letras minusculas e _");
        require(o.has("parts") && o.get("parts").isJsonArray(), "'parts' precisa ser uma lista de partes");

        JsonObject blueprint = new JsonObject();
        String entrance = text(o, "entrance", false, 10);
        if (entrance == null) entrance = "south";
        entrance = BlueprintOrientation.parse(entrance);
        require(entrance != null, "entrance precisa ser north, south, east ou west");
        blueprint.addProperty("entrance", entrance);
        blueprint.addProperty("name", id);
        blueprint.add("parts", o.getAsJsonArray("parts").deepCopy());

        BlueprintCompiler.Result compiled = BlueprintCompiler.compile(blueprint, 0, 0, 0, limits);
        if (!compiled.isValid()) throw new IllegalArgumentException(String.join("; ", compiled.errors));
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (BlueprintCompiler.Block b : compiled.blocks) {
            minX = Math.min(minX, b.x()); maxX = Math.max(maxX, b.x());
            minZ = Math.min(minZ, b.z()); maxZ = Math.max(maxZ, b.z());
            minY = Math.min(minY, b.y()); maxY = Math.max(maxY, b.y());
        }
        require((long)maxX - minX + 1 <= limits.width() && (long)maxZ - minZ + 1 <= limits.depth(),
                "footprint maior que " + limits.width() + " x " + limits.depth());
        require(maxY - minY + 1 <= maxHeight, "altura maior que " + maxHeight + " blocos");
        require(limits.fits((long)maxX-minX+1,(long)maxY-minY+1,(long)maxZ-minZ+1), "volume maior que construction.max-volume");
        require(minY >= -1, "a parte mais baixa pode ir ate 1 bloco abaixo do chao (beirais); nada mais baixo");

        String gardenProblem = CatalogGardenPolicy.problem(CatalogGardenPolicy.cells(compiled.blocks));
        require(gardenProblem == null, gardenProblem);

        Set<String> checked = new LinkedHashSet<>();
        for (BlueprintCompiler.Block b : compiled.blocks) {
            String key = b.material() + "|" + b.data();
            if (!checked.add(key)) continue;
            String problem = checker.problem(b.material(), b.data());
            require(problem == null, problem);
        }
        return new PrebuiltCatalog.Entry(id, category, title, description, blueprint);
    }

    private static String text(JsonObject o, String key, boolean required, int max) {
        if (!o.has(key) || o.get(key).isJsonNull()) {
            require(!required, "campo obrigatorio '" + key + "' ausente");
            return null;
        }
        JsonElement value = o.get(key);
        require(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString(), "'" + key + "' precisa ser texto");
        String s = value.getAsString().trim();
        require(!s.isEmpty() && s.length() <= max, "'" + key + "' precisa ter de 1 a " + max + " caracteres");
        return s;
    }

    /** Assinatura dos *.json da pasta (nome, tamanho e data): muda quando algo e criado, editado ou removido. */
    public static String signature(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) return "";
        try (Stream<Path> stream = Files.list(dir)) {
            StringBuilder sb = new StringBuilder();
            stream.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p)).sorted()
                    .forEach(p -> {
                        try {
                            sb.append(p.getFileName()).append('|').append(Files.size(p)).append('|')
                                    .append(Files.getLastModifiedTime(p).toMillis()).append('\n');
                        } catch (IOException ignored) {
                            sb.append(p.getFileName()).append("|?\n");
                        }
                    });
            return sb.toString();
        } catch (IOException e) {
            return "?";
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
