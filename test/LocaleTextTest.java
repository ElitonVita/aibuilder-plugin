import com.elitonvita.aibuilder.AIBuilderPlugin;
import com.google.gson.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;

/** Language files, complete templates, opaque data, and English command grammar without a server. */
public final class LocaleTextTest {
    static int checks;
    static void check(boolean value, String message) { checks++; if(!value) throw new AssertionError(message); }
    static Object call(String owner, String method, Class<?>[] types, Object... args) throws Exception {
        Method m=Class.forName("com.elitonvita.aibuilder."+owner).getDeclaredMethod(method,types);m.setAccessible(true);return m.invoke(null,args);
    }
    static String text(JavaPlugin plugin,String input)throws Exception { return (String)call("LocaleText","text",new Class[]{JavaPlugin.class,String.class},plugin,input); }
    static String format(JavaPlugin plugin,String key,Object... args)throws Exception { return (String)call("LocaleText","format",new Class[]{JavaPlugin.class,String.class,Object[].class},plugin,key,args); }
    static void field(Object object,String name,Object value)throws Exception {Field f=JavaPlugin.class.getDeclaredField(name);f.setAccessible(true);f.set(object,value);}
    public static void main(String[]args)throws Exception {
        Path folder=Files.createTempDirectory("aibuilder-languages-");
        try {
            Class<?> unsafe=Class.forName("sun.misc.Unsafe");Field singleton=unsafe.getDeclaredField("theUnsafe");singleton.setAccessible(true);
            AIBuilderPlugin plugin=(AIBuilderPlugin)unsafe.getMethod("allocateInstance",Class.class).invoke(singleton.get(null),AIBuilderPlugin.class);
            YamlConfiguration config=new YamlConfiguration();field(plugin,"newConfig",config);field(plugin,"dataFolder",folder.toFile());field(plugin,"logger",Logger.getLogger("LocaleTextTest"));
            call("LocaleText","reload",new Class[]{JavaPlugin.class},plugin);
            Path enFile=folder.resolve("languages/en.yml"),ptFile=folder.resolve("languages/pt.yml");
            check(Files.isRegularFile(enFile)&&Files.isRegularFile(ptFile),"first install must create both catalogs");
            YamlConfiguration en=YamlConfiguration.loadConfiguration(enFile.toFile()),pt=YamlConfiguration.loadConfiguration(ptFile.toFile());
            check(en.getKeys(false).size()>900,"catalog unexpectedly incomplete");check(en.getKeys(false).equals(pt.getKeys(false)),"language key parity");
            JsonObject manifest=JsonParser.parseString(Files.readString(Path.of("src/languages/templates.json"))).getAsJsonObject();
            for(var e:manifest.entrySet()) {
                String key=e.getKey(),source=e.getValue().getAsJsonObject().get("source").getAsString();
                Set<String> placeholders=new HashSet<>();var matcher=java.util.regex.Pattern.compile("\\{\\d+\\}").matcher(source);while(matcher.find())placeholders.add(matcher.group());
                for(var language:List.of(en,pt)){
                    Set<String> actual=new HashSet<>();matcher=java.util.regex.Pattern.compile("\\{\\d+\\}").matcher(language.getString(key));while(matcher.find())actual.add(matcher.group());
                    check(actual.equals(placeholders),"placeholder mismatch: "+key);
                }
            }
            check(text(plugin,"§bChamar para perto").equals("§bCall nearby"),"nested builder call menu remains Portuguese");
            check(text(plugin,"Distancia maxima: 16 blocos").equals("Maximum distance: 16 blocks"),"dynamic menu label");
            check(text(plugin,"nivelamento normal recusado: liquido ou construcao na superficie; use Deep (OP) para remover construcoes").startsWith("normal leveling refused:"),"validator error translation");
            check(text(plugin,"Um nome personalizado que contém chamar").equals("Um nome personalizado que contém chamar"),"no word substitution inside data: "+text(plugin,"Um nome personalizado que contém chamar"));
            String key=manifest.entrySet().stream().filter(e->e.getValue().getAsJsonObject().get("source").getAsString().equals("Apagar {0}?")).findFirst().orElseThrow().getKey();
            check(format(plugin,key,"Chamar para perto").equals("Delete Chamar para perto?"),"format arguments must remain opaque");
            config.set("language","PT");
            check(text(plugin,"[Stay]").equals("[Ficar]"),"PT movement button");
            check(text(plugin,"Country Cottage").equals("Casa de campo"),"PT built-in title");
            check(text(plugin,"Movement: ").equals("Movimento: "),"trailing whitespace preserved");
            check(text(plugin,"Cancel").equals("Cancelar"),"native dialog cancel");
            check(text(plugin,"Distancia maxima: 16 blocos").equals("Distancia maxima: 16 blocos"),"PT dynamic message");
            config.set("language","EN");
            en.set(key,"Remove {0} now?");String another=en.getKeys(false).stream().filter(k->!k.equals(key)).findFirst().orElseThrow();en.set(another,null);en.save(enFile.toFile());
            call("LocaleText","reload",new Class[]{JavaPlugin.class},plugin);
            check(format(plugin,key,"Alfred").equals("Remove Alfred now?"),"custom text lost on reload");
            check(YamlConfiguration.loadConfiguration(enFile.toFile()).contains(another),"upgrade did not merge missing key");
            en=YamlConfiguration.loadConfiguration(enFile.toFile());en.set(key,"Wrong {7}");en.save(enFile.toFile());call("LocaleText","reload",new Class[]{JavaPlugin.class},plugin);
            check(format(plugin,key,"Alfred").equals("Delete Alfred?"),"invalid custom placeholders must fall back");
            Files.writeString(enFile,"broken: [\n");call("LocaleText","reload",new Class[]{JavaPlugin.class},plugin);
            check(Files.readString(enFile).equals("broken: [\n"),"malformed catalog was overwritten");check(format(plugin,key,"Alfred").equals("Delete Alfred?"),"malformed catalog fallback");
            String[] parsed=(String[])call("BuilderCommands","internal",new Class[]{String[].class},(Object)new String[]{"alfred","project","place","level"});
            check(Arrays.equals(parsed,new String[]{"alfred","projeto","colocar","level"}),"parser renamed a project ID");
            parsed=(String[])call("BuilderCommands","internal",new Class[]{String[].class},(Object)new String[]{"terrain","deep","level","yes","token"});
            check(Arrays.equals(parsed,new String[]{"terreno","deep","nivelar","sim","token"}),"English deep confirmation grammar");
            check(call("BuilderCommands","internal",new Class[]{String[].class},(Object)new String[]{"alfred","chamar"})==null,"Portuguese command still public");
            check(call("BuilderCommands","canonical",new Class[]{String.class},"/construtor alfred terreno deep nivelar sim token").equals("/builder alfred terrain deep level yes token"),"callback not canonical English");
            check(call("BuilderCommands","canonical",new Class[]{String.class},"construtor projeto colocar nivelar").equals("builder project place nivelar"),"callback changed saved project name");
            check(call("BuilderCommands","internal",new Class[]{String[].class},(Object)new String[]{"menu"})!=null,"explicit menu command rejected");
            parsed=(String[])call("BuilderCommands","internal",new Class[]{String[].class},(Object)new String[]{"alfred","terrain","cancel","token"});
            check(Arrays.equals(parsed,new String[]{"alfred","terreno","cancelar","token"}),"terrain cancellation token rejected");
            check(format(plugin,"terrain.dialog.body","Deep clean level","world",0,10,0,10,64,80,25,3," Target floor y=64.","").contains("Remove: 25; fill: 3."),"native terrain dialog is mixed language");
            List<net.kyori.adventure.text.Component> shown=new ArrayList<>();
            var player=(org.bukkit.entity.Player)Proxy.newProxyInstance(org.bukkit.entity.Player.class.getClassLoader(),new Class[]{org.bukkit.entity.Player.class},(o,m,a)->{if(m.getName().equals("sendMessage")){shown.add((net.kyori.adventure.text.Component)a[0]);return null;}throw new UnsupportedOperationException(m.toString());});
            for(String lang:List.of("EN","PT")) {
                config.set("language",lang);shown.clear();
                com.elitonvita.aibuilder.BuilderUi.followControls(plugin,player,true,"/builder alfred");
                var control=shown.getFirst().children().getFirst();
                check(((net.kyori.adventure.text.TextComponent)control).content().equals(lang.equals("EN")?"[Stay]":"[Ficar]"),"actual movement component has wrong language");
                check(control.clickEvent().equals(net.kyori.adventure.text.event.ClickEvent.runCommand("/builder alfred stay")),"localized label changed movement command");
                check(((String)call("LocaleText","raw",new Class[]{String.class,Object[].class},"message_e8e91dc6b587",new Object[0])).equals("ok: ja construido"),"language affected action-result classification");
            }
            config.set("language","EN");
            long start=System.nanoTime();for(int i=0;i<50;i++)text(plugin,"a".repeat(30000));check(System.nanoTime()-start<5_000_000_000L,"unbounded template matching");
            System.out.println("LocaleTextTest: "+checks+" checks passed");
        } finally {try(var paths=Files.walk(folder)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
}
