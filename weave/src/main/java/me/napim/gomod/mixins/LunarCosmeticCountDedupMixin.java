package me.napim.gomod.mixins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Removes duplicate IDs from the owned-cosmetics list before Lunar Locker counts it. */
@Pseudo
@Mixin(
        targets = "com.moonsworth.lunar.client.OIOIICIHIOORICIIHRRIHIHOOCOOOO."
                + "HCHORCCCCHCOHHHROOCIRICCCIRHHH.HOCIIIHOIOHRCHOHOHRROCCHCOOHOC",
        remap = false
)
public abstract class LunarCosmeticCountDedupMixin {
    private static final java.util.concurrent.atomic.AtomicBoolean LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final String STATE_SETTER =
            "Lcom/moonsworth/lunar/client/OOIRIHRRCHRIHRHHCOOCICRIRCHIIO/"
                    + "COCIICOHOOCCHCHRIRHHCHORROOCOR/OHOIIOOOCOOIRIHRIHIIRROIICRICH/"
                    + "CROOIHHIORIOCIHCRRCHOIOCOHRCHO;"
                    + "IRRICCROHCCHROCCCICOICOCOCIORI(Ljava/lang/String;Ljava/lang/Object;)V";
    private static final String PUBLISH_LOCKER_STATE = "ORRHIOOIRROICCCCIIHRICRRHCOOHO()V";

    @Redirect(
            method = PUBLISH_LOCKER_STATE,
            at = @At(value = "INVOKE", target = STATE_SETTER),
            require = 0,
            remap = false
    )
    private void gomod$deduplicateOwnedCosmeticRows(@Coerce Object stateOwner, String stateKey, Object value) {
        if ("ownedCosmetics".equals(stateKey) && value instanceof JsonArray) {
            JsonArray original = (JsonArray) value;
            JsonArray unique = new JsonArray();
            java.util.HashSet<String> seenIds = new java.util.HashSet<String>();
            for (JsonElement element : original) {
                if (element != null && element.isJsonObject()) {
                    JsonObject cosmetic = element.getAsJsonObject();
                    if (cosmetic.has("id") && !cosmetic.get("id").isJsonNull()) {
                        String id = cosmetic.get("id").getAsString();
                        if (!seenIds.add(id)) continue;
                    }
                }
                unique.add(element);
            }

            if (LOGGED.compareAndSet(false, true)) {
                System.out.println("[gomod] Lunar Locker ownedCosmetics hook active: "
                        + original.size() + " entries, " + unique.size() + " unique IDs");
            }
            if (unique.size() != original.size()) {
                System.out.println("[gomod] Removed duplicate Lunar cosmetic rows: "
                        + original.size() + " -> " + unique.size());
                value = unique;
            }
        }

        LunarLockerStateBridge.set(stateOwner, stateKey, value);
    }
}
