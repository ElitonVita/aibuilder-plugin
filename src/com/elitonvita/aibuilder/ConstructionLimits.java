package com.elitonvita.aibuilder;

/** Operator-configured workload limits, shared by import, planning, selection and persistence. */
public record ConstructionLimits(int width, int depth, int height, long volume, int blocks,
                                 int parts, int openings, long visits, long fileBytes, int previewBlocks) {
    public ConstructionLimits {
        if(width<1||depth<1||height<1||volume<1||blocks<1||parts<1||openings<1||visits<1||fileBytes<1||previewBlocks<1)
            throw new IllegalArgumentException("limites de construcao precisam ser positivos");
    }
    public static ConstructionLimits defaults(int blocks) {
        return new ConstructionLimits(200,200,100,8_000_000L,blocks,4096,1024,128_000_000L,67_108_864L,20_000);
    }
    public ConstructionLimits withHeight(int height) {
        return new ConstructionLimits(width,depth,height,volume,blocks,parts,openings,visits,fileBytes,previewBlocks);
    }
    public ConstructionLimits withBlocks(int blocks) {
        return new ConstructionLimits(width,depth,height,volume,blocks,parts,openings,visits,fileBytes,previewBlocks);
    }
    public boolean fits(long width,long height,long depth) {
        return width>0&&height>0&&depth>0&&width<=this.width&&height<=this.height&&depth<=this.depth
            && width<=volume&&height<=volume/width&&depth<=volume/(width*height);
    }
}
