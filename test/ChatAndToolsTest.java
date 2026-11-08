import com.elitonvita.aibuilder.*;
import java.util.*;
import java.lang.reflect.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import net.kyori.adventure.text.*;
import net.kyori.adventure.text.format.*;

/** Real cleanup and chat components, with inventories and PDC modelled without a server. */
public class ChatAndToolsTest {
    static int checks;
    static void check(boolean ok,String message){checks++;if(!ok)throw new AssertionError(message);}
    static <T>T proxy(Class<T> type,InvocationHandler handler){return CatalogGardenRuntimeTest.proxy(type,handler);}
    static class Tool extends ItemStack {
        final ItemMeta meta;
        Tool(String id, boolean marked) {
            super();
            PersistentDataContainer data=proxy(PersistentDataContainer.class,(o,m,a)->switch(m.getName()){
                case "has" -> marked && a[0].toString().equals("aibuilder:wand");
                case "get" -> a[0].toString().equals("aibuilder:builder-id")?id:null;
                default -> throw new UnsupportedOperationException(m.toString());
            });
            meta=proxy(ItemMeta.class,(o,m,a)->{if(m.getName().equals("getPersistentDataContainer"))return data;throw new UnsupportedOperationException(m.toString());});
        }
        @Override public boolean hasItemMeta(){return true;}
        @Override public ItemMeta getItemMeta(){return meta;}
    }
    static <T extends Inventory>T inventory(Class<T> type,ItemStack[] slots){return proxy(type,(o,m,a)->switch(m.getName()){
        case "getSize" -> slots.length;
        case "getItem" -> slots[(int)a[0]];
        case "setItem" -> {slots[(int)a[0]]=(ItemStack)a[1];yield null;}
        default -> throw new UnsupportedOperationException(m.toString());
    });}
    static void colors(Component c,NamedTextColor color){check(color.equals(c.color()),"missing color "+color);check(c.decoration(TextDecoration.BOLD)==TextDecoration.State.TRUE,"prompt/action not bold");}
    public static void main(String[]args)throws Exception {
        colors(BuilderUi.prompt("Choose a size?"),NamedTextColor.YELLOW);
        var ct=Class.forName("PluginLifecycleTest$Fixture").getDeclaredConstructor();ct.setAccessible(true);
        try(AutoCloseable fixture=(AutoCloseable)ct.newInstance()) {
            AIBuilderPlugin plugin=(AIBuilderPlugin)CatalogGardenRuntimeTest.get(fixture,"plugin");
            Object session=CatalogGardenRuntimeTest.get(fixture,"session");
            var field=org.bukkit.plugin.java.JavaPlugin.class.getDeclaredField("pluginMeta");field.setAccessible(true);
            field.set(plugin,proxy(io.papermc.paper.plugin.configuration.PluginMeta.class,(o,m,a)->switch(m.getName()){
                case "namespace" -> "aibuilder";case "getName" -> "AIBuilder";default -> throw new UnsupportedOperationException(m.toString());
            }));
            UUID id=(UUID)CatalogGardenRuntimeTest.get(fixture,"builderId");
            UUID otherId=UUID.randomUUID();
            Tool bound=new Tool(id.toString(),true),other=new Tool(otherId.toString(),true),normal=new Tool(id.toString(),false);
            ((Map<UUID,Object>)CatalogGardenRuntimeTest.get(plugin,"builders")).put(otherId,session);
            ItemStack[] slots=new ItemStack[41];slots[0]=bound;slots[40]=bound;slots[1]=other;slots[2]=normal;
            ItemStack[] ender={bound,normal},chest={bound,other},cursor={bound};
            PlayerInventory inv=inventory(PlayerInventory.class,slots);Inventory end=inventory(Inventory.class,ender),top=inventory(Inventory.class,chest);
            Player[] viewer=new Player[1];
            InventoryView view=proxy(InventoryView.class,(o,m,a)->{if(m.getName().equals("getTopInventory"))return top;if(m.getName().equals("getPlayer"))return viewer[0];throw new UnsupportedOperationException(m.toString());});
            List<Component> messages=new ArrayList<>();
            Player player=proxy(Player.class,(o,m,a)->switch(m.getName()){
                case "getInventory" -> inv;case "getEnderChest" -> end;case "getOpenInventory" -> view;
                case "getItemOnCursor" -> cursor[0];case "setItemOnCursor" -> {cursor[0]=(ItemStack)a[0];yield null;}
                case "sendMessage" -> {messages.add((Component)a[0]);yield null;}
                default -> throw new UnsupportedOperationException(m.toString());
            });
            viewer[0]=player;
            ((Map<String,Object>)CatalogGardenRuntimeTest.get(fixture,"serverValues")).put("getOnlinePlayers",List.of(player));
            ((Map<String,Object>)CatalogGardenRuntimeTest.get(fixture,"serverValues")).put("getPlayer",player);
            CatalogGardenRuntimeTest.invoke(session,"broadcastBot",new Class[]{String.class},"What size would you like?");
            colors(messages.getLast(),NamedTextColor.YELLOW);
            check(((TextComponent)messages.getLast()).content().contains("What size would you like?"),"question text changed");
            check((int)CatalogGardenRuntimeTest.invoke(session,"removeWands",new Class[0])==5,"bound tools not removed from inventory/offhand/ender/cursor/container");
            check(slots[0]==null&&slots[40]==null&&ender[0]==null&&chest[0]==null&&cursor[0]==null,"bound tool survived deletion");
            check(slots[1]==other&&slots[2]==normal&&ender[1]==normal&&chest[1]==other,"deleted another builder tool or ordinary shovel");
            check(!(boolean)CatalogGardenRuntimeTest.invoke(plugin,"isOrphanTool",new Class[]{ItemStack.class},bound),"live builder tool considered orphan");
            ((Map<?,?>)CatalogGardenRuntimeTest.get(plugin,"builders")).remove(id);
            slots[3]=bound;ender[0]=bound;cursor[0]=bound;
            // Remove the dummy session to keep onJoin independent of NPC greeting scheduling.
            CatalogGardenRuntimeTest.set(session,"npc",null);
            plugin.onJoin(new org.bukkit.event.player.PlayerJoinEvent(player,(Component)null));
            check(slots[3]==null&&ender[0]==null&&cursor[0]==null,"offline player kept deleted builder tool on join");
            check(slots[1]==other&&chest[1]==other,"orphan cleanup removed surviving builder tool");
            chest[0]=bound;
            plugin.onToolInventoryOpen(new org.bukkit.event.inventory.InventoryOpenEvent(view));
            check(chest[0]==null,"closed container retained orphan after opening");
            check(!(boolean)CatalogGardenRuntimeTest.invoke(plugin,"isOrphanTool",new Class[]{ItemStack.class},normal),"ordinary shovel considered orphan");
            boolean[] droppedRemoved={false};
            var dropped=proxy(org.bukkit.entity.Item.class,(o,m,a)->switch(m.getName()){
                case "getItemStack" -> bound;case "remove" -> {droppedRemoved[0]=true;yield null;}
                default -> throw new UnsupportedOperationException(m.toString());
            });
            var pickup=new org.bukkit.event.entity.EntityPickupItemEvent(player,dropped,0);
            plugin.onToolPickup(pickup);
            check(pickup.isCancelled()&&droppedRemoved[0],"orphan ground tool can still be picked up");
            CatalogGardenRuntimeTest.set(plugin,"registryHealthy",false);
            check(!(boolean)CatalogGardenRuntimeTest.invoke(plugin,"isOrphanTool",new Class[]{ItemStack.class},bound),"registry load failure destroyed tools");
            for(String language:List.of("EN","PT")) {
                plugin.getConfig().set("language",language);messages.clear();
                BuilderUi.confirmation(plugin,player,"Alfred","summary","","/builder alfred yes token",false);
                colors(messages.get(0),NamedTextColor.YELLOW);colors(messages.get(1),NamedTextColor.YELLOW);
                Component actions=messages.get(2);colors(actions,NamedTextColor.GREEN);
                colors(actions.children().getLast(),NamedTextColor.RED);
                check(actions.clickEvent().equals(net.kyori.adventure.text.event.ClickEvent.runCommand("/builder alfred yes token")),"confirmation token changed");
                Component terrain=BuilderUi.terrainChoices(plugin,"/builder alfred terrain deep level yes token","/builder alfred terrain cancel token");
                colors(terrain,NamedTextColor.GREEN);colors(terrain.children().getLast(),NamedTextColor.RED);
                check(((TextComponent)terrain).content().equals(language.equals("EN")?"[Prepare area]":"[Preparar área]"),"terrain label language changed");
                check(terrain.children().getLast().clickEvent().equals(net.kyori.adventure.text.event.ClickEvent.runCommand("/builder alfred terrain cancel token")),"cancel token changed");
            }
        }
        System.out.println("ChatAndToolsTest: "+checks+" checks passed");
    }
}
