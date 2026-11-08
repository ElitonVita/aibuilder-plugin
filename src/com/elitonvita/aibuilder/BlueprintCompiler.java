package com.elitonvita.aibuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compila uma planta (blueprint JSON) em uma lista ordenada de blocos absolutos.
 * Sem dependencia do Bukkit: pode ser testado fora do servidor.
 *
 * Tipos de parte: box, walls (retangulo fechado), wall (parede reta), roof_steps (degraus).
 * Coordenadas relativas a origem; "to" e inclusivo; y = altura (0 = camada da fundacao).
 */
public final class BlueprintCompiler {

    public record Block(int x, int y, int z, String material, int phase, String data) {
        public Block(int x, int y, int z, String material, int phase) { this(x, y, z, material, phase, null); }
    }

    public static final class Result {
        public final List<Block> blocks = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public final List<String> errors = new ArrayList<>();
        public boolean isValid() { return errors.isEmpty(); }
    }

    private static final class Cell {
        final String material;
        final int phase;
        final String data;
        Cell(String material, int phase, String data) { this.material = material; this.phase = phase; this.data = data; }
    }

    private BlueprintCompiler() {}

    public static Result compile(JsonObject bp, int ox, int oy, int oz) {
        return compile(bp, ox, oy, oz, 2500);
    }

    /** Invalid plans fail atomically. The visit budget also bounds overlapping parts and openings. */
    public static Result compile(JsonObject bp, int ox, int oy, int oz, int maxBlocks) {
        if(maxBlocks < 1) {
            Result invalid = new Result();invalid.errors.add("planta invalida: limite de blocos invalido");
            invalid.warnings.addAll(invalid.errors);return invalid;
        }
        return compile(bp, ox, oy, oz, ConstructionLimits.defaults(maxBlocks));
    }

