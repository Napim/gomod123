package me.napim.gomod.features;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import me.napim.gomod.Main;
import me.napim.gomod.util.GameContext;
import me.napim.gomod.util.InventoryQolOverlay;
import me.napim.gomod.mixins.GuiScreenInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.inventory.GuiChest;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.Item;
import net.minecraft.init.Items;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemBook;
import net.minecraft.item.ItemEnchantedBook;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemWritableBook;
import net.minecraft.item.ItemArmor.ArmorMaterial;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.EnumChatFormatting;

public class BestArmorDisplay extends Gui {
   private static boolean enabled = false;
   private static final BestArmorDisplay INSTANCE = new BestArmorDisplay();
   public static final double EPSILON = 1.0E-9D;
   /** Min DR gain from swapping one slot alone; avoids bogus tips when the mixed set wins from another piece. */
   private static final double MIN_ARMOR_SLOT_MARGINAL_DR = 1.0E-6D;

   /** Fingerprint of main + armor + cursor + selected hotbar — invalidates mixed-set and underlay caches together. */
   private static long inventoryQolContentFingerprint = Long.MIN_VALUE;
   /**
    * Always the result of {@link #computeInventoryContentFingerprint} for the local player inventory on the last
    * {@link #prepareInventoryQolCachesForFrame} call — lets {@link #findBestMixedSet} avoid repeating the same work
    * dozens of times per frame (underlay + useless + chest hints).
    */
   private static long playerInvLatestContentFingerprint = Long.MIN_VALUE;
   private static long mixedSetCacheFingerprint = Long.MIN_VALUE;
   private static ItemStack[] mixedSetCachedResult = null;
   private static InventoryPlayer mixedSetCachedInventory;
   private static InventoryPlayer preparedInventory;
   private static Object preparedWorld;
   private static Container preparedCandidateContainer;
   private static long preparedCandidateContents = Long.MIN_VALUE;
   private static long preparedCandidateDeepContents = Long.MIN_VALUE;
   private static final ItemStack[] SCRATCH_PLAYER_INV_VIEW40 = new ItemStack[40];
   private static final IdentityHashMap<Slot, UnderlayIntentCacheEntry> underlayIntentBySlot =
         new IdentityHashMap<Slot, UnderlayIntentCacheEntry>(128);
   private static final IdentityHashMap<ItemStack, UnderlayIntentCacheEntry> underlayIntentWithoutSlot =
         new IdentityHashMap<ItemStack, UnderlayIntentCacheEntry>(64);

   /** Slot recommendation scans are expensive; retain their result until the inventory cache is invalidated. */
   private static final ItemStack[] bestArmorPerSlotCache = new ItemStack[5];
   private static final boolean[] bestArmorPerSlotCacheValid = new boolean[5];
   private static final ItemStack[] bestArmorInventoryOnlyPerSlotCache = new ItemStack[5];
   private static final boolean[] bestArmorInventoryOnlyPerSlotCacheValid = new boolean[5];
   private static InventoryPlayer bestArmorRecommendationCacheInventory;
   private static long bestArmorRecommendationCacheFingerprint = Long.MIN_VALUE;

   /** Inventory screens call the prepare method from both the frame hook and every slot hook. */
   private static final long INVENTORY_QOL_SNAPSHOT_CHECK_INTERVAL_NANOS = 16_000_000L;
   /** In-place NBT edits do not change stack/tag identity, so audit them periodically instead of per rendered slot. */
   private static final long INVENTORY_QOL_DEEP_AUDIT_INTERVAL_NANOS = 250_000_000L;
   private static long lastInventoryQolSnapshotCheckNanos;
   private static long lastInventoryQolDeepAuditNanos;

   /**
    * Per-slot memo for {@link #isSlotBetterArmor} while {@link #inventoryQolContentFingerprint} is unchanged.
    * Cleared when inventory fingerprint changes (same moment as underlay cache).
    */
   private static final byte[] slotIsBetterArmorCache = new byte[40];
   private static final byte SLOT_IS_BETTER_ARMOR_UNKNOWN = -1;
   private static final byte SLOT_IS_BETTER_ARMOR_FALSE = 0;
   private static final byte SLOT_IS_BETTER_ARMOR_TRUE = 1;

   private static final class UnderlayIntentCacheEntry {
      final InventoryPlayer inventory;
      final ItemStack stack;
      final boolean draw;
      final int tieType;
      final boolean split;

      UnderlayIntentCacheEntry(InventoryPlayer inventory, ItemStack stack, BestArmorUnderlayIntent intent) {
         this.inventory = inventory;
         this.stack = stack;
         this.draw = intent.draw;
         this.tieType = intent.tieType;
         this.split = intent.split;
      }

      void copyTo(BestArmorUnderlayIntent out) {
         out.draw = draw;
         out.tieType = tieType;
         out.split = split;
      }
   }

   /**
    * Avoids {@link #computeInventoryContentFingerprint} (deep {@link NBTTagCompound#hashCode()} per item) on every
    * frame — that ran from {@code renderGameOverlay} HEAD at 60+ Hz and could dominate CPU with enchanted gear.
    */
   private static boolean prepareShallowSnapshotValid;
   private static int prepCurrentItem = Integer.MIN_VALUE;
   private static ItemStack prepCursorRef;
   private static int prepCursorSize;
   private static int prepCursorMeta;
   private static int prepCursorTagId;
   private static final ItemStack[] prepMainRef = new ItemStack[36];
   private static final int[] prepMainSize = new int[36];
   private static final int[] prepMainMeta = new int[36];
   private static final int[] prepMainTagId = new int[36];
   private static final ItemStack[] prepArmorRef = new ItemStack[4];
   private static final int[] prepArmorSize = new int[4];
   private static final int[] prepArmorMeta = new int[4];
   private static final int[] prepArmorTagId = new int[4];

   /** Call once per frame before inventory QoL overlays (game overlay head + {@link GuiContainer#drawScreen}). */
   public static void prepareInventoryQolCachesForFrame(Minecraft mc) {
      if (mc == null || mc.thePlayer == null || mc.thePlayer.inventory == null) {
         invalidateArmorDecisionCaches();
         prepareShallowSnapshotValid = false;
         preparedInventory = null;
         preparedWorld = null;
         preparedCandidateContainer = null;
         preparedCandidateContents = Long.MIN_VALUE;
         preparedCandidateDeepContents = Long.MIN_VALUE;
         lastInventoryQolSnapshotCheckNanos = 0L;
         lastInventoryQolDeepAuditNanos = 0L;
         return;
      }
      if (!enabled && !UselessItemOverlay.isEnabled() && !UselessItemOverlay.isChestPickHintDebugForceEnabled()) {
         prepareShallowSnapshotValid = false;
         lastInventoryQolSnapshotCheckNanos = 0L;
         lastInventoryQolDeepAuditNanos = 0L;
         return;
      }
      InventoryPlayer inv = mc.thePlayer.inventory;
      Container candidates = getArmorCandidateContainer(mc);
      long nowNanos = System.nanoTime();
      boolean playerContextChanged = !prepareShallowSnapshotValid || preparedInventory != inv
            || preparedWorld != mc.theWorld;
      boolean candidateSourceChanged = preparedCandidateContainer != candidates;
      boolean deepAuditDue = playerContextChanged || lastInventoryQolDeepAuditNanos == 0L
            || nowNanos - lastInventoryQolDeepAuditNanos >= INVENTORY_QOL_DEEP_AUDIT_INTERVAL_NANOS;
      if (!playerContextChanged && !candidateSourceChanged && !deepAuditDue && nowNanos - lastInventoryQolSnapshotCheckNanos
            < INVENTORY_QOL_SNAPSHOT_CHECK_INTERVAL_NANOS) {
         return;
      }
      lastInventoryQolSnapshotCheckNanos = nowNanos;

      long candidateContents = computeContainerCandidateShallowFingerprint(candidates, inv);
      boolean playerChanged = !prepareShallowSnapshotValid || preparedInventory != inv
            || preparedWorld != mc.theWorld || !inventoryContentShallowUnchanged(inv);
      boolean containerChanged = preparedCandidateContents != candidateContents;
      long fp = playerChanged || deepAuditDue
            ? computeInventoryContentFingerprint(inv) : playerInvLatestContentFingerprint;
      long candidateDeepContents = containerChanged || deepAuditDue
            ? computeContainerCandidateContentFingerprint(candidates, inv) : preparedCandidateDeepContents;
      boolean deepPlayerChanged = !playerChanged && deepAuditDue && fp != playerInvLatestContentFingerprint;
      boolean deepContainerChanged = !containerChanged && deepAuditDue
            && candidateDeepContents != preparedCandidateDeepContents;
      if (!playerChanged && !containerChanged && !deepPlayerChanged && !deepContainerChanged) {
         if (candidateSourceChanged) {
            // The open screen changed, but its external candidate stacks did not. Drop slot-keyed underlays while
            // keeping the expensive optimizer/recommendation results for the same inventory contents.
            underlayIntentBySlot.clear();
            underlayIntentWithoutSlot.clear();
            preparedCandidateContainer = candidates;
            preparedCandidateContents = candidateContents;
         }
         if (deepAuditDue) {
            preparedCandidateDeepContents = candidateDeepContents;
            lastInventoryQolDeepAuditNanos = nowNanos;
         }
         return;
      }
      playerInvLatestContentFingerprint = fp;
      inventoryQolContentFingerprint = fp;
      invalidateArmorDecisionCaches();
      preparedInventory = inv;
      preparedWorld = mc.theWorld;
      preparedCandidateContainer = candidates;
      preparedCandidateContents = candidateContents;
      preparedCandidateDeepContents = candidateDeepContents;
      if (deepAuditDue) {
         lastInventoryQolDeepAuditNanos = nowNanos;
      }
      // Always mirror live slot references after a shallow change: logical fingerprint can match while
      // slot arrays hold new ItemStack instances (same items/NBT), which would leave stale refs in v40 and break
      // useless-item / duplicate logic until something else (e.g. inventory GUI) reframed caches.
      for (int i = 0; i < 40; i++) {
         SCRATCH_PLAYER_INV_VIEW40[i] = inv.getStackInSlot(i);
      }
      persistInventoryShallowSnapshot(inv);
   }

   private static void invalidateArmorDecisionCaches() {
      underlayIntentBySlot.clear();
      underlayIntentWithoutSlot.clear();
      Arrays.fill(slotIsBetterArmorCache, SLOT_IS_BETTER_ARMOR_UNKNOWN);
      Arrays.fill(bestArmorPerSlotCache, null);
      Arrays.fill(bestArmorPerSlotCacheValid, false);
      Arrays.fill(bestArmorInventoryOnlyPerSlotCache, null);
      Arrays.fill(bestArmorInventoryOnlyPerSlotCacheValid, false);
      bestArmorRecommendationCacheInventory = null;
      bestArmorRecommendationCacheFingerprint = Long.MIN_VALUE;
      mixedSetCacheFingerprint = Long.MIN_VALUE;
      mixedSetCachedResult = null;
      mixedSetCachedInventory = null;
   }

   private static Container getArmorCandidateContainer(Minecraft mc) {
      if (mc == null || !(mc.currentScreen instanceof GuiContainer) || shouldExcludeCurrentContainer()) {
         return null;
      }
      return ((GuiContainer) mc.currentScreen).inventorySlots;
   }

   /** Match the candidate scan without deep-hashing chest NBT at every slot draw. */
   private static long computeContainerCandidateShallowFingerprint(Container container, InventoryPlayer inv) {
      if (container == null || container.inventorySlots == null) return 0L;
      long fingerprint = 0L;
      for (Slot slot : container.inventorySlots) {
         if (slot == null || slot.inventory == null || slot.inventory == inv) continue;
         ItemStack stack = slot.getStack();
         if (stack == null) continue;
         fingerprint = fingerprint * 0x100000001b3L + System.identityHashCode(slot);
         fingerprint = fingerprint * 0x100000001b3L + System.identityHashCode(stack);
         fingerprint = fingerprint * 0x100000001b3L + stack.stackSize;
         fingerprint = fingerprint * 0x100000001b3L + stack.getMetadata();
         fingerprint = fingerprint * 0x100000001b3L + tagIdentity(stack.getTagCompound());
      }
      return fingerprint;
   }

   /** Deep counterpart used only on first preparation and the throttled in-place-NBT audit. */
   private static long computeContainerCandidateContentFingerprint(Container container, InventoryPlayer inv) {
      if (container == null || container.inventorySlots == null) return 0L;
      long fingerprint = 0L;
      for (Slot slot : container.inventorySlots) {
         if (slot == null || slot.inventory == null || slot.inventory == inv) continue;
         ItemStack stack = slot.getStack();
         if (stack == null) continue;
         fingerprint = fingerprint * 0x100000001b3L + System.identityHashCode(slot);
         fingerprint = mixItemStackFingerprint(fingerprint, stack);
      }
      return fingerprint;
   }

   // Diagnostics must inspect the cached decision without recomputing it and hiding a stale-cache report.
   static ItemStack[] getCachedMixedSetForDebug() {
      return mixedSetCachedResult == null ? null : mixedSetCachedResult.clone();
   }

   static String getArmorCandidateCacheContextForDebug() {
      return "container=" + (preparedCandidateContainer == null ? "<none>"
            : preparedCandidateContainer.getClass().getName() + ":" + preparedCandidateContainer.windowId)
            + " containerContents=" + preparedCandidateContents
            + " playerFingerprint=" + playerInvLatestContentFingerprint
            + " mixedSetFingerprint=" + mixedSetCacheFingerprint;
   }

   private static int tagIdentity(NBTTagCompound tag) {
      return tag == null ? 0 : System.identityHashCode(tag);
   }

   private static boolean stackShallowUnchanged(ItemStack now, ItemStack wasRef, int wasSize, int wasMeta,
         int wasTagId) {
      if (now != wasRef) {
         return false;
      }
      if (now == null) {
         return true;
      }
      if (now.stackSize != wasSize || now.getMetadata() != wasMeta) {
         return false;
      }
      return tagIdentity(now.getTagCompound()) == wasTagId;
   }

   private static boolean inventoryContentShallowUnchanged(InventoryPlayer inv) {
      if (inv.currentItem != prepCurrentItem) {
         return false;
      }
      if (!stackShallowUnchanged(inv.getItemStack(), prepCursorRef, prepCursorSize, prepCursorMeta, prepCursorTagId)) {
         return false;
      }
      for (int i = 0; i < 36; i++) {
         if (!stackShallowUnchanged(inv.getStackInSlot(i), prepMainRef[i], prepMainSize[i], prepMainMeta[i],
               prepMainTagId[i])) {
            return false;
         }
      }
      for (int i = 0; i < 4; i++) {
         if (!stackShallowUnchanged(inv.armorInventory[i], prepArmorRef[i], prepArmorSize[i], prepArmorMeta[i],
               prepArmorTagId[i])) {
            return false;
         }
      }
      return true;
   }

   private static void persistInventoryShallowSnapshot(InventoryPlayer inv) {
      prepCurrentItem = inv.currentItem;
      ItemStack c = inv.getItemStack();
      prepCursorRef = c;
      prepCursorSize = c == null ? 0 : c.stackSize;
      prepCursorMeta = c == null ? 0 : c.getMetadata();
      prepCursorTagId = c == null ? 0 : tagIdentity(c.getTagCompound());
      for (int i = 0; i < 36; i++) {
         ItemStack s = inv.getStackInSlot(i);
         prepMainRef[i] = s;
         prepMainSize[i] = s == null ? 0 : s.stackSize;
         prepMainMeta[i] = s == null ? 0 : s.getMetadata();
         prepMainTagId[i] = s == null ? 0 : tagIdentity(s.getTagCompound());
      }
      for (int i = 0; i < 4; i++) {
         ItemStack s = inv.armorInventory[i];
         prepArmorRef[i] = s;
         prepArmorSize[i] = s == null ? 0 : s.stackSize;
         prepArmorMeta[i] = s == null ? 0 : s.getMetadata();
         prepArmorTagId[i] = s == null ? 0 : tagIdentity(s.getTagCompound());
      }
      prepareShallowSnapshotValid = true;
   }

