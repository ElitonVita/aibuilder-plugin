package com.elitonvita.aibuilder;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.*;

/** Original voxel designs; compressed final geometry uses the standard blueprint compiler. */
public final class PrebuiltCatalog {
    private PrebuiltCatalog() {}
    public record Entry(String id, String category, String title, String description, JsonObject blueprint) {}
    public static final List<String> CATEGORIES = List.of("houses", "trees", "buildings", "treehouses");
    public static final List<String> PLANNED = List.of("furniture", "gardens", "bridges", "towers");
    private static final List<Entry> ENTRIES = List.of(cottage(), modern(), cube(), ranch(), giantTree(), building(), treehouse());
    private static volatile List<Entry> USER = List.of();
    /** Embutidos primeiro; depois os pre-moldados carregados da pasta prebuilts/ do plugin. */
    public static List<Entry> all() {
        List<Entry> all = new ArrayList<>(ENTRIES);
        all.addAll(USER);
        return List.copyOf(all);
    }
    public static List<Entry> builtIn() { return ENTRIES; }
    public static void setUser(List<Entry> entries) { USER = List.copyOf(entries); }
    public static Entry find(String id) { return all().stream().filter(e -> e.id().equalsIgnoreCase(id)).findFirst().orElse(null); }

    private record Point(int x, int y, int z) {}
    private record Voxel(String material, String data) {}
    private static final class Design {
        final Map<Point,Voxel> blocks = new HashMap<>();
        final JsonArray route = new JsonArray();
        void put(int x,int y,int z,String material) { put(x,y,z,material,null); }
        void put(int x,int y,int z,String material,String properties) {
            blocks.put(new Point(x,y,z),new Voxel(material,properties==null?null:"minecraft:"+material.toLowerCase(Locale.ROOT)+"["+properties+"]"));
        }
        void box(int x,int y,int z,int xx,int yy,int zz,String material) {
            for(int a=x;a<=xx;a++)for(int b=y;b<=yy;b++)for(int c=z;c<=zz;c++)put(a,b,c,material);
        }
        void clear(int x,int y,int z,int xx,int yy,int zz) {
            blocks.keySet().removeIf(p -> p.x>=x&&p.x<=xx&&p.y>=y&&p.y<=yy&&p.z>=z&&p.z<=zz);
        }
        void walls(int x,int y,int z,int xx,int yy,int zz,String material) {
            box(x,y,z,xx,yy,z,material);box(x,y,zz,xx,yy,zz,material);
            box(x,y,z,x,yy,zz,material);box(xx,y,z,xx,yy,zz,material);
        }
        void window(int x,int y,int z,int xx,int yy,int zz) { box(x,y,z,xx,yy,zz,"GLASS_PANE"); }
        void door(int x,int y,int z,String material,String facing,String hinge) {
            for(int half=0;half<2;half++)put(x,y+half,z,material,"facing="+facing+",half="+(half==0?"lower":"upper")+",hinge="+hinge+",open=false,powered=false");
        }
        void stair(int x,int y,int z,String material,String facing) {
            put(x,y,z,material,"facing="+facing+",half=bottom,shape=straight,waterlogged=false");
        }
        void flight(int x,int baseY,int z,int rise,int direction,String material) {
            for(int step=0;step<rise;step++)for(int lane=0;lane<2;lane++) {
                int sx=x+step*direction,sy=baseY+step+1;
                stair(sx,sy,z+lane,material,direction>0?"east":"west");
            }
        }
        void gable(int x,int y,int z,int xx,int zz,String roof,String gable) {
            for(int inset=0;x+inset<=xx-inset;inset++) {
                int left=x+inset,right=xx-inset;
                for(int depth=z;depth<=zz;depth++) {
                    if(left==right)put(left,y+inset,depth,roof.replace("_STAIRS","_PLANKS"));
                    else{stair(left,y+inset,depth,roof,"east");stair(right,y+inset,depth,roof,"west");}
                }
                if(left+1<=right-1){box(left+1,y+inset,z+1,right-1,y+inset,z+1,gable);box(left+1,y+inset,zz-1,right-1,y+inset,zz-1,gable);}
            }
        }
        void visit(int x,int y,int z) { route.add(vector(x,y,z)); }
        void crown(double x,double y,double z,double rx,double ry,double rz) {
            for(int a=(int)Math.floor(x-rx);a<=Math.ceil(x+rx);a++)for(int b=(int)Math.floor(y-ry);b<=Math.ceil(y+ry);b++)
                for(int c=(int)Math.floor(z-rz);c<=Math.ceil(z+rz);c++)
                    if(Math.pow((a-x)/rx,2)+Math.pow((b-y)/ry,2)+Math.pow((c-z)/rz,2)<=1&&!blocks.containsKey(new Point(a,b,c)))
                        put(a,b,c,"OAK_LEAVES","persistent=true,waterlogged=false");
        }
        boolean connect(int x,int y,int z) {
            Voxel v=blocks.get(new Point(x,y,z));
            return v!=null&&!v.material.endsWith("_DOOR")&&!v.material.endsWith("_STAIRS")&&!v.material.endsWith("_LEAVES");
        }
        void paneStates() {
            for(var e:new ArrayList<>(blocks.entrySet()))if(e.getValue().material.endsWith("_PANE")) {
                Point p=e.getKey();String state="north="+connect(p.x,p.y,p.z-1)+",east="+connect(p.x+1,p.y,p.z)
                    +",south="+connect(p.x,p.y,p.z+1)+",west="+connect(p.x-1,p.y,p.z)+",waterlogged=false";
                put(p.x,p.y,p.z,e.getValue().material,state);
            }
        }
        JsonArray vector(int x,int y,int z) {JsonArray v=new JsonArray();v.add(x);v.add(y);v.add(z);return v;}
        JsonObject blueprint(String name) {
            paneStates();Map<Point,Voxel> remaining=new HashMap<>(blocks);JsonArray parts=new JsonArray();
            List<Point> order=new ArrayList<>(remaining.keySet());
            order.sort(Comparator.comparingInt(Point::y).thenComparingInt(Point::z).thenComparingInt(Point::x));
            for(Point p:order) {
                Voxel v=remaining.get(p);if(v==null)continue;int xx=p.x,yy=p.y,zz=p.z;
                while(v.equals(remaining.get(new Point(xx+1,p.y,p.z))))xx++;
                boolean extend=true;
                while(extend){for(int x=p.x;x<=xx;x++)if(!v.equals(remaining.get(new Point(x,p.y,zz+1)))){extend=false;break;}if(extend)zz++;}
                extend=true;
                while(extend){outer:for(int x=p.x;x<=xx;x++)for(int z=p.z;z<=zz;z++)if(!v.equals(remaining.get(new Point(x,yy+1,z)))){extend=false;break outer;}if(extend)yy++;}
                JsonObject part=new JsonObject();part.addProperty("type","box");part.addProperty("material",v.material);
                part.add("from",vector(p.x,p.y,p.z));part.add("to",vector(xx,yy,zz));if(v.data!=null)part.addProperty("data",v.data);parts.add(part);
                for(int x=p.x;x<=xx;x++)for(int y=p.y;y<=yy;y++)for(int z=p.z;z<=zz;z++)remaining.remove(new Point(x,y,z));
            }
            JsonObject bp=new JsonObject();bp.addProperty("name",name);bp.add("parts",parts);bp.add("walkthrough",route);return bp;
        }
        Entry entry(String id,String category,String title,String description){return new Entry(id,category,title,description,blueprint(id));}
    }
    private static Entry cottage() {
        Design d=new Design();d.box(1,0,1,11,0,9,"STONE_BRICKS");d.box(2,0,2,10,0,8,"SPRUCE_PLANKS");d.walls(1,1,1,11,4,9,"OAK_PLANKS");
        for(int x:new int[]{1,11})for(int z:new int[]{1,9})d.box(x,1,z,x,4,z,"SPRUCE_LOG");
        for(int x:new int[]{3,8}){d.window(x,2,9,x+1,3,9);d.window(x,2,1,x+1,3,1);}
        d.window(1,2,3,1,3,6);d.window(11,2,3,11,3,6);d.door(6,1,9,"SPRUCE_DOOR","south","left");
        d.gable(0,5,0,12,10,"DARK_OAK_STAIRS","OAK_PLANKS");d.box(9,5,3,9,10,3,"BRICKS");d.box(5,0,10,7,0,12,"COBBLESTONE");
        d.put(3,1,3,"CRAFTING_TABLE");d.put(3,1,4,"FURNACE");d.box(9,1,3,9,2,3,"BOOKSHELF");d.box(8,1,6,9,1,6,"BROWN_WOOL");d.put(6,4,5,"GLOWSTONE");
        d.visit(6,1,12);d.visit(6,1,8);d.visit(6,1,4);
        return d.entry("casa_campo","houses","Country Cottage","13 x 13 x 12. Spruce door, glass windows, gabled roof, chimney, and walkable interior.");
    }
    private static void modernShell(Design d,int width,int depth,int floors) {
        for(int level=0;level<=floors;level++)d.box(0,level*5,0,width-1,level*5,depth-1,level==0?"POLISHED_ANDESITE":"SMOOTH_QUARTZ");
        for(int level=0;level<floors;level++) {
            int y=level*5;d.walls(0,y+1,0,width-1,y+4,depth-1,"WHITE_CONCRETE");
            for(int x:new int[]{0,width-1})for(int z:new int[]{0,depth-1})d.box(x,y+1,z,x,y+4,z,"GRAY_CONCRETE");
            for(int x:new int[]{2,width-5})d.window(x,y+2,0,x+2,y+4,0);
            d.window(2,y+2,depth-1,4,y+4,depth-1);d.window(width-4,y+2,depth-1,width-2,y+4,depth-1);
            d.window(width-1,y+2,3,width-1,y+4,depth-4);d.window(0,y+2,5,0,y+4,depth-3);
            d.box(width-4,y+1,depth-4,width-3,y+1,depth-4,"LIGHT_BLUE_WOOL");d.put(width-3,y+1,2,"CRAFTING_TABLE");d.put(width-4,y+4,depth-4,"SEA_LANTERN");
            if(level>0){d.clear(2,y,2,6,y,3);d.flight(2,y-5,2,5,1,"SMOOTH_QUARTZ_STAIRS");d.window(2,y+1,1,6,y+1,1);d.window(2,y+1,4,6,y+1,4);}
        }
        d.box(width/2-1,0,depth,width/2+1,0,depth,"SMOOTH_STONE");d.door(width/2,1,depth-1,"BIRCH_DOOR","south","left");
        d.visit(width/2,1,depth);d.visit(width/2,1,depth-3);
        for(int level=1;level<floors;level++){d.visit(7,level*5+1,3);d.visit(width-3,level*5+1,depth-3);}
    }
    private static Entry modern() {
        Design d=new Design();modernShell(d,12,10,2);d.box(8,11,2,10,11,3,"MOSS_BLOCK");d.put(9,12,2,"OAK_LEAVES","persistent=true,waterlogged=false");
        return d.entry("casa_moderna","houses","Modern House","12 x 11 x 13. Two floors joined by a two-wide quartz staircase, with rails and large windows.");
    }
    private static Entry cube() {
        Design d=new Design();d.box(1,0,1,8,0,8,"POLISHED_ANDESITE");d.box(2,0,2,7,0,7,"BIRCH_PLANKS");
        d.walls(1,1,1,8,4,8,"WHITE_CONCRETE");d.box(0,5,0,9,5,9,"SMOOTH_QUARTZ");d.box(3,0,9,6,0,10,"SMOOTH_STONE");
        for(int x:new int[]{1,8})for(int z:new int[]{1,8})d.box(x,1,z,x,4,z,"GRAY_CONCRETE");
        d.window(2,2,8,2,3,8);d.window(7,2,8,7,3,8);d.window(3,2,1,6,3,1);
        d.window(1,2,3,1,3,6);d.window(8,2,3,8,3,6);
        d.door(4,1,8,"BIRCH_DOOR","south","left");d.door(5,1,8,"BIRCH_DOOR","south","right");
        d.put(2,1,3,"LIGHT_BLUE_BED","facing=north,part=head,occupied=false");
        d.put(2,1,4,"LIGHT_BLUE_BED","facing=north,part=foot,occupied=false");
        d.put(2,1,2,"BOOKSHELF");d.put(7,1,2,"CRAFTING_TABLE");d.put(7,1,3,"FURNACE");d.box(6,1,6,7,1,6,"LIGHT_GRAY_WOOL");
        d.put(4,4,4,"SEA_LANTERN");d.put(6,4,6,"SEA_LANTERN");
        d.visit(4,1,10);d.visit(4,1,7);d.visit(3,1,4);d.visit(6,1,2);
        return d.entry("cubo_moderno","houses","Modern Cube Home","10 x 11 x 6. A 6 x 6 interior, three-block headroom, double door, functional bed, crafting table, and lit living area.");
    }
    private static Entry ranch() {
        Design d=new Design();d.box(1,0,1,9,0,5,"STONE_BRICKS");d.box(2,0,2,8,0,4,"BIRCH_PLANKS");d.walls(1,1,1,9,4,5,"SPRUCE_PLANKS");
        for(int x:new int[]{1,9})for(int z:new int[]{1,5})d.box(x,1,z,x,4,z,"SPRUCE_LOG");
        d.box(1,0,6,9,0,8,"SMOOTH_STONE");d.box(0,5,0,10,5,6,"DARK_OAK_PLANKS");d.door(3,1,5,"SPRUCE_DOOR","south","left");
        d.window(5,2,5,8,3,5);d.window(9,2,2,9,3,3);d.window(3,2,1,6,3,1);d.put(2,1,2,"BOOKSHELF");d.put(8,1,2,"CRAFTING_TABLE");d.box(6,1,3,7,1,3,"BLACK_WOOL");d.put(4,4,3,"SEA_LANTERN");
        d.visit(3,1,8);d.visit(3,1,4);d.visit(4,1,3);
        return d.entry("casa_rasa","houses","Ranch House","11 x 9 x 6. Single-storey spruce home with a deck, usable door, and framed windows.");
    }
    private static void tree(Design d,int cx,int cz) {
        d.box(cx-1,0,cz-1,cx+1,11,cz+1,"DARK_OAK_LOG");d.box(cx,12,cz,cx+1,16,cz+1,"OAK_LOG");
        d.box(cx-3,0,cz-1,cx+3,0,cz+1,"ROOTED_DIRT");d.box(cx-1,0,cz-3,cx+1,0,cz+3,"ROOTED_DIRT");
        for(int arm=-1;arm<=1;arm+=2)for(int step=1;step<=4;step++){
            d.put(cx+arm*step,8+(step-1)/2,cz,"OAK_LOG","axis=x");d.put(cx+arm*step,8+step/2,cz,"OAK_LOG","axis=x");
            d.put(cx,10+(step-1)/2,cz+arm*step,"DARK_OAK_LOG","axis=z");d.put(cx,10+step/2,cz+arm*step,"DARK_OAK_LOG","axis=z");
        }
        d.crown(cx,15,cz,5.6,3.4,5.6);d.crown(cx-3,12,cz+1,3.2,2.3,3.4);d.crown(cx+3,13,cz-2,3.2,2.3,3.4);
    }
    private static Entry giantTree() {
        Design d=new Design();tree(d,7,7);
        return d.entry("arvore_gigante","trees","Giant Oak Tree","15 x 19 x 15 reserved. Thick trunk, roots, branches, and a rounded canopy of persistent leaves.");
    }
    private static Entry building() {
        Design d=new Design();modernShell(d,13,11,3);d.door(7,1,10,"BIRCH_DOOR","south","right");
        return d.entry("predio_moderno_3_andares","buildings","Modern 3-Story Building","13 x 12 x 16. Concrete, quartz, and glass; ground floor plus two upper floors, wide stairs, landings, rails, and lighting.");
    }
    private static Entry treehouse() {
        Design d=new Design();tree(d,9,7);d.box(2,8,2,14,8,13,"SPRUCE_PLANKS");d.clear(4,9,4,12,12,11);d.walls(4,9,4,12,12,11,"OAK_PLANKS");
        d.box(3,7,4,13,7,4,"DARK_OAK_LOG");d.box(3,7,11,13,7,11,"DARK_OAK_LOG");d.box(9,7,4,10,7,11,"DARK_OAK_LOG");d.door(6,9,11,"SPRUCE_DOOR","south","left");
        d.window(8,10,11,10,11,11);d.window(4,10,6,4,11,8);d.window(12,10,6,12,11,9);d.window(6,10,4,10,11,4);d.gable(3,13,3,13,12,"SPRUCE_STAIRS","OAK_PLANKS");
        d.box(9,0,7,10,18,8,"DARK_OAK_LOG");d.window(2,9,2,14,9,2);d.window(2,9,3,2,9,13);d.window(14,9,3,14,9,13);d.window(8,9,13,13,9,13);
        d.clear(3,8,13,6,8,14);d.clear(2,1,13,8,7,16);d.box(2,0,15,3,0,16,"COARSE_DIRT");d.flight(3,0,15,4,1,"SPRUCE_STAIRS");
        d.box(7,4,13,8,4,16,"SPRUCE_PLANKS");d.flight(6,4,13,4,-1,"SPRUCE_STAIRS");d.put(5,9,5,"CRAFTING_TABLE");d.put(11,9,5,"BOOKSHELF");d.put(7,12,7,"SHROOMLIGHT");
        d.visit(2,1,15);d.visit(7,5,15);d.visit(3,9,12);d.visit(6,9,10);
        return d.entry("casa_na_arvore","treehouses","Treehouse","17 x 19 x 17 reserved. Includes the tree, raised cabin, railed balcony, and a two-flight staircase from the ground.");
    }
}
