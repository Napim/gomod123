package me.napim.gomod;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = "gomod123", name = "gomod123", version = "1.0.1", clientSideOnly = true, acceptedMinecraftVersions = "[1.8.9]")
public class GomodForgeMod {

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        if (event.getModMetadata() != null) {
            event.getModMetadata().logoFile = "icon.png";
        }
        System.out.println("[gomod] GomodForgeMod.preInit — loading via Forge @Mod");
        GomodRuntimeBootstrap.ensureStarted();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        GomodRuntimeBootstrap.ensureStartGamePosted();
    }
}