   /** Shared view of slots 0–39 (references, read-only for {@link UselessItemOverlay#evaluate}). */
   public static ItemStack[] getPreparedPlayerInventoryView40() {
      return SCRATCH_PLAYER_INV_VIEW40;
   }

   private static long computeInventoryContentFingerprint(InventoryPlayer inv) {
      if (inv == null) {
         return 0L;
      }
      long h = 0xcbf29ce484222325L;
      for (int i = 0; i < 36; i++) {
         h = mixItemStackFingerprint(h, inv.getStackInSlot(i));
      }
      for (int i = 0; i < 4; i++) {
         h = mixItemStackFingerprint(h, inv.armorInventory[i]);
      }
      h = mixItemStackFingerprint(h, inv.getItemStack());
      h = h * 0x100000001b3L + inv.currentItem;
      return h;
   }

   private static long mixItemStackFingerprint(long h, ItemStack s) {
      if (s == null || s.getItem() == null) {
         return h * 0x100000001b3L + 1L;
      }
      h = h * 0x100000001b3L + Item.getIdFromItem(s.getItem());
      h = h * 0x100000001b3L + s.stackSize;
      h = h * 0x100000001b3L + s.getMetadata();
      NBTTagCompound tag = s.getTagCompound();
      h = h * 0x100000001b3L + (tag == null ? 0 : tag.hashCode());
      return h;
   }

   /**
    * Same rules as {@link #computeBestArmorUnderlayIntent}; caches per inventory fingerprint + slot + stack identity.
    */
   public static void computeBestArmorUnderlayIntentCached(ItemStack stack, InventoryPlayer inv, Slot slotContext,
         BestArmorUnderlayIntent out) {
      Minecraft mc = Minecraft.getMinecraft();
      prepareInventoryQolCachesForFrame(mc);
      UnderlayIntentCacheEntry cached = slotContext == null
            ? underlayIntentWithoutSlot.get(stack) : underlayIntentBySlot.get(slotContext);
      if (cached != null && cached.inventory == inv && cached.stack == stack) {
         cached.copyTo(out);
         return;
      }
      computeBestArmorUnderlayIntent(stack, inv, slotContext, out);
      UnderlayIntentCacheEntry result = new UnderlayIntentCacheEntry(inv, stack, out);
      if (slotContext == null) {
         underlayIntentWithoutSlot.put(stack, result);
      } else {
         underlayIntentBySlot.put(slotContext, result);
      }
   }
   private static final Set<String> EXCLUDED_CONTAINER_NAMES = new HashSet<String>();

   static {
      EXCLUDED_CONTAINER_NAMES.add("kit selector");
      EXCLUDED_CONTAINER_NAMES.add("kill effect selector");
      EXCLUDED_CONTAINER_NAMES.add("pick a blitz effect");
      EXCLUDED_CONTAINER_NAMES.add("kill effects");
      EXCLUDED_CONTAINER_NAMES.add("blitz shop");
      EXCLUDED_CONTAINER_NAMES.add("ultimate kits");
      EXCLUDED_CONTAINER_NAMES.add("advanced kits");
      EXCLUDED_CONTAINER_NAMES.add("basic kits");
      EXCLUDED_CONTAINER_NAMES.add("teleporter");
   }

