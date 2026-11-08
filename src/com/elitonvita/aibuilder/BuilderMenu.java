package com.elitonvita.aibuilder;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Menu de /construtor (sem argumentos). Cada botao executa o mesmo comando de texto que o jogador
 * digitaria, entao permissoes, confirmacoes e mensagens continuam vindo do mesmo lugar.
 */
final class BuilderMenu implements InventoryHolder {
    enum Page { MAIN, LIST, BUILDER, BUILD, DELETE_CONFIRM, PREBUILT, CONFIG, UNFINISHED, TERRAIN, DEEP_TERRAIN, UNFINISHED_DELETE_CONFIRM }

    static final int ZONE_HEIGHT_MIN = 10;
    static final int PREBUILTS_PER_PAGE = 7;

    private final AIBuilderPlugin plugin;
    private final Page page;
    private final String builderName;
    private final int prebuiltPage;
    private final Map<Integer, Consumer<Player>> actions = new HashMap<>();
    private Inventory inventory;

    private BuilderMenu(AIBuilderPlugin plugin, Page page, String builderName, int prebuiltPage) {
        this.plugin = plugin; this.page = page; this.builderName = builderName;
        this.prebuiltPage = Math.max(0, prebuiltPage);
    }

    @Override public Inventory getInventory() { return inventory; }

    /** Abre (ou troca) a pagina do menu para o jogador. */
    static void open(AIBuilderPlugin plugin, Player player, Page page, String builderName) {
        open(plugin, player, page, builderName, 0);
    }

    private static void open(AIBuilderPlugin plugin, Player player, Page page, String builderName, int prebuiltPage) {
        BuilderMenu menu = new BuilderMenu(plugin, page, builderName, prebuiltPage);
        menu.inventory = Bukkit.createInventory(menu, 54, "§6" + LocaleText.text(plugin, "Builder") + " §8· " + LocaleText.text(plugin, menu.title()));
        menu.build(player);
        player.openInventory(menu.inventory);
    }

    /** Chamado pelo listener de clique. Slots sem acao nao fazem nada. */
    void click(Player player, int slot) {
        Consumer<Player> action = actions.get(slot);
        if (action != null) action.accept(player);
    }

    private String title() {
        return switch (page) {
            case MAIN -> "Menu";
            case LIST -> LocaleText.format(plugin, "message_01323a8de0f3");
            case BUILDER -> builderName;
            case BUILD -> LocaleText.format(plugin, "message_bdd254b65baa");
            case DELETE_CONFIRM -> LocaleText.format(plugin, "message_1e72120bb0e5", builderName);
            case PREBUILT -> LocaleText.format(plugin, "message_29ffffbf168e", builderName);
            case CONFIG -> "Configuracoes";
            case UNFINISHED -> LocaleText.format(plugin, "message_041c147b4415");
            case UNFINISHED_DELETE_CONFIRM -> LocaleText.format(plugin, "message_1b9d1141d5f3");
            case TERRAIN -> LocaleText.format(plugin, "message_10fa61830d9b");
            case DEEP_TERRAIN -> LocaleText.format(plugin, "message_e89ad063cffd");
        };
    }

    private void build(Player player) {
        switch (page) {
            case MAIN -> buildMain(player);
            case LIST -> buildList(player);
            case BUILDER -> buildBuilder(player);
            case BUILD -> buildBuild(player);
            case DELETE_CONFIRM -> buildDeleteConfirm();
            case PREBUILT -> buildPrebuilt();
            case CONFIG -> buildConfig(player);
            case UNFINISHED -> buildUnfinished(player);
            case UNFINISHED_DELETE_CONFIRM -> buildUnfinishedDeleteConfirm();
            case TERRAIN -> buildTerrain(player);
            case DEEP_TERRAIN -> buildDeepTerrain(player);
        }
        for (int i = 0; i < inventory.getSize(); i++) {
            if (inventory.getItem(i) == null) put(i, Material.GRAY_STAINED_GLASS_PANE, " ", List.of(), null);
        }
    }

    // ---------- paginas ----------

