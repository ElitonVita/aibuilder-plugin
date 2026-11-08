import com.elitonvita.aibuilder.BuilderAppearance;
import com.google.gson.JsonParser;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public class BuilderAppearanceTest {
    public static void main(String[] args) {
        int checks=0;
        String value=BuilderAppearance.textureValue(BuilderAppearance.DEFAULT_TEXTURE);
        String url=JsonParser.parseString(new String(Base64.getDecoder().decode(value),StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonObject("textures").getAsJsonObject("SKIN").get("url").getAsString();
        if (!url.equals("https://textures.minecraft.net/texture/"+BuilderAppearance.DEFAULT_TEXTURE)) throw new AssertionError("unpinned texture"); checks++;
        for (String invalid : new String[]{null,"", "https://evil.invalid/skin.png", "a".repeat(63), "g".repeat(64), "a".repeat(65)}) {
            try { BuilderAppearance.textureValue(invalid); throw new AssertionError("unsafe skin input accepted"); }
            catch (IllegalArgumentException expected) { checks++; }
        }
        if (!BuilderAppearance.textureValue("A".repeat(64)).equals(BuilderAppearance.textureValue("a".repeat(64)))) throw new AssertionError("hash case"); checks++;
        System.out.println("PASS: "+checks+" builder appearance assertions");
    }
}
