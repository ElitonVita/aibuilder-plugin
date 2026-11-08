package com.elitonvita.aibuilder;

import java.util.*;
import java.util.regex.*;

/** English public grammar. Translate grammar positions only; IDs, names and tokens remain untouched. */
final class BuilderCommands {
    private BuilderCommands() {}
    static final List<String> ROOT = List.of("menu", "spawn", "list", "select", "delete", "status", "help", "tool", "zone", "build", "yes", "no", "cancel", "stop", "stay", "follow", "call", "distance", "orientation", "project", "terrain", "mission", "prebuilt", "settings");
    private static final Set<String> NAMES = Set.of("alfred", "bruce", "clark", "diana", "tony");
    private static final Map<String, String> INTERNAL = Map.ofEntries(
        Map.entry("list","listar"),Map.entry("select","selecionar"),Map.entry("delete","deletar"),Map.entry("tool","ferramenta"),
        Map.entry("yes","sim"),Map.entry("confirm","sim"),Map.entry("no","nao"),Map.entry("zone","zona"),Map.entry("project","projeto"),
        Map.entry("mission","missao"),Map.entry("terrain","terreno"),Map.entry("clean","limpar"),Map.entry("level","nivelar"),
        Map.entry("inspect","analisar"),Map.entry("analyze","analisar"),Map.entry("cancel","cancelar"),Map.entry("continue","continuar"),
        Map.entry("undo","desfazer"),Map.entry("finish","end"),Map.entry("prebuilt","pre-moldado"),Map.entry("call","chamar"),
        Map.entry("distance","distancia"),Map.entry("orientation","orientacao"),Map.entry("build","obra"),Map.entry("show","mostrar"),
        Map.entry("clear","limpar"),Map.entry("save","salvar"),Map.entry("place","colocar"),Map.entry("parent","pais"),
        Map.entry("corner1","canto1"),Map.entry("corner2","canto2"),Map.entry("set1","pos1"),Map.entry("set2","pos2"),
        Map.entry("all","todos"),Map.entry("hours","horario"),Map.entry("limit","limite"),Map.entry("history","historico"));
    private static final Map<String, String> PUBLIC = new HashMap<>();
    static {
        INTERNAL.forEach((en, pt) -> PUBLIC.put(pt, en));
        PUBLIC.putAll(Map.ofEntries(Map.entry("limpar","clean"),Map.entry("analisar","inspect"),Map.entry("sim","yes"),Map.entry("end","finish"),
            Map.entry("seguir","follow"),Map.entry("ficar","stay"),Map.entry("teleportar","call"),Map.entry("parar","stop"),Map.entry("confirmar","yes"),
            Map.entry("recarregar","reload"),Map.entry("norte","north"),Map.entry("sul","south"),Map.entry("leste","east"),Map.entry("oeste","west")));
    }
    static String word(String word) { return PUBLIC.getOrDefault(word.toLowerCase(Locale.ROOT), word); }
    static String[] internal(String[] input) {
        String[] out = input.clone(); int start = input.length > 0 && NAMES.contains(input[0].toLowerCase(Locale.ROOT)) ? 1 : 0;
        if (start >= input.length) return out;
        String root = input[start].toLowerCase(Locale.ROOT);
        Set<String> supported = new HashSet<>(ROOT); supported.addAll(List.of("reload","parent","confirm","config","end"));
        if (!supported.contains(root)) return null;
        out[start] = INTERNAL.getOrDefault(root, root);
        if (root.equals("config")) out[start] = "settings";
        if (start + 1 >= input.length) return out;
        String next = input[start + 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (root) {
            case "zone" -> List.of("set1","set2","show","clear");
            case "terrain" -> List.of("inspect","analyze","clean","level","deep","cancel");
            case "project" -> List.of("corner1","corner2","save","list","place");
            case "build" -> List.of("continue","finish","end","undo","save","speed");
            case "parent" -> List.of("hours","limit","history");
            case "call" -> List.of("all");
            case "orientation" -> List.of("north","south","east","west","original");
            default -> List.of();
        };
        if (!options.isEmpty()) {
            if (!options.contains(next)) return null;
            out[start+1] = INTERNAL.getOrDefault(next, next);
        } else if (root.equals("prebuilt") && List.of("list","reload").contains(next)) out[start+1] = next.equals("reload") ? "recarregar" : "listar";
        if (root.equals("terrain")) {
            int action = start + 1;
            if (next.equals("deep")) {
                action++;
                if (action < input.length) {
                    String mode = input[action].toLowerCase(Locale.ROOT);
                    if (!List.of("clean","level").contains(mode)) return null;
                    out[action] = INTERNAL.get(mode);
                }
            }
            if (!next.equals("cancel") && action + 1 < input.length) {
                String confirm = input[action+1].toLowerCase(Locale.ROOT);
                if (!List.of("yes","select").contains(confirm)) return null;
                out[action+1] = INTERNAL.get(confirm);
            }
        }
        if (root.equals("prebuilt") && start + 2 < input.length && !List.of("north","south","east","west").contains(input[start+2].toLowerCase(Locale.ROOT))) return null;
        return out;
    }
    /** Canonicalize known legacy callbacks, not arbitrary argument values. */
    static String canonical(String command) {
        String[] args = command.split(" "); int i = 0;
        if (args.length == 0) return command;
        if (List.of("/construtor","construtor","/builder","builder","/aibuilder","aibuilder").contains(args[0])) {
            args[0] = args[0].startsWith("/") ? "/builder" : "builder"; i++;
        }
        if (i < args.length && NAMES.contains(args[i].toLowerCase(Locale.ROOT))) i++;
        if (i >= args.length) return String.join(" ", args);
        String root = word(args[i]); args[i++] = root;
        if (i < args.length) {
            if (List.of("zone","project","build","parent","call","orientation").contains(root)) args[i] = word(args[i]);
            if (root.equals("zone") && args[i].equals("clean")) args[i] = "clear";
            if (root.equals("prebuilt") && List.of("listar","recarregar").contains(args[i])) args[i] = word(args[i]);
            if (root.equals("prebuilt") && i + 1 < args.length) args[i+1] = word(args[i+1]);
            if (root.equals("terrain")) {
                args[i] = word(args[i]); if (args[i].equals("deep")) i++;
                if (i < args.length) args[i] = word(args[i]);
                if (i + 1 < args.length) args[i+1] = word(args[i+1]);
            }
        }
        return String.join(" ", args);
    }
    private static final Pattern LEGACY_COMMAND = Pattern.compile("/(?:construtor|builder)(?: [\\p{L}0-9_-]+)*");
    /** Used only at UI boundaries to keep commands English inside translated prose. */
    static String inMessage(String message) {
        Matcher matcher = LEGACY_COMMAND.matcher(message);StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(canonical(matcher.group())));
        matcher.appendTail(out);return out.toString();
    }
}