    private void buildMain(Player player) {
        int count = plugin.builders.size();
        put(4, Material.NETHER_STAR, "§6AIBuilder", List.of(
                "§7" + LocaleText.format(plugin, "message_1d9a862b2666", count, plugin.builderLimit()),
                "§7" + LocaleText.format(plugin, "message_51d4ae646089")), null);
        put(10, Material.EMERALD, "§a" + LocaleText.format(plugin, "message_e8e2b5db3034"),
                List.of("§7" + LocaleText.format(plugin, "message_501ba7027540"), "§7" + LocaleText.format(plugin, "message_2f040aacc21e")),
                run("spawn"));
        put(12, Material.BOOK, "§e" + LocaleText.format(plugin, "message_01323a8de0f3"),
                List.of("§7" + LocaleText.format(plugin, "message_dcd7e75bcf64")), go(Page.LIST, null));
        BuilderSession selected = plugin.selectedFor(player);
        put(14, Material.BRICKS, "§6Pre-moldados",
                List.of("§7" + LocaleText.format(plugin, "message_2de06f249866"), selected == null ? "§c" + LocaleText.format(plugin, "message_a82ebb2f4199")
                        : "§7" + LocaleText.format(plugin, "message_208f75e79a8e", selected.name)),
                selected == null ? null : go(Page.PREBUILT, selected.name));
        put(16, Material.COMPARATOR, "§bConfiguracoes", List.of("§7" + LocaleText.format(plugin, "message_8fd2a39f0d80")), go(Page.CONFIG, null));
        put(20, Material.DIAMOND_SHOVEL, "§bFerramenta",
                List.of("§7" + LocaleText.format(plugin, "message_df075f62f3e8")), run("ferramenta"));
        put(22, Material.ENCHANTED_BOOK, "§dAjuda", List.of("§7" + LocaleText.format(plugin, "message_4e196fa0a578")), run("help"));
        put(24, Material.PAPER, "§f" + LocaleText.format(plugin, "message_b59935538173"), List.of("§7" + LocaleText.format(plugin, "message_aee7de92e96b")), run("listar"));
        put(31, Material.ENDER_PEARL, "§b" + LocaleText.format(plugin, "message_10207c6ffd7f"),
                List.of("§7" + LocaleText.format(plugin, "message_ac33033b5e92"), "§7" + LocaleText.format(plugin, "message_03a72cb854a5")), run("chamar todos"));
        put(33, Material.WRITABLE_BOOK, "§6" + LocaleText.format(plugin, "message_041c147b4415"),
                List.of("§7" + LocaleText.format(plugin, "message_4d80b4cb2708")), go(Page.UNFINISHED, null));
        put(49, Material.BARRIER, "§cFechar", List.of(), p -> p.closeInventory());
    }

