package com.elitonvita.aibuilder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Gera prebuilts/preview/data.js: a mesma geometria que o construtor compila (BlueprintCompiler),
 * em forma de script para a pagina local index.html abrir por file:// sem servidor.
 */
public final class PrebuiltPreview {
    public static final String DATA_FILE = "data.js";
    private static final Gson GSON = new Gson();

    private PrebuiltPreview() {}

    public static String render(List<PrebuiltCatalog.Entry> builtIn, List<PrebuiltCatalog.Entry> user,
                                UserPrebuilts.Load load, int maxBlocks, long generatedAt) {
        return render(builtIn,user,load,ConstructionLimits.defaults(maxBlocks),generatedAt);
    }

    public static String render(List<PrebuiltCatalog.Entry> builtIn, List<PrebuiltCatalog.Entry> user,
                                UserPrebuilts.Load load, ConstructionLimits limits, long generatedAt) {
        JsonObject root = new JsonObject();
        root.addProperty("generated", generatedAt);
        root.addProperty("files", load == null ? 0 : load.files());
        JsonArray entries = new JsonArray();
        for (PrebuiltCatalog.Entry e : builtIn) entries.add(entry(e, "embutido", limits));
        for (PrebuiltCatalog.Entry e : user) entries.add(entry(e, "usuario", limits));
        root.add("entries", entries);
        JsonArray errors = new JsonArray();
        if (load != null) for (String error : load.errors()) errors.add(error);
        root.add("errors", errors);
        return "window.AIBUILDER_PREVIEW = " + GSON.toJson(root) + ";\n";
    }

    private static JsonObject entry(PrebuiltCatalog.Entry e, String source, ConstructionLimits limits) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("title", e.title());
        o.addProperty("category", e.category());
        o.addProperty("description", e.description());
        o.addProperty("source", source);
        JsonArray blocks = new JsonArray();
        var result = BlueprintCompiler.compile(e.blueprint(), 0, 0, 0, limits);
        if (result.isValid()) {
            o.addProperty("blockCount", result.blocks.size());
            o.addProperty("previewTruncated", result.blocks.size() > limits.previewBlocks());
            JsonObject dimensions = new JsonObject();
            dimensions.addProperty("width", result.blocks.stream().mapToInt(BlueprintCompiler.Block::x).max().orElse(0)-result.blocks.stream().mapToInt(BlueprintCompiler.Block::x).min().orElse(0)+1);
            dimensions.addProperty("depth", result.blocks.stream().mapToInt(BlueprintCompiler.Block::z).max().orElse(0)-result.blocks.stream().mapToInt(BlueprintCompiler.Block::z).min().orElse(0)+1);
            dimensions.addProperty("height", result.blocks.stream().mapToInt(BlueprintCompiler.Block::y).max().orElse(0)-result.blocks.stream().mapToInt(BlueprintCompiler.Block::y).min().orElse(0)+1);
            o.add("dimensions", dimensions);
            int step = Math.max(1,(int)Math.ceil((double)result.blocks.size()/limits.previewBlocks()));
            for (int i=0;i<result.blocks.size();i+=step) {
                BlueprintCompiler.Block b = result.blocks.get(i);
                JsonObject block = new JsonObject();
                block.addProperty("x", b.x());
                block.addProperty("y", b.y());
                block.addProperty("z", b.z());
                block.addProperty("material", b.material());
                if (b.data() != null) block.addProperty("data", b.data());
                blocks.add(block);
            }
        }
        o.add("blocks", blocks);
        return o;
    }

    /** Escreve prebuilts/preview/data.js (sobrescreve: e um arquivo gerado). */
    public static void write(Path previewDir, String script) throws IOException {
        Files.createDirectories(previewDir);
        Path target = previewDir.resolve(DATA_FILE);
        Path temp = previewDir.resolve(DATA_FILE + ".tmp");
        Files.writeString(temp, script, StandardCharsets.UTF_8);
        Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
