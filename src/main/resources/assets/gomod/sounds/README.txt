Custom sounds (optional)
========================

Minecraft 1.8.9 Java Edition uses Ogg Vorbis (.ogg) files registered in sounds.json
(same as resource packs) and already includes the vanilla witch sounds. Gomod's
additional witch samples are registered under its own namespace, for example:

  mob.witch.idle, mob.witch.hurt, mob.witch.death
  mob.witch.drink, mob.witch.throw, mob.witch.celebrate  (add JSON entries for these too)

Automatic witch SFX (hurt / death / throw) use the client SoundHandler at the
witch’s position — keep your pack enabled in Resource Packs and add JSON entries
for mob.witch.throw (and drink if you want) not only idle/hurt/death.

The /witchsound command is still available for manual tests; append "player" to
hear a sound at yourself.

If you add your OWN files:
  - Format: .ogg (Vorbis), NOT .mp3
  - Place: assets/gomod/sounds/<your_name>.ogg
  - Register in assets/gomod/sounds.json with a namespaced key, e.g. "gomod:custom_witch"
  - Play from code with that same string.

Do not redistribute Mojang's extracted .ogg files from the wiki or jar; use your
own recordings or sounds you have rights to use.

Wiki reference (file names only): https://minecraft.wiki/w/Category:Witch_sounds
