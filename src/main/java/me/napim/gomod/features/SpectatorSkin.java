package me.napim.gomod.features;

import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.*;

/** Public Minecraft texture identifiers only; no arbitrary URLs or account properties on the relay. */
public final class SpectatorSkin {
    private static final Pattern TEXTURE=Pattern.compile("^https?://textures\\.minecraft\\.net/texture/([a-f0-9]{64})$",Pattern.CASE_INSENSITIVE);
    public final String hash,model;
    public SpectatorSkin(String hash,String model){this.hash=hash;this.model=model;}
    public boolean valid(){return hash!=null&&hash.matches("[a-f0-9]{64}")&&("slim".equals(model)||"default".equals(model));}
    public static SpectatorSkin fromProperty(String encoded){
        if(encoded==null||encoded.isEmpty()||encoded.length()>16384)return null;
        try{
            JsonObject root=new JsonParser().parse(new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8)).getAsJsonObject();
            JsonObject skin=root.getAsJsonObject("textures").getAsJsonObject("SKIN");
            Matcher match=TEXTURE.matcher(skin.get("url").getAsString());if(!match.matches())return null;
            JsonObject metadata=skin.getAsJsonObject("metadata");
            String model=metadata!=null&&metadata.has("model")&&"slim".equals(metadata.get("model").getAsString())?"slim":"default";
            return new SpectatorSkin(match.group(1).toLowerCase(java.util.Locale.ROOT),model);
        }catch(RuntimeException invalid){return null;}
    }
}
