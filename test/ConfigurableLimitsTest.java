import com.elitonvita.aibuilder.*;
import com.google.gson.*;
import java.util.*;
import java.nio.file.*;

public class ConfigurableLimitsTest {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    static ConstructionLimits limits(int side,int height,long volume,int blocks,int parts,long visits,int preview) {
        return new ConstructionLimits(side,side,height,volume,blocks,parts,1024,visits,67_108_864L,preview);
    }
    static String sparse(int side,int height) {
        return "{\"id\":\"estadio_teste\",\"title\":\"Estadio\",\"description\":\"Envelope de teste\",\"parts\":[{\"type\":\"box\",\"material\":\"STONE\",\"from\":[0,0,0],\"to\":[0,0,0]},{\"type\":\"box\",\"material\":\"STONE\",\"from\":["+(side-1)+","+(height-1)+","+(side-1)+"],\"to\":["+(side-1)+","+(height-1)+","+(side-1)+"]}]}";
    }
    static boolean valid(String text,ConstructionLimits limits) {
        try{UserPrebuilts.parse(text,Set.of(),limits,(m,d)->null);return true;}catch(IllegalArgumentException e){return false;}
    }
    public static void main(String[] args) throws Exception {
        var normal=limits(200,200,8_000_000,2500,4096,128_000_000,20000);
        check(valid(sparse(200,200),normal),"200x200x200 envelope rejected");
        check(!valid(sparse(201,200),normal),"configured width not enforced");
        check(!valid(sparse(200,201),normal),"configured height not enforced");
        check(valid(sparse(220,220),limits(220,220,11_000_000,2500,4096,128_000_000,20000)),"custom envelope above 200 rejected");
        check(!valid(sparse(200,200),limits(200,200,7_999_999,2500,4096,128_000_000,20000)),"volume cap ignored");
        check(!BlueprintCompiler.compile(JsonParser.parseString(sparse(1,1)).getAsJsonObject(),0,0,0,0).isValid(),"invalid block limit must fail atomically");
        check(normal.fits(200,200,200)&&!normal.fits(Long.MAX_VALUE,Long.MAX_VALUE,Long.MAX_VALUE),"overflowing dimensions accepted");
        var entry=UserPrebuilts.parse(OrientationLimitsTest.model(2,200,200),Set.of(),limits(200,200,8_000_000,150000,4096,128_000_000,100),(m,d)->null);
        var compiled=BlueprintCompiler.compile(entry.blueprint(),0,0,0,limits(200,200,8_000_000,150000,4096,128_000_000,100));
        check(compiled.isValid()&&compiled.blocks.size()==120000,"old 100000 block ceiling remains");
        check(!valid(OrientationLimitsTest.model(2,200,200),normal),"explicit 2500 block cap bypassed");
        String preview=PrebuiltPreview.render(List.of(),List.of(entry),null,limits(200,200,8_000_000,150000,4096,128_000_000,100),1);
        check(preview.contains("\"blockCount\":120000")&&preview.contains("\"previewTruncated\":true"),"large preview hides sampling");
        JsonObject bp=JsonParser.parseString(sparse(1,1)).getAsJsonObject();
        for(int i=0;i<4095;i++)bp.getAsJsonArray("parts").add(bp.getAsJsonArray("parts").get(0).deepCopy());
        check(!valid(bp.toString(),normal),"part budget not enforced");
        check(valid(bp.toString(),limits(200,200,8_000_000,2500,5000,128_000_000,20000)),"parts not configurable");
        check(!valid(OrientationLimitsTest.model(0,200,200),limits(200,200,8_000_000,50000,4096,1000,20000)),"processing budget ignored");
        Path dir=Files.createTempDirectory("aibuilder-limits");Files.writeString(dir.resolve("test.json"),sparse(200,200));
        check(UserPrebuilts.load(dir,Set.of(),normal,(m,d)->null).entries().size()==1,"loader did not use configured limits");
        var tinyFile=new ConstructionLimits(200,200,200,8_000_000,2500,4096,1024,128_000_000,10,20000);
        check(!UserPrebuilts.load(dir,Set.of(),tinyFile,(m,d)->null).errors().isEmpty(),"file budget ignored");
        var cube=PrebuiltCatalog.builtIn().stream().filter(e->e.id().equals("cubo_moderno")).toList();
        check(cube.size()==1,"replacement cube is not a unique built-in");
        var blocks=BlueprintCompiler.compile(cube.getFirst().blueprint(),0,0,0,normal).blocks;
        check(blocks.size()==293&&blocks.stream().filter(b->b.material().equals("LIGHT_BLUE_BED")).count()==2,"old built-in geometry still present");
        try{limits(0,200,8_000_000,2500,4096,128_000_000,20000);throw new AssertionError("invalid config accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" configurable construction limit assertions");
    }
}
