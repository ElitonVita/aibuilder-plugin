package com.elitonvita.aibuilder;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.IntBinaryOperator;

/** Small, player-scoped presentation helpers. Call only from the server thread. */
public final class BuilderUi {
    private final JavaPlugin plugin;
    public BuilderUi() { this(null); }
    public BuilderUi(JavaPlugin plugin) { this.plugin = plugin; }
    private final Map<UUID, Display> displays = new HashMap<>();

    private record Display(Player player, BossBar bar) {}

    public static void confirmation(JavaPlugin plugin, Player player, String name, String summary, String materials, String confirmCommand, boolean nativeDialog) {
        confirmCommand = BuilderCommands.canonical(confirmCommand);
        String cancelCommand = confirmCommand.replaceFirst(" yes(?= |$)", " cancel");
        String heading = LocaleText.text(plugin, LocaleText.format(plugin, "message_adb8b860e8f0", name));
        if (nativeDialog) confirmationDialog(plugin, player, heading, summary + "\n" + materials,
                LocaleText.text(plugin, "Build"), confirmCommand, cancelCommand);
        player.sendMessage(prompt(heading));
        player.sendMessage(prompt(LocaleText.text(plugin, summary)));
        if (materials != null && !materials.isBlank()) player.sendMessage(Component.text(LocaleText.text(plugin, materials)));
        Component actions = action("[" + LocaleText.text(plugin, "Build") + "]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand(BuilderCommands.canonical(confirmCommand)))
                .hoverEvent(HoverEvent.showText(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_e7aeb07c638e")))))
                .append(Component.text("   "))
                .append(action("[" + LocaleText.text(plugin, "Cancel") + "]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand(BuilderCommands.canonical(cancelCommand)))
                        .hoverEvent(HoverEvent.showText(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_84d925b6d967"))))));
        player.sendMessage(actions);
        player.sendMessage(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_725ff5c9d888"))));
    }

    /** Visible chat prompts; text stays literal, never parsed as formatting or commands. */
    public static Component prompt(String text) {
        return Component.text(text, NamedTextColor.YELLOW).decorate(TextDecoration.BOLD);
    }

    private static Component action(String label, NamedTextColor color) {
        return Component.text(label, color).decorate(TextDecoration.BOLD);
    }

    /** Native Paper dialog; static commands retain the normal owner, permission and token checks. */
    public static void confirmationDialog(JavaPlugin plugin, Player player, String title, String summary, String acceptLabel,
                                          String acceptCommand, String cancelCommand) {
        var body = List.of(
                io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(LocaleText.text(plugin, summary))),
                io.papermc.paper.registry.data.dialog.body.DialogBody.plainMessage(Component.text(
                        LocaleText.text(plugin, LocaleText.format(plugin, "message_5b2931ba00a4")))));
        var base = io.papermc.paper.registry.data.dialog.DialogBase.builder(Component.text(LocaleText.text(plugin, title)))
                .canCloseWithEscape(true).body(body).build();
        player.showDialog(io.papermc.paper.dialog.Dialog.create(builder -> builder.empty()
                .base(base).type(io.papermc.paper.registry.data.dialog.type.DialogType.confirmation(
                        dialogButton(LocaleText.text(plugin, acceptLabel), acceptCommand), dialogButton(LocaleText.text(plugin, "Cancel"), cancelCommand)))));
    }

    private static io.papermc.paper.registry.data.dialog.ActionButton dialogButton(String label, String command) {
        return io.papermc.paper.registry.data.dialog.ActionButton.create(Component.text(label), null, 150,
                io.papermc.paper.registry.data.dialog.action.DialogAction.staticAction(ClickEvent.runCommand(BuilderCommands.canonical(command))));
    }

    public static Component terrainChoices(JavaPlugin plugin, String confirmCommand, String cancelCommand) {
        return action("[" + LocaleText.format(plugin, "terrain.easy.prepare") + "]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand(BuilderCommands.canonical(confirmCommand)))
                .hoverEvent(HoverEvent.showText(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_251efea6eba0")))))
                .append(Component.text("   "))
                .append(action("[" + LocaleText.format(plugin, "terrain.easy.cancel") + "]", NamedTextColor.RED)
                        .clickEvent(ClickEvent.runCommand(BuilderCommands.canonical(cancelCommand)))
                        .hoverEvent(HoverEvent.showText(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_bee795c59127"))))));
    }

    /** Compatibility overload used by serverless UI fixtures. */
    public static Component terrainChoices(String confirmCommand, String cancelCommand) {
        return action("[Sim]", NamedTextColor.GREEN).clickEvent(ClickEvent.runCommand(BuilderCommands.canonical(confirmCommand)))
                .append(Component.text("   "))
                .append(action("[Não]", NamedTextColor.RED).clickEvent(ClickEvent.runCommand(BuilderCommands.canonical(cancelCommand))));
    }

    public static void followControls(JavaPlugin plugin, Player player, boolean following, String commandPrefix) {
        player.sendMessage(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_3ba917fcfbf8")))
                .append(Component.text(LocaleText.text(plugin, "[Stay]"))
                        .clickEvent(ClickEvent.runCommand(commandPrefix + " stay"))
                        .hoverEvent(HoverEvent.showText(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_c072909b1243"))))))
                .append(Component.text("  "))
                .append(Component.text(LocaleText.text(plugin, "[Follow]"))
                        .clickEvent(ClickEvent.runCommand(commandPrefix + " follow"))
                        .hoverEvent(HoverEvent.showText(Component.text(LocaleText.text(plugin, LocaleText.format(plugin, "message_314f98e36dff")))))));
    }

    /** A zero total represents a stage without measurable progress, such as planning. */
    public void update(Player player, String stage, int done, int total) {
        stage = LocaleText.text(plugin, stage);
        float progress = total > 0 ? (float) Math.max(0, Math.min(done, total)) / total : 0f;
        String label = total > 0
                ? stage + " • " + Math.round(progress * 100) + "% (" + Math.max(0, Math.min(done, total)) + "/" + total + ")"
                : stage;
        Component title = Component.text(label, NamedTextColor.AQUA);
        Display display = displays.computeIfAbsent(player.getUniqueId(), ignored -> {
            BossBar bar = BossBar.bossBar(title, progress, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            player.showBossBar(bar);
            return new Display(player, bar);
        });
        display.bar().name(title).progress(progress);
        player.sendActionBar(title);
    }

    public void hide(Player player) {
        Display display = displays.remove(player.getUniqueId());
        if (display != null) display.player().hideBossBar(display.bar());
        player.sendActionBar(Component.empty());
    }

    public void hideAll() {
        for (Display display : displays.values()) {
            display.player().hideBossBar(display.bar());
            display.player().sendActionBar(Component.empty());
        }
        displays.clear();
    }

    /** Draws a bright private cuboid outline and a block-by-block floor perimeter. */
    public static void preview(Player player, Location first, Location second) {
        preview(player, first, second, null);
    }

    /** Draws the floor perimeter at the current surface height for each boundary column. */
    public static void preview(Player player, Location first, Location second, IntBinaryOperator surfaceYAt) {
        if (first == null || second == null || first.getWorld() == null
                || !first.getWorld().equals(second.getWorld())
                || !player.getWorld().equals(first.getWorld())) return;
        double[] low = {Math.min(first.getBlockX(), second.getBlockX()),
                Math.min(first.getBlockY(), second.getBlockY()), Math.min(first.getBlockZ(), second.getBlockZ())};
        double[] high = {Math.max(first.getBlockX(), second.getBlockX()) + 1.0,
                Math.max(first.getBlockY(), second.getBlockY()) + 1.0, Math.max(first.getBlockZ(), second.getBlockZ()) + 1.0};
        Particle.DustOptions glow = new Particle.DustOptions(Color.fromRGB(72, 255, 132), 1.7f);
        for (int axis = 0; axis < 3; axis++) {
            int otherA = (axis + 1) % 3;
            int otherB = (axis + 2) % 3;
            for (int edge = 0; edge < 4; edge++) {
                int segments = Math.max(1, Math.min(128, (int) Math.ceil(high[axis] - low[axis])));
                for (int point = 0; point <= segments; point++) {
                    double[] xyz = low.clone();
                    xyz[axis] += (high[axis] - low[axis]) * point / segments;
                    xyz[otherA] = (edge & 1) == 0 ? low[otherA] : high[otherA];
                    xyz[otherB] = (edge & 2) == 0 ? low[otherB] : high[otherB];
                    player.spawnParticle(Particle.DUST, xyz[0], xyz[1], xyz[2], 1, 0, 0, 0, 0, glow);
                }
            }
        }

        // Mark the actual floor blocks around the selection. Particles sit just above
        // the surface, so this never changes blocks or depends on their material.
        int minX = (int) low[0], maxX = (int) high[0] - 1;
        int minZ = (int) low[2], maxZ = (int) high[2] - 1;
        int fallbackSurfaceY = (int) low[1];
        long perimeter = 2L * (maxX - minX + 1) + 2L * (maxZ - minZ + 1) - 4L;
        int stride = Math.max(1, (int) Math.ceil(perimeter / 512.0));
        for (int x = minX; x <= maxX; x += stride) {
            floorMarker(player, glow, x, minZ, fallbackSurfaceY, surfaceYAt);
            if (maxZ != minZ) floorMarker(player, glow, x, maxZ, fallbackSurfaceY, surfaceYAt);
        }
        for (int z = minZ + stride; z < maxZ; z += stride) {
            floorMarker(player, glow, minX, z, fallbackSurfaceY, surfaceYAt);
            if (maxX != minX) floorMarker(player, glow, maxX, z, fallbackSurfaceY, surfaceYAt);
        }
    }

    private static void floorMarker(Player player, Particle.DustOptions glow, int x, int z,
                                    int fallbackSurfaceY, IntBinaryOperator surfaceYAt) {
        int surfaceY = surfaceYAt == null ? fallbackSurfaceY : surfaceYAt.applyAsInt(x, z);
        player.spawnParticle(Particle.DUST, x + 0.5, surfaceY + 1.06, z + 0.5, 1, 0, 0, 0, 0, glow);
    }

    public static List<String> completions(String prefix, List<String> options) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
