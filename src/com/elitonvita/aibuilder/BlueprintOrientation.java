package com.elitonvita.aibuilder;

import java.util.*;
import com.google.gson.JsonObject;

/** Cardinal rotation of final geometry and Minecraft block states, without Bukkit. */
public final class BlueprintOrientation {
    private BlueprintOrientation() {}
    public static final List<String> DIRECTIONS = List.of("norte", "leste", "sul", "oeste");
    private static final List<String> CARDINAL = List.of("north", "east", "south", "west");
    public static String parse(String input) {
        if(input==null)return null;
        return switch(input.toLowerCase(Locale.ROOT)) {
            case "norte","north","n" -> "north";
            case "leste","east","e","l" -> "east";
            case "sul","south","s" -> "south";
            case "oeste","west","w","o" -> "west";
            default -> null;
        };
    }
    public static String label(String direction) {
        if(direction==null)return "original";
        int i=CARDINAL.indexOf(direction);return i<0?"original":DIRECTIONS.get(i);
    }
    public static int turns(String source,String target) {
        if(source==null||target==null)throw new IllegalArgumentException("orientacao cardinal invalida");
        int a=CARDINAL.indexOf(source), b=CARDINAL.indexOf(target);
        if(a<0||b<0)throw new IllegalArgumentException("orientacao cardinal invalida");
        return Math.floorMod(b-a,4);
    }
    public static JsonObject orient(JsonObject blueprint,String target) {
        JsonObject copy=blueprint.deepCopy();copy.remove("_rotation");
        String source=copy.has("entrance")?parse(copy.get("entrance").getAsString()):"south";
        if(source==null)throw new IllegalArgumentException("entrada original invalida");
        copy.addProperty("entrance",source);
        if(target!=null) {
            copy.addProperty("_rotation",turns(source,target));
            copy.addProperty("entrance",target);
        }
        return copy;
    }
    private static String turn(String value,int turns) {
        int i=CARDINAL.indexOf(value);return i<0?value:CARDINAL.get((i+turns)%4);
    }
    public static String data(String data,int turns) {
        if(data==null||turns==0)return data;
        int bracket=data.indexOf('[');if(bracket<0)return data;
        var fields=new ArrayList<String>();
        for(String field:data.substring(bracket+1,data.length()-1).split(",")) {
            String[] pair=field.split("=",2);String key=pair[0];
            String value=pair.length>1?pair[1]:"";
            String rotatedKey=turn(key,turns);
            if(key.equals("facing"))value=turn(value,turns);
            else if(key.equals("axis")&&(turns%2==1))value=value.equals("x")?"z":value.equals("z")?"x":value;
            else if(key.equals("rotation"))value=Integer.toString((Integer.parseInt(value)+4*turns)%16);
            else if(key.equals("orientation")) {
                String[] axes=value.split("_");for(int i=0;i<axes.length;i++)axes[i]=turn(axes[i],turns);value=String.join("_",axes);
            }
            fields.add(rotatedKey+"="+value);
        }
        return data.substring(0,bracket+1)+String.join(",",fields)+"]";
    }
    public static List<BlueprintCompiler.Block> rotate(List<BlueprintCompiler.Block> blocks,int turns) {
        if(turns==0||blocks.isEmpty())return List.copyOf(blocks);
        if(turns<0||turns>3)throw new IllegalArgumentException("rotacao deve ser 0..3");
        int minX=blocks.stream().mapToInt(BlueprintCompiler.Block::x).min().orElseThrow();
        int maxX=blocks.stream().mapToInt(BlueprintCompiler.Block::x).max().orElseThrow();
        int minZ=blocks.stream().mapToInt(BlueprintCompiler.Block::z).min().orElseThrow();
        int maxZ=blocks.stream().mapToInt(BlueprintCompiler.Block::z).max().orElseThrow();
        var rotated=new ArrayList<BlueprintCompiler.Block>();
        for(var b:blocks) {
            long x=(long)b.x()-minX,z=(long)b.z()-minZ,w=(long)maxX-minX,d=(long)maxZ-minZ;
            long xx=switch(turns){case 1->d-z;case 2->w-x;default->z;};
            long zz=switch(turns){case 1->x;case 2->d-z;default->w-x;};
            rotated.add(new BlueprintCompiler.Block(Math.toIntExact(minX+xx),b.y(),Math.toIntExact(minZ+zz),b.material(),b.phase(),data(b.data(),turns)));
        }
        return List.copyOf(rotated);
    }
}