    /** Obras inacabadas do criador; no submenu Build, a retomada usa o construtor selecionado. */
    private void buildUnfinished(Player player) {
        List<JsonObject> rows;
        try {
            rows = BuilderSession.unfinishedFor(plugin, player.getUniqueId());
        } catch (IOException e) {
            put(22, Material.BARRIER, "§c" + LocaleText.format(plugin, "message_d8234e0b1889"), List.of("§7" + LocaleText.format(plugin, "message_e6404974d0c8")), null);
            put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.MAIN, null));
            return;
        }
        rows = rows.stream().sorted(java.util.Comparator.comparing(
                row -> row.get("name").getAsString(), String.CASE_INSENSITIVE_ORDER)).toList();
        int pageCount = Math.max(1, (rows.size() + 33) / 34);
        int currentPage = Math.min(prebuiltPage, pageCount - 1);
        int start = currentPage * 34;
        int end = Math.min(rows.size(), start + 34);
        if (rows.isEmpty()) put(22, Material.PAPER, "§7" + LocaleText.format(plugin, "message_fcc959e504cb"), List.of(), null);
        int slot = 10;
        for (JsonObject row : rows.subList(start, end)) {
            String name = row.get("name").getAsString();
            String command = builderName == null ? "build continue " + name
                    : builderName.toLowerCase(Locale.ROOT) + " build continue " + name;
            put(slot++, Material.WRITABLE_BOOK, "§6" + name,
                    List.of("§7" + LocaleText.format(plugin, "message_7670d3dd0d84", row.get("status").getAsString()),
                            "§7" + LocaleText.format(plugin, "message_d501fcc130e7", row.get("blocks").getAsInt()),
                            "§e" + LocaleText.format(plugin, "message_7a9771653094")),
                    run(command));
        }
        put(45, Material.ARROW, "§e" + LocaleText.format(plugin, "message_6829eba10490"), List.of(), currentPage > 0
                ? p -> open(plugin, p, Page.UNFINISHED, builderName, currentPage - 1) : null);
        put(46, Material.PAPER, "§f" + LocaleText.format(plugin, "message_e6e6311ccd09", currentPage + 1, pageCount), List.of(), null);
        put(53, Material.ARROW, "§e" + LocaleText.format(plugin, "message_84c6e3cecde4"), List.of(), currentPage + 1 < pageCount
                ? p -> open(plugin, p, Page.UNFINISHED, builderName, currentPage + 1) : null);
        put(47, Material.RED_DYE, "§c" + LocaleText.format(plugin, "message_bd18cdfadf11"),
                List.of("§7" + LocaleText.format(plugin, "message_70c0fd02bab8"), "§7" + LocaleText.format(plugin, "message_dfa1e13b6635")), go(Page.UNFINISHED_DELETE_CONFIRM, null));
        put(49, Material.ARROW, "§7Voltar", List.of(), builderName == null ? go(Page.MAIN, null) : go(Page.BUILD, builderName));
    }

    private void buildUnfinishedDeleteConfirm() {
        put(13, Material.RED_CONCRETE, "§c" + LocaleText.format(plugin, "message_029a9cc80ded"),
                List.of("§7" + LocaleText.format(plugin, "message_4d282f6c385c"), "§7" + LocaleText.format(plugin, "message_850acd591f64")), p -> {
                    try {
                        int count = BuilderSession.deleteUnfinishedFor(plugin, p.getUniqueId());
                        p.sendMessage(LocaleText.text(plugin, "§6" + LocaleText.format(plugin, "message_c704be6af1c7", count)));
                    } catch (IOException error) {
                        p.sendMessage(LocaleText.text(plugin, "§6" + LocaleText.format(plugin, "message_6a0b0f6099a3", LocaleText.text(plugin, error.getMessage()))));
                    }
                    open(plugin, p, Page.UNFINISHED, null);
                });
        put(15, Material.LIME_CONCRETE, "§aCancelar", List.of(), go(Page.UNFINISHED, null));
    }

    private void buildList(Player player) {
        List<BuilderSession> mine = plugin.builders.values().stream()
                .filter(b -> plugin.controls(player, b))
                .sorted(java.util.Comparator.comparingInt(b -> AIBuilderPlugin.NAMES.indexOf(b.name))).toList();
        int slot = 10;
        for (BuilderSession b : mine) {
            List<String> lore = new ArrayList<>(b.statusLines());
            lore.add("§e" + LocaleText.format(plugin, "message_f542ab53856f"));
            put(slot, Material.PLAYER_HEAD, "§6" + b.name, lore, go(Page.BUILDER, b.name));
            slot += 2;
        }
        if (mine.isEmpty()) put(22, Material.PAPER, "§7" + LocaleText.format(plugin, "message_0746c55c734f"),
                List.of("§7" + LocaleText.format(plugin, "message_868e589ab4f1")), null);
        put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.MAIN, null));
    }

    private void buildBuilder(Player player) {
        BuilderSession b = plugin.named(builderName);
        if (b == null || !plugin.controls(player, b)) {
            put(22, Material.BARRIER, "§c" + LocaleText.format(plugin, "message_95abd629b531"), List.of("§7" + LocaleText.format(plugin, "message_aad3f626dcd7")), null);
            put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.LIST, null));
            return;
        }
        String cmd = b.name.toLowerCase(Locale.ROOT);
        put(4, Material.PLAYER_HEAD, "§6" + b.name, b.statusLines(), null);
        put(10, Material.BOOK, "§e" + LocaleText.format(plugin, "message_a287257d42a9"), List.of("§7" + LocaleText.format(plugin, "message_52bdef9b8053")), run(cmd + " status"));
        put(12, Material.LIME_DYE, "§aSeguir-me",
                List.of(b.followsOwner() ? "§7" + LocaleText.format(plugin, "message_f4dbc2134aaa") : "§7" + LocaleText.format(plugin, "message_e7c51791f8c1")), run(cmd + " follow"));
        put(13, Material.GRAY_DYE, "§7" + LocaleText.format(plugin, "message_0abdc157492a"),
                List.of(b.followsOwner() ? "§7" + LocaleText.format(plugin, "message_cb176d726a73") : "§7" + LocaleText.format(plugin, "message_48cef1ee8785")), run(cmd + " stay"));
        put(14, Material.RED_DYE, "§c" + LocaleText.format(plugin, "message_7b048ede6e65"), List.of("§7" + LocaleText.format(plugin, "message_9e78bce72a42")), run(cmd + " stop"));
        if (b.hasPendingPlan()) {
            put(16, Material.LIME_CONCRETE, "§a" + LocaleText.format(plugin, "message_bea688a1e75d"), List.of("§7" + LocaleText.format(plugin, "message_47ddc1d59cad")), run(cmd + " sim"));
            put(17, Material.RED_CONCRETE, "§c" + LocaleText.format(plugin, "message_65f4c93832f7"), List.of("§7" + LocaleText.format(plugin, "message_2c333e5fa7b3")), run(cmd + " nao"));
        } else {
            put(16, Material.GRAY_CONCRETE, "§7" + LocaleText.format(plugin, "message_0a58ffc0483e"), List.of("§7" + LocaleText.format(plugin, "message_e82b7855c1fb")), null);
        }
        put(19, Material.DIAMOND_SHOVEL, "§bFerramenta", List.of("§7" + LocaleText.format(plugin, "message_8e199c286616")), run(cmd + " ferramenta"));
        put(20, Material.MAP, "§b" + LocaleText.format(plugin, "message_84e7ea6e8658"), List.of("§7" + LocaleText.format(plugin, "message_4d2c99500c41")), run(cmd + " zona mostrar"));
        put(21, Material.BUCKET, "§b" + LocaleText.format(plugin, "message_afdd2d100f61"), List.of("§7" + LocaleText.format(plugin, "message_b9d1a33b81e6")), run(cmd + " zona limpar"));
        put(22, Material.GRASS_BLOCK, "§a" + LocaleText.format(plugin, "message_10fa61830d9b"), List.of("§7" + LocaleText.format(plugin, "message_ac5e84e2ce40")), go(Page.TERRAIN, b.name));
        put(23, Material.RED_DYE, "§c" + LocaleText.format(plugin, "message_a8e0e72e3a60"), List.of("§7" + LocaleText.format(plugin, "message_43c02d6b9218"), "§7" + LocaleText.format(plugin, "message_ce92181948df")), run(cmd + " obra desfazer"));
        put(24, Material.BOOK, "§e" + LocaleText.format(plugin, "message_e3a7704a3a0e"), List.of("§7" + LocaleText.format(plugin, "message_47258786cd05")), go(Page.BUILD, b.name));
        put(25, Material.WRITABLE_BOOK, "§e" + LocaleText.format(plugin, "message_a2f488b50681"), List.of("§7" + LocaleText.format(plugin, "message_5bd6d4929f9e")), go(Page.UNFINISHED, b.name));
        put(26, Material.CHISELED_BOOKSHELF, "§e" + LocaleText.format(plugin, "message_bff80357081f"), List.of("§7" + LocaleText.format(plugin, "message_de71c45d7b75")), run(cmd + " obra end"));
        put(28, Material.BRICKS, "§6Pre-moldados", List.of("§7" + LocaleText.format(plugin, "message_2de06f249866")), go(Page.PREBUILT, b.name));
        put(29, Material.FILLED_MAP, "§7Missao", List.of("§7" + LocaleText.format(plugin, "message_05acf178a930")), run(cmd + " missao"));
        put(30, Material.CHEST, "§7Projetos", List.of("§7" + LocaleText.format(plugin, "message_b28a9f98563d")), run(cmd + " projeto listar"));
        put(32, Material.ENDER_PEARL, "§b" + LocaleText.format(plugin, "message_218795fdb594"), List.of("§7" + LocaleText.format(plugin, "message_d34115be81cc"),
                "§7" + LocaleText.format(plugin, "message_49ba7baea85b")), run(cmd + " chamar"));
        put(33, Material.LEAD, "§b" + LocaleText.format(plugin, "message_c436700df693", b.travelRadius()),
                List.of("§7" + LocaleText.format(plugin, "message_674f8c14eaa1"), "§7" + LocaleText.format(plugin, "message_1d141f1faf14"),
                        "§7" + LocaleText.format(plugin, "message_7abd7d6be328", cmd)), p -> {
                    int next = b.travelRadius() < 8 ? 8 : b.travelRadius() < 16 ? 16 : b.travelRadius() < 32 ? 32 : b.travelRadius() < 64 ? 64 : 4;
                    p.performCommand("builder " + cmd + " distance " + next);
                    open(plugin, p, Page.BUILDER, b.name);
                });
        put(40, Material.BARRIER, "§4" + LocaleText.format(plugin, "message_c0904c2003ab"), List.of("§7" + LocaleText.format(plugin, "message_48726a9850f9")),
                go(Page.DELETE_CONFIRM, b.name));
        put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.LIST, null));
    }

    private void buildBuild(Player player) {
        BuilderSession b = plugin.named(builderName);
        if (b == null || !plugin.controls(player, b)) {
            put(22, Material.BARRIER, "§c" + LocaleText.format(plugin, "message_95abd629b531"), List.of(), null);
            put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.BUILDER, builderName));
            return;
        }
        String cmd = b.name.toLowerCase(Locale.ROOT);
        put(10, Material.MAP, "§e" + LocaleText.format(plugin, "message_a287257d42a9"),
                List.of("§7" + LocaleText.format(plugin, "message_52bdef9b8053")), run(cmd + " build"));
        put(12, Material.WRITABLE_BOOK, "§6" + LocaleText.format(plugin, "message_1509f561f241"),
                List.of("§7" + LocaleText.format(plugin, "message_dc9e0088d799")), run(cmd + " build save"));
        put(14, Material.LIME_DYE, "§a" + LocaleText.format(plugin, "message_a2f488b50681"),
                List.of("§7" + LocaleText.format(plugin, "message_5bd6d4929f9e")), go(Page.UNFINISHED, b.name));
        put(16, Material.RED_DYE, "§c" + LocaleText.format(plugin, "message_a8e0e72e3a60"),
                List.of("§7" + LocaleText.format(plugin, "message_43c02d6b9218")), run(cmd + " build undo"));
        put(31, Material.CHISELED_BOOKSHELF, "§e" + LocaleText.format(plugin, "message_bff80357081f"),
                List.of("§7" + LocaleText.format(plugin, "message_de71c45d7b75")), run(cmd + " build finish"));
        if (player.isOp()) {
            String state = LocaleText.format(plugin, b.quickBuildMode() ? "message_fb9cf75606b4" : "message_17eb3c0168d0");
            put(20, Material.FIREWORK_ROCKET, "§c" + LocaleText.format(plugin, "message_e45b8d1a844f", state),
                    List.of("§7" + LocaleText.format(plugin, "message_5b9c3d85239e", b.quickBuildLimit())), p -> {
                        p.closeInventory();
                        p.performCommand("builder " + cmd + " build speed");
                        open(plugin, p, Page.BUILD, b.name);
                    });
        }
        put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.BUILDER, b.name));
    }

    private void buildDeleteConfirm() {
        String cmd = builderName.toLowerCase(Locale.ROOT);
        put(13, Material.RED_CONCRETE, "§4" + LocaleText.format(plugin, "message_4ce3eefc1a70", builderName),
                List.of("§7" + LocaleText.format(plugin, "message_1ed3e458e8c1"), "§7" + LocaleText.format(plugin, "message_62e6a4de4df7")),
                run(cmd + " delete"));
        put(15, Material.LIME_CONCRETE, "§aCancelar", List.of(), go(Page.BUILDER, builderName));
    }

    private void buildPrebuilt() {
        BuilderSession selectedBuilder = plugin.named(builderName);
        String facing = selectedBuilder == null ? null : selectedBuilder.entranceDirection();
        List<PrebuiltCatalog.Entry> catalog = PrebuiltCatalog.all();
        int pageCount = Math.max(1, (catalog.size() + PREBUILTS_PER_PAGE - 1) / PREBUILTS_PER_PAGE);
        int currentPage = Math.min(prebuiltPage, pageCount - 1);
        int start = currentPage * PREBUILTS_PER_PAGE;
        int end = Math.min(catalog.size(), start + PREBUILTS_PER_PAGE);
        int slot = 10;
        for (PrebuiltCatalog.Entry e : catalog.subList(start, end)) {
            Set<String> materials = new LinkedHashSet<>();
            int blocks = 0;
            var result = BlueprintCompiler.compile(e.blueprint(), 0, 0, 0, plugin.constructionLimits());
            if (result.isValid()) {
                for (BlueprintCompiler.Block b : result.blocks) materials.add(b.material());
                blocks = result.blocks.size();
            }
            List<String> lore = new ArrayList<>();
            lore.add("§7" + LocaleText.format(plugin, "message_31d138e771e2", LocaleText.text(plugin, e.category()), blocks));
            lore.addAll(wrap(LocaleText.text(plugin, e.description()), 42));
            lore.add("§7" + LocaleText.format(plugin, "message_617cc3b7c2b4", String.join(", ", materials.stream().limit(8).toList()))
                    + (materials.size() > 8 ? " e mais" : ""));
            lore.add("§7" + LocaleText.format(plugin, "message_4cb31f37ab54", LocaleText.text(plugin, BlueprintOrientation.label(facing))));
            lore.add("§7" + LocaleText.format(plugin, "message_4baa636f4581"));
            lore.add("§7" + LocaleText.format(plugin, "message_f35ebb08baae"));
            lore.add("§e" + LocaleText.format(plugin, "message_eb9ca31161ec"));
            String cmd = builderName.toLowerCase(Locale.ROOT);
            put(slot, iconFor(e.id()), "§6" + e.title(), lore, run(cmd + " pre-moldado " + e.id()));
            slot++;
        }
        for(int i=0;i<BlueprintOrientation.DIRECTIONS.size();i++) {
            String direction=List.of("north", "east", "south", "west").get(i);
            put(45+i, Material.COMPASS, "§b" + LocaleText.format(plugin, "message_bf2efad5aeee", LocaleText.text(plugin, direction)),
                List.of("§7" + LocaleText.format(plugin, "message_7e69a772d01b"),"§7" + LocaleText.format(plugin, "message_35b5321deeda"),"§7" + LocaleText.format(plugin, "message_5a01aa8157cb")), p -> {
                    p.performCommand("builder " + builderName.toLowerCase(Locale.ROOT) + " orientation " + direction);
                    open(plugin,p,Page.PREBUILT,builderName,currentPage);
                });
        }
        put(50, Material.COMPASS, "§7" + LocaleText.format(plugin, "message_f85a0e335366"), List.of("§7" + LocaleText.format(plugin, "message_035a8897c081")), p -> {
            p.performCommand("builder " + builderName.toLowerCase(Locale.ROOT) + " orientation original");
            open(plugin,p,Page.PREBUILT,builderName,currentPage);
        });
        put(51, Material.ARROW, "§e" + LocaleText.format(plugin, "message_6829eba10490"), List.of(), currentPage > 0
                ? p -> open(plugin, p, Page.PREBUILT, builderName, currentPage - 1) : null);
        put(52, Material.PAPER, "§f" + LocaleText.format(plugin, "message_e6e6311ccd09", currentPage + 1, pageCount),
                List.of("§7" + LocaleText.format(plugin, "message_e4b0ee64ea33", catalog.size()),
                        "§7" + LocaleText.format(plugin, "message_c4cb60637026", PREBUILTS_PER_PAGE)), null);
        put(53, Material.ARROW, "§e" + LocaleText.format(plugin, "message_84c6e3cecde4"), List.of(), currentPage + 1 < pageCount
                ? p -> open(plugin, p, Page.PREBUILT, builderName, currentPage + 1) : null);
        put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.BUILDER, builderName));
    }

    private void buildConfig(Player player) {
        if(prebuiltPage>0) { buildNumericConfig(player); return; }
        boolean op = player.isOp();
        String note = op ? "§e" + LocaleText.format(plugin, "message_f4761de29343") : "§c" + LocaleText.format(plugin, "message_c23b52f07e06");
        put(4, Material.COMPARATOR, "§b" + LocaleText.format(plugin, "message_056e573751cd"), List.of("§7" + LocaleText.format(plugin, "message_9bd6caded26e"), note), null);
        put(10, Material.REDSTONE_TORCH, "§6" + LocaleText.format(plugin, "message_3a3dd3105ee8"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("creative-mode", false)), note), op ? toggle("creative-mode", false) : null);
        put(12, Material.CHEST, "§6" + LocaleText.format(plugin, "message_4fa486485055"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("use-owner-inventory", true)), note), op ? toggle("use-owner-inventory", true) : null);
        put(14, Material.OAK_SIGN, "§6" + LocaleText.format(plugin, "message_69c5e3f1c552"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("announce-actions", true)), note), op ? toggle("announce-actions", true) : null);
        put(16, Material.LEAD, "§6" + LocaleText.format(plugin, "message_35a5e15d1459"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("follow-owner", true)), note), op ? toggle("follow-owner", true) : null);
        put(28, Material.REDSTONE, "§6" + LocaleText.format(plugin, "message_a8e0e72e3a60"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("features.undo.enabled", true)), "§7" + LocaleText.format(plugin, "message_9156bec09a3c"), note), op ? toggle("features.undo.enabled", true) : null);
        put(30, Material.TNT, "§c" + LocaleText.format(plugin, "message_e89ad063cffd"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("terrain.deep-clean.enabled", false)), "§7" + LocaleText.format(plugin, "message_021395e5da4b"), note), op ? toggle("terrain.deep-clean.enabled", false) : null);
        put(32, Material.SHIELD, "§6" + LocaleText.format(plugin, "message_e7ed2d0a3143"), List.of("§7" + LocaleText.format(plugin, "message_2376a1e33306", onOff("allow-break-without-coreprotect-in-selection", true)), note), op ? toggle("allow-break-without-coreprotect-in-selection", true) : null);
        put(34, Material.ZOMBIE_HEAD, "§6Combate", List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("combat.enabled", true)), note), op ? toggle("combat.enabled", true) : null);
        put(36, Material.WRITABLE_BOOK, "§6" + LocaleText.format(plugin, "message_f47099f29801"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("ui.native-dialogs", true)), note), op ? toggle("ui.native-dialogs", true) : null);
        put(38, Material.BRICKS, "§6" + LocaleText.format(plugin, "message_4b579d520cd9"), List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", onOff("prebuilt.free-materials", true)), "§7" + LocaleText.format(plugin, "message_233e47b754df"), "§7" + LocaleText.format(plugin, "message_6474b987f8a8"), note), op ? toggle("prebuilt.free-materials", true) : null);
        put(46, Material.ARROW, "§e" + LocaleText.format(plugin, "message_6aa5b2e149f7"), List.of("§7" + LocaleText.format(plugin, "message_2bf78ef2e7cb")), p -> open(plugin,p,Page.CONFIG,null,1));
        put(48, Material.ARROW, "§e" + LocaleText.format(plugin, "message_9612aadc89cf"), List.of("§7" + LocaleText.format(plugin, "message_ed1a4f6cad09")), p -> open(plugin,p,Page.CONFIG,null,2));
        put(50, Material.ARROW, "§e" + LocaleText.format(plugin, "message_098ebebc5cad"), List.of("§7" + LocaleText.format(plugin, "message_902613c964d3")), p -> open(plugin,p,Page.CONFIG,null,3));
        put(49, Material.ARROW, "§7Voltar", List.of(), go(Page.MAIN, null));
    }

    private record ConfigOption(String key, String label, double step, double min, double max, boolean decimal) {}
    private void buildNumericConfig(Player player) {
        List<ConfigOption> all = List.of(
                new ConfigOption("builders.max","Construtores",1,1,5,false),new ConfigOption("max-project-blocks",LocaleText.format(plugin, "message_3b46a5f51330"),100,100,100000,false),
                new ConfigOption("construction.max-width",LocaleText.format(plugin, "message_ab463adcdef1"),10,1,1000,false),new ConfigOption("construction.max-depth",LocaleText.format(plugin, "message_5dcb8c3f7173"),10,1,1000,false),
                new ConfigOption("construction.max-height",LocaleText.format(plugin, "message_d36a6b56940e"),10,1,1000,false),new ConfigOption("construction.max-volume",LocaleText.format(plugin, "message_178aee913012"),100000,1000,1000000000,false),
                new ConfigOption("construction.max-parts",LocaleText.format(plugin, "message_ea9a31f42ef7"),100,1,100000,false),new ConfigOption("construction.max-ai-actions",LocaleText.format(plugin, "message_59f5911bc7f8"),10,1,2500,false),
                new ConfigOption("construction.max-openings",LocaleText.format(plugin, "message_36f8a65cd927"),25,1,100000,false),new ConfigOption("construction.max-processing-visits",LocaleText.format(plugin, "message_c363e9f264e1"),1000000,1000,1000000000,false),
                new ConfigOption("construction.max-file-bytes",LocaleText.format(plugin, "message_c0294ef5e7f1"),1048576,1024,1073741824,false),new ConfigOption("construction.preview-max-blocks",LocaleText.format(plugin, "message_29122c92fb00"),1000,1,1000000,false),
                new ConfigOption("prebuilt.max-user-models","Pre-moldados",1,1,256,false),
                new ConfigOption("max-blocks-per-minute",LocaleText.format(plugin, "message_a259f87a5b95"),50,1,10000,false),new ConfigOption("zone.build-height",LocaleText.format(plugin, "message_797f16b61f45"),10,10,plugin.constructionLimits().height(),false),
                new ConfigOption("movement.action-reach",LocaleText.format(plugin, "message_486aba0bf6bf"),1,1,32,false),new ConfigOption("movement.max-target-distance",LocaleText.format(plugin, "message_6f229f768344"),5,4,256,false),
                new ConfigOption("walk-speed",LocaleText.format(plugin, "message_90c00955009d"),0.05,0.05,1,true),new ConfigOption("follow-distance",LocaleText.format(plugin, "message_d7ca5fc04ffe"),1,1,16,false),
                new ConfigOption("travel-radius",LocaleText.format(plugin, "message_1ebb133e1527"),4,4,64,false),new ConfigOption("terrain.level-depth",LocaleText.format(plugin, "message_ef8abc4d8ac8"),2,1,64,false),
                new ConfigOption("max-clear-volume",LocaleText.format(plugin, "message_0bedb1a0d566"),4096,1000,16_000_000,false),new ConfigOption("terrain.scan-per-tick",LocaleText.format(plugin, "message_52b4da0d6d30"),256,64,8192,false),
                new ConfigOption("terrain.changes-per-step",LocaleText.format(plugin, "message_61ba6bed3374"),16,1,512,false),new ConfigOption("terrain.max-changes",LocaleText.format(plugin, "message_ef639902a9f4"),4096,1,262144,false),
                new ConfigOption("ai.connect-timeout-seconds",LocaleText.format(plugin, "message_02fedb04813c"),1,1,60,false),new ConfigOption("ai.request-timeout-seconds",LocaleText.format(plugin, "message_8a62f6f81694"),10,1,600,false),
                new ConfigOption("ai.max-response-tokens",LocaleText.format(plugin, "message_5e35367e8d8a"),500,500,100000,false),new ConfigOption("planning.confirmation-timeout-seconds",LocaleText.format(plugin, "message_d8b947d2eb78"),10,10,600,false),
                new ConfigOption("planning.selection-timeout-seconds",LocaleText.format(plugin, "message_c414cfc1d2cb"),30,30,3600,false),new ConfigOption("conversation.history-messages",LocaleText.format(plugin, "message_e2ba98056977"),1,0,50,false),
                new ConfigOption("spawn-exclusion-radius",LocaleText.format(plugin, "message_c5fb9e90ca63"),5,0,256,false),new ConfigOption("parental.daily-limit",LocaleText.format(plugin, "message_1dd4e96de47a"),1,0,10000,false),
                new ConfigOption("combat.target-range",LocaleText.format(plugin, "message_44be57d3ffee"),1,1,64,false),new ConfigOption("combat.strike-reach",LocaleText.format(plugin, "message_d7c0e5ebf1a2"),0.2,0.5,8,true),
                new ConfigOption("combat.cooldown-ticks",LocaleText.format(plugin, "message_9eb980cd9715"),5,1,200,false),new ConfigOption("combat.damage",LocaleText.format(plugin, "message_d9c8dc52f52e"),1000,0,1000000,false));
        int page=Math.min(prebuiltPage,4), start=(page-1)*10, end=Math.min(all.size(),start+10);
        put(4,Material.COMPARATOR,"§b" + LocaleText.format(plugin, "message_e9e25acc38c0", page+1),List.of("§7" + LocaleText.format(plugin, "message_ec2c87c0d7e7"),"§7" + LocaleText.format(plugin, "message_bbc73bccc83c")),null);
        for(int i=start;i<end;i++) {
            ConfigOption option=all.get(i); int offset=i-start; int slot=10+(offset/3)*9+(offset%3)*3;
            double value=plugin.getConfig().getDouble(option.key());
            String shown=option.decimal()?String.format(Locale.ROOT,"%.2f",value):Long.toString(Math.round(value));
            put(slot,Material.RED_CONCRETE,"§c− " + option.label(),List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", shown),"§7" + LocaleText.format(plugin, "message_c4e82339e7e9", option.step())),player.isOp()?adjust(option,-1,page):null);
            put(slot+1,Material.PAPER,"§e"+option.label(),List.of("§7" + LocaleText.format(plugin, "message_fc459b0bea6d", shown),"§7" + LocaleText.format(plugin, "message_43e7149c18ed", option.min(), option.max())),null);
            put(slot+2,Material.LIME_CONCRETE,"§a+ " + option.label(),List.of("§7" + LocaleText.format(plugin, "message_1c133ec09234", shown),"§7" + LocaleText.format(plugin, "message_3bcd14f01389", option.step())),player.isOp()?adjust(option,1,page):null);
        }
        put(46,Material.ARROW,"§7Configuracoes",List.of(),p->open(plugin,p,Page.CONFIG,null));
        put(48,Material.ARROW,"§e" + LocaleText.format(plugin, "message_6829eba10490"),List.of(),page>1?p->open(plugin,p,Page.CONFIG,null,page-1):null);
        put(50,Material.ARROW,"§e" + LocaleText.format(plugin, "message_84c6e3cecde4"),List.of(),page<4?p->open(plugin,p,Page.CONFIG,null,page+1):null);
    }

    private Consumer<Player> adjust(ConfigOption option,int direction,int page) {
        return p -> {
            if(!p.isOp()) return;
            double current=plugin.getConfig().getDouble(option.key());
            double value=Math.max(option.min(),Math.min(option.max(),current+direction*option.step()));
            if(option.decimal()) plugin.getConfig().set(option.key(),Math.round(value*100.0)/100.0);
            else plugin.getConfig().set(option.key(),(int)Math.round(value));
            plugin.saveConfig(); plugin.reloadPrebuilts();
            open(plugin,p,Page.CONFIG,null,page);
        };
    }

    private void buildTerrain(Player player) {
        String cmd=builderName.toLowerCase(Locale.ROOT);
        put(12,Material.GRASS_BLOCK,"§a" + LocaleText.format(plugin, "message_5b6d26e30a1b"),List.of("§7" + LocaleText.format(plugin, "message_c79caa39c6eb")),run(cmd+" terreno analisar"));
        put(14,Material.IRON_SHOVEL,"§e" + LocaleText.format(plugin, "message_33a44693872b"),List.of("§7" + LocaleText.format(plugin, "message_e291534e50bc")),run(cmd+" terreno limpar"));
        put(16,Material.GRASS_BLOCK,"§b" + LocaleText.format(plugin, "message_fc561eaef584"),List.of("§7" + LocaleText.format(plugin, "message_e21b724e0cbf")),run(cmd+" terreno nivelar"));
        if(player.isOp() && plugin.getConfig().getBoolean("terrain.deep-clean.enabled",false)) {
            put(30,Material.TNT,"§c"+LocaleText.format(plugin,"terrain.easy.clear-op"),
                    List.of(LocaleText.format(plugin,"terrain.easy.destructive")),run(cmd+" terrain deep clean"));
            put(32,Material.GRASS_BLOCK,"§c"+LocaleText.format(plugin,"terrain.easy.level-op"),
                    List.of(LocaleText.format(plugin,"terrain.easy.destructive")),run(cmd+" terrain deep level"));
        }
        put(22,Material.TNT,"§c" + LocaleText.format(plugin, "message_e89ad063cffd"),List.of("§7" + LocaleText.format(plugin, "message_483f96fb6854"),"§7" + LocaleText.format(plugin, "message_3417608e1dd3")),go(Page.DEEP_TERRAIN,builderName));
        put(49,Material.ARROW,"§7Voltar",List.of(),go(Page.BUILDER,builderName));
    }

    private void buildDeepTerrain(Player player) {
        String cmd=builderName.toLowerCase(Locale.ROOT);
        boolean enabled=plugin.getConfig().getBoolean("terrain.deep-clean.enabled",false);
        List<String> details=List.of("§7Configuracao: "+(enabled?"§aativa":"§cdesligada"),"§7" + LocaleText.format(plugin, "message_9ce029fe0232"),"§c" + LocaleText.format(plugin, "message_1603bd807658"),"§7" + LocaleText.format(plugin, "message_ef1e307a9b07"),"§7" + LocaleText.format(plugin, "message_80ea20ec584b"),"§7" + LocaleText.format(plugin, "message_2b5bf646d393"));
        put(12,Material.TNT,"§c" + LocaleText.format(plugin, "message_1fd85953ee5b"),details,enabled&&player.isOp()?run(cmd+" terreno deep limpar"):null);
        put(14,Material.RED_CONCRETE,"§c" + LocaleText.format(plugin, "message_0494b72a25ed"),details,enabled&&player.isOp()?run(cmd+" terreno deep nivelar"):null);
        if(!player.isOp()) put(31,Material.BARRIER,"§c" + LocaleText.format(plugin, "message_20747ecc4869"),List.of("§7" + LocaleText.format(plugin, "message_7298408761cd")),null);
        else if(!enabled) put(31,Material.REDSTONE_BLOCK,"§c" + LocaleText.format(plugin, "message_647565e4dde4"),List.of("§7" + LocaleText.format(plugin, "message_254b2d4c49de")),go(Page.CONFIG,null));
        put(49,Material.ARROW,"§7Voltar",List.of(),go(Page.TERRAIN,builderName));
    }

    private static String onOffConfig(AIBuilderPlugin plugin,String path,boolean def) {
        return plugin.getConfig().getBoolean(path,def)?"§aligado":"§cdesligado";
    }

    // ---------- helpers ----------

    private String onOff(String key, boolean def) {
        return plugin.getConfig().getBoolean(key, def) ? "§aligado" : "§cdesligado";
    }

    private Consumer<Player> toggle(String key, boolean def) {
        return p -> {
            if (!p.isOp()) return;
            plugin.getConfig().set(key, !plugin.getConfig().getBoolean(key, def));
            plugin.saveConfig();
            open(plugin, p, Page.CONFIG, null);
        };
    }

    /** Ajusta zone.build-height em passos ate construction.max-height (padrao inicial 100). */
    private Consumer<Player> zoneHeight(int delta) {
        return p -> {
            if (!p.isOp()) return;
            int current = plugin.getConfig().getInt("zone.build-height", 100);
            int next = Math.max(Math.min(ZONE_HEIGHT_MIN,plugin.constructionLimits().height()), Math.min(plugin.constructionLimits().height(), (int)Math.min(Integer.MAX_VALUE,(long)current + delta)));
            plugin.getConfig().set("zone.build-height", next);
            plugin.saveConfig();
            plugin.reloadPrebuilts();
            open(plugin, p, Page.CONFIG, null);
        };
    }

    private Consumer<Player> run(String command) {
        return p -> { p.closeInventory(); p.performCommand(BuilderCommands.canonical("builder " + command)); };
    }

    private Consumer<Player> go(Page target, String name) {
        return p -> open(plugin, p, target, name);
    }

    private void put(int slot, Material material, String name, List<String> lore, Consumer<Player> action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(LocaleText.text(plugin, name));
        meta.setLore(lore.stream().map(line -> LocaleText.text(plugin, line)).toList());
        item.setItemMeta(meta);
        inventory.setItem(slot, item);
        if (action != null) actions.put(slot, action);
    }

    private static Material iconFor(String id) {
        return switch (id) {
            case "casa_campo" -> Material.OAK_PLANKS;
            case "casa_moderna" -> Material.WHITE_CONCRETE;
            case "arvore_gigante" -> Material.OAK_SAPLING;
            case "predio_moderno_3_andares" -> Material.QUARTZ_PILLAR;
            case "casa_na_arvore" -> Material.OAK_LOG;
            default -> Material.BRICKS;
        };
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() + word.length() + 1 > width && line.length() > 0) {
                lines.add("§7" + line);
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        if (line.length() > 0) lines.add("§7" + line);
        return lines;
    }
}
