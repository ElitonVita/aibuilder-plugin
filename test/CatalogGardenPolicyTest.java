import com.elitonvita.aibuilder.*;
import com.google.gson.JsonObject;
import java.util.*;
import static com.elitonvita.aibuilder.CatalogGardenPolicy.*;

/** Pure final-map adversarial regressions. Does not simulate Minecraft fluids or random ticks. */
public class CatalogGardenPolicyTest {
    static int checks;
    static void check(boolean ok,String text){checks++;if(!ok)throw new AssertionError(text);}
    static Cell block(String m){return new Cell(m,null);}
    static Map<Point,Cell> basin(){
        Map<Point,Cell> map=new HashMap<>();
        for(int x=0;x<4;x++)for(int z=0;z<3;z++){
            map.put(new Point(x,0,z),block("SMOOTH_QUARTZ"));
            map.put(new Point(x,1,z),x==0||x==3||z==0||z==2?block("SMOOTH_QUARTZ"):new Cell("WATER","minecraft:water[level=0]"));
        }return map;
    }
    public static void main(String[]args){
        var map=basin(); check(problem(map)==null,"sealed source basin refused");
        map.put(new Point(1,0,1),block("SEA_LANTERN"));check(problem(map)==null,"full sea lantern pool floor rejected");
        check(mutationProblem(map,new Point(0,1,1),block("STONE_SLAB"))!=null,"runtime replacement opened basin wall");
        check(mutationProblem(map,new Point(1,0,1),null)!=null,"runtime break removed basin floor");
        check(mutationProblem(map,new Point(0,1,1),block("STONE"))==null,"sealed replacement refused");
        Set<Point> supports=basinSupports(map,new Point(1,1,1));
        check(supports.size()==8 && supports.contains(new Point(3,1,1)),"whole connected basin walls and bottom required");
        for(String leak:List.of("OAK_STAIRS","STONE_SLAB","GLASS_PANE","OAK_LEAVES","OAK_DOOR","SAND","POPPY","WATER","LAVA")){
            map=basin();map.put(new Point(0,1,1),block(leak));check(problem(map)!=null,"leaky wall accepted: "+leak);
            map=basin();map.put(new Point(1,0,1),block(leak));check(problem(map)!=null,"leaky bottom accepted: "+leak);
        }
        map=basin();map.remove(new Point(0,1,1));check(problem(map)!=null,"missing wall accepted");
        map=basin();map.remove(new Point(1,0,1));check(problem(map)!=null,"missing floor accepted");
        map=basin();map.put(new Point(1,1,1),new Cell("WATER","minecraft:water[level=1]"));check(problem(map)!=null,"flowing water accepted");
        map=basin();map.put(new Point(1,2,1),new Cell("WATER","minecraft:water[level=0]"));check(problem(map)!=null,"stacked fluid accepted");
        map=basin();map.put(new Point(0,1,1),new Cell("STONE","minecraft:stone[waterlogged=true]"));check(problem(map)!=null,"waterlogged seal accepted");
        for(String flower:FLOWERS){
            map=new HashMap<>();map.put(new Point(0,1,0),block(flower));check(problem(map)!=null,"floating flower accepted");
            map.put(new Point(0,0,0),block("STONE"));check(problem(map)!=null,"flower accepted on stone");
            map.put(new Point(0,0,0),block("GRASS_BLOCK"));check(problem(map)==null,"valid soil flower rejected");
        }
        check(!isGarden("WITHER_ROSE")&&!isGarden("SUNFLOWER"),"harmful or double plant allowed");
        map=new HashMap<>();map.put(new Point(0,5,0),new Cell("LIGHT","minecraft:light[level=15,waterlogged=false]"));check(problem(map)==null,"dry invisible light refused");
        for(String data:Arrays.asList(null,"minecraft:light[level=14,waterlogged=false]","minecraft:light[level=15,waterlogged=true]")){
            map.put(new Point(0,5,0),new Cell("LIGHT",data));check(problem(map)!=null,"unsafe or unqualified light accepted");
        }
        // Import validates resolved geometry even with a deliberately permissive material checker.
        String invalid="{\"id\":\"unsafe_garden\",\"title\":\"T\",\"description\":\"D\",\"parts\":[{\"type\":\"box\",\"material\":\"WATER\",\"data\":\"minecraft:water[level=0]\",\"from\":[0,1,0],\"to\":[0,1,0]}]}";
        try{UserPrebuilts.parse(invalid,Set.of(),100,(m,d)->null);throw new AssertionError("unsafe user JSON accepted");}
        catch(IllegalArgumentException expected){check(expected.getMessage().contains("jardim"),"wrong importer failure");}
        System.out.println("PASS: "+checks+" catalog garden geometry assertions");
    }
}
