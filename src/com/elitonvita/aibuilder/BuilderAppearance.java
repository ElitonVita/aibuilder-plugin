package com.elitonvita.aibuilder;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.entity.Mannequin;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

/** Pinned public builder texture; no username lookup or skin upload at runtime. */
public final class BuilderAppearance {
    public static final String DEFAULT_TEXTURE = "36feb0132b0cc6d44c695e2c7d4388d4820ef1b309ba0eb1327ee3b68043afd2";
    private BuilderAppearance() {}
    public static String textureValue(String hash) {
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("skin.texture precisa de um hash Mojang de 64 caracteres");
        String value = "{\"textures\":{\"SKIN\":{\"url\":\"https://textures.minecraft.net/texture/" + hash.toLowerCase(java.util.Locale.ROOT) + "\"}}}";
        return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    public static void apply(Mannequin npc, String hash) {
        String value = textureValue(hash);
        npc.setProfile(ResolvableProfile.resolvableProfile()
                .uuid(UUID.nameUUIDFromBytes(("AIBuilder:" + value).getBytes(StandardCharsets.UTF_8)))
                .addProperty(new ProfileProperty("textures", value)).build());
    }
}
