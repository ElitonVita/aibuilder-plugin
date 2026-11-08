import com.elitonvita.aibuilder.*;
import com.google.gson.*;
import java.util.*;

/** Geometry/state rotations and importer limits, independent of a Paper server. */
public class OrientationLimitsTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static String model(int top,int width,int depth) {
        return "{\"id\":\"teste_alto\",\"title\":\"T\",\"description\":\"D\",\"parts\":[{\"type\":\"box\",\"material\":\"STONE\",\"from\":[0,0,0],\"to\":["+(width-1)+","+top+","+(depth-1)+"]}]}";
    }
    static void rejects(String json,int maxBlocks,int height,String message) {
        try{UserPrebuilts.parse(json,Set.of(),maxBlocks,height,(m,d)->null);throw new AssertionError("accepted "+message);}
        catch(IllegalArgumentException e){check(e.getMessage().contains(message),e.getMessage());}
    }
    static Set<String> cells(List<BlueprintCompiler.Block> blocks) {
        var result=new HashSet<String>();for(var b:blocks)result.add(b.x()+","+b.y()+","+b.z()+","+b.material()+","+b.data());return result;
    }
    public static void main(String[] args) {
        check(UserPrebuilts.parse(model(99,1,1),Set.of(),2500,(m,d)->null)!=null,"100-high default rejected");
        rejects(model(100,1,1),2500,100,"altura maior que 100");
        check(UserPrebuilts.parse(model(199,1,1),Set.of(),2500,200,(m,d)->null)!=null,"200-high rejected");
        check(UserPrebuilts.parse(model(299,1,1),Set.of(),2500,300,(m,d)->null)!=null,"configured height above 200 rejected");
        check(UserPrebuilts.parse(model(9,25,10),Set.of(),2500,(m,d)->null)!=null,"2500 rejected");
        rejects(model(9,26,10),2500,100,"limite de blocos");
        check(UserPrebuilts.parse(model(9,26,10),Set.of(),3000,(m,d)->null)!=null,"larger configured project rejected");
        rejects(model(0,1,1).replace("\"parts\"","\"entrance\":\"up\",\"parts\""),2500,100,"entrance");
        check(BlueprintOrientation.label(null).equals("original"),"default direction must remain original");
        check(BlueprintOrientation.parse("Norte").equals("north")&&BlueprintOrientation.parse("O").equals("west"),"cardinal aliases");
        String pane="minecraft:glass_pane[north=true,east=false,south=false,west=true,waterlogged=false]";
        String rotated=BlueprintOrientation.data(pane,1);
        check(rotated.contains("east=true")&&rotated.contains("north=true")&&rotated.contains("south=false")&&rotated.contains("waterlogged=false"),"pane connections not rotated");
        check(BlueprintOrientation.data("minecraft:oak_log[axis=x]",1).contains("axis=z"),"log axis");
        check(BlueprintOrientation.data("minecraft:oak_stairs[facing=north,shape=inner_left,half=bottom]",1).contains("facing=east,shape=inner_left"),"stairs facing/handedness");
        check(BlueprintOrientation.data("minecraft:oak_door[facing=south,hinge=left,half=upper]",1).contains("facing=west,hinge=left,half=upper"),"door halves/hinge");
        for(var entry:PrebuiltCatalog.builtIn()) {
            var original=BlueprintCompiler.compile(entry.blueprint(),-31,64,17,2500);check(original.isValid(),"source compile");
            for(String facing:List.of("north","east","south","west")) {
                var bp=BlueprintOrientation.orient(entry.blueprint(),facing);
                var result=BlueprintCompiler.compile(bp,-31,64,17,2500);
                check(result.isValid()&&result.blocks.size()==original.blocks.size(),"rotated compile/count");
                int turns=BlueprintOrientation.turns("south",facing);
                check(cells(BlueprintOrientation.rotate(result.blocks,(4-turns)%4)).equals(cells(original.blocks)),"inverse rotation lost geometry/states: "+entry.id());
                if(entry.id().equals("cubo_moderno")) {
                    var door=result.blocks.stream().filter(b->b.material().endsWith("_DOOR")).toList();
                    check(door.size()==4&&door.stream().allMatch(b->b.data().contains("facing="+facing)),"doors facing wrong direction");
                    var bed=result.blocks.stream().filter(b->b.material().endsWith("_BED")).toList();
                    check(bed.size()==2,"bed pair missing");
                    var head=bed.stream().filter(b->b.data().contains("part=head")).findFirst().orElseThrow();
                    var foot=bed.stream().filter(b->b.data().contains("part=foot")).findFirst().orElseThrow();
                    check(Math.abs(head.x()-foot.x())+Math.abs(head.z()-foot.z())==1&&head.y()==foot.y(),"rotated bed disconnected");
                    check(head.data().contains("facing="+List.of("north","east","south","west").get(turns)),"bed head direction lost");
                    int w=result.blocks.stream().mapToInt(BlueprintCompiler.Block::x).max().orElseThrow()-result.blocks.stream().mapToInt(BlueprintCompiler.Block::x).min().orElseThrow()+1;
                    int d=result.blocks.stream().mapToInt(BlueprintCompiler.Block::z).max().orElseThrow()-result.blocks.stream().mapToInt(BlueprintCompiler.Block::z).min().orElseThrow()+1;
                    check(w==(turns%2==0?10:11)&&d==(turns%2==0?11:10),"rotated footprint not swapped");
                }
            }
            check(!entry.blueprint().has("_rotation"),"shared source mutated");
        }
        var source=PrebuiltCatalog.find("cubo_moderno").blueprint().deepCopy();source.addProperty("entrance","east");
        check(BlueprintOrientation.orient(source,"north").get("_rotation").getAsInt()==3,"source entrance ignored");
        System.out.println("PASS: "+checks+" cardinal rotation and import limit assertions");
    }
}
