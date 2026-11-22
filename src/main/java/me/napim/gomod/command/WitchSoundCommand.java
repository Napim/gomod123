package me.napim.gomod.command;

import me.napim.gomod.Main;
import me.napim.gomod.util.ClasspathGomodResourcePack;
import me.napim.gomod.util.GomodResourcePackInjector;
import me.napim.gomod.util.WitchSounds;
import me.napim.gomod.features.WitchAutoSound;
import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundHandler;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;
import net.weavemc.loader.api.command.Command;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.Locale;

/**
 * Play pack-registered witch SFX at a witch (1.8.9): {@code /witchsound [kind] [player]}.
 * Default: positional at nearest witch. Append {@code player} to hear it at yourself (like old behavior).
 */
public class WitchSoundCommand extends Command {
    public WitchSoundCommand() {
        super("witchsound");
    }

    @Override
    public void handle(String[] args) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer == null) {
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Not in world.");
            return;
        }
        for (String arg : args) {
            if (arg == null || arg.trim().isEmpty()) {
                continue;
            }
            if ("debug".equalsIgnoreCase(arg.trim())) {
                runDebug(mc);
                return;
            }
        }
        boolean atPlayer = false;
        String kind = "random";
        for (String arg : args) {
            if (arg == null || arg.isEmpty()) {
                continue;
            }
            String a = arg.trim().toLowerCase(Locale.ROOT);
            if ("player".equals(a) || "me".equals(a) || "self".equals(a)) {
                atPlayer = true;
            } else {
                kind = arg.trim();
            }
        }
        String kindLow = kind.trim().toLowerCase(Locale.ROOT);
        if ("player".equals(kindLow) || "me".equals(kindLow) || "self".equals(kindLow)) {
            atPlayer = true;
            kind = "random";
        }

        final String kindFinal = kind;
        final boolean atPlayerFinal = atPlayer;
        Runnable play = () -> {
            String err = WitchSounds.play(mc, kindFinal, atPlayerFinal);
            if (err != null) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + err);
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                        + "Usage: /witchsound [idle|hurt|death|drink|throw|celebrate|random] [player]");
                return;
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Witch sound: " + kindFinal
                    + (atPlayerFinal ? " (at player)" : " (at nearest witch)"));
            if (!GomodResourcePackInjector.wasSuccessfullyInstalled()) {
                Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW
                        + "Classpath sound pack did not register — bundled gomod:witch.* will not play. Check console for [gomod] GomodResourcePackInjector.");
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.DARK_GRAY
                    + "Tip: vanilla /playsound mob.witch.* is often missing on 1.8; gomod uses bundled gomod:witch.* when the pack injects.");
        };

        if (mc.isCallingFromMinecraftThread()) {
            play.run();
        } else {
            mc.addScheduledTask(play);
        }
    }

    private void runDebug(Minecraft mc) {
        Runnable work = () -> {
            GomodResourcePackInjector.ensureInstalled();
            boolean reloadNeeded = !GomodResourcePackInjector.isGomodDomainActive();
            boolean reloadSucceeded = reloadNeeded && GomodResourcePackInjector.forceReloadNow();
            ClasspathGomodResourcePack cp = new ClasspathGomodResourcePack();
            boolean hasSoundsJson = cp.resourceExists(new ResourceLocation("gomod", "sounds.json"));
            boolean hasDrinkOgg = cp.resourceExists(new ResourceLocation("gomod", "sounds/mob/witch/witch_drink1.ogg"));
            boolean hasThrowOgg = cp.resourceExists(new ResourceLocation("gomod", "sounds/mob/witch/witch_throw1.ogg"));
            boolean installed = GomodResourcePackInjector.wasSuccessfullyInstalled();
            boolean domainActive = GomodResourcePackInjector.isGomodDomainActive();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "[WitchDebug] "
                    + EnumChatFormatting.WHITE + "installed=" + installed
                    + " packRegistered=" + GomodResourcePackInjector.isPackRegistered()
                    + " soundResourcesActive=" + domainActive
                    + " reloadAttempted=" + reloadNeeded
                    + " reloadSucceeded=" + reloadSucceeded
                    + " sounds.json=" + hasSoundsJson
                    + " drink.ogg=" + hasDrinkOgg
                    + " throw.ogg=" + hasThrowOgg);
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Settings: " + WitchAutoSound.debugState());
            boolean forcedReg = WitchAutoSound.forceRegisterGomodSounds();
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                    + "Manual registry load attempted=" + forcedReg);

            boolean regGomodDrink = false;
            boolean regGomodThrow = false;
            boolean regVanillaDrink = false;
            boolean regVanillaThrow = false;
            SoundHandler sh = mc.getSoundHandler();
            if (sh != null) {
                try {
                    Method m = null;
                    for (Method mm : SoundHandler.class.getDeclaredMethods()) {
                        if (!mm.getName().toLowerCase(Locale.ROOT).contains("getsound")) {
                            continue;
                        }
                        Class<?>[] p = mm.getParameterTypes();
                        if (p.length == 1 && p[0] == ResourceLocation.class) {
                            m = mm;
                            break;
                        }
                    }
                    if (m != null) {
                        m.setAccessible(true);
                        regGomodDrink = m.invoke(sh, new ResourceLocation("gomod", "witch.drink")) != null;
                        regGomodThrow = m.invoke(sh, new ResourceLocation("gomod", "witch.throw")) != null;
                        regVanillaDrink = m.invoke(sh, new ResourceLocation("mob.witch.drink")) != null;
                        regVanillaThrow = m.invoke(sh, new ResourceLocation("mob.witch.throw")) != null;
                    } else {
                        // Fallback: try to access a SoundRegistry field directly.
                        for (Field f : SoundHandler.class.getDeclaredFields()) {
                            if (!f.getType().getName().toLowerCase(Locale.ROOT).contains("soundregistry")) {
                                continue;
                            }
                            f.setAccessible(true);
                            Object reg = f.get(sh);
                            if (reg == null) {
                                continue;
                            }
                            Method getObj = null;
                            for (Method rm : reg.getClass().getDeclaredMethods()) {
                                if (rm.getName().toLowerCase(Locale.ROOT).contains("getobject")
                                        && rm.getParameterTypes().length == 1
                                        && rm.getParameterTypes()[0] == ResourceLocation.class) {
                                    getObj = rm;
                                    break;
                                }
                            }
                            if (getObj != null) {
                                getObj.setAccessible(true);
                                regGomodDrink = getObj.invoke(reg, new ResourceLocation("gomod", "witch.drink")) != null;
                                regGomodThrow = getObj.invoke(reg, new ResourceLocation("gomod", "witch.throw")) != null;
                                regVanillaDrink = getObj.invoke(reg, new ResourceLocation("mob.witch.drink")) != null;
                                regVanillaThrow = getObj.invoke(reg, new ResourceLocation("mob.witch.throw")) != null;
                            }
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.YELLOW + "[WitchDebug] "
                    + EnumChatFormatting.WHITE + "reg gomod drink=" + regGomodDrink
                    + " throw=" + regGomodThrow
                    + " vanilla drink=" + regVanillaDrink
                    + " throw=" + regVanillaThrow);
            try {
                Set<String> domains = mc.getResourceManager() != null ? mc.getResourceManager().getResourceDomains() : null;
                if (domains != null) {
                    Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY
                            + "Domains: " + domains.toString());
                }
            } catch (Throwable ignored) {
            }
            Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GRAY + "Try: /witchsound drink player");
        };
        if (mc.isCallingFromMinecraftThread()) {
            work.run();
        } else {
            mc.addScheduledTask(work);
        }
    }
}
