package com.elitonvita.aibuilder;

import java.util.*;

/** Structural comparison of a planned state against a full server state. */
public final class BlockStateSpec {
    private BlockStateSpec() {}
    private static Map<String,String> properties(String data) {
        Map<String,String> out=new HashMap<>();int start=data.indexOf('[');
        if(start<0)return out;
        if(!data.endsWith("]"))throw new IllegalArgumentException("invalid state");
        for(String property:data.substring(start+1,data.length()-1).split(",")) {
            String[] kv=property.split("=",-1);
            if(kv.length!=2||out.put(kv[0],kv[1])!=null)throw new IllegalArgumentException("invalid state");
        }
        return out;
    }
    public static boolean matches(String expected,String actual) {
        try {
            String type=expected.split("\\[",2)[0],other=actual.split("\\[",2)[0];
            if(!type.contains(":"))type="minecraft:"+type;
            if(!other.contains(":"))other="minecraft:"+other;
            if(!type.equals(other))return false;
            Map<String,String> wanted=properties(expected),present=properties(actual);
            for(var e:wanted.entrySet()) {
                // Opening a door, powering it or updating leaf distance does not destroy a building.
                if(type.endsWith("_door")&&Set.of("open","powered").contains(e.getKey()))continue;
                if(type.endsWith("_leaves")&&e.getKey().equals("distance"))continue;
                if(!e.getValue().equals(present.get(e.getKey())))return false;
            }
            return true;
        }catch(RuntimeException invalid){return false;}
    }
}
