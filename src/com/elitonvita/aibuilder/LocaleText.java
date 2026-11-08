package com.elitonvita.aibuilder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Complete messages, never word substitution. Player data is passed as opaque format arguments. */
final class LocaleText {
    private static final Pattern PARAMETER = Pattern.compile("\\{(\\d+)\\}");
    private static final Pattern COLOR = Pattern.compile("§[0-9a-fk-orA-FK-OR]");
    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    private static final Map<String, Map<String, String>> DEFAULTS = new HashMap<>();
    private static final Map<JavaPlugin, Map<String, Map<String, String>>> INSTALLED = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<String, String> SOURCES = new HashMap<>();
    private static final Map<String, Template> EXACT = new HashMap<>();
    private static final List<Template> PATTERNS = new ArrayList<>();
    private record Template(String key, String source, List<String> literals, List<Integer> arguments, int weight) {
        Map<Integer, String> match(String value) {
            if (!value.startsWith(literals.getFirst())) return null;
            int cursor = literals.getFirst().length();
            Map<Integer, String> result = new HashMap<>();
            for (int i = 0; i < arguments.size(); i++) {
                String next = literals.get(i + 1);
                int end = i == arguments.size() - 1 ? value.length() - next.length() : value.indexOf(next, cursor);
                if (end < cursor || !value.startsWith(next, end)) return null;
                String captured = value.substring(cursor, end);
                String previous = result.put(arguments.get(i), captured);
                if (previous != null && !previous.equals(captured)) return null;
                cursor = end + next.length();
            }
            return cursor == value.length() ? result : null;
        }
    }
    static {
        try {
            for (String lang : List.of("EN", "PT")) {
                try (var stream = resource("languages/" + lang.toLowerCase(Locale.ROOT) + ".yml")) {
                    YamlConfiguration yaml = new YamlConfiguration();
                    yaml.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
                    Map<String, String> values = new LinkedHashMap<>();
                    for (String key : yaml.getKeys(true)) if (yaml.isString(key)) values.put(key, yaml.getString(key));
                    DEFAULTS.put(lang, Map.copyOf(values));
                }
            }
            JsonObject sources;
            try (var stream = resource("languages/templates.json")) {
                sources = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            for (var entry : sources.entrySet()) {
                String source = entry.getValue().getAsJsonObject().get("source").getAsString();
                SOURCES.put(entry.getKey(), source);
                var literals = new ArrayList<String>(); var arguments = new ArrayList<Integer>();
                Matcher matcher = PARAMETER.matcher(source); int start = 0, weight = 0;
                while (matcher.find()) {
                    String literal = source.substring(start, matcher.start()); literals.add(literal); weight += literal.length();
                    arguments.add(Integer.parseInt(matcher.group(1))); start = matcher.end();
                }
                literals.add(source.substring(start)); weight += source.length() - start;
                Template template = new Template(entry.getKey(), source, List.copyOf(literals), List.copyOf(arguments), weight);
                if (arguments.isEmpty()) EXACT.put(source, template); else if (weight >= 8 && !source.matches("\\{\\d+\\} (?:zona|obra|projeto|chamar|terreno)(?: .*|$)")) PATTERNS.add(template);
            }
            PATTERNS.sort(Comparator.comparingInt(Template::weight).reversed());
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    private LocaleText() {}
    private static InputStream resource(String path) throws IOException {
        InputStream stream = LocaleText.class.getClassLoader().getResourceAsStream(path);
        if (stream == null) throw new FileNotFoundException("Missing language resource: " + path);
        return stream;
    }
    static String language(JavaPlugin plugin) {
        return plugin != null && "PT".equalsIgnoreCase(plugin.getConfig().getString("language", "EN")) ? "PT" : "EN";
    }
    static void forget(JavaPlugin plugin) { INSTALLED.remove(plugin); }
    static boolean english(JavaPlugin plugin) { return language(plugin).equals("EN"); }

    /** Load editable catalogs, adding only absent keys. Invalid files/values fall back without destroying edits. */
    static void reload(JavaPlugin plugin) {
        Map<String, Map<String, String>> loaded = new HashMap<>();
        for (String lang : List.of("EN", "PT")) {
            Map<String, String> defaults = DEFAULTS.get(lang), values = new HashMap<>(defaults);
            Path path = plugin.getDataFolder().toPath().resolve("languages/" + lang.toLowerCase(Locale.ROOT) + ".yml");
            try {
                Files.createDirectories(path.getParent());
                if (Files.isSymbolicLink(path) || Files.isSymbolicLink(path.getParent())) throw new IOException("Language path must not be a symbolic link");
                if (!Files.exists(path)) {
                    try (var stream = resource("languages/" + lang.toLowerCase(Locale.ROOT) + ".yml")) { Files.copy(stream, path); }
                }
                if (Files.size(path) > MAX_FILE_BYTES) throw new IOException("Language file is too large");
                YamlConfiguration yaml = new YamlConfiguration(); yaml.load(path.toFile());
                boolean changed = false;
                for (var entry : defaults.entrySet()) {
                    String key = entry.getKey(), custom = yaml.getString(key);
                    if (!yaml.contains(key)) { yaml.set(key, entry.getValue()); changed = true; }
                    else if (yaml.isString(key) && parameters(custom).equals(parameters(entry.getValue()))) values.put(key, custom);
                    else plugin.getLogger().warning("Invalid language placeholders/value: " + path.getFileName() + ":" + key);
                }
                if (changed) {
                    Path temporary = Files.createTempFile(path.getParent(), ".language-", ".yml");
                    try {
                        yaml.save(temporary.toFile());
                        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } finally { Files.deleteIfExists(temporary); }
                }
            } catch (Exception failure) {
                plugin.getLogger().warning("Could not load " + path.getFileName() + "; using bundled messages: " + failure.getMessage());
            }
            loaded.put(lang, Map.copyOf(values));
        }
        INSTALLED.put(plugin, Map.copyOf(loaded));
    }
    private static Set<String> parameters(String value) {
        Set<String> result = new HashSet<>(); Matcher matcher = PARAMETER.matcher(value);
        while (matcher.find()) result.add(matcher.group()); return result;
    }
    private static String value(JavaPlugin plugin, String key) {
        String lang = language(plugin);
        var catalogs = INSTALLED.get(plugin);
        return (catalogs == null ? DEFAULTS : catalogs).get(lang).getOrDefault(key, key);
    }
    static String raw(String key, Object... arguments) {
        Map<Integer, String> values = new HashMap<>();
        for (int i = 0; i < arguments.length; i++) values.put(i, String.valueOf(arguments[i]));
        return render(SOURCES.getOrDefault(key, key), values);
    }
    static String format(JavaPlugin plugin, String key, Object... arguments) {
        Map<Integer, String> values = new HashMap<>();
        for (int i = 0; i < arguments.length; i++) values.put(i, String.valueOf(arguments[i]));
        return render(value(plugin, key), values);
    }
    private static String render(String template, Map<Integer, String> arguments) {
        Matcher matcher = PARAMETER.matcher(template); StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(arguments.getOrDefault(Integer.parseInt(matcher.group(1)), matcher.group())));
        matcher.appendTail(out); return out.toString();
    }
    /** Boundary adapter for validator exceptions and older composed messages. Matches entire messages only. */
    static String text(JavaPlugin plugin, String input) { return translate(plugin, input, 0); }
    private static String translate(JavaPlugin plugin, String input, int depth) {
        if (input == null || input.isEmpty() || input.length() > 32768 || depth > 5) return input;
        String plain = COLOR.matcher(input).replaceAll("");
        String prefix = "";
        int index = 0;
        while (index + 1 < input.length() && input.charAt(index) == '§') index += 2;
        if (index > 0) prefix = input.substring(0, index);
        Template exact = EXACT.get(plain);
        if (exact != null) return prefix + value(plugin, exact.key());
        for (Template template : PATTERNS) {
            Map<Integer, String> matches = template.match(plain);
            if (matches != null) {
                // Nested values may be validator messages; static-key callers keep player arguments opaque.
                matches.replaceAll((ignored, captured) -> translate(plugin, captured, depth + 1));
                return prefix + render(value(plugin, template.key()), matches);
            }
        }
        return input;
    }
}
