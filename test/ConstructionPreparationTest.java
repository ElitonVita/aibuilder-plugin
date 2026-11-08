import com.elitonvita.aibuilder.*;
import java.util.*;
import org.bukkit.*;
import org.bukkit.block.Block;
import com.google.gson.JsonObject;

/** Temporary server-planned clearing must not become permission to remove live supports. */
public class ConstructionPreparationTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    public static void main(String[]args)throws Exception {
        var c=Class.forName("PluginLifecycleTest$Fixture").getDeclaredConstructor();c.setAccessible(true);
        try(AutoCloseable f=(AutoCloseable)c.newInstance()){
            Object session=CatalogGardenRuntimeTest.get(f,"session");
            var bt=Class.forName("com.elitonvita.aibuilder.BuilderSession$Build");var bc=bt.getDeclaredConstructor(String.class,String.class,List.class,JsonObject.class);bc.setAccessible(true);
            var soil=new BlueprintCompiler.Block(1,64,1,"GRASS_BLOCK",0);var flower=new BlueprintCompiler.Block(1,65,1,"WHITE_TULIP",1);
            Object build=bc.newInstance("test","world",List.of(soil,flower),ConstructionWorkflowTest.area());CatalogGardenRuntimeTest.set(session,"activeBuild",build);
            Map<String,Material> actual=new HashMap<>();boolean[] loaded={true};
            World world=CatalogGardenRuntimeTest.proxy(World.class,(o,m,a)->switch(m.getName()){
                case "getName" -> "world";case "isChunkLoaded" -> loaded[0];
                case "getBlockAt" -> CatalogGardenRuntimeTest.proxy(Block.class,(b,bm,ba)->{if(bm.getName().equals("getType"))return actual.getOrDefault(a[0]+","+a[1]+","+a[2],Material.AIR);throw new UnsupportedOperationException(bm.toString());});
                default -> throw new UnsupportedOperationException(m.toString());
            });
            ((Map<String,Object>)CatalogGardenRuntimeTest.get(f,"serverValues")).put("getWorld",world);
            JsonObject action=ConstructionWorkflowTest.action("break",1,64,1);
            check(problem(session,action)!=null,"ordinary break removed planned flower support");
            ((Set<String>)CatalogGardenRuntimeTest.get(build,"preparationTargets")).add("1,64,1");
            check(problem(session,action)==null,"server-planned clearing rejected a future, absent flower");
            actual.put("1,65,1",Material.WHITE_TULIP);check(problem(session,action)!=null,"preparation removed support of an actual flower");actual.clear();
            actual.put("2,64,1",Material.WATER);check(problem(session,action)!=null,"preparation broke an actual water support");actual.clear();
            loaded[0]=false;check(problem(session,action)!=null,"preparation read unloaded dependent cells");loaded[0]=true;
            Object increment=CatalogGardenRuntimeTest.invoke(build,"withIncrement",new Class[]{List.class,String.class},List.of(),"add");
            check(((Set<?>)CatalogGardenRuntimeTest.get(increment,"preparationTargets")).contains("1,64,1"),"confirmed increment lost preparation provenance");
            action.addProperty("_aibuilder_preclear",true);((Set<?>)CatalogGardenRuntimeTest.get(build,"preparationTargets")).clear();
            check(problem(session,action)!=null,"model JSON forged preparation provenance");
        }
        System.out.println("ConstructionPreparationTest: "+checks+" checks passed");
    }
    static String problem(Object session,JsonObject a)throws Exception{return(String)CatalogGardenRuntimeTest.invoke(session,"gardenMutationProblem",new Class[]{JsonObject.class,String.class},a,null);}
}