   public static void toggle(boolean enable) {
      enabled = enable;
      ConfigManager.setBestArmorDisplayEnabled(enable);
      ConfigManager.saveConfig();
      if (enabled) {
         Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.GREEN + "Best Armor Display enabled");
      } else {
         Main.sendMessage(Main.CHAT_PREFIX + EnumChatFormatting.RED + "Best Armor Display disabled");
      }

   }

    /**
     * Set enabled state without sending a chat message.
     * Used by GUIs that shouldn't spam chat when toggling.
     */
    public static void setEnabledSilent(boolean enable) {
        enabled = enable;
        ConfigManager.setBestArmorDisplayEnabled(enable);
        ConfigManager.saveConfig();
    }

   public static boolean isEnabled() {
      return enabled;
   }

   /**
    * Feature on and not in a Hypixel main lobby / Blitz pregame lobby — use for all best-armor rendering and hotbar HUD.
    */
   public static boolean isOverlayActiveInContext(Minecraft mc) {
      if (!enabled || mc == null) {
         return false;
      }
      return !GameContext.shouldSuppressInventoryQolOverlays(mc);
   }

   /**
    * Percentage readout above armor slots is enabled (minimal/detailed) and should be allowed to render in the
    * current context even if the overlay highlights are OFF.
    */
   public static boolean isPercentageActiveInContext(Minecraft mc) {
      if (mc == null) {
         return false;
      }
      if (ConfigManager.getBestArmorPercentageMode() == ConfigManager.BEST_ARMOR_PERCENTAGE_OFF) {
         return false;
      }
      return !GameContext.shouldSuppressInventoryQolOverlays(mc);
   }

   /** Either the overlay highlights are enabled, or the percentage readout is enabled. */
   public static boolean isOverlayOrPercentageActiveInContext(Minecraft mc) {
      return isOverlayActiveInContext(mc) || isPercentageActiveInContext(mc);
   }

   public static void loadSettings() {
      enabled = ConfigManager.isBestArmorDisplayEnabled();
      prepareShallowSnapshotValid = false;
      BestArmorContainerRenderStrategy.loadFromConfig();
   }

   public static BestArmorDisplay getInstance() {
      return INSTANCE;
   }

   /**
    * Checks if the currently open container should be excluded from BestArmor checks
    * (e.g., server UI chests like Kit Selector, Kill Effect Selector)
    * @return true if the current container should be excluded
    */
  public static boolean shouldExcludeCurrentContainer() {
      try {
         Minecraft mc = Minecraft.getMinecraft();
         if (mc.currentScreen != null && mc.currentScreen instanceof GuiChest) {
            if (mc.thePlayer != null && mc.thePlayer.openContainer != null && mc.thePlayer.openContainer instanceof ContainerChest) {
               ContainerChest container = (ContainerChest)mc.thePlayer.openContainer;
               IInventory lowerInv = container.getLowerChestInventory();
               return isExcludedContainerName(lowerInv == null ? null : lowerInv.getDisplayName().getUnformattedText());
            }
         }
      } catch (Exception e) {
         // If we can't determine the chest name, don't exclude it
      }
      return false;
   }

   private static boolean isExcludedContainerName(String chestName) {
      if (chestName == null) {
         return false;
      }
      String normalized = EnumChatFormatting.getTextWithoutFormattingCodes(chestName);
      if (normalized == null) {
         normalized = chestName;
      }
      normalized = normalized.replace('\u00A0', ' ').trim().toLowerCase(Locale.ROOT);
      if (normalized.isEmpty()) {
         return false;
      }
      if (EXCLUDED_CONTAINER_NAMES.contains(normalized)) {
         return true;
      }
      return normalized.endsWith(" kits");
   }

   /**
    * Green / tie overlays behind a container slot item. Called immediately before
    * {@link net.minecraft.client.gui.inventory.GuiContainer#drawSlot} so items draw on top. Lunar and similar
    * clients often skip {@code drawSlot} HEAD mixins, so gomod also installs a bytecode hook at the same location.
    */
   /**
    * Overlays while GL origin matches container (e.g. {@code drawSlot} under {@code guiLeft}/{@code guiTop} translation).
    */
   public static void renderSlotBestArmorOverlayIfNeeded(Slot slotIn) {
      renderSlotBestArmorOverlayIfNeeded(slotIn, 0, 0);
   }

   /** Output for {@link #computeBestArmorUnderlayIntent}; consumed by {@link #drawBestArmorUnderlayAbsolute}. */
   public static final class BestArmorUnderlayIntent {
      public boolean draw;
      public int tieType = -1;
      public boolean split;
   }

   /**
    * Same highlight rules as container slot / hotbar underlay: uses logical item identity (enchant/NBT), not {@code ==},
    * so duplicate stacks and chest slots match the mixed-set winner.
    *
    * @param slotContext when non-null, armor GUI slots ({@code InventoryPlayer} index ≥ 36) are skipped; use {@code null}
    *                    for hotbar / Inventory HUD coordinates.
    */
   public static void computeBestArmorUnderlayIntent(ItemStack stack, InventoryPlayer inv, Slot slotContext,
         BestArmorUnderlayIntent out) {
      out.draw = false;
      out.tieType = -1;
      out.split = false;
      if (stack == null || inv == null) {
         return;
      }
      boolean blockPlayerArmorEquipSlot = slotContext != null && slotContext.inventory instanceof InventoryPlayer
            && slotContext.getSlotIndex() >= 36;

      boolean draw = false;
      boolean split = false;
      int tieType = -1;
      double delta = 0.0D;
      ItemStack[] mixedBest = null;

      if (stack.getItem() instanceof ItemArmor) {
         if (blockPlayerArmorEquipSlot) {
            return;
         }
         ItemArmor ia = (ItemArmor) stack.getItem();
         int armorSlot = 4 - ia.armorType;
         mixedBest = getBestMixedSet(inv);
         ItemStack[] bestSet = mixedBest;
         if (bestSet != null) {
            ItemStack desired = bestSet[armorSlot - 1];
            if (desired != null && armorPiecesLogicallyEquivalent(desired, stack)) {
               ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(armorSlot, desired, inv);
               if (keeper == stack) {
                  tieType = detectTieTypeForCandidate(inv, armorSlot, stack);
                  delta = getProtectionDeltaForCandidate(inv, armorSlot, stack);
                  if (tieType != -1) {
                     draw = true;
                     split = (delta > EPSILON);
                  } else if (delta > EPSILON) {
                     draw = true;
                     split = false;
                  }
               }
               if (!draw) {
                  return;
               }
            }
         }
      }

      if (!draw && stack.getItem() instanceof ItemArmor) {
         ItemArmor iaFb = (ItemArmor) stack.getItem();
         int armorSlotFb = 4 - iaFb.armorType;
         if (mixedBest == null) {
            mixedBest = getBestMixedSet(inv);
         }
         ItemStack[] bestForFallback = mixedBest;
         ItemStack desiredForSlot = bestForFallback != null ? bestForFallback[armorSlotFb - 1] : null;
         // When a mixed-set winner exists for this slot, only that identity (and its duplicate-keeper logic above)
         // may get the green underlay. Otherwise unequipped gear in the bag all beats "naked" and this fallback would
         // mark every helmet/chest/etc. as recommended — and in {@link GuiContainer} useless-tint runs before
         // best-armor, so spurious green would paint over correct red.
         boolean allowEquippedVsNakedFallback = bestForFallback == null || desiredForSlot == null
               || armorPiecesLogicallyEquivalent(desiredForSlot, stack);
         if (allowEquippedVsNakedFallback) {
            double directDelta = getProtectionDeltaForCandidate(inv, armorSlotFb, stack);
            if (directDelta > EPSILON) {
               draw = true;
               tieType = -1;
               split = false;
               delta = directDelta;
            } else {
               int fbTie = detectTieTypeForCandidate(inv, armorSlotFb, stack);
               if (fbTie != -1) {
                  draw = true;
                  tieType = fbTie;
                  split = false;
                  delta = 0.0D;
               }
            }
         }
      }

      if (!draw && !(stack.getItem() instanceof ItemArmor)) {
         if (blockPlayerArmorEquipSlot) {
            return;
         }
         int nonArmorSlot = getArmorSlotForItem(stack);
         if (nonArmorSlot != -1) {
            if (mixedBest == null) {
               mixedBest = getBestMixedSet(inv);
            }
            ItemStack[] bestSet = mixedBest;
            if (bestSet != null) {
               ItemStack desired = bestSet[nonArmorSlot - 1];
               if (desired != null && armorPiecesLogicallyEquivalent(desired, stack)) {
                  ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(nonArmorSlot, desired, inv);
                  if (keeper == stack) {
                     tieType = detectTieTypeForCandidate(inv, nonArmorSlot, stack);
                     delta = getProtectionDeltaForCandidate(inv, nonArmorSlot, stack);
                     if (tieType != -1) {
                        draw = true;
                        split = (delta > EPSILON);
                     } else if (delta > EPSILON) {
                        draw = true;
                        split = false;
                     }
                  }
                  if (!draw) {
                     return;
                  }
               }
            } else {
               double directDelta = getProtectionDeltaForCandidate(inv, nonArmorSlot, stack);
               if (directDelta > EPSILON) {
                  draw = true;
                  tieType = -1;
                  split = false;
                  delta = directDelta;
               } else {
                  int fbTie = detectTieTypeForCandidate(inv, nonArmorSlot, stack);
                  if (fbTie != -1) {
                     draw = true;
                     tieType = fbTie;
                     split = false;
                     delta = 0.0D;
                  }
               }
            }
         }
      }

      out.draw = draw;
      out.tieType = tieType;
      out.split = split;
   }

   /** Fills a 16×16-style rect (inclusive coordinates matching {@link Gui#drawRect}) using intent from {@link #computeBestArmorUnderlayIntent}. */
   public static void drawBestArmorUnderlayAbsolute(int x0, int y0, int x1, int y1, BestArmorUnderlayIntent in) {
      if (in == null || !in.draw) {
         return;
      }
      int tieType = in.tieType;
      boolean split = in.split;
      int color = 0x8000FF00;
      int fillColor = 0x4000FF00;

      GlStateManager.pushMatrix();
      GlStateManager.disableLighting();
      GlStateManager.enableBlend();
      GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);

      if (tieType != -1 && split) {
         int width = x1 - x0;
         int height = y1 - y0;
         if (width <= 0) {
            width = 1;
         }
         for (int xi = x0; xi < x1; xi++) {
            int dy = (xi - x0) * height / width;
            int ySplit = y1 - dy;
            InventoryQolOverlay.drawRect(xi, y0, xi + 1, ySplit, fillColor);
            int lower = tieFillColor(tieType);
            InventoryQolOverlay.drawRect(xi, ySplit, xi + 1, y1, lower);
         }
      } else if (tieType != -1) {
         InventoryQolOverlay.drawRect(x0, y0, x1, y1, tieFillColor(tieType));
      } else {
         InventoryQolOverlay.drawRect(x0, y0, x1, y1, fillColor);
      }

      if (tieType != -1 && split) {
         int tieBorder = tieBorderColor(tieType);
         InventoryQolOverlay.drawRect(x0, y0, x1, y0 + 1, color);
         InventoryQolOverlay.drawRect(x0, y1 - 1, x1, y1, tieBorder);
         InventoryQolOverlay.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, color);
         InventoryQolOverlay.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, tieBorder);
      } else if (tieType != -1) {
         int tieBorder = tieBorderColor(tieType);
         InventoryQolOverlay.drawRect(x0, y0, x1, y0 + 1, tieBorder);
         InventoryQolOverlay.drawRect(x0, y1 - 1, x1, y1, tieBorder);
         InventoryQolOverlay.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, tieBorder);
         InventoryQolOverlay.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, tieBorder);
      } else {
         InventoryQolOverlay.drawRect(x0, y0, x1, y0 + 1, color);
         InventoryQolOverlay.drawRect(x0, y1 - 1, x1, y1, color);
         InventoryQolOverlay.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, color);
         InventoryQolOverlay.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, color);
      }

      GlStateManager.disableBlend();
      GlStateManager.enableLighting();
      // Gui.drawRect keeps the last translucent overlay color as the global current color.
      GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
      GlStateManager.popMatrix();
   }

   /**
    * @param guiOriginX screen-space GUI origin for absolute rendering; use 0,0 from {@code drawSlot}, where the
    *                   container translation is already active.
    */
   public static void renderSlotBestArmorOverlayIfNeeded(Slot slotIn, int guiOriginX, int guiOriginY) {
      Minecraft mc = Minecraft.getMinecraft();
      if (!isOverlayActiveInContext(mc)) {
         return;
      }
      if (shouldExcludeCurrentContainer()) {
         return;
      }
      if (mc.thePlayer == null || mc.thePlayer.inventory == null) {
         return;
      }

      ItemStack stack = slotIn.getStack();
      BestArmorUnderlayIntent intent = new BestArmorUnderlayIntent();
      computeBestArmorUnderlayIntentCached(stack, mc.thePlayer.inventory, slotIn, intent);
      if (!intent.draw) {
         return;
      }

      int itemX = guiOriginX + slotIn.xDisplayPosition + 1;
      int itemY = guiOriginY + slotIn.yDisplayPosition + 1;
      int size = 16;
      int x0 = itemX - 1;
      int y0 = itemY - 1;
      int x1 = itemX + size - 1;
      int y1 = itemY + size - 1;
      drawBestArmorUnderlayAbsolute(x0, y0, x1, y1, intent);
   }

   public static ItemStack findBestArmorForSlot(InventoryPlayer inventory, int armorSlot) {
      boolean cacheLocal = prepareBestArmorRecommendationCache(inventory);
      if (cacheLocal && armorSlot >= 1 && armorSlot <= 4 && bestArmorPerSlotCacheValid[armorSlot]) {
         return bestArmorPerSlotCache[armorSlot];
      }
      ItemStack result = findBestArmorForSlotUncached(inventory, armorSlot);
      if (cacheLocal && armorSlot >= 1 && armorSlot <= 4) {
         bestArmorPerSlotCache[armorSlot] = result;
         bestArmorPerSlotCacheValid[armorSlot] = true;
      }
      return result;
   }

   private static boolean prepareBestArmorRecommendationCache(InventoryPlayer inventory) {
      Minecraft mc = Minecraft.getMinecraft();
      if (inventory == null || mc == null || mc.thePlayer == null || mc.thePlayer.inventory != inventory) {
         return false;
      }
      prepareInventoryQolCachesForFrame(mc);
      if (bestArmorRecommendationCacheInventory != inventory
            || bestArmorRecommendationCacheFingerprint != playerInvLatestContentFingerprint) {
         Arrays.fill(bestArmorPerSlotCache, null);
         Arrays.fill(bestArmorPerSlotCacheValid, false);
         Arrays.fill(bestArmorInventoryOnlyPerSlotCache, null);
         Arrays.fill(bestArmorInventoryOnlyPerSlotCacheValid, false);
         bestArmorRecommendationCacheInventory = inventory;
         bestArmorRecommendationCacheFingerprint = playerInvLatestContentFingerprint;
      }
      return true;
   }

   private static ItemStack findBestArmorForSlotUncached(InventoryPlayer inventory, int armorSlot) {
      ItemStack[] currentArmorSet = (ItemStack[])inventory.armorInventory.clone();
      double currentProtection = calculateArmorProtectionPercentage(currentArmorSet);
      ItemStack[] bestCompleteSet = findBestCompleteArmorSet(inventory, currentProtection);
      ItemStack bestArmor;
      if (bestCompleteSet != null) {
         bestArmor = bestCompleteSet[armorSlot - 1];
         if (bestArmor != null) {
            return bestArmor;
         }
      }

      bestArmor = null;
      double bestTotalProtection = -1.0D;

      ItemStack held;
      ItemStack[] simulatedSet;
      double totalProtection;
      for(int i = 0; i < 36; ++i) {
         held = inventory.getStackInSlot(i);
         if (held != null) {
            int itemArmorSlot = getArmorSlotForItem(held);
            if (itemArmorSlot == armorSlot) {
               simulatedSet = (ItemStack[])currentArmorSet.clone();
               simulatedSet[armorSlot - 1] = held;
               totalProtection = calculateArmorProtectionPercentage(simulatedSet);
               if (totalProtection > bestTotalProtection + EPSILON) {
                  bestTotalProtection = totalProtection;
                  bestArmor = held;
               } else if (Math.abs(totalProtection - bestTotalProtection) <= EPSILON && bestArmor != null) {
                  // Prefer higher Projectile/Fire level, then enchant rank
                  int lvlHeld = maxProjectileOrFireLevel(held);
                  int lvlBest = maxProjectileOrFireLevel(bestArmor);
                  if (lvlHeld > lvlBest) {
                     bestTotalProtection = totalProtection;
                     bestArmor = held;
                  } else if (lvlHeld == lvlBest) {
                     int rankHeld = enchantPreferenceRank(held);
                     int rankBest = enchantPreferenceRank(bestArmor);
                     if (rankHeld > rankBest) {
                        bestTotalProtection = totalProtection;
                        bestArmor = held;
                     } else if (rankHeld == rankBest) {
                        if (held.getItemDamage() < bestArmor.getItemDamage()) {
                           bestTotalProtection = totalProtection;
                           bestArmor = held;
                        }
                     }
                  }
               }
            }
         }
      }

      try {
         Minecraft mc = Minecraft.getMinecraft();
         if (mc != null && mc.thePlayer != null && mc.thePlayer.inventory != null) {
            held = mc.thePlayer.inventory.getItemStack();
            if (held != null) {
               int itemArmorSlot = getArmorSlotForItem(held);
               if (itemArmorSlot == armorSlot) {
                  simulatedSet = (ItemStack[])currentArmorSet.clone();
                  simulatedSet[armorSlot - 1] = held;
                  totalProtection = calculateArmorProtectionPercentage(simulatedSet);
                  if (totalProtection > bestTotalProtection + EPSILON) {
                     bestTotalProtection = totalProtection;
                     bestArmor = held;
                  } else if (Math.abs(totalProtection - bestTotalProtection) <= EPSILON && bestArmor != null) {
                     int lvlHeld = maxProjectileOrFireLevel(held);
                     int lvlBest = maxProjectileOrFireLevel(bestArmor);
                     if (lvlHeld > lvlBest) {
                        bestTotalProtection = totalProtection;
                        bestArmor = held;
                     } else if (lvlHeld == lvlBest) {
                        int rankHeld = enchantPreferenceRank(held);
                        int rankBest = enchantPreferenceRank(bestArmor);
                        if (rankHeld > rankBest) {
                           bestTotalProtection = totalProtection;
                           bestArmor = held;
                        } else if (rankHeld == rankBest) {
                           if (held.getItemDamage() < bestArmor.getItemDamage()) {
                              bestTotalProtection = totalProtection;
                              bestArmor = held;
                           }
                        }
                     }
                  }
               }
            }
         }
      } catch (Exception var16) {
      }

      return bestArmor;
   }

   public static void render(int guiLeft, int guiTop, int mouseX, int mouseY) {
      Minecraft mc = Minecraft.getMinecraft();
      if (mc == null || GameContext.shouldSuppressInventoryQolOverlays(mc)) {
         return;
      }
      int percentageMode = ConfigManager.getBestArmorPercentageMode();
      boolean wantPercentage = percentageMode != ConfigManager.BEST_ARMOR_PERCENTAGE_OFF;
      if (!enabled && !wantPercentage) {
         return;
      }
      if (mc.currentScreen instanceof GuiInventory) {
         if (mc.thePlayer != null && mc.thePlayer.inventory != null) {
            InventoryPlayer inventory = mc.thePlayer.inventory;
            int recommendedArmorStartX = guiLeft - 26;
            int armorSlotStartY = guiTop + 7;
            int currentArmorStartX = guiLeft + 7;
            int slotSize = 18;
            double currentProtection = calculateArmorProtectionPercentage(inventory.armorInventory);
            boolean hasBetterArmor = false;

                // Compute globally optimal mixed set (any material mix) and recommend all differing pieces at once
                ItemStack[] currentSetForCompare = (ItemStack[])inventory.armorInventory.clone();
                ItemStack[] bestSetGlobal = findBestMixedSet(inventory);
               boolean usedGlobal = false;
               double bestSetProtection = currentProtection;
               if (bestSetGlobal != null) {
                  bestSetProtection = calculateArmorProtectionPercentage(bestSetGlobal);
                  if (bestSetProtection > currentProtection + 1.0E-6D) {
                     usedGlobal = true;
                     hasBetterArmor = true; // ensure UI shows and uses highlight color when global set is better
                     } else if (Math.abs(bestSetProtection - currentProtection) <= 1.0E-6D) {
                     // Equal protection tie: if any desired piece differs and has a tie type, use global
                     boolean anyTie = false;
                     for (int s = 1; s <= 4; s++) {
                        ItemStack desired = bestSetGlobal[s - 1];
                        ItemStack currentEq = currentSetForCompare[s - 1];
                        if (desired != null && (currentEq == null || !armorPiecesLogicallyEquivalent(desired, currentEq))) {
                           int tieType = detectTieTypeForCandidate(inventory, s, desired);
                           if (tieType != -1) { anyTie = true; break; }
                        }
                     }
                     if (anyTie) {
                        usedGlobal = true;
                        hasBetterArmor = true;
                     }
                  }
               }

               if (!usedGlobal) {
                  for(int slot = 1; slot <= 4; ++slot) {
                     ItemStack[] currentSet = (ItemStack[])inventory.armorInventory.clone();
                     double currentPercent = calculateArmorProtectionPercentage(currentSet);
                     ItemStack bestCandidate = findBestArmorForSlot(inventory, slot);
                     if (bestCandidate != null) {
                        ItemStack[] swapped = (ItemStack[])currentSet.clone();
                        swapped[slot - 1] = bestCandidate;
                        double swappedPercent = calculateArmorProtectionPercentage(swapped);
                        double improvement = swappedPercent - currentPercent;
                        if (improvement > 1.0E-4D) {
                           hasBetterArmor = true;
                        }
                     }
                  }
               }

               if (wantPercentage) {
                  GlStateManager.pushMatrix();
                  GlStateManager.scale(0.6F, 0.6F, 1.0F);
                  EnumChatFormatting currentColor = hasBetterArmor ? EnumChatFormatting.YELLOW : EnumChatFormatting.GREEN;
                  String percentOnly = formatPercentValue(currentProtection);
                  int numWidth = mc.fontRendererObj.getStringWidth(percentOnly);
                  int currentProtectionX = (int)(((float)(currentArmorStartX + 9) - (float)numWidth * 0.6F / 2.0F) / 0.6F);
                  int currentProtectionY = (int)((float)(armorSlotStartY - 5) / 0.6F);
                  float x = (float)currentProtectionX;
                  float y = (float)currentProtectionY;
                  mc.fontRendererObj.drawStringWithShadow(currentColor + percentOnly, x, y, 16777215);
                  x += (float)mc.fontRendererObj.getStringWidth(percentOnly);
                  mc.fontRendererObj.drawStringWithShadow(currentColor + "%", x, y, 16777215);
                  x += (float)mc.fontRendererObj.getStringWidth(currentColor + "%");
                  if (percentageMode == ConfigManager.BEST_ARMOR_PERCENTAGE_DETAILED) {
                     ItemStack[] currentArmorSnapshot = (ItemStack[])inventory.armorInventory.clone();
                     ReductionRollOdds odds = calculateArmorReductionRollOdds(currentArmorSnapshot);
                     String detailText = buildReductionRangeText(odds);
                     if (!detailText.isEmpty()) {
                        mc.fontRendererObj.drawStringWithShadow(EnumChatFormatting.GRAY + " " + detailText, x, y, 16777215);
                     }
                  }
                  GlStateManager.popMatrix();
               }

               if (!enabled) {
                  return;
               }
               if (hasBetterArmor) {
                  boolean[] showSlot = new boolean[5];
                  int[] yPosForSlot = new int[5];
                  ItemStack[] armorToShowArr = new ItemStack[5];
                  double[] improvementArr = new double[5];

                  int slot;
                  int yPos;
                  ItemStack armorToShow;
                  for(slot = 4; slot >= 1; --slot) {
                     yPos = armorSlotStartY + (4 - slot) * slotSize;
                     yPosForSlot[slot] = yPos;
               if (usedGlobal) {
                  ItemStack desired = bestSetGlobal[slot - 1];
                  ItemStack currentEq = currentSetForCompare[slot - 1];
                 boolean differs = false;
                 if (desired != null) {
                    if (currentEq == null) {
                       differs = true;
                    } else if (!armorPiecesLogicallyEquivalent(currentEq, desired)) {
                       differs = true;
                    }
                 }
                 if (differs) {
                     // Contribution of this slot alone: mixed-set wins can come from another piece (e.g. chest).
                     // Do not suggest swapping boots when reverting only boots to equipped yields the same DR.
                     ItemStack[] tempSet = (ItemStack[]) bestSetGlobal.clone();
                     tempSet[slot - 1] = currentEq;
                     double withoutThisSlotChoice = calculateArmorProtectionPercentage(tempSet);
                     double contribution = Math.max(0.0D, bestSetProtection - withoutThisSlotChoice);
                     int tieOnDesired = detectTieTypeForCandidate(inventory, slot, desired);
                     boolean slotMatters = contribution > MIN_ARMOR_SLOT_MARGINAL_DR
                           || (Math.abs(contribution) <= MIN_ARMOR_SLOT_MARGINAL_DR && tieOnDesired != -1);
                     if (slotMatters) {
                        showSlot[slot] = true;
                        armorToShowArr[slot] = desired;
                        improvementArr[slot] = contribution;
                        hasBetterArmor = true;
                     }
                  }
                     } else {
                        armorToShow = findBestArmorForSlotInInventoryOnly(inventory, slot);
                        if (armorToShow != null) {
                           ItemStack[] currentSet = (ItemStack[])inventory.armorInventory.clone();
                           double currentPercent = calculateArmorProtectionPercentage(currentSet);
                           ItemStack[] swapped = (ItemStack[])currentSet.clone();
                           swapped[slot - 1] = armorToShow;
                           double swappedPercent = calculateArmorProtectionPercentage(swapped);
                           if (swappedPercent > currentPercent + 1.0E-6D) {
                              showSlot[slot] = true;
                              armorToShowArr[slot] = armorToShow;
                              improvementArr[slot] = swappedPercent - currentPercent;
                           }
                        }
                     }
                  }

                  slot = 4;

                 // Draw each suggested piece independently (no grouping)
                 while(true) {
                    for(slot = 4; slot >= 1; --slot) {
                        if (showSlot[slot]) {
                           yPos = yPosForSlot[slot];
                           armorToShow = armorToShowArr[slot];
                           ItemStack previewArmorToShow = createStablePreviewStack(armorToShow);
                           double improvement = improvementArr[slot];
                           GlStateManager.pushMatrix();
                           GlStateManager.enableRescaleNormal();
                           GlStateManager.enableAlpha();
                           GlStateManager.enableBlend();
                           GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                           RenderHelper.enableGUIStandardItemLighting();
                           // Optional split-color overlay behind the recommended item if tie (projectile or fire)
                           int tieTypeCandidate = detectTieTypeForCandidate(inventory, slot, armorToShow);
                           double deltaCandidate = getProtectionDeltaForCandidate(inventory, slot, armorToShow);
                           boolean shouldSplit = (tieTypeCandidate != -1 && deltaCandidate > EPSILON);
                           if (tieTypeCandidate != -1) {
                              int itemX = recommendedArmorStartX + 1;
                              int itemY2 = yPos + 1;
                              int itemSize = 16;
                              int x0 = itemX;
                              int y0 = itemY2;
                              int x1 = itemX + itemSize;
                              int y1 = itemY2 + itemSize;
                              int width = itemSize;
                              int height = itemSize;
                              GlStateManager.disableLighting();
                              GlStateManager.enableBlend();
                              GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                              if (shouldSplit) {
                                 // Diagonal fill: green upper, tie color lower (higher DR + special enchant)
                                 for (int xi = x0; xi < x1; xi++) {
                                    int dy = (xi - x0) * height / width;
                                    int ySplit = y1 - dy; // bottom-left to top-right
                                    Gui.drawRect(xi, y0, xi + 1, ySplit, 0x4000FF00);
                                    int lower = tieFillColor(tieTypeCandidate);
                                    Gui.drawRect(xi, ySplit, xi + 1, y1, lower);
                                 }
                                 // Split border: green top/left, tie color bottom/right
                                 int tieBorder = tieBorderColor(tieTypeCandidate);
                                 Gui.drawRect(x0, y0, x1, y0 + 1, 0x8000FF00); // Top green
                                 Gui.drawRect(x0, y1 - 1, x1, y1, tieBorder); // Bottom
                                 Gui.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, 0x8000FF00); // Left green
                                 Gui.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, tieBorder); // Right
                              } else {
                                 // Solid tie color only (equal DR + special enchant)
                                 Gui.drawRect(x0, y0, x1, y1, tieFillColor(tieTypeCandidate));
                                 int tieBorder = tieBorderColor(tieTypeCandidate);
                                 Gui.drawRect(x0, y0, x1, y0 + 1, tieBorder);
                                 Gui.drawRect(x0, y1 - 1, x1, y1, tieBorder);
                                 Gui.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, tieBorder);
                                 Gui.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, tieBorder);
                              }
                              // No gradient blending per request
                              GlStateManager.disableBlend();
                              GlStateManager.enableLighting();
                           } else {
                              int itemX = recommendedArmorStartX + 1;
                              int itemY2 = yPos + 1;
                              int itemSize = 16;
                              int x0 = itemX;
                              int y0 = itemY2;
                              int x1 = itemX + itemSize;
                              int y1 = itemY2 + itemSize;
                              GlStateManager.disableLighting();
                              GlStateManager.enableBlend();
                              GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                              // Solid green fill and border like inventory overlay
                              Gui.drawRect(x0, y0, x1, y1, 0x4000FF00);
                              Gui.drawRect(x0, y0, x1, y0 + 1, 0x8000FF00); // Top
                              Gui.drawRect(x0, y1 - 1, x1, y1, 0x8000FF00); // Bottom
                              Gui.drawRect(x0, y0 + 1, x0 + 1, y1 - 1, 0x8000FF00); // Left
                              Gui.drawRect(x1 - 1, y0 + 1, x1, y1 - 1, 0x8000FF00); // Right
                              GlStateManager.disableBlend();
                              GlStateManager.enableLighting();
                           }
                           mc.getRenderItem().zLevel = 200.0F;
                           mc.getRenderItem().renderItemAndEffectIntoGUI(previewArmorToShow, recommendedArmorStartX + 1, yPos + 1);
                           mc.getRenderItem().renderItemOverlayIntoGUI(mc.fontRendererObj, previewArmorToShow, recommendedArmorStartX + 1, yPos + 1, (String)null);
                           mc.getRenderItem().zLevel = 0.0F;
                           RenderHelper.disableStandardItemLighting();
                           GlStateManager.disableRescaleNormal();
                           GlStateManager.disableBlend();
                           GlStateManager.popMatrix();
                           if (improvement > 0.0D && !wouldRenderAsZeroPercent(improvement)) {
                              GlStateManager.pushMatrix();
                              GlStateManager.scale(0.5F, 0.5F, 1.0F);
                              GlStateManager.disableLighting();
                              GlStateManager.disableDepth();
                              String improvementNum = formatPercentValue(improvement);
                              String visibleImprovement = "+" + improvementNum + "%";
                              String improvementText = EnumChatFormatting.GREEN + visibleImprovement;
                              int textWidth = mc.fontRendererObj.getStringWidth(visibleImprovement);
                              int improvementX = (int)((float)(recommendedArmorStartX - 2) / 0.5F - (float)textWidth);
                              int improvementY = (int)((float)(yPos + 7) / 0.5F);
                              mc.fontRendererObj.drawStringWithShadow(improvementText, (float)improvementX, (float)improvementY, 16777215);
                              GlStateManager.enableDepth();
                              GlStateManager.enableLighting();
                              GlStateManager.popMatrix();
                           }
                        }
                     }

                     for(slot = 1; slot <= 4; ++slot) {
                        if (showSlot[slot]) {
                           yPos = yPosForSlot[slot];
                           armorToShow = armorToShowArr[slot];
                           ItemStack previewArmorToShow = createStablePreviewStack(armorToShow);
                           if (mouseX >= recommendedArmorStartX && mouseX < recommendedArmorStartX + slotSize && mouseY >= yPos && mouseY < yPos + slotSize) {
                              GlStateManager.pushMatrix();
                              GlStateManager.disableLighting();
                              GlStateManager.disableDepth();
                              GlStateManager.enableBlend();
                              GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
                              GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
                              if (mc.currentScreen instanceof GuiScreenInvoker) {
                                 ((GuiScreenInvoker) mc.currentScreen).gomod$invokeRenderToolTip(previewArmorToShow, mouseX, mouseY);
                              }
                              GlStateManager.enableLighting();
                              GlStateManager.enableDepth();
                              GlStateManager.popMatrix();
                           }
                        }
                     }

                     return;
                  }
               }
            }
         }
      }

   /**
    * Survival inventory (including Lunar subclasses of {@link GuiInventory}): percentage and recommendations.
    * Tooltip ordering is handled at the original vanilla call site by {@code GuiContainerMixin}; drawing it again
    * here would compound the translucent tooltip background and make the inventory appear to darken on hover.
    */
   public static void renderSurvivalInventoryOverlay(GuiContainer container, int guiLeft, int guiTop, int mouseX, int mouseY) {
      Minecraft mc = Minecraft.getMinecraft();
      if (!isOverlayOrPercentageActiveInContext(mc) || container == null) {
         return;
      }
      if (mc.currentScreen != container || !(container instanceof GuiInventory)) {
         return;
      }
      render(guiLeft, guiTop, mouseX, mouseY);
   }

   /**
    * Survival inventory panel only (no tooltip rendering).
    *
    * Used for z-order-safe hooks that run before items are drawn (e.g. background layer hooks).
    * We pass offscreen mouse coordinates to prevent tooltips inside {@link #render(int, int, int, int)}.
    */
   public static void renderSurvivalInventoryPanelOnly(GuiContainer container, int guiLeft, int guiTop) {
      Minecraft mc = Minecraft.getMinecraft();
      if (!isOverlayOrPercentageActiveInContext(mc) || container == null) {
         return;
      }
      if (mc.currentScreen != container || !(container instanceof GuiInventory)) {
         return;
      }
      // Render panel, but keep mouse outside the recommended-slot hitboxes so no tooltip draws.
      render(guiLeft, guiTop, -10000, -10000);
   }

   /**
    * Checks if an item has any relevant protection enchantments (Protection, Feather Falling, etc.)
    * @param stack The item stack to check
    * @return true if the item has relevant protection enchantments
   */
   private static boolean hasRelevantProtectionEnchantments(ItemStack stack) {
      if (stack == null) {
         return false;
      }
      return getEffectiveEnchantmentLevel(stack, 0) > 0
            || getEffectiveEnchantmentLevel(stack, 1) > 0
            || getEffectiveEnchantmentLevel(stack, 2) > 0
            || getEffectiveEnchantmentLevel(stack, 3) > 0
            || getEffectiveEnchantmentLevel(stack, 4) > 0;
   }

   private static int getEffectiveEnchantmentLevel(ItemStack stack, int enchantId) {
      return Math.max(getDirectEnchantmentLevel(stack, enchantId), getLoreEnchantmentLevel(stack, enchantId));
   }

   private static int getDirectEnchantmentLevel(ItemStack stack, int enchantId) {
      if (stack == null) {
         return 0;
      }
      int level = 0;
      try {
         level = Math.max(level, getEnchantmentLevelFromTagList(stack.getEnchantmentTagList(), enchantId));
         NBTTagCompound tag = stack.getTagCompound();
         if (tag != null && tag.hasKey("StoredEnchantments", 9)) {
            level = Math.max(level, getEnchantmentLevelFromTagList(tag.getTagList("StoredEnchantments", 10), enchantId));
         }
      } catch (Exception ignored) {
      }
      return level;
   }

   private static int getEnchantmentLevelFromTagList(NBTTagList enchantments, int enchantId) {
      if (enchantments == null) {
         return 0;
      }
      int level = 0;
      for (int j = 0; j < enchantments.tagCount(); ++j) {
         NBTTagCompound enchantment = enchantments.getCompoundTagAt(j);
         if (enchantment.getShort("id") == enchantId) {
            level = Math.max(level, enchantment.getShort("lvl"));
         }
      }
      return Math.max(0, level);
   }

   private static int getLoreEnchantmentLevel(ItemStack stack, int enchantId) {
      if (stack == null) {
         return 0;
      }
      String[] aliases = getLoreEnchantmentAliases(enchantId);
      if (aliases == null || aliases.length == 0) {
         return 0;
      }
      try {
         NBTTagCompound tag = stack.getTagCompound();
         if (tag == null || !tag.hasKey("display", 10)) {
            return 0;
         }
         NBTTagCompound display = tag.getCompoundTag("display");
         if (!display.hasKey("Lore", 9)) {
            return 0;
         }
         NBTTagList lore = display.getTagList("Lore", 8);
         int best = 0;
         for (int i = 0; i < lore.tagCount(); ++i) {
            String rawLine = lore.getStringTagAt(i);
            String stripped = EnumChatFormatting.getTextWithoutFormattingCodes(rawLine);
            if (stripped == null) {
               continue;
            }
            String trimmedLine = stripped.trim();
            String lower = trimmedLine.toLowerCase(Locale.ROOT);
            for (String alias : aliases) {
               if (lower.equals(alias)) {
                  best = Math.max(best, 1);
               } else if (lower.startsWith(alias + " ")) {
                  String suffix = trimmedLine.substring(alias.length()).trim();
                  String[] parts = suffix.split("\\s+");
                  if (parts.length > 0) {
                     best = Math.max(best, parseEnchantLevelToken(parts[parts.length - 1]));
                  }
               }
            }
         }
         return best;
      } catch (Exception ignored) {
         return 0;
      }
   }

   private static String[] getLoreEnchantmentAliases(int enchantId) {
      switch (enchantId) {
         case 0: return new String[]{"protection"};
         case 1: return new String[]{"fire protection"};
         case 2: return new String[]{"feather falling"};
         case 3: return new String[]{"blast protection"};
         case 4: return new String[]{"projectile protection"};
         case 5: return new String[]{"respiration"};
         case 6: return new String[]{"aqua affinity"};
         case 7: return new String[]{"thorns"};
         case 8: return new String[]{"depth strider", "depth rider"};
         case 34: return new String[]{"unbreaking"};
         default: return null;
      }
   }

   private static int parseEnchantLevelToken(String token) {
      if (token == null) {
         return 0;
      }
      String trimmed = token.trim();
      if (trimmed.isEmpty()) {
         return 0;
      }
      try {
         return Math.max(0, Integer.parseInt(trimmed));
      } catch (NumberFormatException ignored) {
      }

      int total = 0;
      int prev = 0;
      String upper = trimmed.toUpperCase(Locale.ROOT);
      for (int i = upper.length() - 1; i >= 0; --i) {
         int value;
         switch (upper.charAt(i)) {
            case 'I': value = 1; break;
            case 'V': value = 5; break;
            case 'X': value = 10; break;
            case 'L': value = 50; break;
            case 'C': value = 100; break;
            case 'D': value = 500; break;
            case 'M': value = 1000; break;
            default: return 0;
         }
         if (value < prev) {
            total -= value;
         } else {
            total += value;
            prev = value;
         }
      }
      return Math.max(0, total);
   }

   private static int approximateGenericProtectionValue(int level) {
      if (level <= 0) {
         return 0;
      }
      return (int)Math.floor(((double)(6 + level * level) / 3.0D) * 0.75D);
   }

   private static String formatPercentValue(double value) {
      // Avoid String.format + replaceAll per call — simple char-based trailing zero trim.
      long hundredths = Math.round(value * 100);
      long whole = hundredths / 100;
      long frac = Math.abs(hundredths % 100);
      if (frac == 0) {
         return Long.toString(whole);
      } else if (frac % 10 == 0) {
         return whole + "." + (frac / 10);
      } else {
         return whole + "." + (frac < 10 ? "0" : "") + frac;
      }
   }

   private static final class ReductionRollOdds {
      final double minTotal;
      final double maxTotal;
      final double averageTotal;
      final double minChance;
      final double maxChance;

      ReductionRollOdds(double minTotal, double maxTotal, double averageTotal, double minChance, double maxChance) {
         this.minTotal = minTotal;
         this.maxTotal = maxTotal;
         this.averageTotal = averageTotal;
         this.minChance = minChance;
         this.maxChance = maxChance;
      }
   }

   private static ReductionRollOdds calculateArmorReductionRollOdds(ItemStack[] armorSet) {
      if (armorSet == null) {
         return new ReductionRollOdds(0.0D, 0.0D, 0.0D, 1.0D, 0.0D);
      }

      int totalArmorPoints = 0;
      int totalEPF = 0;
      for (int i = 0; i < armorSet.length; i++) {
         ItemStack armor = armorSet[i];
         if (armor == null) {
            continue;
         }
         if (armor.getItem() instanceof ItemArmor) {
            ItemArmor armorItem = (ItemArmor)armor.getItem();
            totalArmorPoints += armorItem.damageReduceAmount;
            totalEPF += calculateEPFForItem(armor);
         } else if (hasRelevantProtectionEnchantments(armor)) {
            totalEPF += calculateEPFForItem(armor);
         }
      }

      totalEPF = Math.min(totalEPF, 20);
      double baseArmorReduction = Math.min((double)totalArmorPoints * 4.0D, 80.0D);
      double remainingAfterBase = 100.0D - baseArmorReduction;
      double minEpfReduction = remainingAfterBase * 0.04D * ((double)totalEPF / 2.0D);
      double maxEpfReduction = remainingAfterBase * 0.04D * (double)totalEPF;

      double minTotal = clampPercent(baseArmorReduction + minEpfReduction);
      double maxTotal = clampPercent(baseArmorReduction + maxEpfReduction);
      double averageTotal = clampPercent((minTotal + maxTotal) / 2.0D);

      double spread = maxTotal - minTotal;
      if (spread <= EPSILON) {
         return new ReductionRollOdds(minTotal, maxTotal, averageTotal, 1.0D, 0.0D);
      }

      double maxChance = (averageTotal - minTotal) / spread;
      maxChance = Math.max(0.0D, Math.min(1.0D, maxChance));
      double minChance = 1.0D - maxChance;
      return new ReductionRollOdds(minTotal, maxTotal, averageTotal, minChance, maxChance);
   }

   private static String buildReductionRangeText(ReductionRollOdds odds) {
      if (odds == null) {
         return "";
      }
      double spread = odds.maxTotal - odds.minTotal;
      if (spread <= EPSILON) {
         return "";
      }
      return "(" + formatPercentValue(odds.minTotal) + "% - " + formatPercentValue(odds.maxTotal) + "%)";
   }

   private static double clampPercent(double value) {
      return Math.max(0.0D, Math.min(100.0D, value));
   }

   private static boolean wouldRenderAsZeroPercent(double value) {
      return "0".equals(formatPercentValue(Math.max(0.0D, value)));
   }

   private static ItemStack createStablePreviewStack(ItemStack stack) {
      if (stack == null) {
         return null;
      }
      ItemStack preview = stack.copy();
      if (preview.stackSize <= 0) {
         preview.stackSize = 1;
      }
      return preview;
   }

   /**
    * Determines which armor slot a non-armor item should be considered for.
    * Returns -1 if the item cannot be considered for any armor slot.
    * @param stack The item stack to check
    * @return The armor slot (4=helmet, 3=chestplate, 2=leggings, 1=boots) or -1 if not applicable
    */
   private static int getArmorSlotForNonArmorItem(ItemStack stack) {
      if (stack == null || !hasRelevantProtectionEnchantments(stack)) {
         return -1;
      }
      try {
         net.minecraft.item.Item item = stack.getItem();
         // Books / non-wearables: never pretend they occupy the helmet slot for comparisons
         if (item instanceof ItemEnchantedBook || item instanceof ItemWritableBook || item instanceof ItemBook) {
            return -1;
         }
         
         // Method 1: Check by item ID (skulls are ID 397 in 1.8.9)
         int itemId = net.minecraft.item.Item.getIdFromItem(item);
         if (itemId == 397) { // ItemSkull
            return 4; // Helmet slot (4)
         }
         
         // Method 2: Check by class name
         String className = item.getClass().getName();
         if (className != null && className.contains("ItemSkull")) {
            return 4; // Helmet slot (4)
         }
         
         // Method 3: Check by unlocalized name
         String itemName = item.getUnlocalizedName();
         if (itemName != null && (itemName.toLowerCase().contains("skull") || itemName.toLowerCase().contains("head"))) {
            return 4; // Helmet slot (4)
         }
         
         // Method 4: Check by display name if available
         if (stack.hasDisplayName()) {
            String displayName = stack.getDisplayName();
            if (displayName != null) {
               String lowerDisplay = displayName.toLowerCase();
               if (lowerDisplay.contains("skull") || lowerDisplay.contains("head") || 
                   lowerDisplay.contains("wither skeleton") || lowerDisplay.contains("player head")) {
                  return 4; // Helmet slot (4)
               }
            }
         }
         
         // Unknown non-armor (e.g. glitched stack): do not assign a fake armor slot
         return -1;
      } catch (Exception ignored) {
         return -1;
      }
   }

   /**
    * Gets the armor slot for an item, whether it's armor or a non-armor item with enchantments
    * @param stack The item stack to check
    * @return The armor slot (4=helmet, 3=chestplate, 2=leggings, 1=boots) or -1 if not applicable
    */
   public static int getArmorSlotForItem(ItemStack stack) {
      if (stack == null) {
         return -1;
      }
      if (stack.getItem() instanceof ItemArmor) {
         ItemArmor armor = (ItemArmor)stack.getItem();
         return 4 - armor.armorType;
      }
      // Check if it's a non-armor item with relevant enchantments
      return getArmorSlotForNonArmorItem(stack);
   }

   /** Helmet/chest/legs/boots gear BestArmor can evaluate (vanilla armor, prot skulls, etc.). */
   public static boolean isWearableArmorPiece(ItemStack stack) {
      return getArmorSlotForItem(stack) >= 1;
   }

   /**
    * Approximate EPF contribution used by the main protection percentage.
    * This intentionally counts only generic Protection (id 0), so Fire / Blast /
    * Projectile / Feather stay as tie-break information instead of inflating the
    * displayed "overall protection %" score.
    */
   private static int calculateEPFForItem(ItemStack stack) {
      if (stack == null) {
         return 0;
      }
      return approximateGenericProtectionValue(getEffectiveEnchantmentLevel(stack, 0));
   }

   private static double calculateArmorProtectionPercentage(ItemStack[] armorSet) {
      if (armorSet == null) {
         return 0.0D;
      } else {
         int totalArmorPoints = 0;
         int totalEPF = 0;
         ItemStack[] var3 = armorSet;
         int var4 = armorSet.length;

         for(int var5 = 0; var5 < var4; ++var5) {
            ItemStack armor = var3[var5];
            if (armor != null) {
               // Handle regular armor items
               if (armor.getItem() instanceof ItemArmor) {
                  ItemArmor armorItem = (ItemArmor)armor.getItem();
                  totalArmorPoints += armorItem.damageReduceAmount;
                  totalEPF += calculateEPFForItem(armor);
               }
               // Handle non-armor items with relevant protection enchantments (e.g., skulls with Protection)
               else if (hasRelevantProtectionEnchantments(armor)) {
                  // Non-armor items don't provide base armor points, but generic Protection still contributes
                  totalEPF += calculateEPFForItem(armor);
               }
            }
         }

         totalEPF = Math.min(totalEPF, 20);
         double baseArmorReduction = Math.min((double)totalArmorPoints * 4.0D, 80.0D);
         double remainingAfterBase = 100.0D - baseArmorReduction;
         double minEpfReduction = remainingAfterBase * 0.04D * ((double)totalEPF / 2.0D);
         double maxEpfReduction = remainingAfterBase * 0.04D * (double)totalEPF;
         double minTotal = baseArmorReduction + minEpfReduction;
         double maxTotal = baseArmorReduction + maxEpfReduction;
         double midpoint = (minTotal + maxTotal) / 2.0D;
         if (midpoint < 0.0D) {
            midpoint = 0.0D;
         }

         if (midpoint > 100.0D) {
            midpoint = 100.0D;
         }

         return midpoint;
      }
   }

   private static void drawEmptySlot(int x, int y, int size) {
      GlStateManager.enableBlend();
      GlStateManager.tryBlendFuncSeparate(770, 771, 1, 0);
      GlStateManager.disableLighting();
      GlStateManager.disableDepth();
      Gui.drawRect(x, y, x + size, y + size, 1082887051);
      Gui.drawRect(x + 1, y + 1, x + size - 1, y + size - 1, 540489527);
      GlStateManager.enableLighting();
      GlStateManager.enableDepth();
      GlStateManager.disableBlend();
   }

   private static void drawHighlightBorder(int x, int y, int size) {
      GlStateManager.disableLighting();
      GlStateManager.disableDepth();
      GlStateManager.colorMask(true, true, true, false);
      int color = -2147418368;
      Gui.drawRect(x, y, x + size, y + 1, color);
      Gui.drawRect(x, y + size - 1, x + size, y + size, color);
      Gui.drawRect(x, y + 1, x + 1, y + size - 1, color);
      Gui.drawRect(x + size - 1, y + 1, x + size, y + size - 1, color);
      GlStateManager.colorMask(true, true, true, true);
      GlStateManager.enableLighting();
      GlStateManager.enableDepth();
   }

   public static boolean isSlotBestArmorConsideringAllLocations(Slot slot, InventoryPlayer playerInventory) {
      if (enabled && slot != null && playerInventory != null) {
         // Don't process items from excluded containers (server UI chests)
         if (shouldExcludeCurrentContainer()) {
            return false;
         }

         try {
            ItemStack stack = slot.getStack();
            if (stack != null) {
               int armorSlot = getArmorSlotForItem(stack);
               if (armorSlot == -1) {
                  return false; // Not an armor item or item with relevant enchantments
               }
               ItemStack[] currentArmorSet = (ItemStack[])playerInventory.armorInventory.clone();
               double currentProtection = calculateArmorProtectionPercentage(currentArmorSet);
               ItemStack currentlyEquipped = currentArmorSet[armorSlot - 1];

               // First, mirror the sidebar logic: use the global mixed best set if it improves protection
               ItemStack[] bestSetGlobal = findBestMixedSet(playerInventory);
               if (bestSetGlobal != null) {
                  double bestSetProtection = calculateArmorProtectionPercentage(bestSetGlobal);
                  if (bestSetProtection > currentProtection + 1.0E-6D) {
                     ItemStack desired = bestSetGlobal[armorSlot - 1];
                     if (desired != null && armorPiecesLogicallyEquivalent(desired, stack)) {
                        ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(armorSlot, desired,
                              playerInventory);
                        if (keeper != stack) {
                           return false;
                        }
                        if (currentlyEquipped == null) return true;
                        // Only highlight if swapping this specific slot actually improves protection.
                        // (The best set may have improved via a different slot; this slot might be equal.)
                        ItemStack[] setWithCurrent = (ItemStack[]) bestSetGlobal.clone();
                        setWithCurrent[armorSlot - 1] = currentlyEquipped;
                        double protWithDesired = calculateArmorProtectionPercentage(bestSetGlobal);
                        double protWithCurrent = calculateArmorProtectionPercentage(setWithCurrent);
                        if (protWithDesired > protWithCurrent + MIN_ARMOR_SLOT_MARGINAL_DR) return true;
                        if (Math.abs(protWithDesired - protWithCurrent) <= MIN_ARMOR_SLOT_MARGINAL_DR) {
                           // Equal contribution: only highlight if this stack has an enchant tie advantage
                           return detectTieTypeForCandidate(playerInventory, armorSlot, stack) != -1;
                        }
                        return false;
                     }
                     return false;
                  }
               }

               // Fallback: compute best armor across inventories (legacy per-slot behavior)
               ItemStack bestArmor = findBestArmorAcrossAllInventories(slot, playerInventory, armorSlot, currentProtection);
               if (bestArmor != null && armorPiecesLogicallyEquivalent(bestArmor, stack)) {
                  ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(armorSlot, bestArmor,
                        playerInventory);
                  if (keeper != stack) {
                     return false;
                  }
                  if (currentlyEquipped != null && currentlyEquipped == stack) {
                     return false;
                  } else {
                     ItemStack[] testSet = (ItemStack[])currentArmorSet.clone();
                     testSet[armorSlot - 1] = stack;
                     double newProtection = calculateArmorProtectionPercentage(testSet);
                     return newProtection > currentProtection + 0.01D;
                  }
               } else {
                  return false;
               }
            } else {
               return false;
            }
         } catch (Exception var14) {
            System.err.println("[BestArmorDisplay] Error checking slot: " + var14.getMessage());
            return false;
         }
      } else {
         return false;
      }
   }

   private static ItemStack findBestArmorAcrossAllInventories(Slot containerSlot, InventoryPlayer playerInventory, int armorSlot, double currentProtection) {
      ItemStack[] currentArmorSet = (ItemStack[])playerInventory.armorInventory.clone();
      ItemStack[] bestCompleteSet = findBestCompleteArmorSetAcrossAllInventories(containerSlot, playerInventory, currentProtection);
      ItemStack bestArmor;
      if (bestCompleteSet != null) {
         bestArmor = bestCompleteSet[armorSlot - 1];
         if (bestArmor != null) {
            return bestArmor;
         }
      }

      bestArmor = null;
      double bestTotalProtection = -1.0D;

      int i;
      ItemStack equipped;
      ItemStack[] simulatedSet;
      double totalProtection;
      for(i = 0; i < 36; ++i) {
         equipped = playerInventory.getStackInSlot(i);
         if (equipped != null) {
            int itemArmorSlot = getArmorSlotForItem(equipped);
            if (itemArmorSlot == armorSlot) {
               simulatedSet = (ItemStack[])currentArmorSet.clone();
               simulatedSet[armorSlot - 1] = equipped;
               totalProtection = calculateArmorProtectionPercentage(simulatedSet);
               if (totalProtection > bestTotalProtection + EPSILON) {
                  bestTotalProtection = totalProtection;
                  bestArmor = equipped;
               } else if (Math.abs(totalProtection - bestTotalProtection) <= EPSILON && bestArmor != null) {
                  int lvlNew = maxProjectileOrFireLevel(equipped);
                  int lvlBest = maxProjectileOrFireLevel(bestArmor);
                  if (lvlNew > lvlBest) {
                     bestTotalProtection = totalProtection;
                     bestArmor = equipped;
                  } else if (lvlNew == lvlBest) {
                     int rankNew = enchantPreferenceRank(equipped);
                     int rankBest = enchantPreferenceRank(bestArmor);
                     if (rankNew > rankBest) {
                        bestTotalProtection = totalProtection;
                        bestArmor = equipped;
                     } else if (rankNew == rankBest) {
                        if (equipped.getItemDamage() < bestArmor.getItemDamage()) {
                           bestTotalProtection = totalProtection;
                           bestArmor = equipped;
                        }
                     }
                  }
               }
            }
         }
      }

      for(i = 0; i < 4; ++i) {
         equipped = playerInventory.armorInventory[i];
         if (equipped != null) {
            int itemArmorSlot = getArmorSlotForItem(equipped);
            if (itemArmorSlot == armorSlot) {
               simulatedSet = (ItemStack[])currentArmorSet.clone();
               simulatedSet[armorSlot - 1] = equipped;
               totalProtection = calculateArmorProtectionPercentage(simulatedSet);
               if (totalProtection > bestTotalProtection + EPSILON) {
                  bestTotalProtection = totalProtection;
                  bestArmor = equipped;
               } else if (Math.abs(totalProtection - bestTotalProtection) <= EPSILON && bestArmor != null) {
                  int lvlNew = maxProjectileOrFireLevel(equipped);
                  int lvlBest = maxProjectileOrFireLevel(bestArmor);
                  if (lvlNew > lvlBest) {
                     bestTotalProtection = totalProtection;
                     bestArmor = equipped;
                  } else if (lvlNew == lvlBest) {
                     int rankNew = enchantPreferenceRank(equipped);
                     int rankBest = enchantPreferenceRank(bestArmor);
                     if (rankNew > rankBest) {
                        bestTotalProtection = totalProtection;
                        bestArmor = equipped;
                     } else if (rankNew == rankBest) {
                        if (equipped.getItemDamage() < bestArmor.getItemDamage()) {
                           bestTotalProtection = totalProtection;
                           bestArmor = equipped;
                        }
                     }
                  }
               }
            }
         }
      }

      // Check container slots if we're viewing a container (but skip excluded containers)
      if (!shouldExcludeCurrentContainer()) {
         try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen != null && mc.currentScreen instanceof GuiContainer) {
               GuiContainer container = (GuiContainer)mc.currentScreen;
               Container openContainer = container.inventorySlots;
               Iterator var25 = openContainer.inventorySlots.iterator();

               while(var25.hasNext()) {
                  Slot slot = (Slot)var25.next();
                  if (slot.inventory != playerInventory) {
                     ItemStack stack = slot.getStack();
                     if (stack != null) {
                        int itemArmorSlot = getArmorSlotForItem(stack);
                        if (itemArmorSlot == armorSlot) {
                           simulatedSet = (ItemStack[])currentArmorSet.clone();
                           simulatedSet[armorSlot - 1] = stack;
                           totalProtection = calculateArmorProtectionPercentage(simulatedSet);
                           if (totalProtection > bestTotalProtection + EPSILON) {
                              bestTotalProtection = totalProtection;
                              bestArmor = stack;
                           } else if (Math.abs(totalProtection - bestTotalProtection) <= EPSILON && bestArmor != null) {
                              int rankNew = enchantPreferenceRank(stack);
                              int rankBest = enchantPreferenceRank(bestArmor);
                              if (rankNew > rankBest) {
                                 bestTotalProtection = totalProtection;
                                 bestArmor = stack;
                              } else if (rankNew == rankBest) {
                                 if (stack.getItemDamage() < bestArmor.getItemDamage()) {
                                    bestTotalProtection = totalProtection;
                                    bestArmor = stack;
                                 }
                              }
                           }
                        }
                     }
                  }
               }
            }
         } catch (Exception var21) {
         }
      }

      return bestArmor;
   }

   private static ItemStack[] findBestCompleteArmorSetAcrossAllInventories(Slot containerSlot, InventoryPlayer playerInventory, double currentProtection) {
      Map<ArmorMaterial, List<ItemStack>> armorByMaterial = new HashMap();

      int i;
      ItemStack equipped;
      ItemArmor armor;
      ArmorMaterial material;
      for(i = 0; i < 36; ++i) {
         equipped = playerInventory.getStackInSlot(i);
         if (equipped != null && equipped.getItem() instanceof ItemArmor) {
            armor = (ItemArmor)equipped.getItem();
            material = armor.getArmorMaterial();
            if (!armorByMaterial.containsKey(material)) {
               armorByMaterial.put(material, new ArrayList());
            }

            ((List)armorByMaterial.get(material)).add(equipped);
         }
      }

      for(i = 0; i < 4; ++i) {
         equipped = playerInventory.armorInventory[i];
         if (equipped != null && equipped.getItem() instanceof ItemArmor) {
            armor = (ItemArmor)equipped.getItem();
            material = armor.getArmorMaterial();
            if (!armorByMaterial.containsKey(material)) {
               armorByMaterial.put(material, new ArrayList());
            }

            ((List)armorByMaterial.get(material)).add(equipped);
         }
      }
      
      // Note: Non-armor items with enchantments are handled separately in findBestMixedSet
      // They don't have ArmorMaterial, so they can't be part of a complete set by material

      // Add from container (but skip excluded containers)
      Iterator var27;
      if (!shouldExcludeCurrentContainer()) {
         try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc.currentScreen != null && mc.currentScreen instanceof GuiContainer) {
               GuiContainer container = (GuiContainer)mc.currentScreen;
               Container openContainer = container.inventorySlots;
               var27 = openContainer.inventorySlots.iterator();

               while(var27.hasNext()) {
                  Slot slot = (Slot)var27.next();
                  if (slot.inventory != playerInventory) {
                     ItemStack stack = slot.getStack();
                     if (stack != null && stack.getItem() instanceof ItemArmor) {
                        armor = (ItemArmor)stack.getItem();
                        material = armor.getArmorMaterial();
                        if (!armorByMaterial.containsKey(material)) {
                           armorByMaterial.put(material, new ArrayList());
                        }

                        ((List)armorByMaterial.get(material)).add(stack);
                     }
                     // Note: Non-armor items with enchantments are handled separately
                  }
               }
            }
         } catch (Exception var21) {
         }
      }

      ItemStack[] bestSet = null;
      double bestSetProtection = currentProtection;
      var27 = armorByMaterial.entrySet().iterator();

      while(var27.hasNext()) {
         Entry<ArmorMaterial, List<ItemStack>> entry = (Entry)var27.next();
         List<ItemStack> pieces = (List)entry.getValue();
         ItemStack[] candidateSet = new ItemStack[4];
         boolean[] hasSlot = new boolean[4];
         Iterator var13 = pieces.iterator();

         while(var13.hasNext()) {
            ItemStack piece = (ItemStack)var13.next();
            armor = (ItemArmor)piece.getItem();
            int slot = 3 - armor.armorType;
            if (!hasSlot[slot]) {
               candidateSet[slot] = piece;
               hasSlot[slot] = true;
            } else {
               double currentSlotValue = calculateSingleArmorValue(candidateSet[slot]);
               double newSlotValue = calculateSingleArmorValue(piece);
               if (newSlotValue > currentSlotValue) {
                  candidateSet[slot] = piece;
               }
            }
         }

         if (hasSlot[0] && hasSlot[1] && hasSlot[2] && hasSlot[3]) {
            double setProtection = calculateArmorProtectionPercentage(candidateSet);
            if (setProtection > bestSetProtection + 0.01D) {
               bestSetProtection = setProtection;
               bestSet = candidateSet;
            }
         }
      }

      return bestSet;
   }

   public static boolean isSlotBetterArmor(InventoryPlayer inventory, int slotIndex) {
      if (!enabled) {
         return false;
      }
      Minecraft mcProbe = Minecraft.getMinecraft();
      if (mcProbe != null && GameContext.shouldSuppressInventoryQolOverlays(mcProbe)) {
         return false;
      }
      if (slotIndex >= 0 && slotIndex < 40) {
         byte c = slotIsBetterArmorCache[slotIndex];
         if (c != SLOT_IS_BETTER_ARMOR_UNKNOWN) {
            return c == SLOT_IS_BETTER_ARMOR_TRUE;
         }
      }
      boolean r = isSlotBetterArmorUncached(inventory, slotIndex);
      if (slotIndex >= 0 && slotIndex < 40) {
         slotIsBetterArmorCache[slotIndex] = r ? SLOT_IS_BETTER_ARMOR_TRUE : SLOT_IS_BETTER_ARMOR_FALSE;
      }
      return r;
   }

   private static boolean isSlotBetterArmorUncached(InventoryPlayer inventory, int slotIndex) {
      try {
            ItemStack stack = inventory.getStackInSlot(slotIndex);
            if (stack != null) {
               int armorSlot = getArmorSlotForItem(stack);
               if (armorSlot == -1) {
                  return false; // Not an armor item or item with relevant enchantments
               }
               
               // Replicate the exact UI logic
               double currentProtection = calculateArmorProtectionPercentage(inventory.armorInventory);
               ItemStack[] bestSetGlobal = findBestMixedSet(inventory);
               boolean usedGlobal = false;
               
               if (bestSetGlobal != null) {
                  double bestSetProtection = calculateArmorProtectionPercentage(bestSetGlobal);
                  if (bestSetProtection > currentProtection + 1.0E-6D) {
                     usedGlobal = true;
                  } else if (Math.abs(bestSetProtection - currentProtection) <= 1.0E-6D) {
                     // Equal protection but different piece with special tie type: still use global
                     ItemStack desired = bestSetGlobal[armorSlot - 1];
                     ItemStack currentEquipped = inventory.armorInventory[armorSlot - 1];
                     if (desired != null && (currentEquipped == null || !armorPiecesLogicallyEquivalent(desired, currentEquipped))) {
                        int tieType = detectTieTypeForCandidate(inventory, armorSlot, desired);
                        usedGlobal = (tieType != -1);
                     }
                  }
               }
               
               if (usedGlobal) {
                  // Use global mixed-set logic - only highlight if this piece differs from equipped
                  ItemStack desired = bestSetGlobal[armorSlot - 1];
                  ItemStack currentEquipped = inventory.armorInventory[armorSlot - 1];
                  
                  if (desired != null && armorPiecesLogicallyEquivalent(desired, stack)) {
                     ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(armorSlot, desired, inventory);
                     if (keeper != stack) {
                        return false;
                     }
                     // This stack is the single keeper among logical duplicates (best durability / tie-break slot).
                     boolean shouldHighlight = false;
                     if (currentEquipped == null) {
                        shouldHighlight = true; // No current piece, so this is better
                     } else {
                        // Only highlight if swapping this specific slot actually improves protection.
                        // (The best set may have improved via a different slot; this slot might be equal.)
                        ItemStack[] setWithCurrent = (ItemStack[]) bestSetGlobal.clone();
                        setWithCurrent[armorSlot - 1] = currentEquipped;
                        double protWithDesired = calculateArmorProtectionPercentage(bestSetGlobal);
                        double protWithCurrent = calculateArmorProtectionPercentage(setWithCurrent);
                        if (protWithDesired > protWithCurrent + MIN_ARMOR_SLOT_MARGINAL_DR) {
                           shouldHighlight = true;
                        } else if (Math.abs(protWithDesired - protWithCurrent) <= MIN_ARMOR_SLOT_MARGINAL_DR) {
                           // Equal contribution: only highlight if desired has an enchant tie advantage
                           shouldHighlight = (detectTieTypeForCandidate(inventory, armorSlot, desired) != -1);
                        }
                     }
                     
                     return shouldHighlight;
                  }
               } else {
                  // Use individual slot logic (fallback)
                  ItemStack bestArmor = findBestArmorForSlotInInventoryOnly(inventory, armorSlot);
                  boolean sameArmorPiece = bestArmor != null && (
                        (bestArmor.getItem() instanceof ItemArmor && stack.getItem() instanceof ItemArmor)
                              ? armorPiecesLogicallyEquivalent(bestArmor, stack)
                              : (bestArmor.isItemEqual(stack) && ItemStack.areItemStackTagsEqual(bestArmor, stack)));
                  if (sameArmorPiece) {
                     ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(armorSlot, bestArmor, inventory);
                     if (keeper != stack) {
                        return false;
                     }
                     ItemStack currentEquipped = inventory.armorInventory[armorSlot - 1];
                     if (currentEquipped == null) {
                        return true;
                     } else {
                        ItemStack[] currentSet = (ItemStack[])inventory.armorInventory.clone();
                        ItemStack[] swapped = (ItemStack[])currentSet.clone();
                        swapped[armorSlot - 1] = stack;
                        double currentPercent = calculateArmorProtectionPercentage(currentSet);
                        double swappedPercent = calculateArmorProtectionPercentage(swapped);
                        return swappedPercent > currentPercent + 1.0E-6D;
                     }
                  }
               }
               return false;
            } else {
               return false;
            }
      } catch (Exception var14) {
         System.err.println("[BestArmorDisplay] Error in isSlotBetterArmor: " + var14.getMessage());
         return false;
      }
   }

   private static ItemStack findBestArmorForSlotInInventoryOnly(InventoryPlayer inventory, int armorSlot) {
      boolean cacheLocal = prepareBestArmorRecommendationCache(inventory);
      if (cacheLocal && armorSlot >= 1 && armorSlot <= 4
            && bestArmorInventoryOnlyPerSlotCacheValid[armorSlot]) {
         return bestArmorInventoryOnlyPerSlotCache[armorSlot];
      }
      ItemStack result = findBestArmorForSlotInInventoryOnlyUncached(inventory, armorSlot);
      if (cacheLocal && armorSlot >= 1 && armorSlot <= 4) {
         bestArmorInventoryOnlyPerSlotCache[armorSlot] = result;
         bestArmorInventoryOnlyPerSlotCacheValid[armorSlot] = true;
      }
      return result;
   }

   private static ItemStack findBestArmorForSlotInInventoryOnlyUncached(InventoryPlayer inventory, int armorSlot) {
      ItemStack[] currentArmorSet = (ItemStack[])inventory.armorInventory.clone();
      double currentProtection = calculateArmorProtectionPercentage(currentArmorSet);
      ItemStack[] bestCompleteSet = findBestCompleteArmorSet(inventory, currentProtection);
      ItemStack bestArmor;
      if (bestCompleteSet != null) {
         bestArmor = bestCompleteSet[armorSlot - 1];
         if (bestArmor != null) {
            return bestArmor;
         }
      }

      bestArmor = null;
      double bestTotalProtection = -1.0D;

      for(int i = 0; i < 36; ++i) {
         ItemStack stack = inventory.getStackInSlot(i);
         if (stack != null) {
            int itemArmorSlot = getArmorSlotForItem(stack);
            if (itemArmorSlot == armorSlot) {
               ItemStack[] simulatedSet = (ItemStack[])currentArmorSet.clone();
               simulatedSet[armorSlot - 1] = stack;
               double totalProtection = calculateArmorProtectionPercentage(simulatedSet);
               if (totalProtection > bestTotalProtection + EPSILON) {
                  bestTotalProtection = totalProtection;
                  bestArmor = stack;
               } else if (Math.abs(totalProtection - bestTotalProtection) <= EPSILON && bestArmor != null) {
                  int rankNew = enchantPreferenceRank(stack);
                  int rankBest = enchantPreferenceRank(bestArmor);
                  if (rankNew > rankBest) {
                     bestTotalProtection = totalProtection;
                     bestArmor = stack;
                  } else if (rankNew == rankBest) {
                     if (stack.getItemDamage() < bestArmor.getItemDamage()) {
                        bestTotalProtection = totalProtection;
                        bestArmor = stack;
                     }
                  }
               }
            }
         }
      }

      return bestArmor;
   }

   private static ItemStack[] findBestCompleteArmorSet(InventoryPlayer inventory, double currentProtection) {
      Map<ArmorMaterial, List<ItemStack>> armorByMaterial = new HashMap();

      int i;
      ItemStack equipped;
      ItemArmor armor;
      ArmorMaterial material;
      for(i = 0; i < 36; ++i) {
         equipped = inventory.getStackInSlot(i);
         if (equipped != null && equipped.getItem() instanceof ItemArmor) {
            armor = (ItemArmor)equipped.getItem();
            material = armor.getArmorMaterial();
            if (!armorByMaterial.containsKey(material)) {
               armorByMaterial.put(material, new ArrayList());
            }

  

            ((List)armorByMaterial.get(material)).add(equipped);
         }
      }

      for(i = 0; i < 4; ++i) {
         equipped = inventory.armorInventory[i];
         if (equipped != null && equipped.getItem() instanceof ItemArmor) {
            armor = (ItemArmor)equipped.getItem();
            material = armor.getArmorMaterial();
            if (!armorByMaterial.containsKey(material)) {
               armorByMaterial.put(material, new ArrayList());
            }

            ((List)armorByMaterial.get(material)).add(equipped);
         }
      }

      ItemStack[] bestSet = null;
      double bestSetProtection = currentProtection;
      Iterator var22 = armorByMaterial.entrySet().iterator();

      while(var22.hasNext()) {
         Entry<ArmorMaterial, List<ItemStack>> entry = (Entry)var22.next();
         List<ItemStack> pieces = (List)entry.getValue();
         ItemStack[] candidateSet = new ItemStack[4];
         boolean[] hasSlot = new boolean[4];
         Iterator var12 = pieces.iterator();

         while(var12.hasNext()) {
            ItemStack piece = (ItemStack)var12.next();
            armor = (ItemArmor)piece.getItem();
            int slot = 3 - armor.armorType;
            if (!hasSlot[slot]) {
               candidateSet[slot] = piece;
               hasSlot[slot] = true;
            } else {
               double currentSlotValue = calculateSingleArmorValue(candidateSet[slot]);
               double newSlotValue = calculateSingleArmorValue(piece);
               if (newSlotValue > currentSlotValue) {
                  candidateSet[slot] = piece;
               }
            }
         }

         if (hasSlot[0] && hasSlot[1] && hasSlot[2] && hasSlot[3]) {
            double setProtection = calculateArmorProtectionPercentage(candidateSet);
            if (setProtection > bestSetProtection + 0.01D) {
               bestSetProtection = setProtection;
               bestSet = candidateSet;
            }
         }
      }

      return bestSet;
   }

   private static double calculateSingleArmorValue(ItemStack armor) {
      if (armor != null && armor.getItem() instanceof ItemArmor) {
         ItemArmor armorItem = (ItemArmor)armor.getItem();
         double value = (double)(armorItem.damageReduceAmount * 10);
         value += (double)(getEffectiveEnchantmentLevel(armor, 0) * 2);
         return value;
      } else {
         return 0.0D;
      }
   }

   private static void renderItemTooltip(Minecraft mc, ItemStack item, int mouseX, int mouseY) {
      try {
         if (mc.currentScreen != null && item != null) {
            GlStateManager.pushMatrix();
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.enableLighting();
            GlStateManager.enableDepth();
            GlStateManager.disableBlend();
            GlStateManager.colorMask(true, true, true, true);
            if (mc.currentScreen instanceof GuiScreenInvoker) {
               ((GuiScreenInvoker) mc.currentScreen).gomod$invokeRenderToolTip(item, mouseX, mouseY);
            }
            GlStateManager.popMatrix();
         }
      } catch (Exception var5) {
      }

   }

   /**
    * Same armor "identity" as the internal type+enchant matcher (durability ignored for {@link ItemArmor}) — for
    * {@link me.napim.gomod.features.UselessItemOverlay} worn vs duplicate rules.
    */
   public static boolean isArmorLogicallyEquivalent(ItemStack a, ItemStack b) {
      return armorPiecesLogicallyEquivalent(a, b);
   }

   /**
    * Whether {@code stack} is the durability-keeper for its armor slot among logical duplicates of the current mixed
    * best set (same rule as the green overlay). {@link UselessItemOverlay} uses this so surplus copies can tint red.
    */
   public static boolean isBestArmorDuplicateKeeperStack(ItemStack stack, InventoryPlayer inv) {
      if (stack == null || inv == null) {
         return false;
      }
      int armorSlot = getArmorSlotForItem(stack);
      if (armorSlot < 1 || armorSlot > 4) {
         return false;
      }
      ItemStack[] best = getBestMixedSet(inv);
      if (best == null) {
         return false;
      }
      ItemStack desired = best[armorSlot - 1];
      if (desired == null || !armorPiecesLogicallyEquivalent(desired, stack)) {
         return false;
      }
      ItemStack keeper = resolveDuplicateKeeperReferenceForBestArmorHighlight(armorSlot, desired, inv);
      return keeper == stack;
   }

   /**
    * Same armor "identity" for recommendations: item type + enchant NBT match. Durability ({@code itemDamage}) is ignored
    * for {@link ItemArmor} so duplicate chain in inv does not read as "swap helm for helm" vs the worn copy.
    * Leather dye ({@code display.color}) is ignored so colored leather is not treated as a different piece from identical enchants.
    */
   private static boolean armorPiecesLogicallyEquivalent(ItemStack a, ItemStack b) {
      if (a == null || b == null) {
         return a == b;
      }
      if (a.getItem() != b.getItem()) {
         return false;
      }
      if (a.getItem() instanceof ItemArmor) {
         return areArmorTagsEqualForLogic(a, b);
      }
      return a.isItemEqual(b) && ItemStack.areItemStackTagsEqual(a, b);
   }

   /** NBT equality for armor logic: leather ignores {@code display.color} only. */
   private static boolean areArmorTagsEqualForLogic(ItemStack a, ItemStack b) {
      NBTTagCompound ta = copyArmorNbtStrippingLeatherDye(a);
      NBTTagCompound tb = copyArmorNbtStrippingLeatherDye(b);
      if (ta == null && tb == null) {
         return true;
      }
      if (ta == null || tb == null) {
         return false;
      }
      return ta.equals(tb);
   }

   private static boolean isDyedLeatherArmor(ItemStack stack) {
      if (!(stack.getItem() instanceof ItemArmor)) {
         return false;
      }
      ItemArmor ia = (ItemArmor) stack.getItem();
      return ia == Items.leather_helmet || ia == Items.leather_chestplate || ia == Items.leather_leggings
            || ia == Items.leather_boots;
   }

   private static NBTTagCompound copyArmorNbtStrippingLeatherDye(ItemStack stack) {
      NBTTagCompound tag = stack.getTagCompound();
      if (tag == null) {
         return null;
      }
      NBTTagCompound copy = (NBTTagCompound) tag.copy();
      if (isDyedLeatherArmor(stack) && copy.hasKey("display", 10)) {
         NBTTagCompound display = copy.getCompoundTag("display");
         display.removeTag("color");
         if (display.hasNoTags()) {
            copy.removeTag("display");
         }
      }
      return copy.hasNoTags() ? null : copy;
   }

   /** Prefer equipped {@link ItemStack} references when they match the chosen set piece logically. */
   private static void preferEquippedReferencesWhereLogicallySame(ItemStack[] set, InventoryPlayer inventory) {
      if (set == null || inventory == null || inventory.armorInventory == null) {
         return;
      }
      for (int i = 0; i < 4; i++) {
         ItemStack equipped = inventory.armorInventory[i];
         ItemStack chosen = set[i];
         if (equipped != null && chosen != null && equipped != chosen
                 && armorPiecesLogicallyEquivalent(chosen, equipped)) {
            set[i] = equipped;
         }
      }
   }

   // Mixed-set optimizer - finds the best combination across all materials
   private static ItemStack[] findBestMixedSet(InventoryPlayer inventory) {
      try {
         if (inventory == null) {
            return null;
         }
         long fp;
         Minecraft mc = Minecraft.getMinecraft();
         if (mc != null && mc.thePlayer != null && inventory == mc.thePlayer.inventory) {
            prepareInventoryQolCachesForFrame(mc);
            fp = playerInvLatestContentFingerprint;
         } else {
            fp = computeInventoryContentFingerprint(inventory);
         }
         if (inventory == mixedSetCachedInventory && fp == mixedSetCacheFingerprint
               && mixedSetCacheFingerprint != Long.MIN_VALUE) {
            return mixedSetCachedResult;
         }
         
         // Per slot: 1=boots, 2=leggings, 3=chest, 4=helmet. Result array matches armorInventory: [boots, legs, chest, helm].
         List<ItemStack> bootsCandidates = gatherTopCandidatesForSlot(inventory, 1, 8);
         List<ItemStack> leggingsCandidates = gatherTopCandidatesForSlot(inventory, 2, 8);
         List<ItemStack> chestCandidates = gatherTopCandidatesForSlot(inventory, 3, 8);
         List<ItemStack> helmetCandidates = gatherTopCandidatesForSlot(inventory, 4, 8);
         
         if (bootsCandidates.isEmpty() && leggingsCandidates.isEmpty() && chestCandidates.isEmpty() && helmetCandidates.isEmpty()) {
            mixedSetCacheFingerprint = fp;
            mixedSetCachedResult = null;
            mixedSetCachedInventory = inventory;
            return null;
         }
         
         // Include currently equipped pieces if not already in lists
         if (inventory.armorInventory != null) {
            if (inventory.armorInventory[0] != null && !containsStack(bootsCandidates, inventory.armorInventory[0])) {
               bootsCandidates.add(inventory.armorInventory[0]);
            }
            if (inventory.armorInventory[1] != null && !containsStack(leggingsCandidates, inventory.armorInventory[1])) {
               leggingsCandidates.add(inventory.armorInventory[1]);
            }
            if (inventory.armorInventory[2] != null && !containsStack(chestCandidates, inventory.armorInventory[2])) {
               chestCandidates.add(inventory.armorInventory[2]);
            }
            if (inventory.armorInventory[3] != null && !containsStack(helmetCandidates, inventory.armorInventory[3])) {
               helmetCandidates.add(inventory.armorInventory[3]);
            }
         }
         
         double bestProtection = -1.0D;
         int bestTieScore = -1;
         ItemStack[] bestSet = null;
         
         // Try all combinations of top candidates
         int n1 = Math.max(1, bootsCandidates.size());
         int n2 = Math.max(1, leggingsCandidates.size());
         int n3 = Math.max(1, chestCandidates.size());
         int n4 = Math.max(1, helmetCandidates.size());
         
         for (int i1 = 0; i1 < n1; ++i1) {
            ItemStack s1 = bootsCandidates.isEmpty() ? inventory.armorInventory[0] : bootsCandidates.get(i1 % bootsCandidates.size());
            for (int i2 = 0; i2 < n2; ++i2) {
               ItemStack s2 = leggingsCandidates.isEmpty() ? inventory.armorInventory[1] : leggingsCandidates.get(i2 % leggingsCandidates.size());
               for (int i3 = 0; i3 < n3; ++i3) {
                  ItemStack s3 = chestCandidates.isEmpty() ? inventory.armorInventory[2] : chestCandidates.get(i3 % chestCandidates.size());
                  for (int i4 = 0; i4 < n4; ++i4) {
                     ItemStack s4 = helmetCandidates.isEmpty() ? inventory.armorInventory[3] : helmetCandidates.get(i4 % helmetCandidates.size());
                     ItemStack[] set = new ItemStack[]{s1, s2, s3, s4};
                     double protection = calculateArmorProtectionPercentage(set);
                     if (protection > bestProtection + 1.0E-9D) {
                        bestProtection = protection;
                        bestTieScore = getSetTieScore(set);
                        bestSet = set;
                     } else if (Math.abs(protection - bestProtection) <= 1.0E-9D) {
                        int score = getSetTieScore(set);
                        if (score > bestTieScore) {
                           bestTieScore = score;
                           bestSet = set;
                        }
                     }
                  }
               }
            }
         }
         
         if (bestSet != null) {
            preferEquippedReferencesWhereLogicallySame(bestSet, inventory);
         }

         mixedSetCacheFingerprint = fp;
         mixedSetCachedResult = bestSet;
         mixedSetCachedInventory = inventory;

         return bestSet;
      } catch (Exception e) {
         System.err.println("[BestArmorDisplay] Error in findBestMixedSet: " + e.getMessage());
         return null;
      }
   }

   // Public accessor for mixins/HUD to mirror inventory logic
   public static ItemStack[] getBestMixedSet(InventoryPlayer inventory) {
      return findBestMixedSet(inventory);
   }

   private static boolean containsStack(List<ItemStack> list, ItemStack needle) {
      if (needle == null) return false;
      for (ItemStack s : list) {
         if (s == null) continue;
         if (needle.getItem() instanceof ItemArmor && s.getItem() instanceof ItemArmor) {
            if (armorPiecesLogicallyEquivalent(s, needle)) return true;
         } else if (s.isItemEqual(needle) && ItemStack.areItemStackTagsEqual(s, needle)) {
            return true;
         }
      }
      return false;
   }

   private static int getSetTieScore(ItemStack[] set) {
      if (set == null) return 0;
      int score = 0;
      for (ItemStack s : set) {
         if (s == null) continue;
         // Sum of relevant enchant levels on each piece (max of the categories for simple scoring)
         int lvl = 0;
         lvl = Math.max(lvl, getEnchantmentLevel(s, 4)); // Projectile
         lvl = Math.max(lvl, getEnchantmentLevel(s, 1)); // Fire
         lvl = Math.max(lvl, getEnchantmentLevel(s, 3)); // Blast
         lvl = Math.max(lvl, getEnchantmentLevel(s, 2)); // Feather
         lvl = Math.max(lvl, getEnchantmentLevel(s, 7)); // Thorns
         score += lvl;
      }
      return score;
   }
   
   // Returns 2 if item has any enchantment other than Unbreaking; 1 if no enchants; 0 if only Unbreaking
   private static int enchantPreferenceRank(ItemStack stack) {
      try {
         if (stack == null) return 1;
         NBTTagList enchantments = stack.getEnchantmentTagList();
         boolean hasAny = false;
         boolean hasNonUnbreaking = false;
         if (enchantments != null) {
            for (int j = 0; j < enchantments.tagCount(); ++j) {
               NBTTagCompound enchantment = enchantments.getCompoundTagAt(j);
               int enchantId = enchantment.getShort("id");
               hasAny = true;
               if (enchantId != 34) { // 34 = Unbreaking (1.8.9)
                  hasNonUnbreaking = true;
                  break;
               }
            }
         }
         if (!hasNonUnbreaking) {
            int[] loreAwareIds = new int[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 34};
            for (int enchantId : loreAwareIds) {
               int level = getEffectiveEnchantmentLevel(stack, enchantId);
               if (level > 0) {
                  hasAny = true;
                  if (enchantId != 34) {
                     hasNonUnbreaking = true;
                     break;
                  }
               }
            }
         }
         if (hasNonUnbreaking) return 2;
         return hasAny ? 0 : 1;
      } catch (Exception ignored) {
         return 1;
      }
   }

   private static boolean hasProjectileProtection(ItemStack stack) {
      return getEffectiveEnchantmentLevel(stack, 4) > 0;
   }

   private static boolean hasFireProtection(ItemStack stack) {
      return getEffectiveEnchantmentLevel(stack, 1) > 0;
   }

   private static int getEnchantmentLevel(ItemStack stack, int enchantId) {
      return getEffectiveEnchantmentLevel(stack, enchantId);
   }

   private static int maxProjectileOrFireLevel(ItemStack stack) {
      return Math.max(getEnchantmentLevel(stack, 4), getEnchantmentLevel(stack, 1));
   }

   // Priority order for equal-DR enchant type selection: Feather (2) > Projectile (4) > Depth Strider (8) > Fire (1) > Thorns (7) > Blast (3)
   private static final int[] TIE_TYPE_PRIORITY = new int[]{2, 4, 8, 1, 7, 3};

   /** Index in {@link #TIE_TYPE_PRIORITY} (lower = higher priority). Do not use {@code Arrays.binarySearch} — array is not sorted by id. */
   private static int tieTypePriorityIndex(int enchantId) {
      if (enchantId < 0) {
         return Integer.MAX_VALUE;
      }
      for (int i = 0; i < TIE_TYPE_PRIORITY.length; i++) {
         if (TIE_TYPE_PRIORITY[i] == enchantId) {
            return i;
         }
      }
      return Integer.MAX_VALUE;
   }

   private static int getBestTieTypeByPriority(ItemStack stack) {
      if (stack == null) return -1;
      int chosenId = -1;
      for (int id : TIE_TYPE_PRIORITY) {
         int lvl = getEnchantmentLevel(stack, id);
         if (lvl > 0) { chosenId = id; break; }
      }
      return chosenId;
   }

   public static int detectTieTypeForSlot(Slot slot, InventoryPlayer inv) {
      try {
         if (slot == null || inv == null) return -1;
         ItemStack stack = slot.getStack();
         if (stack == null) return -1;
         int armorSlot = getArmorSlotForItem(stack);
         if (armorSlot == -1) return -1;
         ItemStack[] base = (ItemStack[])inv.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[])base.clone();
         withThis[armorSlot - 1] = stack;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         // Allow equal-protection or better to qualify for tie visualization
         if (thisProt + EPSILON < baseProt) return -1;

         int[] ids = new int[]{4,1,3,2,7};
         int bestId = -1; int bestLvl = -1;
         for (int id : ids) {
            int lvl = getEnchantmentLevel(stack, id);
            if (lvl > bestLvl) { bestLvl = lvl; bestId = id; }
         }
         return bestLvl > 0 ? bestId : -1;
      } catch (Exception ignored) { return -1; }
   }

   public static int detectTieTypeForCandidate(InventoryPlayer inventory, int armorSlot, ItemStack candidate) {
      try {
         if (candidate == null) return -1;
         int itemArmorSlot = getArmorSlotForItem(candidate);
         if (itemArmorSlot != armorSlot) return -1;
         ItemStack[] base = (ItemStack[])inventory.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[])base.clone();
         withThis[armorSlot - 1] = candidate;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         if (thisProt + EPSILON < baseProt) return -1; // only when equal or higher
         int bestId = getBestTieTypeByPriority(candidate);
         return bestId;
      } catch (Exception ignored) { return -1; }
   }

   public static int tieFillColor(int enchantId) {
      switch (enchantId) {
         case 4: return 0x40FFFF33; // projectile - yellow
         case 1: return 0x40FF9900; // fire - orange
         case 3: return 0x40FF3333; // blast - red
         case 2: return 0x40FF66CC; // feather - pink
         case 7: return 0x40006600; // thorns - dark green
         case 8: return 0x4033CCFF; // depth strider - light blue
         default: return 0x00000000;
      }
   }

   public static int tieBorderColor(int enchantId) {
      switch (enchantId) {
         case 4: return 0x80FFFF33;
         case 1: return 0x80FF9900;
         case 3: return 0x80FF3333;
         case 2: return 0x80FF66CC; // feather - pink
         case 7: return 0x80006600; // thorns - dark green
         case 8: return 0x8033CCFF; // depth strider - light blue
         default: return 0x00000000;
      }
   }

   public static int lerpColor(int c1, int c2, float t) {
      int a1 = (c1 >>> 24) & 0xFF, r1 = (c1 >>> 16) & 0xFF, g1 = (c1 >>> 8) & 0xFF, b1 = c1 & 0xFF;
      int a2 = (c2 >>> 24) & 0xFF, r2 = (c2 >>> 16) & 0xFF, g2 = (c2 >>> 8) & 0xFF, b2 = c2 & 0xFF;
      int a = (int)(a1 + (a2 - a1) * t) & 0xFF;
      int r = (int)(r1 + (r2 - r1) * t) & 0xFF;
      int g = (int)(g1 + (g2 - g1) * t) & 0xFF;
      int b = (int)(b1 + (b2 - b1) * t) & 0xFF;
      return (a << 24) | (r << 16) | (g << 8) | b;
   }

   // Utility: protection delta for a given slot's stack vs current equipped
   public static double getProtectionDeltaForSlot(Slot slot, InventoryPlayer inv) {
      try {
         if (slot == null || inv == null) return 0.0D;
         ItemStack stack = slot.getStack();
         if (stack == null) return 0.0D;
         int armorSlot = getArmorSlotForItem(stack);
         if (armorSlot == -1) return 0.0D;
         ItemStack[] base = (ItemStack[]) inv.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[]) base.clone();
         withThis[armorSlot - 1] = stack;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         return thisProt - baseProt;
      } catch (Exception ignored) { return 0.0D; }
   }

   // Utility: protection delta for a candidate piece in a given armor slot vs current
   public static double getProtectionDeltaForCandidate(InventoryPlayer inv, int armorSlot, ItemStack candidate) {
      try {
         if (inv == null || candidate == null) return 0.0D;
         int itemArmorSlot = getArmorSlotForItem(candidate);
         if (itemArmorSlot != armorSlot) return 0.0D;
         ItemStack[] base = (ItemStack[]) inv.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[]) base.clone();
         withThis[armorSlot - 1] = candidate;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         return thisProt - baseProt;
      } catch (Exception ignored) { return 0.0D; }
   }

   public static double getProtectionDeltaForArmorSet(ItemStack[] baseSet, ItemStack candidate) {
      try {
         if (baseSet == null || candidate == null) return 0.0D;
         int armorSlot = getArmorSlotForItem(candidate);
         if (armorSlot == -1) return 0.0D;

         ItemStack[] normalized = new ItemStack[4];
         for (int i = 0; i < normalized.length && i < baseSet.length; i++) {
            normalized[i] = baseSet[i];
         }

         double baseProt = calculateArmorProtectionPercentage(normalized);
         ItemStack[] withCandidate = normalized.clone();
         withCandidate[armorSlot - 1] = candidate;
         double candidateProt = calculateArmorProtectionPercentage(withCandidate);
         return candidateProt - baseProt;
      } catch (Exception ignored) { return 0.0D; }
   }

   // Detects if this stack ties in protection gain with another same-slot piece where this stack has Projectile Prot and the other does not
   public static boolean shouldUseProjectileTieStyleForSlot(Slot slot, InventoryPlayer playerInventory) {
      try {
         if (slot == null || playerInventory == null) return false;
         ItemStack stack = slot.getStack();
         if (stack == null) return false;
         int armorSlot = getArmorSlotForItem(stack);
         if (armorSlot == -1) return false;
         if (!hasProjectileProtection(stack)) return false;

         ItemStack[] base = (ItemStack[]) playerInventory.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[]) base.clone();
         withThis[armorSlot - 1] = stack;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         if (thisProt <= baseProt + EPSILON) return false;

         // Scan alternatives of same type (inventory + currently equipped pieces)
         for (int i = 0; i < 36; ++i) {
            ItemStack other = playerInventory.getStackInSlot(i);
            if (other != null && other != stack) {
               int otherArmorSlot = getArmorSlotForItem(other);
               if (otherArmorSlot == armorSlot) {
                  ItemStack[] withOther = (ItemStack[]) base.clone();
                  withOther[armorSlot - 1] = other;
                  double otherProt = calculateArmorProtectionPercentage(withOther);
                  if (Math.abs(otherProt - thisProt) <= EPSILON && !hasProjectileProtection(other)) {
                     return true;
                  }
               }
            }
         }
      } catch (Exception ignored) {}
      return false;
   }

   public static boolean shouldUseFireTieStyleForSlot(Slot slot, InventoryPlayer playerInventory) {
      try {
         if (slot == null || playerInventory == null) return false;
         ItemStack stack = slot.getStack();
         if (stack == null) return false;
         int armorSlot = getArmorSlotForItem(stack);
         if (armorSlot == -1) return false;
         if (!hasFireProtection(stack)) return false;

         ItemStack[] base = (ItemStack[]) playerInventory.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[]) base.clone();
         withThis[armorSlot - 1] = stack;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         if (thisProt <= baseProt + EPSILON) return false;

         for (int i = 0; i < 36; ++i) {
            ItemStack other = playerInventory.getStackInSlot(i);
            if (other != null && other != stack) {
               int otherArmorSlot = getArmorSlotForItem(other);
               if (otherArmorSlot == armorSlot) {
                  ItemStack[] withOther = (ItemStack[]) base.clone();
                  withOther[armorSlot - 1] = other;
                  double otherProt = calculateArmorProtectionPercentage(withOther);
                  if (Math.abs(otherProt - thisProt) <= EPSILON && !hasFireProtection(other)) {
                     return true;
                  }
               }
            }
         }
      } catch (Exception ignored) {}
      return false;
   }

   // Sidebar helper: check tie for a candidate piece for a given armor slot against inventory alternatives
   private static boolean shouldUseProjectileTieStyleForCandidate(InventoryPlayer inventory, int armorSlot, ItemStack candidate) {
      try {
         if (candidate == null) return false;
         if (!hasProjectileProtection(candidate)) return false;
         int itemArmorSlot = getArmorSlotForItem(candidate);
         if (itemArmorSlot != armorSlot) return false;
         ItemStack[] base = (ItemStack[]) inventory.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[]) base.clone();
         withThis[armorSlot - 1] = candidate;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         if (thisProt <= baseProt + EPSILON) return false;
         for (int i = 0; i < 36; ++i) {
            ItemStack other = inventory.getStackInSlot(i);
            if (other != null && other != candidate) {
               int otherArmorSlot = getArmorSlotForItem(other);
               if (otherArmorSlot == armorSlot) {
                  ItemStack[] withOther = (ItemStack[]) base.clone();
                  withOther[armorSlot - 1] = other;
                  double otherProt = calculateArmorProtectionPercentage(withOther);
                  if (Math.abs(otherProt - thisProt) <= EPSILON && !hasProjectileProtection(other)) {
                     return true;
                  }
               }
            }
         }
      } catch (Exception ignored) {}
      return false;
   }

   /**
    * {@code stack} occupies {@code armorSlot} (1..4) and matches {@code desired} for best-armor identity (enchants / NBT;
    * armor ignores durability dye quirks via {@link #armorPiecesLogicallyEquivalent}).
    */
   private static boolean isLogicalArmorPeerForSlot(ItemStack stack, ItemStack desired, int armorSlot) {
      if (stack == null || desired == null) {
         return false;
      }
      if (getArmorSlotForItem(stack) != armorSlot) {
         return false;
      }
      return armorPiecesLogicallyEquivalent(desired, stack);
   }

   /**
    * Among all logical duplicates of the mixed-set piece for this slot (main/hotbar, worn armor, open container),
    * exactly one {@link ItemStack} reference should get the green overlay: lowest {@link ItemStack#getItemDamage()}
    * (most durability left). Ties: lower main/hotbar index (0–35), then equipped, then container slot order.
    * <p>
    * Wearing a more damaged copy while a fresher one sits in the bag makes the bag copy the keeper (min damage).
    * The useless-item overlay still uses {@code WORN_ARMOR_DUPLICATE_RED_MIN_LOST_FRACTION} (40% lost) for red dup tint.
    */
   private static ItemStack resolveDuplicateKeeperReferenceForBestArmorHighlight(int armorSlot, ItemStack desired,
         InventoryPlayer inv) {
      if (desired == null || inv == null || armorSlot < 1 || armorSlot > 4) {
         return null;
      }
      ItemStack best = null;
      int bestDamage = Integer.MAX_VALUE;
      int bestPri = Integer.MAX_VALUE;
      for (int i = 0; i < 36; i++) {
         ItemStack s = inv.getStackInSlot(i);
         if (!isLogicalArmorPeerForSlot(s, desired, armorSlot)) {
            continue;
         }
         int d = s.getItemDamage();
         int pri = i;
         if (d < bestDamage || (d == bestDamage && pri < bestPri)) {
            bestDamage = d;
            best = s;
            bestPri = pri;
         }
      }
      if (inv.armorInventory != null) {
         ItemStack s = inv.armorInventory[armorSlot - 1];
         if (isLogicalArmorPeerForSlot(s, desired, armorSlot)) {
            int d = s.getItemDamage();
            int pri = 1000 + armorSlot;
            if (d < bestDamage || (d == bestDamage && pri < bestPri)) {
               bestDamage = d;
               best = s;
               bestPri = pri;
            }
         }
      }
      if (!shouldExcludeCurrentContainer()) {
         try {
            Minecraft mc = Minecraft.getMinecraft();
            if (mc != null && mc.currentScreen instanceof GuiContainer) {
               GuiContainer gui = (GuiContainer) mc.currentScreen;
               Container open = gui.inventorySlots;
               if (open != null && open.inventorySlots != null) {
                  int pri = 2000;
                  for (Slot sl : open.inventorySlots) {
                     if (sl == null || sl.inventory == inv) {
                        continue;
                     }
                     ItemStack s = sl.getStack();
                     if (isLogicalArmorPeerForSlot(s, desired, armorSlot)) {
                        int d = s.getItemDamage();
                        if (d < bestDamage || (d == bestDamage && pri < bestPri)) {
                           bestDamage = d;
                           best = s;
                           bestPri = pri;
                        }
                     }
                     pri++;
                  }
               }
            }
         } catch (Throwable ignored) {
         }
      }
      return best;
   }

   private static boolean shouldUseFireTieStyleForCandidate(InventoryPlayer inventory, int armorSlot, ItemStack candidate) {
      try {
         if (candidate == null) return false;
         if (!hasFireProtection(candidate)) return false;
         int itemArmorSlot = getArmorSlotForItem(candidate);
         if (itemArmorSlot != armorSlot) return false;
         ItemStack[] base = (ItemStack[]) inventory.armorInventory.clone();
         double baseProt = calculateArmorProtectionPercentage(base);
         ItemStack[] withThis = (ItemStack[]) base.clone();
         withThis[armorSlot - 1] = candidate;
         double thisProt = calculateArmorProtectionPercentage(withThis);
         if (thisProt <= baseProt + EPSILON) return false;
         for (int i = 0; i < 36; ++i) {
            ItemStack other = inventory.getStackInSlot(i);
            if (other != null && other != candidate) {
               int otherArmorSlot = getArmorSlotForItem(other);
               if (otherArmorSlot == armorSlot) {
                  ItemStack[] withOther = (ItemStack[]) base.clone();
                  withOther[armorSlot - 1] = other;
                  double otherProt = calculateArmorProtectionPercentage(withOther);
                  if (Math.abs(otherProt - thisProt) <= EPSILON && !hasFireProtection(other)) {
                     return true;
                  }
               }
            }
         }
      } catch (Exception ignored) {}
      return false;
   }

   private static List<ItemStack> gatherTopCandidatesForSlot(InventoryPlayer inventory, int armorSlot, int topK) {
      List<ItemStack> all = new ArrayList<>();
      try {
         // Include currently equipped piece
         if (inventory.armorInventory != null) {
            ItemStack eq = inventory.armorInventory[armorSlot - 1];
            if (eq != null) {
               int itemArmorSlot = getArmorSlotForItem(eq);
               if (itemArmorSlot == armorSlot) {
                  all.add(eq);
               }
            }
         }
         
         // Scan inventory for armor pieces of this type (including non-armor items with enchantments)
         for (int i = 0; i < 36; ++i) {
            ItemStack stack = inventory.getStackInSlot(i);
            if (stack != null) {
               int itemArmorSlot = getArmorSlotForItem(stack);
               if (itemArmorSlot == armorSlot) {
                  all.add(stack);
               }
            }
         }
         
         // Also scan container inventories (chests, etc.) if a container is open
         {
            try {
               Minecraft mc = Minecraft.getMinecraft();
               Container openContainer = getArmorCandidateContainer(mc);
                  if (openContainer != null) {
                     java.util.Iterator<Slot> it = openContainer.inventorySlots.iterator();
                     while (it.hasNext()) {
                        Slot slot = it.next();
                        // Only process container slots (not player inventory slots)
                        if (slot.inventory != inventory && slot.inventory != null) {
                           ItemStack stack = slot.getStack();
                           if (stack != null) {
                              int itemArmorSlot = getArmorSlotForItem(stack);
                              if (itemArmorSlot == armorSlot) {
                                 all.add(stack);
                              }
                           }
                        }
                     }
                  }
            } catch (Exception e) {
               // Silently ignore container scanning errors
            }
         }
         
         // Deduplicate by item type + enchantments, IGNORING durability (itemDamage).
         // Two diamond helmets with different durabilities but same enchantments are the same "type".
         // Keep the copy with LOWER getItemDamage() (= higher durability remaining).
         // If both have the same damage, keep the first encountered (effectively random).
         List<ItemStack> unique = new ArrayList<>();
         for (ItemStack s : all) {
            if (s == null) continue;
            int existingIdx = -1;
            for (int ui = 0; ui < unique.size(); ui++) {
               ItemStack u = unique.get(ui);
               // Compare by Item reference (same base item) + NBT tags (same enchantments).
               // Deliberately ignore itemDamage (durability) so two helmets with
               // different wear but same enchants are treated as duplicates.
               if (u == null || s.getItem() != u.getItem()) {
                  continue;
               }
               boolean sameIdentity = (s.getItem() instanceof ItemArmor && u.getItem() instanceof ItemArmor)
                       ? areArmorTagsEqualForLogic(s, u)
                       : ItemStack.areItemStackTagsEqual(s, u);
               if (sameIdentity) {
                  existingIdx = ui;
                  break;
               }
            }
            if (existingIdx == -1) {
               unique.add(s);
            } else {
               // Replace existing entry only if this copy has lower damage (more durable)
               if (s.getItemDamage() < unique.get(existingIdx).getItemDamage()) {
                  unique.set(existingIdx, s);
               }
               // Same damage: keep first (effectively random as requested)
            }
         }
         
         // Sort by individual value when swapped into current set
         final ItemStack[] base = (ItemStack[]) inventory.armorInventory.clone();
         List<ItemStack> sorted = new ArrayList<>(unique);
         sorted.sort((a, b) -> {
            ItemStack[] setA = (ItemStack[]) base.clone();
            setA[armorSlot - 1] = a;
            ItemStack[] setB = (ItemStack[]) base.clone();
            setB[armorSlot - 1] = b;
            double pa = calculateArmorProtectionPercentage(setA);
            double pb = calculateArmorProtectionPercentage(setB);
            int cmp = Double.compare(pb, pa);
            if (cmp != 0) return cmp;
            // Equal DR: prefer enchant type by priority list, then level of that type
            int typeB = getBestTieTypeByPriority(b);
            int typeA = getBestTieTypeByPriority(a);
            int priA = typeA == -1 ? Integer.MAX_VALUE : tieTypePriorityIndex(typeA);
            int priB = typeB == -1 ? Integer.MAX_VALUE : tieTypePriorityIndex(typeB);
            if (priA != priB) return Integer.compare(priA, priB);
            if (typeA != -1) {
               int lvlB = getEnchantmentLevel(b, typeB);
               int lvlA = getEnchantmentLevel(a, typeA);
               int cmpLvl2 = Integer.compare(lvlB, lvlA);
               if (cmpLvl2 != 0) return cmpLvl2;
            }
            int rankB = enchantPreferenceRank(b);
            int rankA = enchantPreferenceRank(a);
            int cmpRank = Integer.compare(rankB, rankA);
            if (cmpRank != 0) return cmpRank;
            // Prefer lower damage (higher durability)
            return Integer.compare(a.getItemDamage(), b.getItemDamage());
         });
         
         // Return top K candidates
         if (sorted.size() > topK) {
            return new ArrayList<>(sorted.subList(0, topK));
         }
         return sorted;
      } catch (Exception e) {
         System.err.println("[BestArmorDisplay] Error in gatherTopCandidatesForSlot: " + e.getMessage());
         return all;
      }
   }
}