    public static Result compile(JsonObject bp, int ox, int oy, int oz, ConstructionLimits limits) {
        int maxBlocks = limits.blocks();
        Result r = new Result();
        Map<String, Cell> cells = new LinkedHashMap<>() {
            @Override public Cell put(String key, Cell value) {
                require(containsKey(key) || size() < maxBlocks, "limite de blocos excedido");
                return super.put(key, value);
            }
        };
        try {
            require(maxBlocks > 0, "limite de blocos invalido");
            require(bp != null && bp.has("parts") && bp.get("parts").isJsonArray(), "parts precisa ser uma lista");
            JsonArray parts = bp.getAsJsonArray("parts");
            require(parts.size() > 0 && parts.size() <= limits.parts(), "parts deve conter entre 1 e " + limits.parts() + " partes");
            parts = normalizeVertical(parts);
            long remaining = limits.visits();
            // Validate every part before expanding any cell.
            for (JsonElement el : parts) {
                require(el.isJsonObject(), "parte precisa ser objeto");
                remaining -= validate(el.getAsJsonObject(), ox, oy, oz, limits);
                require(remaining >= 0, "orcamento de processamento excedido");
            }
            int phase = 0;
            for (JsonElement el : parts) {
                JsonObject p = el.getAsJsonObject();
                switch (str(p, "type", "")) {
                    case "box" -> box(p, ox, oy, oz, phase, cells);
                    case "walls" -> walls(p, ox, oy, oz, phase, cells);
                    case "wall" -> straightWall(p, ox, oy, oz, phase, cells);
                    case "roof_steps" -> roofSteps(p, ox, oy, oz, phase, cells);
                    default -> throw new IllegalArgumentException("tipo desconhecido");
                }
                require(cells.size() <= maxBlocks, "limite de blocos excedido");
                phase++;
            }
            for (Map.Entry<String, Cell> e : cells.entrySet()) {
                String[] c = e.getKey().split(",");
                r.blocks.add(new Block(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2]),
                        e.getValue().material, e.getValue().phase, e.getValue().data));
            }
            int rotation = bp.has("_rotation") ? integer(bp.get("_rotation")) : 0;
            require(rotation >= 0 && rotation <= 3, "rotacao deve ser 0..3");
            if (rotation != 0) {
                var rotated = BlueprintOrientation.rotate(r.blocks, rotation);
                r.blocks.clear(); r.blocks.addAll(rotated);
            }
            r.blocks.sort(Comparator.comparingInt(Block::phase).thenComparingInt(Block::y)
                    .thenComparingInt(Block::x).thenComparingInt(Block::z));
            require(!r.blocks.isEmpty(), "a planta nao gerou nenhum bloco");
        } catch (RuntimeException e) {
            r.blocks.clear();
            String error = "planta invalida: " + e.getMessage();
            r.errors.add(error);
            r.warnings.add(error);
        }
        return r;
    }

    /**
     * walls, wall e roof_steps tomam a altura de height/layers; o "to" so define o plano horizontal.
     * O modelo costuma repetir o y de "to" com outro valor: usamos o y de "from" e nao rejeitamos a planta.
     */
    private static JsonArray normalizeVertical(JsonArray parts) {
        JsonArray out = new JsonArray();
        for (JsonElement el : parts) {
            if (!el.isJsonObject()) { out.add(el); continue; }
            JsonObject p = el.getAsJsonObject().deepCopy();
            String type = str(p, "type", "");
            boolean vertical = type.equals("walls") || type.equals("wall") || type.equals("roof_steps");
            if (vertical && p.has("from") && p.has("to")
                    && p.get("from").isJsonArray() && p.get("from").getAsJsonArray().size() == 3
                    && p.get("to").isJsonArray() && p.get("to").getAsJsonArray().size() == 3) {
                p.getAsJsonArray("to").set(1, p.getAsJsonArray("from").get(1));
            }
            out.add(p);
        }
        return out;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    private static long validate(JsonObject p, int ox, int oy, int oz, ConstructionLimits limits) {
        int[] a = vec(p, "from"), b = vec(p, "to");
        for (int i = 0; i < 3; i++) {
            require(a[i] <= b[i], "from deve ser menor ou igual a to");
        }
        String material = str(p, "material", null);
        require(material != null && material.matches("[A-Za-z0-9_:]{1,128}"), "material invalido");
        if (p.has("data")) {
            String data = str(p, "data", "");
            require(data.length() <= 1024 && data.matches("(?:minecraft:)?[a-z0-9_]+(?:\\[[a-z0-9_=,]+\\])?"), "BlockData invalido");
            String typeName = data.split("\\[", 2)[0].replace("minecraft:", "");
            require(typeName.equals(material.toLowerCase(java.util.Locale.ROOT).replace("minecraft:", "")), "BlockData nao corresponde ao material");
        }
        String type = str(p, "type", "");
        long dx = (long) b[0] - a[0] + 1, dz = (long) b[2] - a[2] + 1;
        long height = (long) b[1] - a[1] + 1;
        long visits;
        switch (type) {
            case "box" -> visits = Math.multiplyExact(Math.multiplyExact(dx, dz), height);
            case "walls", "wall" -> {
                height = integer(p.get("height"));
                require(height > 0, "height fora do limite");
                require(a[1] == b[1], "paredes precisam de from/to na mesma altura");
                require(!type.equals("wall") || a[0] == b[0] || a[2] == b[2], "parede nao e reta");
                visits = Math.multiplyExact(Math.multiplyExact(dx, dz), height);
            }
            case "roof_steps" -> {
                height = p.has("layers") ? integer(p.get("layers")) : 1;
                require(height > 0 && height <= (Math.min(dx, dz) + 1) / 2, "layers fora do limite");
                require(a[1] == b[1], "telhado precisa de from/to na mesma altura");
                visits = Math.multiplyExact(Math.multiplyExact(dx, dz), height);
            }
            default -> throw new IllegalArgumentException("tipo desconhecido: " + type);
        }
        long top = (long) a[1] + height - 1;
        for (long value : new long[] { (long) ox + a[0], (long) ox + b[0], (long) oz + a[2],
                (long) oz + b[2], (long) oy + a[1], (long) oy + top }) {
            require(value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE, "origem causa overflow");
        }
        require(visits <= limits.visits(), "orcamento de processamento excedido");
        if (p.has("openings")) {
            require(p.get("openings").isJsonArray(), "openings precisa ser lista");
            JsonArray openings = p.getAsJsonArray("openings");
            require(openings.size() <= limits.openings(), "muitas aberturas");
            for (JsonElement el : openings) {
                require(el.isJsonObject(), "abertura precisa ser objeto");
                JsonObject o = el.getAsJsonObject();
                int at = integer(o.get("at")), width = integer(o.get("width")), h = integer(o.get("height"));
                int y = o.has("y") ? integer(o.get("y")) : 0;
                require(at >= 0 && width > 0 && h > 0 && y >= 0, "dimensoes da abertura invalidas");
                long length = Math.max(dx, dz);
                if (type.equals("walls")) {
                    String side = str(o, "side", "");
                    require(List.of("north", "south", "east", "west").contains(side), "lado da abertura invalido");
                    length = side.equals("north") || side.equals("south") ? dx : dz;
                }
                require((long) at + width <= length && (long) y + h <= height, "abertura fora da parede");
                require(type.equals("walls") || type.equals("wall"), "aberturas somente em paredes");
            }
            visits = Math.multiplyExact(visits, Math.max(1, openings.size()));
        }
        return visits;
    }

    private static int integer(JsonElement value) {
        require(value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber(), "numero inteiro esperado");
        try {
            return value.getAsBigDecimal().intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("numero inteiro fora do limite");
        }
    }

    // ---------- partes ----------

    private static void box(JsonObject p, int ox, int oy, int oz, int phase, Map<String, Cell> cells) {
        int[] a = vec(p, "from"), b = vec(p, "to");
        String mat = str(p, "material", null);
        for (int y = a[1]; y <= b[1]; y++)
            for (int x = a[0]; x <= b[0]; x++)
                for (int z = a[2]; z <= b[2]; z++)
                    put(cells, ox + x, oy + y, oz + z, mat, phase, str(p, "data", null));
    }

    private static void walls(JsonObject p, int ox, int oy, int oz, int phase, Map<String, Cell> cells) {
        int[] a = vec(p, "from"), b = vec(p, "to");
        int h = integer(p.get("height"));
        String mat = str(p, "material", null);
        List<JsonObject> openings = openings(p);
        for (int dy = 0; dy < h; dy++) {
            int y = a[1] + dy;
            for (int x = a[0]; x <= b[0]; x++)
                for (int z = a[2]; z <= b[2]; z++) {
                    boolean edge = x == a[0] || x == b[0] || z == a[2] || z == b[2];
                    if (!edge) continue;
                    if (inOpening(openings, x, z, y, a, b)) remove(cells, ox + x, oy + y, oz + z);
                    else put(cells, ox + x, oy + y, oz + z, mat, phase, str(p, "data", null));
                }
        }
    }

    private static boolean inOpening(List<JsonObject> openings, int x, int z, int y, int[] a, int[] b) {
        for (JsonObject o : openings) {
            String side = str(o, "side", "");
            int at = integer(o.get("at")), w = integer(o.get("width")), h = integer(o.get("height"));
            int oyOff = o.has("y") ? integer(o.get("y")) : 0;
            if (y < a[1] + oyOff || y >= a[1] + oyOff + h) continue;
            switch (side) {
                case "north" -> { if (z == a[2] && x >= a[0] + at && x < a[0] + at + w) return true; }
                case "south" -> { if (z == b[2] && x >= a[0] + at && x < a[0] + at + w) return true; }
                case "west" -> { if (x == a[0] && z >= a[2] + at && z < a[2] + at + w) return true; }
                case "east" -> { if (x == b[0] && z >= a[2] + at && z < a[2] + at + w) return true; }
                default -> { }
            }
        }
        return false;
    }

    private static void straightWall(JsonObject p, int ox, int oy, int oz, int phase, Map<String, Cell> cells) {
        int[] a = vec(p, "from"), b = vec(p, "to");
        if (a[0] != b[0] && a[2] != b[2]) throw new IllegalArgumentException("parede nao e reta");
        int h = integer(p.get("height"));
        String mat = str(p, "material", null);
        List<JsonObject> openings = openings(p);
        boolean alongX = a[2] == b[2];
        int len = alongX ? Math.abs(b[0] - a[0]) + 1 : Math.abs(b[2] - a[2]) + 1;
        for (int i = 0; i < len; i++) {
            int x = alongX ? Math.min(a[0], b[0]) + i : a[0];
            int z = alongX ? a[2] : Math.min(a[2], b[2]) + i;
            for (int dy = 0; dy < h; dy++) {
                int y = a[1] + dy;
                boolean open = false;
                for (JsonObject o : openings) {
                    int at = integer(o.get("at")), w = integer(o.get("width")), oh = integer(o.get("height"));
                    int off = o.has("y") ? integer(o.get("y")) : 0;
                    if (i >= at && i < at + w && dy >= off && dy < off + oh) { open = true; break; }
                }
                if (open) remove(cells, ox + x, oy + y, oz + z);
                else put(cells, ox + x, oy + y, oz + z, mat, phase, str(p, "data", null));
            }
        }
    }

    private static void roofSteps(JsonObject p, int ox, int oy, int oz, int phase, Map<String, Cell> cells) {
        int[] a = vec(p, "from"), b = vec(p, "to");
        int layers = p.has("layers") ? integer(p.get("layers")) : 1;
        String mat = str(p, "material", null);
        for (int k = 0; k < layers; k++)
            for (int x = a[0] + k; x <= b[0] - k; x++)
                for (int z = a[2] + k; z <= b[2] - k; z++)
                    put(cells, ox + x, oy + a[1] + k, oz + z, mat, phase, str(p, "data", null));
    }

    // ---------- helpers ----------

    private static void put(Map<String, Cell> cells, int x, int y, int z, String mat, int phase, String data) {
        cells.put(x + "," + y + "," + z, new Cell(mat, phase, data));
    }

    private static void remove(Map<String, Cell> cells, int x, int y, int z) {
        cells.remove(x + "," + y + "," + z);
    }

    private static List<JsonObject> openings(JsonObject p) {
        List<JsonObject> out = new ArrayList<>();
        if (p.has("openings") && p.get("openings").isJsonArray()) {
            for (JsonElement el : p.getAsJsonArray("openings")) if (el.isJsonObject()) out.add(el.getAsJsonObject());
        }
        return out;
    }

    private static int[] vec(JsonObject p, String key) {
        JsonArray arr = p.getAsJsonArray(key);
        if (arr == null || arr.size() != 3) throw new IllegalArgumentException("'" + key + "' precisa de 3 numeros");
        return new int[] { integer(arr.get(0)), integer(arr.get(1)), integer(arr.get(2)) };
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
