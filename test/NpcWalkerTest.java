import com.elitonvita.aibuilder.NpcWalker;
import com.elitonvita.aibuilder.BuilderTravel;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.Mannequin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import java.lang.reflect.*;
import java.util.*;

/** Tests the real velocity controller with modeled flat-ground physics, not Minecraft rendering. */
public class NpcWalkerTest {
    static int checks;
    static void check(boolean value,String message){ checks++; if(!value)throw new AssertionError(message); }
    interface Call { Object call(Method method,Object[] args); }
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,Call call){
        return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class[]{type},(object,method,args)->{
            if(method.getName().equals("equals"))return object==args[0];
            if(method.getName().equals("hashCode"))return System.identityHashCode(object);
            if(method.getName().equals("toString"))return "Test"+type.getSimpleName();
            return call.call(method,args);
        });
    }
    static Object throwUnsupported(Method m){throw new UnsupportedOperationException(m.toString());}
    static class Scene {
        World world;
        Location location;
        Vector velocity=new Vector();
        Mannequin npc;
        boolean loaded=true, liquid=false;
        int reads, teleports;
        Set<String> obstacles=new HashSet<>();
        Scene(){
            world=proxy(World.class,(method,args)->switch(method.getName()){
                case "getName" -> "world";
                case "getUID" -> new UUID(0,1);
                case "getSpawnLocation" -> new Location(world,10000,64,10000);
                case "getWorldBorder" -> proxy(WorldBorder.class,(m,a)->m.getName().equals("isInside")?true:throwUnsupported(m));
                case "getMinHeight" -> -64;
                case "getMaxHeight" -> 320;
                case "isChunkLoaded" -> loaded;
                case "getBlockAt" -> {reads++;if(args.length==1){Location l=(Location)args[0];yield block(l.getBlockX(),l.getBlockY(),l.getBlockZ());}yield block((int)args[0],(int)args[1],(int)args[2]);}
                default -> throw new UnsupportedOperationException(method.toString());
            });
            location=new Location(world,.5,64,.5);
            npc=proxy(Mannequin.class,(method,args)->switch(method.getName()){
                case "isDead" -> false;
                case "getLocation" -> location.clone();
                case "getWorld" -> world;
                case "getVelocity" -> velocity.clone();
                case "getBoundingBox" -> new BoundingBox(location.getX()-.3,location.getY(),location.getZ()-.3,location.getX()+.3,location.getY()+1.8,location.getZ()+.3);
                case "isOnGround" -> true;
                case "setVelocity" -> {velocity=((Vector)args[0]).clone();yield null;}
                case "setRotation" -> {location.setYaw((float)args[0]);location.setPitch((float)args[1]);yield null;}
                case "setBodyYaw","setJumping","setGravity" -> null;
                case "teleport","teleportAsync" -> {teleports++;throw new AssertionError("walker teleported");}
                default -> throw new UnsupportedOperationException(method.toString());
            });
        }
        Block block(int x,int y,int z){
            boolean solid=y<64||obstacles.contains(x+","+y+","+z);
            return proxy(Block.class,(method,args)->switch(method.getName()){
                case "getX" -> x;case "getY" -> y;case "getZ" -> z;
                case "getWorld" -> world;case "getLocation" -> new Location(world,x,y,z);
                case "isPassable" -> !solid;
                case "isLiquid" -> liquid;
                case "getType" -> solid?Material.STONE:Material.AIR;
                case "getBoundingBox" -> solid?new BoundingBox(x,y,z,x+1,y+1,z+1):new BoundingBox();
                default -> throw new UnsupportedOperationException(method.toString());
            });
        }
        void advance(){location.add(velocity.getX(),0,velocity.getZ());}
    }
    public static void main(String[]args){
        Scene scene=new Scene();NpcWalker walker=new NpcWalker();
        walker.request(scene.npc,new Location(scene.world,6.5,64,.5),1,.2,l->true);
        for(int i=0;i<80&&walker.hasGoal();i++){
            walker.tick(scene.npc);
            check(scene.velocity.clone().setY(0).length()<=.201,"speed jumped");scene.advance();
        }
        check(walker.state()==NpcWalker.State.ARRIVED,"walker did not arrive");
        check(scene.location.getX()>5.4&&scene.location.getX()<6.7,"unbounded displacement");
        check(scene.teleports==0,"teleport fallback");
        scene.velocity=new Vector(.2,-.1,.2);walker.stop(scene.npc);
        check(scene.velocity.getX()==0&&scene.velocity.getZ()==0&&scene.velocity.getY()==-.1,"Stay interferes with gravity");
        Scene unloaded=new Scene();unloaded.loaded=false;
        walker.request(unloaded.npc,new Location(unloaded.world,6.5,64,.5),1,.2,l->true);walker.tick(unloaded.npc);
        check(walker.state()==NpcWalker.State.BLOCKED&&unloaded.reads==0,"force-read unloaded chunks");
        Scene restricted=new Scene();walker.stop(restricted.npc);
        walker.request(restricted.npc,new Location(restricted.world,6.5,64,.5),1,.2,l->false);walker.tick(restricted.npc);
        check(walker.state()==NpcWalker.State.BLOCKED&&restricted.velocity.lengthSquared()==0,"ignored movement boundary");
        Scene far=new Scene();walker.stop(far.npc);
        walker.request(far.npc,new Location(far.world,200,64,.5),1,.2,l->true);walker.tick(far.npc);
        check(walker.state()==NpcWalker.State.BLOCKED&&far.reads==0&&far.teleports==0,"far owner teleported or forced chunks");
        Scene other=new Scene();walker.stop(other.npc);
        walker.request(other.npc,new Location(scene.world,1,64,1),1,.2,l->true);walker.tick(other.npc);
        check(walker.state()==NpcWalker.State.BLOCKED&&other.teleports==0,"changed worlds");
        Scene stuck=new Scene();walker.stop(stuck.npc);
        walker.request(stuck.npc,new Location(stuck.world,6.5,64,.5),1,.2,l->true);
        for(int i=0;i<65;i++)walker.tick(stuck.npc);
        check(walker.state()==NpcWalker.State.BLOCKED&&stuck.velocity.lengthSquared()==0,"stuck NPC did not stop");
        Scene bobbing=new Scene();walker.stop(bobbing.npc);
        walker.request(bobbing.npc,new Location(bobbing.world,6.5,64,.5),1,.2,l->true);
        for(int i=0;i<65;i++){bobbing.location.setY(64+(i%2)*.1);walker.tick(bobbing.npc);}
        check(walker.state()==NpcWalker.State.BLOCKED,"vertical bobbing hid a stuck path");
        check(NpcWalker.lookPitch(0)==5f,"look does not start level");
        float low=Float.MAX_VALUE, high=-Float.MAX_VALUE;
        for(int t=0;t<320;t++){ low=Math.min(low,NpcWalker.lookPitch(t)); high=Math.max(high,NpcWalker.lookPitch(t)); }
        check(low>=-45f&&high<=55f,"look pitch out of range");
        check(low<-40f&&high>50f,"analysis never looks up at the building and down at the ground");
        Scene leash = new Scene();
        NpcWalker limited = new NpcWalker();
        Location owner = leash.location.clone();
        limited.setBoundary(l -> BuilderTravel.within(l, owner, 4));
        limited.request(leash.npc, new Location(leash.world, 12.5, 64, .5), .4, .2, l -> true);
        for (int tick = 0; tick < 60; tick++) {
            limited.tick(leash.npc); leash.advance();
            check(BuilderTravel.within(leash.location, owner, 4), "walker escaped owner boundary");
        }
        owner.setX(-20);
        limited.tick(leash.npc);
        check(limited.state() == NpcWalker.State.BLOCKED && leash.velocity.lengthSquared() == 0,
                "moving owner did not block existing route");
        check(!BuilderTravel.within(leash.location, new Scene().location, 4), "cross-world radius accepted");
        check(BuilderTravel.within(new Location(leash.world, 4,64,0), new Location(leash.world,0,64,0),4), "radius edge rejected");
        check(!BuilderTravel.within(new Location(leash.world, 4.01,64,0), new Location(leash.world,0,64,0),4), "radius overflow accepted");
        check(!BuilderTravel.within(new Location(leash.world,0,69,0), new Location(leash.world,0,64,0),4), "vertical radius ignored");
        Scene recall = new Scene();
        Location spot = BuilderTravel.recallSpot(recall.location, l -> true, box -> false);
        check(spot != null && spot.distanceSquared(recall.location) <= 16, "no nearby recall destination");
        check(BuilderTravel.recallSpot(recall.location, l -> false, box -> false) == null, "recall ignored permissions/border");
        check(BuilderTravel.recallSpot(recall.location, l -> true, box -> true) == null, "recall overlaps living entity");
        recall.liquid=true;
        check(BuilderTravel.recallSpot(recall.location,l -> true,box -> false)==null,"recall selected liquid");
        recall.liquid=false;
        recall.loaded = false; recall.reads = 0;
        check(BuilderTravel.recallSpot(recall.location, l -> true, box -> false) == null && recall.reads == 0,
                "recall forced unloaded chunks");
        recall.loaded = true;
        for(int x=-4;x<=4;x++) for(int z=-4;z<=4;z++) for(int y=64;y<=68;y++) recall.obstacles.add(x+","+y+","+z);
        check(BuilderTravel.recallSpot(recall.location,l -> true,box -> false)==null,"recall selected enclosed blocks");
        System.out.println("PASS: "+checks+" physical walker assertions (modeled ground)");
    }
}
