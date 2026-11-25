package me.napim.gomod.features;

import me.napim.gomod.GomodReleaseBuild;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Applies Gomod's Locker UI compatibility layer to Lunar's cached WebOSR bundle.
 *
 * The UI is shipped by Lunar rather than Gomod, so this version-guarded patch is
 * bundled with Gomod and applied automatically before Lunar opens the Locker.
 * Unknown bundles are left untouched.
 */
public final class LunarLockerUiBundlePatcher {
    private static final String RESOURCE = "/gomod/lunar-locker-ui-fragment.js";
    private static final String RANDOM_FIT_RESOURCE = "/gomod/lunar-random-fit-ui-fragment.js";
    private static final String RANDOM_FIT_COMPONENT_MARKER = "GomodRandomFitButton=e=>{";
    private static final String RANDOM_FIT_PLAN_MARKER = "GomodRandomFitPlan=";
    private static final String RANDOM_FIT_VERSION_MARKER = "\"data-gomod-random-fit\":\"v4\"";
    private static final String RANDOM_FIT_BUTTON_MARKER = "i.jsx(GomodRandomFitButton,{onSelect:y})";
    private static final String FULLACCESS_RANDOM_FIT_BUTTON_MARKER =
            "gomodFullAccess&&i.jsx(GomodRandomFitButton,{onSelect:y,fullAccess:gomodFullAccess})";
    private static final String FRESH_FIT_SELECTION =
            "y(A=>R.outfits.find(P=>A&&P.id==A.id)||R.outfits.find(P=>P.id==R.defaultOutfitId)||null)";
    private static final String LOGO_COLOR_TAB_MARKER =
            "case i0.LOGO_COLOR:return i.jsx(LogoColorPanel,{})";
    private static final String LEGACY_LOGO_COLOR_TAB_MARKER =
            "case i0.LOGO_COLOR:return gomodLunarFullAccessEnabled()?i.jsx(LogoColorPanel,{}):null";
    private static final String SAVED_LOGO_COLOR_MARKER = "gomodSavedLunarLogoColor=()=>";
    private static final String LOGO_COLOR_PICKER_COMPONENT_MARKER = "GomodLunarLogoColorPicker=e=>";
    private static final String LOGO_COLOR_PICKER_VERSION_MARKER = "\"data-gomod-logo-picker\":\"hsv-v2\"";
    private static final String GOPARKY_SETTINGS_COMPONENT_MARKER = "GomodGoparkyCosmeticSettings=()=>";
    private static final String GOPARKY_SETTINGS_SCROLL_MARKER = "\"data-gomod-settings-scroll\":\"v1\"";
    private static final String COSMETIC_OWNERSHIP_BADGE_DATA_MARKER = "GomodCosmeticOwnershipBadgeData=()=>";
    private static final String COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER = "GomodCosmeticOwnershipBadge=e=>";
    private static final String COSMETIC_OWNERSHIP_BADGE_STATUS_MARKER =
            "gomodOwnershipBadge:gomodCosmeticOwnershipBadgeFor(H2.id,gomodOwnership)";
    private static final String COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER =
            "topRight:i.jsxs(i.Fragment,{children:[F,i.jsx(GomodCosmeticOwnershipBadge,{status:t.gomodOwnershipBadge,cosmeticId:t.id})]})";
    private static final String LEGACY_COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER =
            "topRight:i.jsxs(i.Fragment,{children:[F,i.jsx(GomodCosmeticOwnershipBadge,{status:t.gomodOwnershipBadge})]})";
    private static final String COSMETIC_OWNERSHIP_BADGE_VERSION_MARKER =
            "\"data-gomod-cosmetic-source-badges\":\"v11\"";
    private static final String BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER =
            "topRight:i.jsxs(\"div\",{style:{display:\"flex\",alignItems:\"center\","
                    + "justifyContent:\"flex-end\",gap:\"6px\"},children:["
                    + "i.jsx(GomodCosmeticOwnershipBadge,{badgeId:k.badge.id}),"
                    + "i.jsx(GomodStudioLauncher,{kind:\"badge\",item:k.badge})]})";
    private static final String LEGACY_BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER =
            "topRight:i.jsxs(i.Fragment,{children:[i.jsx(GomodCosmeticOwnershipBadge,{badgeId:k.badge.id}),"
                    + "i.jsx(GomodStudioLauncher,{kind:\"badge\",item:k.badge})]})";
    private static final String EMOTE_OWNERSHIP_BADGE_MARKER =
            "topRight:i.jsx(GomodCosmeticOwnershipBadge,{emoteId:e.emote.id,isFreeLunarPlus:e.isFreeLunarPlus,emoteLimited:e.emote.expireTime!=null&&e.emote.expireTime>0&&e.emote.expiresIn!=null})";
    private static final String LEGACY_EMOTE_OWNERSHIP_BADGE_MARKER =
            "topRight:i.jsx(GomodCosmeticOwnershipBadge,{status:e.isFreeLunarPlus?\"none\":e.emote.expireTime!=null&&e.emote.expireTime>0&&e.emote.expiresIn!=null?\"limited\":\"owned\"})";
    private static final String COSMETIC_OWNERSHIP_BADGE_LAYER_MARKER =
            "nk2=_(\"div\",{display:\"flex\",flexDirection:\"column\",alignItems:\"flex-start\","
                    + "gap:\"4px\",position:\"absolute\",right:0,top:0,zIndex:20})";
    private static final String COSMETIC_GRID_WIDTH_MARKER =
            "name:\"COSMETIC_TAB\",minItemWidth:175";
    private static final String EQUIPPED_SORT_DATA_SYNC_MARKER = "c2(q.current)";
    private static final String GOPARKY_SETTINGS_ROUTE_MARKER =
            "children:Number(t.id)===2000418004?i.jsx(GomodGoparkyCosmeticSettings,{})";
    private static final String COSMETIC_SETTINGS_SCROLL_STYLE =
            "P5=_(\"div\",{display:\"flex\",minWidth:\"275px\",flex:\"1 1 auto\","
                    + "alignItems:\"flex-start\",alignSelf:\"stretch\",flexDirection:\"column\","
                    + "gap:\"var(--spacings-radii-3)\",minHeight:0,maxHeight:\"100%\","
                    + "overflowY:\"auto\",overflowX:\"hidden\",overscrollBehavior:\"contain\","
                    + "boxSizing:\"border-box\"});";
    private static final String LEGACY_DEFAULT_WHITE_PRESET =
            "const savePreset=e=>saveValue(e.value===\"#FFFFFF\"?\"DEFAULT\":e.value.slice(1));";
    private static final String EXPLICIT_WHITE_PRESET =
            "const savePreset=e=>saveValue(e.value.slice(1));";
    private static final String FULLACCESS_HELPER_MARKER = "gomodLunarFullAccessEnabled=()=>";
    private static final String LEGACY_LOCAL_QA_COSMETIC_HELPER =
            "gomodLocalQaCosmetic=e=>{try{const n=window.lunar;return!!(n&&typeof n.gomodIsLocalQaCosmetic===\"function\"&&n.gomodIsLocalQaCosmetic(Number(e)))}catch{return!1}}";
    private static final String LOCAL_QA_COSMETIC_PROP_MARKER =
            "gomodCosmeticId:t.id";
    private static final String LEGACY_LOCAL_QA_COSMETIC_PROP_MARKER =
            "gomodLocalQa:gomodLocalQaCosmetic(t.id)";
    private static final String LOCAL_QA_COSMETIC_EFFECT_MARKER =
            "gomodGetLocalQaCosmeticId(gomodQaId)";
    private static final String OVERLAY_TEXT_FOCUS_GUARD_MARKER =
            "gomodTextFocusGuardInstalled=gomodInstallLunarTextFocusGuard()";
    private static final String OVERLAY_TEXT_FOCUS_GUARD_DEFINITION_MARKER =
            "gomodInstallLunarTextFocusGuard=()=>";
    private static final String LEGACY_LOCAL_QA_COSMETIC_EFFECT_BLOCK =
            "g.useEffect(()=>{let gomodQaActive=!0;try{const gomodQaBridge=window.lunar;"
                    + "if(!gomodQaBridge||typeof gomodQaBridge.gomodIsLocalQaCosmetic!==\"function\")"
                    + "{setGomodLocalQa(!1)}else{Promise.resolve(gomodQaBridge."
                    + "gomodIsLocalQaCosmetic(Number(e.gomodCosmeticId))).then(gomodQaResult=>{"
                    + "if(gomodQaActive)setGomodLocalQa(gomodQaResult===!0||gomodQaResult===\"true\"||"
                    + "gomodQaResult===1||gomodQaResult===\"1\")}).catch(()=>{"
                    + "if(gomodQaActive)setGomodLocalQa(!1)})}}catch{setGomodLocalQa(!1)}"
                    + "return()=>{gomodQaActive=!1}},[e.gomodCosmeticId]);const o=";
    private static final String LOCAL_QA_COSMETIC_EFFECT_BLOCK =
            "g.useEffect(()=>{let gomodQaActive=!0;const gomodQaId=Number(e.gomodCosmeticId);"
                    + "setGomodLocalQa(!1);try{const gomodQaBridge=window.lunar;"
                    + "if(!gomodQaBridge||typeof gomodQaBridge.gomodGetLocalQaCosmeticId!==\"function\")"
                    + "{setGomodLocalQa(!1)}else{Promise.resolve(gomodQaBridge."
                    + "gomodGetLocalQaCosmeticId(gomodQaId)).then(gomodQaResult=>{"
                    + "if(gomodQaActive)setGomodLocalQa(typeof gomodQaResult===\"string\"&&"
                    + "gomodQaResult===String(gomodQaId))}).catch(()=>{"
                    + "if(gomodQaActive)setGomodLocalQa(!1)})}}catch{setGomodLocalQa(!1)}"
                    + "return()=>{gomodQaActive=!1}},[e.gomodCosmeticId]);const o=";
    private static final String LOCAL_QA_COSMETIC_THUMBNAIL_STYLE_MARKER =
            "style:gomodLocalQa?{background:\"rgba(255,145,0,.12)\",borderRadius:\"8px\"}:void 0";
    private static final String LEGACY_LOCAL_QA_COSMETIC_THUMBNAIL_STYLE_MARKER =
            "style:e.gomodLocalQa?{background:\"rgba(255,145,0,.12)\",borderRadius:\"8px\"}:void 0";
    private static final String LEGACY_FULLACCESS_HELPER_MARKER =
            "gomodLunarFullAccessEnabled=()=>{try{return!!(window.lunar";
    private static final String FULLACCESS_TAB_MARKER =
            "LogoColorTab=e=>{const[gomodTabEnabled,setGomodTabEnabled]=g.useState(!1);"
                    + "g.useEffect(()=>{let gomodTabActive=!0";
    private static final String FULLACCESS_PANEL_MARKER =
            "LogoColorPanel=()=>{const[gomodPanelEnabled,setGomodPanelEnabled]=g.useState(!1);"
                    + "g.useEffect(()=>{let gomodPanelActive=!0";
    private static final String FULLACCESS_PANEL_CONTENT_MARKER =
            "return gomodPanelEnabled?i.jsx(LogoColorPanelContent,{}):null},"
                    + "LogoColorPanelContent=()=>{";
    private static final String PREVIEW_MARKER =
            "GomodLunarPlusPreviewNametag,{activeColor:d";
    private static final String ACCOUNT_TILE_PLUS_COLOR =
            "plusColor:e.lunarPlus.gomodHidePlus?\"transparent\":"
                    + "e.lunarPlus.isLunarPlus?e.lunarPlus.plusColor:\"#000000\"";
    private static final String NATIVE_ACCOUNT_TILE_PLUS_COLOR =
            "plusColor:e.lunarPlus.isLunarPlus?e.lunarPlus.plusColor:\"#000000\"";

    private LunarLockerUiBundlePatcher() {
    }

    /** Runs at Weave pre-init, before Lunar's WebOSR page reads its cached bundle. */
    public static void patchSupportedBundles() {
        // Both Lunar flavors expose FullAccess logo-color and No+ controls.
        String userHome = System.getProperty("user.home");
        if (userHome == null || userHome.trim().isEmpty()) {
            System.out.println("[gomod] Locker UI patch skipped: user home is unavailable");
            return;
        }

        Path uiRoot = Paths.get(userHome, ".lunarclient", "ui");
        if (!Files.isDirectory(uiRoot)) {
            System.out.println("[gomod] Locker UI patch skipped: Lunar UI cache is not present yet");
            return;
        }

        final String fragment;
        try {
            fragment = readUiFragment();
        } catch (Throwable error) {
            System.err.println("[gomod] Locker UI patch resource unavailable: "
                    + error.getClass().getSimpleName());
            return;
        }

        try (Stream<Path> files = Files.find(uiRoot, 8,
                (Path path, BasicFileAttributes attributes) -> attributes.isRegularFile()
                        && "index.js".equals(path.getFileName().toString())
                        && path.getParent() != null
                        && "static".equals(path.getParent().getFileName().toString()))) {
            files.forEach(path -> patchStaticDirectory(uiRoot, path, fragment));
        } catch (Throwable error) {
            System.err.println("[gomod] Locker UI patch scan failed: "
                    + error.getClass().getSimpleName());
        }
    }

    private static void patchStaticDirectory(Path uiRoot, Path indexBundle, String fragment) {
        Path staticDir = indexBundle.getParent();
        if (staticDir != null && isChunkedLockerDirectory(staticDir)) {
            patchChunkedLockerDirectory(uiRoot, staticDir);
            return;
        }
        patchOneBundle(uiRoot, indexBundle, fragment);
    }

    private static String readUiFragment() throws IOException {
        InputStream stream = LunarLockerUiBundlePatcher.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IOException("missing packaged UI fragment");
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            String fragment = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!fragment.contains("LogoColorPanel=()=>")
                    || !fragment.contains(LOGO_COLOR_PICKER_COMPONENT_MARKER)
                    || !fragment.contains(LOGO_COLOR_PICKER_VERSION_MARKER)
                    || !fragment.contains(GOPARKY_SETTINGS_COMPONENT_MARKER)
                    || !fragment.contains(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER)
                    || !fragment.contains(COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER)
                    || !fragment.contains(SAVED_LOGO_COLOR_MARKER)
                    || !fragment.contains(EXPLICIT_WHITE_PRESET)
                    || !fragment.contains("GomodLunarPlusPreviewNametag=")) {
                throw new IOException("packaged UI fragment failed its marker checks");
            }
            return fragment;
        }
    }

    private static void patchOneBundle(Path uiRoot, Path bundle, String fragment) {
        try {
            byte[] originalBytes = Files.readAllBytes(bundle);
            boolean hasUtf8Bom = originalBytes.length >= 3
                    && (originalBytes[0] & 0xFF) == 0xEF
                    && (originalBytes[1] & 0xFF) == 0xBB
                    && (originalBytes[2] & 0xFF) == 0xBF;
            int offset = hasUtf8Bom ? 3 : 0;
            String original = new String(originalBytes, offset,
                    originalBytes.length - offset, StandardCharsets.UTF_8);

            if (isFullyPatched(original)) {
                return;
            }

            String patched = transformBundleText(original, fragment);
            if (patched.equals(original)) {
                return;
            }

            byte[] body = patched.getBytes(StandardCharsets.UTF_8);
            byte[] replacement = hasUtf8Bom ? withUtf8Bom(body) : body;
            replaceWithBackup(uiRoot, bundle, originalBytes, replacement);
            System.out.println("[gomod] Locker UI compatibility patch applied: "
                    + uiRoot.relativize(bundle).toString().replace('\\', '/'));
        } catch (UnsupportedBundleException error) {
            System.out.println("[gomod] Locker UI patch skipped (unsupported bundle): "
                    + uiRoot.relativize(bundle).toString().replace('\\', '/')
                    + " reason=" + error.getMessage());
        } catch (Throwable error) {
            System.err.println("[gomod] Locker UI patch failed safely: "
                    + uiRoot.relativize(bundle).toString().replace('\\', '/')
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private static boolean isFullyPatched(String source) {
        return source.contains(LOGO_COLOR_TAB_MARKER)
                && source.contains("\"data-gomod-studio\":\"v11\"")
                && source.contains("onMouseLeave:()=>{visible&&O.setAllowKeybinds(!0)}")
                && source.contains("kind:\"cosmetic\",id:d.id,visible:l.visible")
                && source.contains("kind:\"badge\",id:k.badge.id,item:k.badge")
                && source.contains("kind:\"speed\",id:e.emote.id,item:e.emote")
                && source.contains(BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER)
                && source.contains("i.jsx(GomodCustomBadgeImage,{badgeId:e.ownedBadge.badge.id,resource:e.ownedBadge.badge.resource")
                && count(source, "GomodUseFullAccess=()=>{") == 1
                && source.contains("\"data-gomod-emote-sharing\":\"v5\"")
                && source.contains("kind:\"badge\",item:k.badge")
                && source.contains("kind:\"speed\",item:e.emote")
                && source.contains("i.jsx(GomodStudioLauncher,{kind:\"custom\"})")
                && source.contains("\"data-gomod-cosmetic-appearance\":\"v11\"")
                && source.contains(COSMETIC_SETTINGS_SCROLL_STYLE)
                && source.contains("Od0=({cosmetics:e,cosmetic:t,fitId,onReturn:r})=>{")
                && source.contains("cosmetic:u1,fitId:p&&p.id,onReturn:")
                && source.contains("title:\"Appearance\",children:i.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:t.id,cosmeticName:t.name,cosmeticType:t.type,fitId:fitId})")
                && source.contains("Hue (degree)")
                && source.contains("Saturation %")
                && source.contains("Brightness %")
                && source.contains("format:value=>control.key===\"scale\"?Number(value).toFixed(2)+\"×\":String(Math.round(value))")
                && source.contains("n=g.useRef(e.format||(S=>S.toFixed(2)))")
                && source.contains("l=S=>e.format?e.format(S):S.toFixed(2)")
                && source.contains(SAVED_LOGO_COLOR_MARKER)
                && source.contains(LOGO_COLOR_PICKER_COMPONENT_MARKER)
                && source.contains(LOGO_COLOR_PICKER_VERSION_MARKER)
                && source.contains("i.jsx(GomodLunarLogoColorPicker,{value:draft,onChange:preview})")
                && source.contains(FULLACCESS_HELPER_MARKER)
                && source.contains(FULLACCESS_TAB_MARKER)
                && source.contains(FULLACCESS_PANEL_MARKER)
                && source.contains(FULLACCESS_PANEL_CONTENT_MARKER)
                && source.contains(EXPLICIT_WHITE_PRESET)
                && !source.contains(LEGACY_FULLACCESS_HELPER_MARKER)
                && source.contains("children:e.noPlus?i.jsx(wa,{w:40,h:40,plusColor:\"#000000\"")
                && source.contains("logoColor:gomodSavedLunarLogoColor()")
                && source.contains("noPlus:k.color===0,logoColor:gomodSavedLunarLogoColor()")
                && source.contains(PREVIEW_MARKER)
                && source.contains(ACCOUNT_TILE_PLUS_COLOR)
                && count(source, "GomodLunarOnlineLogo=e=>{") == 1
                && source.contains("i.jsx(GomodLunarOnlineLogo,{")
                && source.contains("transform:e.gomodOffline?\"rotate(45 128.84 36.5)\":void 0")
                && source.contains(GOPARKY_SETTINGS_COMPONENT_MARKER)
                && source.contains(GOPARKY_SETTINGS_SCROLL_MARKER)
                && source.contains(GOPARKY_SETTINGS_ROUTE_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_STATUS_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_VERSION_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_LAYER_MARKER)
                && source.contains(COSMETIC_GRID_WIDTH_MARKER)
                && count(source, EQUIPPED_SORT_DATA_SYNC_MARKER) == 2
                && source.contains("randomPlayerInteractions")
                && source.contains("goparky-native-walk-v2")
                && source.contains("i.jsx(zb,{title:a.name")
                && source.contains("i.jsx(Nb,{title:\"Random player interactions\"")
                && source.contains("Save goparky settings")
                && source.contains("gomodGetGoparkyTauntSettings")
                && source.contains("gomodSaveGoparkyTauntSettings")
                && source.contains("renderNametag:!1")
                && !source.contains("rgba(255,145,0,.12)")
                && !source.contains("gomodLocalQa")
                && !source.contains(LOCAL_QA_COSMETIC_PROP_MARKER)
                && !source.contains(LOCAL_QA_COSMETIC_EFFECT_MARKER)
                && source.contains("gomodSetLockerTextFocus(!0)")
                && source.contains(OVERLAY_TEXT_FOCUS_GUARD_MARKER)
                && source.contains(RANDOM_FIT_COMPONENT_MARKER)
                && source.contains(FULLACCESS_RANDOM_FIT_BUTTON_MARKER)
                && source.contains(RANDOM_FIT_VERSION_MARKER)
                && source.contains("\"data-gomod-fit-nametag\":\"v2\"")
                && source.contains("gomodNameTag:i.jsx(GomodFitNametag,{fit:A,state:gomodFitColors})")
                && source.contains("gomodNameTag:F?i.jsx(GomodFitNametag,{fit:F,state:gomodFitColors}):null")
                && source.contains("e.gomodNameTag||i.jsx(Z,")
                && !source.contains("items:GomodFitColorItems(A,gomodFitColors)")
                && !source.contains("GomodFitColorsSaveButton")
                && source.contains("\"data-gomod-fit-sharing\":\"v9\"")
                && source.contains("\"data-gomod-gift-notification\":\"v4\"")
                && source.contains("gomodGiftNotificationBounds")
                && source.contains("GomodGiftPreview=e=>i.jsx(Ga0,{children:")
                && source.contains("gomodGiftDecision")
                && source.contains("const gomodFullAccess=GomodUseFullAccess();")
                && source.contains("g.useEffect(()=>{if(!gomodFullAccess)gomodSetShareFit(null)},[gomodFullAccess])")
                && source.contains("gomodFullAccess&&i.jsx(GomodFitShareButton,{fit:F,state:gomodFitColors,fullAccess:gomodFullAccess")
                && source.contains("gomodFullAccess&&gomodShareFit&&GomodFitOwner(gomodShareFit.account)")
                && source.contains("canShare:gomodFullAccess&&!!(gomodFitColors.sharing&&gomodFitColors.sharing.available)")
                && source.contains("gomodShare&&gomodCanShare&&i.jsx(S0,{label:\"Share Fit\"")
                && source.contains("id:\"GOMOD_FIT_\"+(F?F.id:\"none\")")
                && !source.contains("topLeft:i.jsx(GomodFitSenderBadge,{fit:A,state:gomodFitColors})")
                && source.contains(FRESH_FIT_SELECTION);
    }

    private static boolean isGoparkySettingsPatched(String source) {
        return source.contains(GOPARKY_SETTINGS_COMPONENT_MARKER)
                && source.contains(GOPARKY_SETTINGS_SCROLL_MARKER)
                && source.contains(GOPARKY_SETTINGS_ROUTE_MARKER)
                && source.contains("randomPlayerInteractions")
                && source.contains("goparky-native-walk-v2")
                && source.contains("i.jsx(zb,{title:a.name")
                && source.contains("i.jsx(Nb,{title:\"Random player interactions\"")
                && source.contains("Save goparky settings")
                && source.contains("gomodGetGoparkyTauntSettings")
                && source.contains("gomodSaveGoparkyTauntSettings");
    }

    private static boolean isCosmeticOwnershipBadgesPatched(String source) {
        return source.contains(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_STATUS_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_VERSION_MARKER)
                && source.contains(COSMETIC_OWNERSHIP_BADGE_LAYER_MARKER)
                && source.contains(EMOTE_OWNERSHIP_BADGE_MARKER)
                && !source.contains(LEGACY_EMOTE_OWNERSHIP_BADGE_MARKER);
    }

    /** Keep Locker tiles narrow enough for the third column when its vertical scrollbar appears. */
    private static String patchCosmeticGridLayout(String source) {
        String originalGrid = "name:\"COSMETIC_TAB\",minItemWidth:wr";
        if (!source.contains(COSMETIC_GRID_WIDTH_MARKER)) {
            source = replaceUnique(source, originalGrid, COSMETIC_GRID_WIDTH_MARKER,
                    "cosmetic Locker grid width");
        } else {
            requireUnique(source, COSMETIC_GRID_WIDTH_MARKER, "cosmetic Locker grid width");
        }
        String originalTile = "md:{height:\"225px\",minWidth:\"185px\"},lg:{height:\"280px\",minWidth:\"260px\",marginLeft:\"2px\"}";
        String mappedTile = "md:{height:\"225px\",minWidth:\"175px\"},lg:{height:\"280px\",minWidth:\"260px\",marginLeft:\"2px\"}";
        if (!source.contains(mappedTile)) {
            source = replaceUnique(source, originalTile, mappedTile,
                    "cosmetic Locker tile minimum width");
        } else {
            requireUnique(source, mappedTile, "cosmetic Locker tile minimum width");
        }
        return source;
    }

    /** Adds source badges to each Locker cosmetic tile from account-owned, FullAccess and local QA state. */
    private static String patchCosmeticOwnershipBadges(String source, String fragment) {
        String badgeStatus = cosmeticOwnershipBadgeStatusMarker();
        String badgeTopRight = COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER;
        String helper = cosmeticOwnershipBadgeHelpers(fragment);
        if (source.contains(badgeStatus) || source.contains(badgeTopRight)
                || source.contains(LEGACY_COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER)) {
            requireUnique(source, badgeStatus, "cosmetic ownership source mapping");
            if (!source.contains(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER)
                    || !source.contains(COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER)) {
                throw new UnsupportedBundleException("cosmetic ownership badge helpers are incomplete");
            }
            if (source.contains(badgeTopRight)) {
                requireUnique(source, badgeTopRight, "cosmetic ownership preview badge");
            } else {
                source = replaceUnique(source, LEGACY_COSMETIC_OWNERSHIP_BADGE_TOP_RIGHT_MARKER,
                        badgeTopRight, "cosmetic ownership preview badge refresh");
            }
            if (!source.contains(COSMETIC_OWNERSHIP_BADGE_VERSION_MARKER)) {
                int helperStart = source.indexOf(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER);
                int helperEnd = source.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER, helperStart);
                if (helperStart < 0 || helperEnd <= helperStart) {
                    throw new UnsupportedBundleException("cosmetic ownership badge helpers cannot be refreshed");
                }
                String oldHelper = source.substring(helperStart, helperEnd);
                requireUnique(source, oldHelper, "cosmetic ownership badge helper refresh");
                source = replaceUnique(source, oldHelper, helper + ",",
                        "cosmetic ownership badge helper refresh");
            }
            source = patchCosmeticOwnershipBadgeLayer(source);
            source = patchEmoteOwnershipBadge(source);
            return source;
        }

        String helperMarker = "Id0=({cosmetic:t,index:r";
        if (!source.contains(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER)) {
            source = replaceUnique(source, helperMarker, helper + "," + helperMarker,
                    "cosmetic ownership badge helper insertion");
        } else if (!source.contains(COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER)) {
            throw new UnsupportedBundleException("partial cosmetic ownership badge helpers detected");
        }

        String originalList = "e2=u1=>{const A1=new Set(u1.discordCosmeticIds??[])";
        String mappedList = "e2=u1=>{const gomodOwnership=GomodCosmeticOwnershipBadgeData(),"
                + "A1=new Set(u1.discordCosmeticIds??[])";
        source = replaceUnique(source, originalList, mappedList,
                "cosmetic ownership source snapshot");
        source = replaceUnique(source, "s2({...H2,owned:!0},A1,P1)",
                "s2({...H2,owned:!0," + badgeStatus + "},A1,P1)",
                "cosmetic ownership status mapping");
        source = replaceUnique(source, "topLeft:M,topRight:F,children:t.display==null",
                "topLeft:M," + badgeTopRight + ",children:t.display==null",
                "cosmetic ownership preview badge placement");
        source = patchCosmeticOwnershipBadgeLayer(source);
        source = patchEmoteOwnershipBadge(source);
        if (!isCosmeticOwnershipBadgesPatched(source)) {
            throw new UnsupportedBundleException("cosmetic ownership badges did not validate");
        }
        return source;
    }

    /** Emote tiles come from a broader catalogue; ownership is resolved against official account IDs at render time. */
    private static String patchEmoteOwnershipBadge(String source) {
        if (source.contains(EMOTE_OWNERSHIP_BADGE_MARKER)) {
            requireUnique(source, EMOTE_OWNERSHIP_BADGE_MARKER, "Emote source badge");
            return source;
        }
        if (source.contains(LEGACY_EMOTE_OWNERSHIP_BADGE_MARKER)) {
            requireUnique(source, LEGACY_EMOTE_OWNERSHIP_BADGE_MARKER, "legacy Emote source badge");
            return replaceUnique(source, LEGACY_EMOTE_OWNERSHIP_BADGE_MARKER,
                    EMOTE_OWNERSHIP_BADGE_MARKER, "Emote source badge ownership fix");
        }
        String original = "onMouseEnter:()=>{O.model.playEmote(`EMOTE_MANNEQUIN_${e.emote.id}`)";
        return replaceUnique(source, original,
                EMOTE_OWNERSHIP_BADGE_MARKER + "," + original,
                "Emote source badge placement");
    }

    /** Keep the top-right badge overlay above the absolute-positioned 3D cosmetic preview. */
    private static String patchCosmeticOwnershipBadgeLayer(String source) {
        String original = "nk2=_(\"div\",{display:\"flex\",flexDirection:\"column\",alignItems:\"flex-start\","
                + "gap:\"4px\",position:\"absolute\",right:0,top:0})";
        if (!source.contains(COSMETIC_OWNERSHIP_BADGE_LAYER_MARKER)) {
            source = replaceUnique(source, original, COSMETIC_OWNERSHIP_BADGE_LAYER_MARKER,
                    "cosmetic source badge overlay stacking");
        } else {
            requireUnique(source, COSMETIC_OWNERSHIP_BADGE_LAYER_MARKER,
                    "cosmetic source badge overlay stacking");
        }
        return source;
    }

    private static String cosmeticOwnershipBadgeStatusMarker() {
        return "gomodOwnershipBadge:gomodCosmeticOwnershipBadgeFor(H2.id,gomodOwnership)";
    }

    private static String cosmeticOwnershipBadgeHelpers(String fragment) {
        int start = fragment.indexOf(COSMETIC_OWNERSHIP_BADGE_DATA_MARKER);
        int end = fragment.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER, start);
        if (start < 0 || end <= start) {
            throw new UnsupportedBundleException("packaged cosmetic ownership badge helpers are missing");
        }
        String helper = fragment.substring(start, end).trim();
        while (helper.endsWith(",")) helper = helper.substring(0, helper.length() - 1).trim();
        if (!helper.contains(COSMETIC_OWNERSHIP_BADGE_COMPONENT_MARKER)) {
            throw new UnsupportedBundleException("packaged cosmetic ownership badge component is missing");
        }
        return helper;
    }

    /** Public builds add the local-only cosmetic source badges and Gomod's Goparky settings route. */
    private static String transformPublicBundleText(String source, String fragment) {
        if (source == null || fragment == null) {
            throw new UnsupportedBundleException("input is missing");
        }
        String result = patchGoparkyCosmeticSettings(source, fragment);
        result = patchCosmeticGridLayout(result);
        result = patchCosmeticOwnershipBadges(result, fragment);
        result = patchStudio(result);
        if (!isGoparkySettingsPatched(result)) {
            throw new UnsupportedBundleException("Goparky settings markers did not validate");
        }
        if (!isCosmeticOwnershipBadgesPatched(result)) {
            throw new UnsupportedBundleException("Cosmetic ownership badges did not validate");
        }
        return result;
    }

    /**
     * Pure text transform so the supported bundle shapes can be smoke-tested
     * without touching a user's Lunar cache.
     */
    static String transformBundleText(String source, String fragment) {
        if (source == null || fragment == null) {
            throw new UnsupportedBundleException("input is missing");
        }
        requireUnique(source, "Gd0=e=>", "Locker color-card renderer");
        requireUnique(source, "Yd0=()=>{", "Lunar+ Locker panel");
        requireUnique(source, "Je=e=>", "Lunar nametag renderer");
        if (!source.contains("i0.LUNARPLUS")) {
            throw new UnsupportedBundleException("Lunar+ tab signature was not found");
        }

        String result = ensureLogoColorTab(migrateGoparkyOnlyBundle(source), fragment);
        result = patchGoparkyCosmeticSettings(result, fragment);
        result = patchLogoColorPicker(result, fragment);
        result = patchDefaultWhitePreset(result);
        result = patchFullAccessVisibility(result);
        result = patchColorCard(result);
        result = patchAccountTileNoPlusColor(result);
        result = patchAccountTileConnectionLogo(result);
        result = patchSelectionPreview(result);
        result = patchLockerSearchFocus(result);
        result = patchOverlayTextFocusGuard(result);
        result = removeLocalQaCosmeticThumbnailTint(result);
        result = patchRandomFitButton(result);
        result = patchCosmeticAppearanceSettings(result);
        result = patchEquippedFirstDataSync(result);
        result = patchCosmeticGridLayout(result);
        result = patchCosmeticOwnershipBadges(result, fragment);

        result = patchStudio(result);

        if (!isFullyPatched(result) || !isCosmeticOwnershipBadgesPatched(result)) {
            throw new UnsupportedBundleException("post-patch markers did not all validate");
        }
        return result;
    }

    private static String patchEquippedFirstDataSync(String source) {
        int installed = count(source, EQUIPPED_SORT_DATA_SYNC_MARKER);
        if (installed == 2) return source;
        if (installed != 0) {
            throw new UnsupportedBundleException("Equipped First synchronization was only partially applied");
        }
        String cosmeticRefresh = "x.current&&(U.current=Y(x.current,s.current),t1(r1(U.current)))";
        source = replaceUnique(source, cosmeticRefresh,
                "x.current&&(U.current=Y(x.current,s.current),t1(r1(U.current)),c2(q.current))",
                "Equipped First cosmetic refresh");

        String outfitRefresh = "x.current=A1,U.current=Y(A1,s.current);const{hiddenTypes:P1,"
                + "unsupportedCosmeticTypes:o2,unsupportedItemMaterials:y2}=G1(l.current);"
                + "$1(P1),Y1(o2),n2(y2),t1(r1(U.current))";
        source = replaceUnique(source, outfitRefresh,
                outfitRefresh + ",c2(q.current)", "Equipped First Outfit refresh");
        if (count(source, EQUIPPED_SORT_DATA_SYNC_MARKER) != 2) {
            throw new UnsupportedBundleException("Equipped First synchronization did not validate");
        }
        return source;
    }

    private static String patchStudio(String source) {
        String marker="\"data-gomod-studio\":\"v11\"";
        String helper;
        try(InputStream input=LunarLockerUiBundlePatcher.class.getResourceAsStream("/gomod/lunar-studio-ui-fragment.js");ByteArrayOutputStream output=new ByteArrayOutputStream()) {
            if(input==null)throw new IOException("missing studio UI");
            byte[] buffer=new byte[8192];int read;while((read=input.read(buffer))>=0)output.write(buffer,0,read);
            helper=new String(output.toByteArray(),StandardCharsets.UTF_8).trim();
            if(!helper.startsWith("GomodUseFullAccess=")||!helper.contains(marker))throw new IOException("invalid studio UI");
        }catch(IOException error){throw new UnsupportedBundleException("Badge / Emote studio resource unavailable");}
        if(source.contains(marker)) {
            requireUnique(source,marker,"Badge / Emote studio");
            requireUnique(source,"kind:\"badge\",item:k.badge","Badge settings route");
            requireUnique(source,"kind:\"speed\",item:e.emote","Emote settings route");
            requireUnique(source,"i.jsx(GomodStudioLauncher,{kind:\"custom\"})","Emote creator route");
            source=normalizeStudioHelpers(source, helper);
            source=patchBadgeImageSource(source);
            source=patchBadgeOwnershipSourceIcon(source);
            return patchEditedItemContextMenus(source);
        }
        if(source.contains("GomodStudioOwner=")) {
            if(!source.contains("\"data-gomod-studio\":\"v1\"")&&!source.contains("\"data-gomod-studio\":\"v2\"")&&!source.contains("\"data-gomod-studio\":\"v3\"")&&!source.contains("\"data-gomod-studio\":\"v4\"")&&!source.contains("\"data-gomod-studio\":\"v5\"")&&!source.contains("\"data-gomod-studio\":\"v6\"")&&!source.contains("\"data-gomod-studio\":\"v7\"")&&!source.contains("\"data-gomod-studio\":\"v8\"")&&!source.contains("\"data-gomod-studio\":\"v9\"")&&!source.contains("\"data-gomod-studio\":\"v10\""))throw new UnsupportedBundleException("Unknown Badge / Emote studio version");
            source=normalizeStudioHelpers(source, helper);
            source=patchBadgeImageSource(source);
            source=patchBadgeOwnershipSourceIcon(source);
            return patchEditedItemContextMenus(source);
        }
        source=replaceUnique(source,"Gc0=e=>",helper+",\nGc0=e=>","Studio helpers");
        int badge=source.indexOf("p41=e=>"),badgeEnd=source.indexOf(",og=",badge);
        if(badge<0||badgeEnd<0)throw new UnsupportedBundleException("Badge image renderer unavailable");
        String image=source.substring(badge,badgeEnd);
        image=replaceUnique(image,"p41=e=>i.jsx(\"div\",{style:{","p41=e=>{const gomodBadgeFilter=GomodUseBadgeFilter(e.ownedBadge.badge.id);return i.jsx(\"div\",{style:{filter:gomodBadgeFilter,","Badge image colors")+"}";
        source=source.substring(0,badge)+image+source.substring(badgeEnd);
        source=patchBadgeImageSource(source);
        source=replaceUnique(source,"name:k.badge.name,equipped:","name:k.badge.name,"+BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER+",equipped:","Badge Settings and edited marker");
        int tile=source.indexOf("Ud0=W1.memo"),tileEnd=source.indexOf(",Kd0=e=>",tile);
        if(tile<0||tileEnd<0)throw new UnsupportedBundleException("Emote card unavailable");
        String emote=source.substring(tile,tileEnd);
        emote=replaceUnique(emote,"topLeft:i.jsxs(i.Fragment,{children:[","topLeft:i.jsxs(i.Fragment,{children:[i.jsx(GomodStudioLauncher,{kind:\"speed\",item:e.emote}),","Emote Settings button");
        source=source.substring(0,tile)+emote+source.substring(tileEnd);
        source=replaceUnique(source,"i.jsx(Vl,{sortId:\"locker-emotes\"","i.jsx(GomodStudioLauncher,{kind:\"custom\"}),i.jsx(Vl,{sortId:\"locker-emotes\"","Emote creator button");
        return patchEditedItemContextMenus(source);
    }

    private static String patchBadgeImageSource(String source) {
        String mapped = "i.jsx(GomodCustomBadgeImage,{badgeId:e.ownedBadge.badge.id,resource:e.ownedBadge.badge.resource";
        if (source.contains(mapped)) {
            requireUnique(source, mapped, "custom badge Locker image source");
            return source;
        }
        return replaceUnique(source, "i.jsx(cd0,{src:e.ownedBadge.badge.resource,",
                "i.jsx(GomodCustomBadgeImage,{badgeId:e.ownedBadge.badge.id,resource:e.ownedBadge.badge.resource,",
                "custom badge Locker image source");
    }

    private static String patchBadgeOwnershipSourceIcon(String source) {
        if (source.contains(BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER)) {
            requireUnique(source, BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER,
                    "badge edited source marker");
            return source;
        }
        if (source.contains(LEGACY_BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER)) {
            requireUnique(source, LEGACY_BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER,
                    "legacy badge edited source marker");
            return replaceUnique(source, LEGACY_BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER,
                    BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER,
                    "badge edited source spacing");
        }
        return replaceUnique(source,
                "topRight:i.jsx(GomodStudioLauncher,{kind:\"badge\",item:k.badge})",
                BADGE_OWNERSHIP_BADGE_TOP_RIGHT_MARKER,
                "badge edited source marker");
    }

    private static String patchEditedItemContextMenus(String source) {
        if (!source.contains("GomodResetContextItem=e=>{"))
            throw new UnsupportedBundleException("Edited-item Reset menu helper is unavailable");
        String resetMarker="kind:\"cosmetic\",id:d.id,visible:l.visible";
        if(!source.contains(resetMarker)) {
            String settings=",!u&&i.jsx(S0,{label:\"Settings\",icon:i.jsx(Fd0,{}),onClick:()=>{r(d),l.visible&&O.setAllowKeybinds(!0)}})";
            source=replaceUnique(source,settings,",!u&&i.jsx(GomodResetContextItem,{kind:\"cosmetic\",id:d.id,visible:l.visible})"+settings,"Cosmetic Reset menu item");
        }
        String badgeMarker="kind:\"badge\",id:k.badge.id,item:k.badge";
        if(!source.contains(badgeMarker)) {
            String start="return k?i.jsx(Fr,{className:w==0?\"badgesTour2\":void 0,name:k.badge.name,";
            String wrapped="return k?i.jsx(GomodLockerItemContext,{kind:\"badge\",id:k.badge.id,item:k.badge,equipped:(d==null?void 0:d.id)==k.badge.id,onEquip:()=>{(d==null?void 0:d.id)==k.badge.id?O.badges.equip(-1):O.badges.equip(k.badge.id)},children:i.jsx(Fr,{className:w==0?\"badgesTour2\":void 0,name:k.badge.name,";
            source=replaceUnique(source,start,wrapped,"Badge right-click menu");
            String ending="children:i.jsx(p41,{ownedBadge:k,size:\"lg\"})},`BADGE_${w}`):null";
            String wrappedEnding="children:i.jsx(p41,{ownedBadge:k,size:\"lg\"})})},`BADGE_${w}`):null";
            source=replaceUnique(source,ending,wrappedEnding,"Badge right-click menu boundary");
        }
        String speedMarker="kind:\"speed\",id:e.emote.id,item:e.emote";
        if(!source.contains(speedMarker)) {
            int tile=source.indexOf("Ud0=W1.memo"),tileEnd=tile<0?-1:source.indexOf(",Kd0=e=>",tile);
            if(tile<0||tileEnd<0)throw new UnsupportedBundleException("Emote right-click menu card unavailable");
            String emote=source.substring(tile,tileEnd);
            String card="children:i.jsx(Fr,{name:e.emote.name,className:e.className";
            String wrappedCard="children:i.jsx(GomodLockerItemContext,{kind:\"speed\",id:e.emote.id,item:e.emote,onEquip:o,children:i.jsx(Fr,{name:e.emote.name,className:e.className";
            emote=replaceUnique(emote,card,wrappedCard,"Emote right-click menu");
            String ending="cosmetics:[]},`EMOTE_MANNEQUIN_${e.emote.id}`)})})})";
            String wrappedEnding="cosmetics:[]},`EMOTE_MANNEQUIN_${e.emote.id}`)})})})})";
            emote=replaceUnique(emote,ending,wrappedEnding,"Emote right-click menu boundary");
            source=source.substring(0,tile)+emote+source.substring(tileEnd);
        }
        return source;
    }

    private static String normalizeStudioHelpers(String source, String helper) {
        int owner = source.indexOf("GomodStudioOwner=");
        int end = owner < 0 ? -1 : source.indexOf("Gc0=e=>", owner);
        if (owner < 0 || end <= owner || end - owner > 65536) {
            throw new UnsupportedBundleException("Studio helper boundaries changed");
        }
        String marker = "GomodUseFullAccess=()=>{";
        int start = source.indexOf(marker);
        if (start < 0 || start > owner) start = owner;
        String replacement = helper.trim();
        while (replacement.endsWith(",")) {
            replacement = replacement.substring(0, replacement.length() - 1).trim();
        }
        String existing = source.substring(start, end);
        String normalized = replacement + ",\n";
        if (existing.equals(normalized)) return source;
        return source.substring(0, start) + normalized + source.substring(end);
    }

    private static String patchCosmeticAppearanceSettings(String source) {
        String marker = "\"data-gomod-cosmetic-appearance\":\"v11\"";
        String oldNamedRoute = "title:\"Appearance\",children:i.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:t.id,cosmeticName:t.name})";
        String oldRoute = "title:\"Appearance\",children:i.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:t.id})";
        String oldTypedRoute = "title:\"Appearance\",children:i.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:t.id,cosmeticName:t.name,cosmeticType:t.type})";
        String route = "title:\"Appearance\",children:i.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:t.id,cosmeticName:t.name,cosmeticType:t.type,fitId:fitId})";
        if (source.contains(marker)) {
            requireUnique(source, marker, "Cosmetic Appearance controls");
            requireUnique(source, route, "Cosmetic Appearance settings route");
            requireUnique(source, "Od0=({cosmetics:e,cosmetic:t,fitId,onReturn:r})=>{", "selected Fit appearance context");
            requireUnique(source, "cosmetic:u1,fitId:p&&p.id,onReturn:", "selected Fit settings request");
            return patchCosmeticSettingsScroll(patchCosmeticAppearanceSliderFormatting(source));
        }
        String fragment;
        try (InputStream input = LunarLockerUiBundlePatcher.class.getResourceAsStream(
                "/gomod/lunar-cosmetic-appearance-ui-fragment.js")) {
            if (input == null) throw new IOException("missing appearance UI fragment");
            ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
            fragment = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!fragment.startsWith("GomodCosmeticAppearanceSettings=") || !fragment.contains(marker))
                throw new IOException("invalid appearance UI fragment");
        } catch (IOException error) { throw new UnsupportedBundleException("Cosmetic Appearance resource is unavailable"); }
        source = patchCosmeticAppearanceSliderFormatting(source);
        if (source.contains("GomodCosmeticAppearanceSettings=")) {
            requireUnique(source,"GomodCosmeticAppearanceSettings=","legacy Cosmetic Appearance component");
            String priorRoute=source.contains(route)?route:source.contains(oldTypedRoute)?oldTypedRoute:source.contains(oldNamedRoute)?oldNamedRoute:oldRoute;
            requireUnique(source,priorRoute,"Cosmetic Appearance settings route");
            if(!source.contains("\"data-gomod-cosmetic-appearance\":\"v1\"")
                    &&!source.contains("\"data-gomod-cosmetic-appearance\":\"v2\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v3\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v4\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v5\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v6\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v7\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v8\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v9\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v10\"")&&!source.contains("\"data-gomod-cosmetic-appearance\":\"v11\""))throw new UnsupportedBundleException("Unknown Cosmetic Appearance version");
            int componentStart=source.indexOf("GomodCosmeticAppearanceSettings="),componentEnd=source.indexOf(",Od0=({cosmetics:e,cosmetic:t,onReturn:r})=>{",componentStart);
            if(componentEnd<0)componentEnd=source.indexOf(",Od0=({cosmetics:e,cosmetic:t,fitId,onReturn:r})=>{",componentStart);
            if(componentEnd<=componentStart||componentEnd-componentStart>16384)throw new UnsupportedBundleException("Cosmetic Appearance component boundaries changed");
            source=source.substring(0,componentStart)+fragment+source.substring(componentEnd);
            source=ensureCosmeticFitProps(source);
            source=source.contains(route)?source:replaceUnique(source,priorRoute,route,"Cosmetic Appearance settings route");
            return patchCosmeticSettingsScroll(source);
        }
        source = replaceUnique(source, ",Od0=({cosmetics:e,cosmetic:t,onReturn:r})=>{",
                "," + fragment + ",Od0=({cosmetics:e,cosmetic:t,onReturn:r})=>{", "Cosmetic Appearance component");
        source=ensureCosmeticFitProps(source);
        source=replaceUnique(source, "i.jsx(aZ,{title:\"Cosmetic Settings\",children:",
                "i.jsx(aZ,{title:\"Appearance\",children:i.jsx(GomodCosmeticAppearanceSettings,{cosmeticId:t.id,cosmeticName:t.name,cosmeticType:t.type,fitId:fitId})}),"
                        + "(n.length>0||Number(t.id)===2000418004)&&i.jsx(aZ,{title:\"Cosmetic Settings\",children:",
                "Cosmetic Appearance settings route");
        return patchCosmeticSettingsScroll(source);
    }

    private static String ensureCosmeticFitProps(String source) {
        String signature="Od0=({cosmetics:e,cosmetic:t,onReturn:r})=>{";
        String targetedSignature="Od0=({cosmetics:e,cosmetic:t,fitId,onReturn:r})=>{";
        if(!source.contains(targetedSignature))source=replaceUnique(source,signature,targetedSignature,"selected Fit appearance context");
        String selection="cosmetic:u1,onReturn:";
        String targetedSelection="cosmetic:u1,fitId:p&&p.id,onReturn:";
        if(!source.contains(targetedSelection))source=replaceUnique(source,selection,targetedSelection,"selected Fit settings request");
        return source;
    }

    /** Make the left Cosmetic Settings column scroll so native options stay reachable. */
    private static String patchCosmeticSettingsScroll(String source) {
        String prefix = "P5=_(\"div\",{display:\"flex\",minWidth:\"275px\",flex:\"1 1 auto\","
                + "alignItems:\"flex-start\",alignSelf:\"stretch\",flexDirection:\"column\","
                + "gap:\"var(--spacings-radii-3)\"";
        if (source.contains(COSMETIC_SETTINGS_SCROLL_STYLE)) {
            requireUnique(source, COSMETIC_SETTINGS_SCROLL_STYLE, "scrollable Cosmetic Settings column");
            return source;
        }
        return replaceUnique(source, prefix, COSMETIC_SETTINGS_SCROLL_STYLE.substring(0,
                        COSMETIC_SETTINGS_SCROLL_STYLE.length() - 3),
                "scrollable Cosmetic Settings column");
    }

    /** Allow an explicit slider formatter to control both its tooltip and editable value. */
    private static String patchCosmeticAppearanceSliderFormatting(String source) {
        String slider = "zb=W1.forwardRef((e,t)=>{";
        String oldLabelFormatter = "n=g.useRef(S=>S.toFixed(2))";
        String labelFormatter = "n=g.useRef(e.format||(S=>S.toFixed(2)))";
        String oldFormatter = "l=S=>S.toFixed(2)";
        String formatter = "l=S=>e.format?e.format(S):S.toFixed(2)";
        requireUnique(source, slider, "native Locker numeric slider");
        if (source.contains(labelFormatter)) {
            requireUnique(source, labelFormatter, "native Locker integer-capable label formatter");
        } else {
            source = replaceUnique(source, oldLabelFormatter, labelFormatter,
                    "native Locker slider label formatter");
        }
        if (source.contains(formatter)) {
            requireUnique(source, formatter, "native Locker integer-capable slider formatter");
            return source;
        }
        return replaceUnique(source, oldFormatter, formatter, "native Locker slider formatter");
    }

    /** Use the existing cosmetic callback so native compatibility and Fit persistence remain authoritative. */
    private static String patchRandomFitButton(String source) {
        requireUnique(source,"O4=e=>i.jsx(Ny2,","native sender head renderer");
        String startMarker = "eu0=()=>{";
        requireUnique(source, startMarker, "Fits panel");
        int start = source.indexOf(startMarker);
        int end = source.indexOf(",tu0=()=>", start);
        if (end <= start || end - start > 32768) {
            throw new UnsupportedBundleException("Fits panel boundaries changed");
        }
        String panel = source.substring(start, end);
        if(!panel.contains("id:\"GOMOD_FIT_\"+(F?F.id:\"none\")")) {
            panel=replaceUnique(panel,"id:\"TEST_PLAYER_DUMMY_FIT\"","id:\"GOMOD_FIT_\"+(F?F.id:\"none\")","selected Fit appearance preview");
            panel=replaceUnique(panel,",\"TEST_PLAYER_DUMMY_FIT\")",",\"GOMOD_FIT_\"+(F?F.id:\"none\"))","selected Fit preview identity");
        }
        if (!panel.contains("const gomodFitColors=GomodUseFitColors();")) {
            panel = replaceUnique(panel, "eu0=()=>{", "eu0=()=>{const gomodFitColors=GomodUseFitColors();", "Fit color state");
            panel = replaceUnique(panel, "[r,s,m,F,h]", "[r,s,m,F,h,gomodFitColors]", "Fit color item refresh");
        }
        if (!panel.contains("const gomodFullAccess=GomodUseFullAccess();")) {
            panel = replaceUnique(panel, "const gomodFitColors=GomodUseFitColors();",
                    "const gomodFitColors=GomodUseFitColors();const gomodFullAccess=GomodUseFullAccess();",
                    "Fit sharing FullAccess state");
        }
        if (!panel.contains(FRESH_FIT_SELECTION)) {
            panel = replaceUnique(panel,
                    "y(A=>A&&R.outfits.find(P=>P.id==A.id)?A:R.outfits.find(P=>P.id==R.defaultOutfitId)||null)",
                    FRESH_FIT_SELECTION, "current Fit preview refresh");
        }
        if (!panel.contains(RANDOM_FIT_BUTTON_MARKER)
                && !panel.contains(FULLACCESS_RANDOM_FIT_BUTTON_MARKER)) {
            String search = "i.jsx(o8,{children:i.jsxs(R5,{children:[i.jsx(ig,{}),"
                    + "i.jsx(O5,{value:s,placeholder:\"Search...\",onChange:R=>{j(R.target.value)}})]})})";
            panel = replaceUnique(panel, "i.jsx(n8,{children:" + search + "})",
                    "i.jsx(n8,{children:i.jsxs(\"div\",{style:{display:\"flex\",alignItems:\"center\","
                            + "gap:\"8px\",width:\"100%\",minWidth:0},children:[i.jsx(\"div\",{"
                            + "style:{flex:1,minWidth:0},children:" + search + "}),"
                            + RANDOM_FIT_BUTTON_MARKER + "]})})",
                    "Fits Random button beside search");
        }
        if (!panel.contains("gomodSetShareFit")) {
            panel = replaceUnique(panel,"const gomodFullAccess=GomodUseFullAccess();",
                    "const gomodFullAccess=GomodUseFullAccess();const [gomodShareFit,gomodSetShareFit]=g.useState(null);","Fit share dialog state");
            panel = replaceUnique(panel,"name:A.name,equipped:","name:A.name,topLeft:i.jsx(GomodFitSenderBadge,{fit:A,state:gomodFitColors}),equipped:","Fit sender attribution");
            panel = replaceUnique(panel,RANDOM_FIT_BUTTON_MARKER,
                    "i.jsx(GomodFitShareButton,{fit:F,state:gomodFitColors,onOpen:()=>gomodSetShareFit({fit:F,account:gomodFitColors.account})}),"+RANDOM_FIT_BUTTON_MARKER,"Fit share action");
            panel = replaceUnique(panel,",E]})",",i.jsx(GomodReceivedFitsNotice,{state:gomodFitColors}),E]})","Received Fits status");
            panel = replaceUnique(panel,"i.jsx(Jd0,",
                    "gomodShareFit&&GomodFitOwner(gomodShareFit.account)===GomodFitOwner(gomodFitColors.account)&&i.jsx(GomodFitShareDialog,{fit:gomodShareFit.fit,state:gomodFitColors,onClose:()=>gomodSetShareFit(null)},gomodShareFit.account+gomodShareFit.fit.id),i.jsx(Jd0,","Fit share dialog");
        }
        if (!panel.contains("g.useEffect(()=>{if(!gomodFullAccess)gomodSetShareFit(null)},[gomodFullAccess])")) {
            panel = replaceUnique(panel, "const [gomodShareFit,gomodSetShareFit]=g.useState(null);",
                    "const [gomodShareFit,gomodSetShareFit]=g.useState(null);g.useEffect(()=>{if(!gomodFullAccess)gomodSetShareFit(null)},[gomodFullAccess]);",
                    "close Fit share dialog when FullAccess disconnects");
        }
        if (!panel.contains("gomodFullAccess&&i.jsx(GomodFitShareButton,{fit:F,state:gomodFitColors,fullAccess:gomodFullAccess")) {
            panel = replaceUnique(panel,
                    "i.jsx(GomodFitShareButton,{fit:F,state:gomodFitColors,onOpen:()=>gomodSetShareFit({fit:F,account:gomodFitColors.account})}),",
                    "gomodFullAccess&&i.jsx(GomodFitShareButton,{fit:F,state:gomodFitColors,fullAccess:gomodFullAccess,onOpen:()=>gomodSetShareFit({fit:F,account:gomodFitColors.account})}),",
                    "hide Fit share toolbar button without FullAccess");
        }
        if (!panel.contains("gomodFullAccess&&gomodShareFit&&GomodFitOwner(gomodShareFit.account)")) {
            panel = replaceUnique(panel,
                    "gomodShareFit&&GomodFitOwner(gomodShareFit.account)===GomodFitOwner(gomodFitColors.account)&&i.jsx(GomodFitShareDialog,",
                    "gomodFullAccess&&gomodShareFit&&GomodFitOwner(gomodShareFit.account)===GomodFitOwner(gomodFitColors.account)&&i.jsx(GomodFitShareDialog,",
                    "hide Fit share dialog without FullAccess");
        }
        if (panel.contains("items:GomodFitColorItems(A,gomodFitColors)")) {
            panel = replaceUnique(panel,"items:GomodFitColorItems(A,gomodFitColors),renderItem:P=>P.gomodFitColor?i.jsx(GomodFitColorItem,{item:P}):i.jsx(C8,",
                    "items:A.cosmetics,renderItem:P=>i.jsx(C8,","remove legacy Fit color items");
        }
        if (!panel.contains("gomodNameTag:i.jsx(GomodFitNametag,")) {
            panel = replaceUnique(panel,"name:A.name,","name:A.name,gomodNameTag:i.jsx(GomodFitNametag,{fit:A,state:gomodFitColors}),","Fit nametag title");
            panel = replaceUnique(panel,"i.jsx(Ha,{showModalButton:!1,outfit:F})",
                    "i.jsx(Ha,{showModalButton:!1,outfit:F,gomodNameTag:F?i.jsx(GomodFitNametag,{fit:F,state:gomodFitColors}):null})","selected Fit nametag title");
        }
        if (!panel.contains("GomodFitInboxViewed(gomodFitColors)")) {
            panel = replaceUnique(panel,"const gomodFitColors=GomodUseFitColors();","const gomodFitColors=GomodUseFitColors();GomodFitInboxViewed(gomodFitColors);","Fits inbox viewed state");
            panel = replaceUnique(panel,"i.jsx(Qd0,{outfit:A,","i.jsx(Qd0,{outfit:A,onShare:P=>gomodSetShareFit({fit:P,account:gomodFitColors.account}),canShare:!!(gomodFitColors.sharing&&gomodFitColors.sharing.available),","Fit context share action");
        }
        if (!panel.contains("canShare:gomodFullAccess&&!!(gomodFitColors.sharing&&gomodFitColors.sharing.available)")) {
            panel = replaceUnique(panel,
                    "canShare:!!(gomodFitColors.sharing&&gomodFitColors.sharing.available),",
                    "canShare:gomodFullAccess&&!!(gomodFitColors.sharing&&gomodFitColors.sharing.available),",
                    "hide Fit context share action without FullAccess");
        }
        if (!panel.contains(FULLACCESS_RANDOM_FIT_BUTTON_MARKER)) {
            panel = replaceUnique(panel, RANDOM_FIT_BUTTON_MARKER,
                    FULLACCESS_RANDOM_FIT_BUTTON_MARKER,
                    "hide Random Fit without connected FullAccess");
        }
        if (panel.contains("i.jsx(GomodFitColorsSaveButton,")) {
            panel = replaceUnique(panel,"i.jsx(GomodFitColorsSaveButton,{fit:F,state:gomodFitColors},F.id),","","remove manual Fit color save action");
        }
        panel=panel.replace("topLeft:i.jsx(GomodFitSenderBadge,{fit:A,state:gomodFitColors}),", "");
        source = source.substring(0, start) + panel + source.substring(end);
        source = patchFitNametagTitles(source);
        source = patchFitInvitations(source);
        String fragment;
        try (InputStream input = LunarLockerUiBundlePatcher.class.getResourceAsStream(RANDOM_FIT_RESOURCE);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("missing Random Fit resource");
            byte[] block = new byte[4096];
            int read;
            while ((read = input.read(block)) >= 0) output.write(block, 0, read);
            fragment = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!fragment.contains(RANDOM_FIT_COMPONENT_MARKER)
                    || !fragment.contains(RANDOM_FIT_PLAN_MARKER)
                    || !fragment.contains(RANDOM_FIT_VERSION_MARKER)
                    || !fragment.contains("GomodApplyRandomFit=")) {
                throw new IOException("incomplete Random Fit resource");
            }
        } catch (IOException error) {
            throw new UnsupportedBundleException("Random Fit UI resource is unavailable");
        }
        try (InputStream input = LunarLockerUiBundlePatcher.class.getResourceAsStream("/gomod/lunar-fit-sharing-ui-fragment.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("missing Fit sharing resource");
            byte[] block = new byte[4096]; int read;
            while ((read = input.read(block)) >= 0) output.write(block,0,read);
            String sharing = new String(output.toByteArray(),StandardCharsets.UTF_8).trim();
            if (!sharing.contains("\"data-gomod-fit-sharing\":\"v9\"")
                    || !sharing.contains("GomodFitSenderBadge=e=>")
                    || !sharing.contains("GomodFitShareButton=e=>e.fullAccess?")) throw new IOException("incomplete Fit sharing resource");
            fragment += ",\n" + sharing;
        } catch (IOException error) { throw new UnsupportedBundleException("Fit sharing UI resource is unavailable"); }
        if (source.contains(RANDOM_FIT_COMPONENT_MARKER)) {
            requireUnique(source, RANDOM_FIT_COMPONENT_MARKER, "Random Fit component");
            requireUnique(source, RANDOM_FIT_PLAN_MARKER, "Random Fit helper block");
            int helperStart = source.indexOf(RANDOM_FIT_PLAN_MARKER);
            int helperEnd = source.indexOf(startMarker, helperStart);
            if (helperEnd <= helperStart || helperEnd - helperStart > 32768) {
                throw new UnsupportedBundleException("cached Random Fit helper boundaries changed");
            }
            String cached = source.substring(helperStart, helperEnd).trim();
            if (!cached.endsWith(",") || !cached.contains("GomodApplyRandomFit=")
                    || !cached.contains(RANDOM_FIT_COMPONENT_MARKER)) {
                throw new UnsupportedBundleException("cached Random Fit helper block was not recognized");
            }
            return source.substring(0, helperStart) + fragment + ",\n" + source.substring(helperEnd);
        }
        return replaceUnique(source, startMarker, fragment + ",\n" + startMarker,
                "Random Fit component insertion");
    }

    private static String patchFitInvitations(String source) {
        String start="Qd0=({outfit:e,children:t,onSettings:r,onApply:o,onDelete:n,canDelete:s";
        requireUnique(source,start,"Fit context menu");
        int a=source.indexOf(start),b=source.indexOf(",Jd0=",a);
        if(b<0||b-a>8192)throw new UnsupportedBundleException("Fit context menu boundaries changed");
        String menu=source.substring(a,b);
        if(!menu.contains("onShare:gomodShare")) {
            menu=replaceUnique(menu,"canDelete:s})","canDelete:s,onShare:gomodShare,canShare:gomodCanShare})","Fit context share callback");
        }
        String hiddenShareItem="gomodShare&&i.jsx(S0,{label:\"Share Fit\",onClick:()=>{gomodCanShare&&gomodShare(l.cachedOutfit)}}),";
        String visibleShareItem="gomodShare&&gomodCanShare&&i.jsx(S0,{label:\"Share Fit\",onClick:()=>{gomodCanShare&&gomodShare(l.cachedOutfit)}}),";
        if(!menu.contains(visibleShareItem)) {
            if(menu.contains(hiddenShareItem)) {
                menu=replaceUnique(menu,hiddenShareItem,visibleShareItem,"gate Fit context menu item on FullAccess");
            } else if(!menu.contains("label:\"Share Fit\"")) {
                menu=replaceUnique(menu,"s&&i.jsx(S0,{label:\"Delete\"",visibleShareItem+"s&&i.jsx(S0,{label:\"Delete\"","Fit context Share Fit menu item");
            } else {
                throw new UnsupportedBundleException("Fit context share item changed");
            }
        }
        source=source.substring(0,a)+menu+source.substring(b);
        start="id0=e=>{";requireUnique(source,start,"Fits category");a=source.indexOf(start);b=source.indexOf(",sd0=",a);
        if(b<0||b-a>8192)throw new UnsupportedBundleException("Fits category boundaries changed");
        String category=source.substring(a,b);
        if(!category.contains("gomodFitInbox")) {
            category=replaceUnique(category,start,start+"const gomodFitInbox=GomodUseFitColors();","Fits category inbox subscription");
            category=replaceUnique(category,"name:\"Fits\"","name:i.jsxs(\"span\",{\"data-gomod-fits-category\":\"v2\",style:{display:\"inline-flex\",alignItems:\"center\",gap:\"7px\"},children:[\"Fits\",gomodFitInbox.sharing&&gomodFitInbox.sharing.unread&&i.jsx(ib,{})]})","Fits category new indicator beside label");
        }
        return source.substring(0,a)+category+source.substring(b);
    }

    /** The shared native card/header keep their original title unless a Fits-only title is supplied. */
    private static String patchFitNametagTitles(String source) {
        String cardStart="wH2=W1.memo(e=>{";
        requireUnique(source,cardStart,"native outfit card");
        int start=source.indexOf(cardStart),end=source.indexOf(",yH2=",start);
        if(end<=start||end-start>16384)throw new UnsupportedBundleException("native outfit card boundaries changed");
        String card=source.substring(start,end);
        String title="i.jsx(Z,{color:\"var(--colors-neutrals-space-alpha-12)\",textAlign:\"center\",fontFamily:\"modern\",fontSize:\"16px\",fontStyling:\"normal\",fontWeight:\"700\",lineHeight:\"normal\",children:s(e.name,32)})";
        if(!card.contains("e.gomodNameTag||"))card=replaceUnique(card,title,"e.gomodNameTag||"+title,"native Fit card title override");
        source=source.substring(0,start)+card+source.substring(end);
        String headerStart="Ha=({outfit:e,showModalButton:t=!0";
        requireUnique(source,headerStart,"native outfit preview header");
        start=source.indexOf(headerStart);end=source.indexOf(",cd0=",start);
        if(end<=start||end-start>8192)throw new UnsupportedBundleException("native outfit preview header boundaries changed");
        String header=source.substring(start,end);
        if(!header.contains("gomodNameTag:gomodFitTitle")) {
            header=replaceUnique(header,"Ha=({outfit:e,showModalButton:t=!0})=>{","Ha=({outfit:e,showModalButton:t=!0,gomodNameTag:gomodFitTitle})=>{","Fit preview title parameter");
            title="i.jsx(Z,{color:\"var(--colors-neutrals-space-11)\",fontFamily:\"modern\",fontSize:\"16px\",fontStyling:\"normal\",fontWeight:\"700\",lineHeight:\"normal\",children:e==null?void 0:e.name})";
            header=replaceUnique(header,title,"gomodFitTitle||"+title,"native Fit preview title override");
        }
        return source.substring(0,start)+header+source.substring(end);
    }

    /** Older public builds inserted only this component at the shared insertion point. */
    private static String migrateGoparkyOnlyBundle(String source) {
        if (source.contains("LogoColorPanel=()=>") || !source.contains(GOPARKY_SETTINGS_COMPONENT_MARKER)) {
            return source;
        }
        requireUnique(source, GOPARKY_SETTINGS_COMPONENT_MARKER, "legacy public Goparky component");
        String boundary = ",od0=\"data:image/png;base64";
        requireUnique(source, boundary, "legacy public Goparky boundary");
        int start = source.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER);
        int end = source.indexOf(boundary, start);
        if (start <= 0 || source.charAt(start - 1) != ',' || end <= start || end - start > 32768) {
            throw new UnsupportedBundleException("legacy public Goparky component boundary was invalid");
        }
        String old = source.substring(start, end).trim();
        if (!old.endsWith("}") || !old.contains("Save goparky settings")
                || !old.contains("gomodGetGoparkyTauntSettings") || !old.contains("gomodSaveGoparkyTauntSettings")) {
            throw new UnsupportedBundleException("legacy public Goparky component was not recognized");
        }
        // The current fragment supplies this component once, preserving its existing route.
        return source.substring(0, start) + source.substring(end + 1);
    }

    /** Upgrades both newly inserted and previously cached Logo Color panels. */
    private static String patchLogoColorPicker(String source, String fragment) {
        requireUnique(fragment, LOGO_COLOR_PICKER_COMPONENT_MARKER, "packaged Logo Color picker");
        int helperStart = fragment.indexOf(LOGO_COLOR_PICKER_COMPONENT_MARKER);
        int helperEnd = fragment.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER, helperStart);
        if (helperEnd <= helperStart) {
            throw new UnsupportedBundleException("packaged Logo Color picker boundaries changed");
        }
        String helper = fragment.substring(helperStart, helperEnd).trim();
        if (!helper.endsWith(",")) {
            throw new UnsupportedBundleException("packaged Logo Color picker separator changed");
        }
        helper = helper.substring(0, helper.length() - 1).trim();
        if (source.contains(LOGO_COLOR_PICKER_COMPONENT_MARKER)) {
            requireUnique(source, LOGO_COLOR_PICKER_COMPONENT_MARKER, "Logo Color picker component");
            int existingStart = source.indexOf(LOGO_COLOR_PICKER_COMPONENT_MARKER);
            int existingEnd = source.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER, existingStart);
            if (existingEnd <= existingStart || !source.substring(existingStart, existingEnd).trim().endsWith(",")) {
                throw new UnsupportedBundleException("cached Logo Color picker boundaries changed");
            }
            source = source.substring(0, existingStart) + helper + ",\n" + source.substring(existingEnd);
        } else {
            source = replaceUnique(source, GOPARKY_SETTINGS_COMPONENT_MARKER,
                    helper + ",\n" + GOPARKY_SETTINGS_COMPONENT_MARKER, "Logo Color picker component");
        }

        String panelMarker = "LogoColorPanel=()=>{";
        requireUnique(fragment, panelMarker, "packaged Logo Color panel");
        requireUnique(source, panelMarker, "Logo Color panel");
        int panelStart = source.indexOf(panelMarker);
        int panelEnd = source.indexOf(",od0=\"data:image/png;base64", panelStart);
        if (panelEnd <= panelStart) {
            throw new UnsupportedBundleException("cached Logo Color panel boundary changed");
        }
        String existingPanel = source.substring(panelStart, panelEnd);
        if (!existingPanel.contains("const[n,s]=g.useState(")
                || !existingPanel.contains("gomodSetLunarLogoColor")
                || !existingPanel.contains("Custom logo color in hex")) {
            throw new UnsupportedBundleException("cached Logo Color panel shape changed");
        }
        String panel = fragment.substring(fragment.indexOf(panelMarker)).trim();
        return source.substring(0, panelStart) + panel + source.substring(panelEnd);
    }

    /** Replace the empty settings state for Gomod's local Goparky row with its saved reaction controls. */
    private static String patchGoparkyCosmeticSettings(String source, String fragment) {
        int componentStart = fragment.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER);
        int componentEnd = fragment.indexOf("lunarLogoPrototypeColors=", componentStart);
        if (componentStart < 0 || componentEnd <= componentStart) {
            throw new UnsupportedBundleException("Goparky settings component is missing from the packaged UI");
        }
        String component = fragment.substring(componentStart, componentEnd).trim();
        while (component.endsWith(",")) component = component.substring(0, component.length() - 1).trim();
        if (component.isEmpty()) {
            throw new UnsupportedBundleException("Goparky settings component is empty");
        }
        if (!component.contains(GOPARKY_SETTINGS_SCROLL_MARKER)) {
            throw new UnsupportedBundleException("Goparky settings scroll area is missing");
        }

        if (!source.contains(GOPARKY_SETTINGS_COMPONENT_MARKER)) {
            if (source.contains("lunarLogoPrototypeColors=")) {
                source = replaceUnique(source, "lunarLogoPrototypeColors=",
                        component + ",lunarLogoPrototypeColors=", "Goparky Cosmetic Settings component");
            } else {
                source = replaceUnique(source, ",od0=\"data:image/png;base64",
                        "," + component + ",od0=\"data:image/png;base64",
                        "Goparky Cosmetic Settings component");
            }
        } else {
            requireUnique(source, GOPARKY_SETTINGS_COMPONENT_MARKER, "Goparky Cosmetic Settings component");
            if (!source.contains("randomPlayerInteractions")
                    || !source.contains("goparky-native-walk-v2")
                    || !source.contains(GOPARKY_SETTINGS_SCROLL_MARKER)
                    || !source.contains("i.jsx(zb,{title:a.name")
                    || !source.contains("Save goparky settings")) {
                int existingStart = source.indexOf(GOPARKY_SETTINGS_COMPONENT_MARKER);
                int colorStart = source.indexOf("lunarLogoPrototypeColors=", existingStart);
                if (colorStart < 0) {
                    throw new UnsupportedBundleException("Goparky settings component boundary was not found");
                }
                source = source.substring(0, existingStart) + component + ","
                        + source.substring(colorStart);
            }
        }

        if (source.contains(GOPARKY_SETTINGS_ROUTE_MARKER)) {
            requireUnique(source, GOPARKY_SETTINGS_ROUTE_MARKER, "Goparky Cosmetic Settings route");
            return source;
        }
        return replaceUnique(source,
                "children:n.length==0?i.jsx(Z,{fontFamily:\"modern\",fontSize:\"16px\","
                        + "color:\"var(--colors-neutrals-space-11)\"",
                "children:Number(t.id)===2000418004?i.jsx(GomodGoparkyCosmeticSettings,{}):"
                        + "n.length==0?i.jsx(Z,{fontFamily:\"modern\",fontSize:\"16px\","
                        + "color:\"var(--colors-neutrals-space-11)\"",
                "Goparky Cosmetic Settings screen route");
    }

    /** Remove the retired local QA thumbnail tint and its per-card lookup from cached bundles. */
    private static String removeLocalQaCosmeticThumbnailTint(String source) {
        if (source.contains(LEGACY_LOCAL_QA_COSMETIC_HELPER + ",")) {
            source = replaceUnique(source, LEGACY_LOCAL_QA_COSMETIC_HELPER + ",", "",
                    "retired local QA cosmetic helper");
        }

        if (source.contains(LEGACY_LOCAL_QA_COSMETIC_PROP_MARKER)) {
            source = replaceUnique(source, LEGACY_LOCAL_QA_COSMETIC_PROP_MARKER + ",", "",
                    "retired local QA cosmetic marker prop");
        }
        if (source.contains(LOCAL_QA_COSMETIC_PROP_MARKER)) {
            source = replaceUnique(source, LOCAL_QA_COSMETIC_PROP_MARKER + ",", "",
                    "retired local QA cosmetic identity prop");
        }

        String componentStart = "Fr=W1.memo(e=>{const[t,r]=g.useState(e.isNew),o=";
        String localQaState = "Fr=W1.memo(e=>{const[t,r]=g.useState(e.isNew),"
                + "[gomodLocalQa,setGomodLocalQa]=g.useState(!1);"
                + LOCAL_QA_COSMETIC_EFFECT_BLOCK;
        String legacyLocalQaState = "Fr=W1.memo(e=>{const[t,r]=g.useState(e.isNew),"
                + "[gomodLocalQa,setGomodLocalQa]=g.useState(!1);"
                + LEGACY_LOCAL_QA_COSMETIC_EFFECT_BLOCK;
        if (source.contains(LOCAL_QA_COSMETIC_EFFECT_MARKER)) {
            source = replaceUnique(source, localQaState, componentStart,
                    "retired ID-bound local QA cosmetic lookup");
        } else if (source.contains("gomodIsLocalQaCosmetic(Number(e.gomodCosmeticId))")) {
            source = replaceUnique(source, legacyLocalQaState, componentStart,
                    "retired legacy local QA cosmetic lookup");
        } else if (source.contains("[gomodLocalQa,setGomodLocalQa]")) {
            throw new UnsupportedBundleException("unrecognized local QA cosmetic lookup");
        }

        if (source.contains(LEGACY_LOCAL_QA_COSMETIC_THUMBNAIL_STYLE_MARKER)) {
            source = replaceUnique(source, "," + LEGACY_LOCAL_QA_COSMETIC_THUMBNAIL_STYLE_MARKER,
                    "", "retired legacy local QA thumbnail tint");
        }
        if (source.contains(LOCAL_QA_COSMETIC_THUMBNAIL_STYLE_MARKER)) {
            source = replaceUnique(source, "," + LOCAL_QA_COSMETIC_THUMBNAIL_STYLE_MARKER,
                    "", "retired local QA thumbnail tint");
        }

        String oldMemoDependencies = "e.gifter,t,gomodLocalQa]);";
        if (source.contains(oldMemoDependencies)) {
            source = replaceUnique(source, oldMemoDependencies, "e.gifter,t]);",
                    "retired local QA thumbnail state dependency");
        }

        if (source.contains("rgba(255,145,0,.12)")
                || source.contains("gomodLocalQa")
                || source.contains(LOCAL_QA_COSMETIC_PROP_MARKER)
                || source.contains(LOCAL_QA_COSMETIC_EFFECT_MARKER)) {
            throw new UnsupportedBundleException("local QA thumbnail tint cleanup did not complete");
        }
        return source;
    }

    /** Forward explicit Locker text-field focus events to Gomod's existing movement guard. */
    private static String patchLockerSearchFocus(String source) {
        String before = "placeholder:e.placeholder,onChange:e.onChange,onBlur:e.onBlur,onKeyDown:t";
        String after = "placeholder:e.placeholder,onChange:e.onChange,"
                + "onFocus:()=>{try{const n=window.lunar;n&&typeof n.gomodSetLockerTextFocus===\"function\""
                + "&&n.gomodSetLockerTextFocus(!0)}catch{}},"
                + "onBlur:r=>{try{const n=window.lunar;n&&typeof n.gomodSetLockerTextFocus===\"function\""
                + "&&n.gomodSetLockerTextFocus(!1)}catch{}e.onBlur&&e.onBlur(r)},onKeyDown:t";
        if (source.contains("gomodSetLockerTextFocus(!0)")) {
            requireUnique(source, "gomodSetLockerTextFocus(!0)", "Locker search focus hook");
            return source;
        }
        return replaceUnique(source, before, after, "Locker search input callbacks");
    }

    /** Catch text focus in every Lunar WebOSR tab, including Friend tab fields. */
    private static String patchOverlayTextFocusGuard(String source) {
        if (source.contains(OVERLAY_TEXT_FOCUS_GUARD_MARKER)) {
            requireUnique(source, OVERLAY_TEXT_FOCUS_GUARD_MARKER,
                    "Lunar overlay text focus guard");
            return source;
        }
        if (source.contains(OVERLAY_TEXT_FOCUS_GUARD_DEFINITION_MARKER)) {
            throw new UnsupportedBundleException("partial Lunar text focus guard detected");
        }

        String helper = "gomodInstallLunarTextFocusGuard=()=>{try{"
                + "const w=window;if(w.__gomodLunarTextFocusGuard)return!0;"
                + "w.__gomodLunarTextFocusGuard=!0;const d=document,"
                + "isTextTarget=e=>{if(!e)return!1;const t=String(e.tagName||\"\").toLowerCase();"
                + "if(t===\"textarea\"||t===\"select\")return!0;"
                + "if(t===\"input\"){const n=String(e.type||\"text\").toLowerCase();"
                + "return![\"button\",\"submit\",\"reset\",\"checkbox\",\"radio\",\"file\","
                + "\"image\",\"hidden\",\"range\",\"color\"].includes(n)}"
                + "if(e.isContentEditable)return!0;const r=typeof e.getAttribute===\"function\"?"
                + "String(e.getAttribute(\"role\")||\"\").toLowerCase():\"\";"
                + "return r===\"textbox\"||r===\"searchbox\"||r===\"combobox\"},"
                + "setFocus=e=>{try{const n=window.lunar;"
                + "n&&typeof n.gomodSetLockerTextFocus===\"function\"&&"
                + "n.gomodSetLockerTextFocus(!!e)}catch{}};"
                + "d.addEventListener(\"focusin\",e=>setFocus(isTextTarget(e.target)),!0);"
                + "d.addEventListener(\"focusout\",()=>window.setTimeout(()=>"
                + "setFocus(isTextTarget(d.activeElement)),0),!0);"
                + "setFocus(isTextTarget(d.activeElement));return!0}catch{return!1}},"
                + OVERLAY_TEXT_FOCUS_GUARD_MARKER;
        return replaceUnique(source, "lunarLogoPrototypeColors=",
                helper + ",lunarLogoPrototypeColors=", "Lunar overlay text focus guard");
    }

    /**
     * Older cached Locker bundles treated the White preset as the API's
     * DEFAULT sentinel, which removes a custom value instead of setting RGB
     * white. Upgrade only that exact known expression; leave unknown bundle
     * variants untouched.
     */
    private static String patchDefaultWhitePreset(String source) {
        if (source.contains(EXPLICIT_WHITE_PRESET)) {
            requireUnique(source, EXPLICIT_WHITE_PRESET, "explicit white preset");
            return source;
        }
        return replaceUnique(source, LEGACY_DEFAULT_WHITE_PRESET, EXPLICIT_WHITE_PRESET,
                "Default / White preset behavior");
    }

    private static String ensureLogoColorTab(String source, String fragment) {
        if (!source.contains("LogoColorPanel=()=>")) {
            source = replaceUnique(source,
                    "e.LUNARPLUS=\"LUNARPLUS\",e.BADGES=",
                    "e.LUNARPLUS=\"LUNARPLUS\",e.LOGO_COLOR=\"LOGO_COLOR\",e.BADGES=",
                    "Locker tab enum");
            source = replaceUnique(source,
                    ",od0=\"data:image/png;base64",
                    "," + fragment + ",od0=\"data:image/png;base64",
                    "Locker tab component insertion");
            source = replaceUnique(source,
                    "case i0.LUNARPLUS:return i.jsx(Yd0,{});",
                    "case i0.LUNARPLUS:return i.jsx(Yd0,{});"
                            + "case i0.LOGO_COLOR:return i.jsx(LogoColorPanel,{});",
                    "Locker tab route");
            source = replaceUnique(source,
                    "i.jsx(nd0,{tab:F,setActiveTab:b}),i.jsx(Gc0,{tab:F,setActiveTab:b})",
                    "i.jsx(nd0,{tab:F,setActiveTab:b}),"
                            + "i.jsx(LogoColorTab,{tab:F,setActiveTab:b}),"
                            + "i.jsx(Gc0,{tab:F,setActiveTab:b})",
                    "Locker sidebar tab");
            source = replaceUnique(source,
                    "j=()=>F==i0.EMOTES?",
                    "j=()=>F==i0.LOGO_COLOR?\"\":F==i0.EMOTES?",
                    "Locker store label");
            source = replaceUnique(source,
                    "i.jsx(g2,{icon:i.jsxs(\"svg\",{xmlns:\"http://www.w3.org/2000/svg\","
                            + "width:\"17\",height:\"16\",viewBox:\"0 0 17 16\",fill:\"none\"",
                    "e.shopText&&i.jsx(g2,{icon:i.jsxs(\"svg\",{xmlns:\"http://www.w3.org/2000/svg\","
                            + "width:\"17\",height:\"16\",viewBox:\"0 0 17 16\",fill:\"none\"",
                    "Locker shop button condition");
            source = replaceUnique(source,
                    "}),[d,t,o,n]),x=g.useMemo",
                    "}),[d,t,o,n,a]),x=g.useMemo",
                    "Lunar+ available-colors memo dependency");
        } else {
            if ((!source.contains(LOGO_COLOR_TAB_MARKER)
                    && !source.contains(LEGACY_LOGO_COLOR_TAB_MARKER))
                    || !source.contains("LogoColorTab=")
                    || !source.contains("gomodSetLunarLogoColor")) {
                throw new UnsupportedBundleException("partial Logo Color tab patch detected");
            }
            if (!source.contains(SAVED_LOGO_COLOR_MARKER)) {
                int colors = source.indexOf("lunarLogoPrototypeColors=");
                int fragmentColors = fragment.indexOf("lunarLogoPrototypeColors=");
                if (colors < 0 || fragmentColors < 0) {
                    throw new UnsupportedBundleException("saved-color helper insertion point missing");
                }
                String helpers = fragment.substring(0, fragmentColors);
                source = source.substring(0, colors) + helpers + source.substring(colors);
            }
        }

        if (!source.contains(SAVED_LOGO_COLOR_MARKER)
                || !source.contains("LogoColorPanel=()=>")) {
            throw new UnsupportedBundleException("Logo Color UI did not validate");
        }
        return source;
    }

    private static String patchFullAccessVisibility(String source) {
        String helper = "gomodLunarFullAccessEnabled=()=>{try{const e=window.lunar;"
                + "if(!e||typeof e.gomodIsLunarFullAccessEnabled!==\"function\")"
                + "return Promise.resolve(!1);"
                + "return Promise.resolve(e.gomodIsLunarFullAccessEnabled())"
                + ".then(e=>e===!0||e===\"true\"||e===1||e===\"1\").catch(()=>!1)"
                + "}catch(e){return Promise.resolve(!1)}}";
        if (!source.contains(FULLACCESS_HELPER_MARKER)) {
            requireUnique(source, "LogoColorTab=", "Logo Color tab component");
            int tab = source.indexOf("LogoColorTab=");
            source = source.substring(0, tab) + helper + "," + source.substring(tab);
        } else {
            requireUnique(source, FULLACCESS_HELPER_MARKER, "FullAccess JavaScript helper");
            int helperStart = source.indexOf(FULLACCESS_HELPER_MARKER);
            int helperEnd = source.indexOf(",LogoColorTab=", helperStart);
            if (helperEnd <= helperStart) {
                throw new UnsupportedBundleException("FullAccess helper boundaries changed");
            }
            if (!source.substring(helperStart, helperEnd).equals(helper)) {
                source = source.substring(0, helperStart) + helper + source.substring(helperEnd);
            }
        }

        String guardedTab = "LogoColorTab=e=>{const[gomodTabEnabled,setGomodTabEnabled]="
                + "g.useState(!1);g.useEffect(()=>{let gomodTabActive=!0;"
                + "const gomodCheckTab=()=>{gomodLunarFullAccessEnabled().then(e=>{"
                + "if(gomodTabActive)setGomodTabEnabled(e)})};gomodCheckTab();"
                + "const gomodTabTimer=window.setInterval(gomodCheckTab,500);"
                + "return()=>{gomodTabActive=!1;window.clearInterval(gomodTabTimer)}},[]);"
                + "return gomodTabEnabled?"
                + "i.jsx(\"div\",{children:i.jsx(I5,{canClose:!0,name:\"Lunar Logo Color\","
                + "icon:A6,itemCount:void 0,hasNewItems:!1,plusButton:!1,selected:e.tab==i0.LOGO_COLOR,"
                + "onClick:()=>{e.setActiveTab(i0.LOGO_COLOR,void 0)}})}):null}";
        int tabStart = source.indexOf("LogoColorTab=");
        int panelStart = source.indexOf(",LogoColorPanel=", tabStart);
        if (tabStart < 0 || panelStart <= tabStart) {
            throw new UnsupportedBundleException("Logo Color tab component boundaries changed");
        }
        String existingTab = source.substring(tabStart, panelStart);
        if (!existingTab.startsWith(FULLACCESS_TAB_MARKER)) {
            if (!existingTab.startsWith("LogoColorTab=e=>")) {
                throw new UnsupportedBundleException("Logo Color tab component changed");
            }
            source = source.substring(0, tabStart) + guardedTab + source.substring(panelStart);
        }

        if (source.contains(LEGACY_LOGO_COLOR_TAB_MARKER)) {
            source = replaceUnique(source, LEGACY_LOGO_COLOR_TAB_MARKER,
                    LOGO_COLOR_TAB_MARKER, "FullAccess-gated Logo Color route");
        } else if (!source.contains(LOGO_COLOR_TAB_MARKER)) {
            throw new UnsupportedBundleException("Logo Color route could not be gated");
        }

        String panelMarker = "LogoColorPanel=()=>{";
        int panelComponentStart = source.indexOf(panelMarker);
        int panelStateStart = source.indexOf("const[n,s]=g.useState(", panelComponentStart);
        if (panelComponentStart < 0 || panelStateStart <= panelComponentStart) {
            throw new UnsupportedBundleException("Logo Color panel boundaries changed");
        }
        String guardedPanel = panelMarker
                    + "const[gomodPanelEnabled,setGomodPanelEnabled]=g.useState(!1);"
                    + "g.useEffect(()=>{let gomodPanelActive=!0;"
                    + "const gomodCheckPanel=()=>{gomodLunarFullAccessEnabled().then(e=>{"
                    + "if(gomodPanelActive)setGomodPanelEnabled(e)})};gomodCheckPanel();"
                    + "const gomodPanelTimer=window.setInterval(gomodCheckPanel,500);"
                    + "return()=>{gomodPanelActive=!1;window.clearInterval(gomodPanelTimer)}},[]);"
                    + "return gomodPanelEnabled?i.jsx(LogoColorPanelContent,{}):null},"
                    + "LogoColorPanelContent=()=>{";
        if (!source.substring(panelComponentStart, panelStateStart).equals(guardedPanel)) {
            source = source.substring(0, panelComponentStart) + guardedPanel + source.substring(panelStateStart);
        }
        return source;
    }

    private static String patchColorCard(String source) {
        int start = source.indexOf("Gd0=e=>");
        int end = source.indexOf(",$d0=", start);
        if (start < 0 || end <= start) {
            throw new UnsupportedBundleException("Locker color-card boundaries changed");
        }
        String renderer = source.substring(start, end);
        String iconMarker =
                "children:i.jsx(\"svg\",{width:40,height:40,viewBox:\"106 14 46 45\"";
        if (!renderer.contains("children:e.noPlus?i.jsx(wa,{w:40,h:40")) {
            int icon = renderer.indexOf(iconMarker);
            if (icon < 0 || count(renderer, iconMarker) != 1) {
                throw new UnsupportedBundleException("No+ icon insertion point changed");
            }
            int expressionStart = icon + "children:".length();
            int expressionEnd = findCallEnd(renderer, expressionStart);
            if (expressionEnd <= expressionStart) {
                throw new UnsupportedBundleException("Lunar+ icon expression could not be isolated");
            }
            String plusIcon = renderer.substring(expressionStart, expressionEnd);
            String noPlusIcon = "e.noPlus?i.jsx(wa,{w:40,h:40,plusColor:\"#000000\","
                    + "logoColor:e.logoColor||gomodSavedLunarLogoColor()}):";
            renderer = renderer.substring(0, icon)
                    + "children:" + noPlusIcon + plusIcon
                    + renderer.substring(expressionEnd);
        } else {
            renderer = replaceAllLiteral(renderer,
                    "logoColor:e.logoColor||\"#FFFFFF\"",
                    "logoColor:e.logoColor||gomodSavedLunarLogoColor()");
            renderer = replaceAllLiteral(renderer,
                    "logoColor:\"#FF45D6\"",
                    "logoColor:gomodSavedLunarLogoColor()");
        }
        renderer = replaceAllLiteral(renderer,
                "logoColor:e.logoColor||\"#FFFFFF\"",
                "logoColor:e.logoColor||gomodSavedLunarLogoColor()");
        renderer = replaceAllLiteral(renderer,
                "logoColor:\"#FF45D6\"",
                "logoColor:gomodSavedLunarLogoColor()");
        source = source.substring(0, start) + renderer + source.substring(end);

        String oldSelectedTint =
                "children:i.jsx(Gd0,{hex:b,noPlus:k.color===0,logoColor:k.color===0?G:b,";
        if (source.contains(oldSelectedTint)) {
            source = replaceUnique(source, oldSelectedTint,
                    "children:i.jsx(Gd0,{hex:b,noPlus:k.color===0,"
                            + "logoColor:gomodSavedLunarLogoColor(),",
                    "No+ and Lunar+ card logo tint");
        } else {
            String noLogoColor =
                    "children:i.jsx(Gd0,{hex:b,noPlus:k.color===0,accountName:";
            if (source.contains(noLogoColor)) {
                source = replaceUnique(source, noLogoColor,
                        "children:i.jsx(Gd0,{hex:b,noPlus:k.color===0,"
                                + "logoColor:gomodSavedLunarLogoColor(),accountName:",
                        "No+ card saved logo tint");
            } else {
                String original =
                        "children:i.jsx(Gd0,{hex:b,accountName:";
                if (source.contains(original)) {
                    source = replaceUnique(source, original,
                            "children:i.jsx(Gd0,{hex:b,noPlus:k.color===0,"
                                    + "logoColor:gomodSavedLunarLogoColor(),accountName:",
                            "No+ card support");
                } else if (!source.contains(
                        "children:i.jsx(Gd0,{hex:b,noPlus:k.color===0,"
                                + "logoColor:gomodSavedLunarLogoColor(),")) {
                    throw new UnsupportedBundleException("Lunar+ card props changed");
                }
            }
        }
        return source;
    }

    private static String patchSelectionPreview(String source) {
        if (source.contains(PREVIEW_MARKER)) {
            return source;
        }

        int panelStart = source.indexOf("Yd0=()=>{");
        int panelEnd = source.indexOf(",qd0=({shown:", panelStart);
        if (panelStart < 0 || panelEnd <= panelStart) {
            throw new UnsupportedBundleException("Lunar+ panel boundaries changed");
        }
        String panel = source.substring(panelStart, panelEnd);
        String lockerPreview = "i.jsx(Uo,{hasOutfitTitle:!0,children:";
        int previewStart = panel.indexOf(lockerPreview);
        if (previewStart < 0 || count(panel, lockerPreview) != 1) {
            throw new UnsupportedBundleException("right-side Locker preview not found uniquely");
        }

        int previewEnd = findCallEnd(panel, previewStart);
        if (previewEnd <= previewStart) {
            throw new UnsupportedBundleException("right-side preview could not be isolated");
        }
        String preview = panel.substring(previewStart, previewEnd);
        String modelStartMarker = "i.jsx(G0,";
        int modelStart = preview.indexOf(modelStartMarker);
        if (modelStart < 0 || count(preview, modelStartMarker) != 1) {
            throw new UnsupportedBundleException("preview mannequin not found uniquely");
        }
        int modelEnd = findCallEnd(preview, modelStart);
        if (modelEnd <= modelStart) {
            throw new UnsupportedBundleException("preview mannequin props could not be isolated");
        }
        String model = preview.substring(modelStart, modelEnd);
        if (!model.contains("renderNametag:!0")) {
            throw new UnsupportedBundleException("native preview nametag prop changed");
        }
        model = replaceUnique(model, "renderNametag:!0", "renderNametag:!1",
                "native nametag suppression");

        String childrenMarker = "children:";
        int childStart = preview.indexOf(childrenMarker) + childrenMarker.length();
        String renderedChildren = "i.jsxs(i.Fragment,{children:[" + model
                + ",i.jsx(GomodLunarPlusPreviewNametag,{activeColor:d,"
                + "username:(o==null?void 0:o.username)??\"Steve\"})]})";
        preview = preview.substring(0, childStart) + renderedChildren
                + preview.substring(modelEnd);
        panel = panel.substring(0, previewStart) + preview + panel.substring(previewEnd);
        return source.substring(0, panelStart) + panel + source.substring(panelEnd);
    }

    /** Uses a transparent SVG fill for the local No+ preference on Lunar's main-menu account tile. */
    private static String patchAccountTileNoPlusColor(String source) {
        if (source.contains(ACCOUNT_TILE_PLUS_COLOR)) {
            requireUnique(source, ACCOUNT_TILE_PLUS_COLOR, "No+ account-tile renderer");
            return source;
        }
        return replaceUnique(source, NATIVE_ACCOUNT_TILE_PLUS_COLOR, ACCOUNT_TILE_PLUS_COLOR,
                "No+ account-tile renderer");
    }

    /** The local account icon follows Lunar Online even while FullAccess is turned off. */
    private static String patchAccountTileConnectionLogo(String source) {
        String helperMarker = "GomodLunarOnlineLogo=e=>{";
        String helper;
        try (InputStream input = LunarLockerUiBundlePatcher.class.getResourceAsStream(
                "/gomod/lunar-connection-logo-ui-fragment.js");
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("missing connection logo UI");
            byte[] buffer = new byte[4096]; int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            helper = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!helper.startsWith(helperMarker) || !helper.contains("gomodIsLunarOnlineConnected"))
                throw new IOException("invalid connection logo UI");
        } catch (IOException unavailable) {
            throw new UnsupportedBundleException("Connection logo UI resource is unavailable");
        }
        String next = ",qL2=_(\"div\",";
        requireUnique(source, next, "native Lunar SVG boundary");
        if (source.contains(helperMarker)) {
            requireUnique(source, helperMarker, "local connection logo helper");
            int start = source.indexOf(helperMarker), end = source.indexOf(next, start);
            if (end <= start || end - start > 4096)
                throw new UnsupportedBundleException("Connection logo helper boundaries changed");
            source = source.substring(0, start) + helper + source.substring(end);
        } else {
            source = replaceUnique(source, next, "," + helper + next, "local connection logo helper");
        }
        String cross = "id:\"svg_2\",fill:e.gomodOffline?\"#8B1E24\":e.plusColor,"
                + "transform:e.gomodOffline?\"rotate(45 128.84 36.5)\":void 0,";
        if (!source.contains(cross)) {
            source = replaceUnique(source, "e.plusColor!=\"#000000\"&&i.jsx(\"g\",{id:\"svg_1\"",
                    "(e.gomodOffline||e.plusColor!=\"#000000\")&&i.jsx(\"g\",{id:\"svg_1\"",
                    "local offline SVG mark visibility");
            source = replaceUnique(source, "id:\"svg_2\",fill:e.plusColor,", cross,
                    "local offline SVG cross");
        }
        if (!source.contains("i.jsx(GomodLunarOnlineLogo,{")) {
            int account = source.indexOf(ACCOUNT_TILE_PLUS_COLOR);
            int call = account < 0 ? -1 : source.lastIndexOf("i.jsx(wa,{", account);
            if (call < 0 || account - call > 1024 || findCallEnd(source, call) < account)
                throw new UnsupportedBundleException("Local account Lunar logo call changed");
            source = source.substring(0, call) + "i.jsx(GomodLunarOnlineLogo,{"
                    + source.substring(call + "i.jsx(wa,{".length());
        }
        return source;
    }

    private static int findCallEnd(String source, int expressionStart) {
        if (expressionStart < 0 || expressionStart >= source.length()) {
            return -1;
        }
        int depth = 0;
        char quote = 0;
        boolean escaped = false;
        for (int index = expressionStart; index < source.length(); index++) {
            char current = source.charAt(index);
            if (quote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (current == '\\') {
                    escaped = true;
                } else if (current == quote) {
                    quote = 0;
                }
                continue;
            }
            if (current == '"' || current == '\'' || current == 96) {
                quote = current;
            } else if (current == '(') {
                depth++;
            } else if (current == ')') {
                depth--;
                if (depth == 0) {
                    return index + 1;
                }
                if (depth < 0) {
                    return -1;
                }
            }
        }
        return -1;
    }

    private static String replaceUnique(String source, String before,
                                        String after, String label) {
        int first = source.indexOf(before);
        if (first < 0 || source.indexOf(before, first + before.length()) >= 0) {
            throw new UnsupportedBundleException(label + " anchor was missing or ambiguous");
        }
        return source.substring(0, first) + after
                + source.substring(first + before.length());
    }

    private static String replaceAllLiteral(String source, String before, String after) {
        int next = source.indexOf(before);
        if (next < 0) {
            return source;
        }
        StringBuilder result = new StringBuilder(source.length());
        int copiedThrough = 0;
        while (next >= 0) {
            result.append(source, copiedThrough, next).append(after);
            copiedThrough = next + before.length();
            next = source.indexOf(before, copiedThrough);
        }
        result.append(source, copiedThrough, source.length());
        return result.toString();
    }

    private static int count(String source, String needle) {
        int total = 0;
        int next = source.indexOf(needle);
        while (next >= 0) {
            total++;
            next = source.indexOf(needle, next + needle.length());
        }
        return total;
    }

    private static void requireUnique(String source, String marker, String label) {
        if (count(source, marker) != 1) {
            throw new UnsupportedBundleException(label + " signature was missing or ambiguous");
        }
    }

    private static byte[] withUtf8Bom(byte[] body) {
        byte[] result = new byte[body.length + 3];
        result[0] = (byte) 0xEF;
        result[1] = (byte) 0xBB;
        result[2] = (byte) 0xBF;
        System.arraycopy(body, 0, result, 3, body.length);
        return result;
    }

    private static void replaceWithBackup(Path uiRoot, Path bundle,
                                          byte[] original, byte[] replacement) throws IOException {
        Path parent = bundle.getParent();
        String fileName = bundle.getFileName().toString();
        String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT)
                .format(new Date());
        Path backup = bundle.resolveSibling(fileName + ".gomod-backup-" + timestamp);
        int suffix = 1;
        while (Files.exists(backup)) {
            backup = bundle.resolveSibling(fileName + ".gomod-backup-" + timestamp + "-" + suffix++);
        }
        Path temporary = Files.createTempFile(parent, fileName + ".gomod-", ".tmp");
        boolean backupCreated = false;
        try {
            Files.write(temporary, replacement, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            if (!Arrays.equals(Files.readAllBytes(bundle), original)) {
                throw new IOException("Lunar bundle changed while the patch was prepared");
            }
            Files.copy(bundle, backup, StandardCopyOption.COPY_ATTRIBUTES);
            backupCreated = true;
            try {
                Files.move(temporary, bundle, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, bundle, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            if (backupCreated && !Files.exists(bundle)) {
                Files.copy(backup, bundle, StandardCopyOption.COPY_ATTRIBUTES);
            }
            throw error;
        } finally {
            Files.deleteIfExists(temporary);
        }

        if (Files.size(bundle) == 0L || original.length == 0
                || !Arrays.equals(Files.readAllBytes(bundle), replacement)) {
            throw new IOException("patched bundle verification failed; original backup retained");
        }
    }

    private static final String CHUNKED_APPEARANCE_RESOURCE =
            "/gomod/lunar-cosmetic-appearance-chunked-ui-fragment.js";
    private static final String CHUNKED_APPEARANCE_MARKER =
            "\"data-gomod-cosmetic-appearance\":\"v12-chunked\"";
    private static final String CHUNKED_EXTRAS_RESOURCE =
            "/gomod/lunar-locker-extras-chunked-ui-fragment.js";
    private static final String CHUNKED_LOCKER_EXTRAS_MARKER =
            "\"data-gomod-locker-extras\":\"v4-chunked\"";
    private static final String CHUNKED_BADGE_STUDIO_RESOURCE =
            "/gomod/lunar-studio-chunked-ui-fragment.js";
    private static final String CHUNKED_BADGE_STUDIO_MARKER =
            "\"data-gomod-badge-studio\":\"v3-chunked\"";
    private static final String CHUNKED_OFFICIAL_LOADOUT_RESOURCE =
            "/gomod/lunar-official-loadout-chunked-ui-fragment.js";
    private static final String CHUNKED_OFFICIAL_LOADOUT_MARKER =
            "\"data-gomod-official-loadout\":\"v2-chunked\"";
    private static final String CHUNKED_FITS_ACTIONS_RESOURCE =
            "/gomod/lunar-fits-actions-chunked-ui-fragment.js";
    private static final String CHUNKED_FITS_ACTIONS_MARKER =
            "\"data-gomod-fits-actions\":\"v1-chunked\"";
    private static final String CHUNKED_NAMETAG_LOGO_COLOR_RESOURCE =
            "/gomod/lunar-nametag-logo-color-chunked-ui-fragment.js";
    private static final String CHUNKED_NAMETAG_LOGO_COLOR_MARKER =
            "\"data-gomod-nametag-logo-color\":\"v4-chunked\"";
    private static final Pattern CHUNKED_NAMETAG_LOGO_EMPTY_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)&&([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{children:([A-Za-z0-9_$]+)\\(\"gui\\.nametag\\.noLogoColors\"\\)\\}\\)");
    private static final Pattern CHUNKED_NAMETAG_PREVIEW_LOGO_PATTERN = Pattern.compile(
            "logoColor:([A-Za-z0-9_$]+)==null\\?void 0:\\1\\.lunarPlus\\.logoColor,plusColor:");
    private static final Pattern CHUNKED_NAMETAG_PLUS_COLORS_GRID_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=\\(\\{slots:([A-Za-z0-9_$]+),active:([A-Za-z0-9_$]+),message:([A-Za-z0-9_$]+)\\}\\)=>\\{"
                    + "const ([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)=>\\{\\6!==\\3&&([A-Za-z0-9_$]+)\\.lunarPlus\\.setColor\\(\\6\\)\\};"
                    + "return ([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{message:\\4,children:\\2\\.map\\(\\(\\{item:([A-Za-z0-9_$]+),hidden:([A-Za-z0-9_$]+)\\}\\)=>"
                    + "\\8\\.jsx\\(([A-Za-z0-9_$]+),\\{label:\\10\\.name,selected:\\10\\.color===\\3,hidden:\\11,onSelect:\\(\\)=>\\5\\(\\10\\.color\\),"
                    + "children:\\8\\.jsx\\(([A-Za-z0-9_$]+),\\{color:([A-Za-z0-9_$]+)\\(\\10\\.color\\)\\}\\)\\},\\10\\.color\\)\\)\\}\\)\\}");
    private static final Pattern CHUNKED_NAMETAG_BADGES_SCROLL_PATTERN = Pattern.compile(
            "className:\"nametagTour4\",title:([A-Za-z0-9_$]+)\\(\"gui\\.nametag\\.badge\"\\),count:([^,]+),scrollable:!0,");
    private static final String CHUNKED_NAMETAG_ROOT_STYLE_ANCHOR =
            "display:\"flex\",flexDirection:\"column\",gap:\"var(--spacings-radii-4)\",flex:\"1 1 auto\",minWidth:0,minHeight:0,alignSelf:\"stretch\"";
    private static final String CHUNKED_NAMETAG_ROOT_STYLE_SCROLL =
            CHUNKED_NAMETAG_ROOT_STYLE_ANCHOR + ",overflowY:\"auto\",overflowX:\"hidden\",paddingRight:\"4px\"";
    private static final Pattern CHUNKED_FITS_TEXT_INPUT_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{autoFocus:!0,ref:[A-Za-z0-9_$]+,value:[A-Za-z0-9_$]+,maxCharacters:32,");
    private static final String CHUNKED_EDITED_SORT_MARKER = "id:\"edited-first\"";
    private static final String CHUNKED_SCROLL_STYLE_SUFFIX =
            ",minHeight:0,maxHeight:\"100%\",overflowY:\"auto\",overflowX:\"hidden\","
                    + "overscrollBehavior:\"contain\",boxSizing:\"border-box\"";
    private static final Pattern CHUNKED_SLIDER_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\\.forwardRef\\(\\(([A-Za-z0-9_$]+),([A-Za-z0-9_$]+)\\)=>\\{"
                    + "const ([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\\.useRef\\(null\\),"
                    + "([A-Za-z0-9_$]+)=\\6\\.useRef\\(null\\),"
                    + "([A-Za-z0-9_$]+)=\\6\\.useRef\\(([A-Za-z0-9_$]+)=>\\9\\.toFixed\\(2\\)\\),"
                    + "([A-Za-z0-9_$]+)=\\6\\.useRef\\(!1\\),"
                    + "([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)=>\\12\\.toFixed\\(2\\)");
    private static final Pattern CHUNKED_INDEX5_FINISH_BUTTON_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{text:\"Finish\",type:\"secondary\"");
    private static final Pattern CHUNKED_INDEX5_SETTINGS_COMPONENT_PATTERN = Pattern.compile(
            ",([A-Za-z0-9_$]+)=\\(\\{cosmetics:([A-Za-z0-9_$]+),cosmetic:([A-Za-z0-9_$]+),onReturn:([A-Za-z0-9_$]+)\\}\\)=>\\{"
                    + "const ([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\\.useRef\\(!1\\),"
                    + "\\[([A-Za-z0-9_$]+),[A-Za-z0-9_$]+\\]=\\6\\.useState\\(\\[\\]\\),"
                    + "\\[[A-Za-z0-9_$]+,[A-Za-z0-9_$]+\\]=\\6\\.useState\\(\\3\\),"
                    + "\\[([A-Za-z0-9_$]+),[A-Za-z0-9_$]+\\]=\\6\\.useState\\(null\\)");
    private static final Pattern CHUNKED_INDEX5_COSMETIC_SETTINGS_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{title:\"Cosmetic Settings\",children:");
    private static final Pattern CHUNKED_TOOLTIP_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{placement:\"right-start\","
                    + "content:\\1\\.jsx\\(([A-Za-z0-9_$]+),\\{children:\\1\\.jsxs\\(\"div\",\\{style:\\{[^}]+\\},"
                    + "children:\\[\\1\\.jsx\\(([A-Za-z0-9_$]+),\\{fontFamily:\"modern\",fontSize:\"14px\","
                    + "color:\"var\\(--colors-neutrals-space-11\\)\",fontWeight:\"500\",children:\"Active in outfits:\"\\}\\)");
    private static final Pattern CHUNKED_COSMETIC_MENU_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=\\(\\{cosmetic:([A-Za-z0-9_$]+),isEquipped:([A-Za-z0-9_$]+),"
                    + "lockedDiscordRewardText:([A-Za-z0-9_$]+),onDiscordRewardCta:([A-Za-z0-9_$]+),"
                    + "onSettings:([A-Za-z0-9_$]+)(?:,visible:gomodVisible)?\\}\\)=>"
                    + "([A-Za-z0-9_$]+)\\.jsxs\\(\\7\\.Fragment,\\{children:\\[\\7\\.jsx\\(([A-Za-z0-9_$]+),\\{title:\\2\\.name,label:\\2\\.type\\}\\),"
                    + ".*?(\\4==null&&\\7\\.jsx\\(\\8,\\{label:\"Settings\",icon:\\7\\.jsx\\([A-Za-z0-9_$]+,\\{\\}\\),onClick:\\6\\}\\)\\]\\}\\))");
    private static final Pattern CHUNKED_COSMETIC_MENU_WRAPPER_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=\\(\\{cosmetic:([A-Za-z0-9_$]+),children:([A-Za-z0-9_$]+),onSettings:([A-Za-z0-9_$]+),"
                    + "getDiscordRewardCtaText:([A-Za-z0-9_$]+),onDiscordRewardCta:([A-Za-z0-9_$]+),outfit:([A-Za-z0-9_$]+)\\}\\)=>\\{"
                    + "const\\[([A-Za-z0-9_$]+),([A-Za-z0-9_$]+)\\]=([A-Za-z0-9_$]+)\\.useState\\(\\{visible:!1,cachedCosmetic:\\2\\}\\)[;,]"
                    + ".*?(options:([A-Za-z0-9_$]+)\\?([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{cosmetic:\\12,(?:visible:\\8\\.visible,)?)");
    private static final Pattern CHUNKED_COSMETIC_CARD_HEADER_PATTERN = Pattern.compile(
            "const\\{cosmetic:([A-Za-z0-9_$]+),[^}]{1,400},topRight:([A-Za-z0-9_$]+)\\}=[A-Za-z0-9_$]+[;,]");
    private static final Pattern CHUNKED_EMOTE_TILE_PATTERN = Pattern.compile(
            "(onMouseEnter:\\(\\)=>\\{([A-Za-z0-9_$]+)\\.model\\.playEmote\\(`EMOTE_MANNEQUIN_\\$\\{([A-Za-z0-9_$]+)\\.emote\\.id\\}`\\)\\})");
    private static final Pattern CHUNKED_BADGE_TILE_PATTERN = Pattern.compile(
            "(topLeft:([A-Za-z0-9_$]+)\\.badge\\.description==null&&\\(\\2\\.grantedAt==null\\|\\|\\2\\.grantedAt<=0\\)"
                    + "\\?void 0:([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{ownedBadge:\\2\\}\\),)(children:\\3\\.jsx\\([A-Za-z0-9_$]+,\\{ownedBadge:\\2,size:\"lg\"\\}\\))\\},`BADGE_\\$\\{\\2\\.badge\\.id\\}`");
    private static final Pattern CHUNKED_COSMETIC_SORT_TAIL_PATTERN = Pattern.compile(
            "(\\{id:\"acquired-oldest\",label:\"Acquired \\(Oldest\\)\",shortLabel:\"ACQ\",icon:[^,]+,sort:\\([^)]+\\)=>[^}]+\\})\\](,)");
    private static final Pattern CHUNKED_COSMETIC_SORT_COMPARATOR_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=\\(\\)==>?".replace("==>?", "=>")
                    + "([A-Za-z0-9_$]+)\\.find\\(([A-Za-z0-9_$]+)=>\\3\\.id===([A-Za-z0-9_$]+)\\.current\\)\\?\\?\\2\\[0\\],"
                    + "([A-Za-z0-9_$]+)=\\(([A-Za-z0-9_$]+),([A-Za-z0-9_$]+)\\)=>\\{"
                    + "const ([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\\(\\6\\),([A-Za-z0-9_$]+)=\\9\\(\\7\\);"
                    + "if\\(\\8!=\\10\\)return \\8-\\10;const ([A-Za-z0-9_$]+)=\\1\\(\\)\\.sort;"
                    + "return \\11\\?\\11\\(\\6,\\7,([A-Za-z0-9_$]+)\\.current\\):0\\}".replace("==>?", "=>"));
    private static final Pattern CHUNKED_COSMETIC_SORT_APPLY_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\\.useCallback\\(([A-Za-z0-9_$]+)=>\\{"
                    + "([A-Za-z0-9_$]+)\\.current=\\3,([A-Za-z0-9_$]+)\\.current=\\5\\.current\\.sort\\(([A-Za-z0-9_$]+)\\),"
                    + "([A-Za-z0-9_$]+)\\(\\[\\.\\.\\.([A-Za-z0-9_$]+)\\.current\\]\\.sort\\(\\6\\)\\),"
                    + "([A-Za-z0-9_$]+)\\(\\)\\},\\[\\7,\\9,\\5\\]\\);return\\{pageEmptyReason:");
    private static final Pattern CHUNKED_MODAL_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsxs\\(([A-Za-z0-9_$]+),\\{title:\"Create new Outfit\",onClose:[^,]+,width:\"532px\",logo:!1");
    private static final Pattern CHUNKED_ICON_RENDER_PATTERN = Pattern.compile(
            "\\{icon:([A-Za-z0-9_$]+)\\(([A-Za-z0-9_$]+)\\.PI_SETTINGS02_SOLID,\\{width:\"20px\",height:\"20px\"\\}\\)");
    private static final Pattern CHUNKED_BADGE_IMAGE_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)=>([A-Za-z0-9_$]+)\\.jsx\\(\"div\",\\{style:\\{([^}]+)\\},"
                    + "children:\\3\\.jsx\\(([A-Za-z0-9_$]+),\\{src:\\2\\.ownedBadge\\.badge\\.resource,"
                    + "alt:\\2\\.ownedBadge\\.badge\\.name,animated:\\2\\.ownedBadge\\.badge\\.animated,"
                    + "width:([^,]+),height:([^}]+)\\}\\)\\}\\)");
    private static final Pattern CHUNKED_BADGE_IMAGE_STYLED_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)=>\\{([^}]+?)"
                    + "return ([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{"
                    + "children:\\4\\.jsx\\(([A-Za-z0-9_$]+),\\{src:\\2\\.ownedBadge\\.badge\\.resource,"
                    + "alt:\\2\\.ownedBadge\\.badge\\.name,animated:\\2\\.ownedBadge\\.badge\\.animated,"
                    + "width:([^,]+),height:([^}]+)\\}\\)\\}\\)\\}");
    private static final Pattern CHUNKED_YOUR_LOADOUT_TITLE_BAR_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)=>([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),"
                    + "\\{canResize:!0,name:\"Your Loadout\",icon:\\2\\.icon,onHelp:\\2\\.onHelp\\}\\)");
    private static final Pattern CHUNKED_PLAYER_DUMMY_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{id:\"TEST_PLAYER_DUMMY\","
                    + "skin:([A-Za-z0-9_$]+)\\.SELF,display:([A-Za-z0-9_$]+)\\.DUMMY,"
                    + "cosmetics:[A-Za-z0-9_$]+,renderContext:([A-Za-z0-9_$]+)\\.IN_COSMETIC_PLAYER_MODEL,");
    private static final Pattern CHUNKED_LOCKER_ROW_PATTERN = Pattern.compile(
            "return ([A-Za-z0-9_$]+)\\.jsxs\\(([A-Za-z0-9_$]+),\\{children:\\[\\1\\.jsxs\\("
                    + "([A-Za-z0-9_$]+),\\{children:\\[\\1\\.jsx\\(([A-Za-z0-9_$]+),\\{activeType:");
    private static final Pattern CHUNKED_BADGE_TILE_STUDIO_PATTERN = Pattern.compile(
            "(const ([A-Za-z0-9_$]+)=([A-Za-z0-9_$]+)\\[([A-Za-z0-9_$]+)\\];return \\2\\?)"
                    + "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{className:\\4==0\\?\"badgesTour2\":void 0,"
                    + "name:\\2\\.badge\\.name,equipped:\\(([A-Za-z0-9_$]+)==null\\?void 0:\\7\\.id\\)==\\2\\.badge\\.id,"
                    + "(.{0,450}?onDoubleClick:\\(\\)=>\\{([A-Za-z0-9_$]+)\\.badges\\.equip\\(\\2\\.badge\\.id\\)\\},"
                    + "topLeft:\\2\\.badge\\.description==null&&\\(\\2\\.grantedAt==null\\|\\|\\2\\.grantedAt<=0\\)\\?void 0:"
                    + "\\5\\.jsx\\([A-Za-z0-9_$]+,\\{ownedBadge:\\2\\}\\),)"
                    + "(topRight:\\5\\.jsx\\(GomodCosmeticOwnershipBadge,\\{badgeId:\\2\\.badge\\.id\\}\\),)?+"
                    + "(children:\\5\\.jsx\\([A-Za-z0-9_$]+,\\{ownedBadge:\\2,size:\"lg\"\\}\\)\\}),"
                    + "(`BADGE_\\$\\{\\2\\.badge\\.id\\}`)"
                    + "\\):null");
    private static final Pattern CHUNKED_BADGE_TILE_NAMETAG_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)=\\(\\{ownedBadge:([A-Za-z0-9_$]+),locked:([A-Za-z0-9_$]+),"
                    + "equipped:([A-Za-z0-9_$]+),onSelect:([A-Za-z0-9_$]+)\\}\\)=>"
                    + "([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{label:\\2\\.badge\\.name,"
                    + "size:\"lg\",tooltip:\\6\\.jsx\\(([A-Za-z0-9_$]+),\\{ownedBadge:\\2,locked:\\3\\}\\),"
                    + "selected:\\4,locked:\\3,onSelect:\\(\\)=>\\5\\(\\2\\.badge\\.id\\),"
                    + "children:(\\6\\.jsx\\([A-Za-z0-9_$]+,\\{ownedBadge:\\2,size:\"tile\"\\}\\))\\}\\)");
    private static final String CHUNKED_OVERLAY_LAYER_ANCHOR =
            "display:\"flex\",flexDirection:\"column\",alignItems:\"flex-start\","
                    + "gap:\"4px\",position:\"absolute\",right:0,top:0";
    private static final String HOME_QUEUE_RESOURCE = "/gomod/lunar-home-queue-ui-fragment.js";
    private static final String HOME_QUEUE_MARKER = "\"data-gomod-home-queue\":\"v3\"";
    private static final Pattern HOME_QUEUE_BUTTONS_PATTERN = Pattern.compile(
            "([A-Za-z0-9_$]+)\\.jsx\\(\"div\",\\{style:\\{display:\"flex\",flexDirection:\"row\",flexWrap:\"wrap\","
                    + "alignItems:\"center\",gap:\"8px\"\\},children:(([A-Za-z0-9_$]+)\\.map\\(\\(([A-Za-z0-9_$]+),"
                    + "([A-Za-z0-9_$]+)\\)=>([A-Za-z0-9_$]+)\\.jsx\\(([A-Za-z0-9_$]+),\\{halfWidth:\\5>1&&\\3\\.length>3,"
                    + "badge:\\4\\.badge,icon:\\4\\.icon,text:([A-Za-z0-9_$]+)\\(\\4\\.id\\),styling:\\4\\.style,"
                    + "onMouseDown:\\(\\)=>\\{([A-Za-z0-9_$]+)\\.onClick\\(\\4\\.id\\)\\}\\},\\4\\.id\\)\\))\\}\\)");

    private static boolean isChunkedLockerDirectory(Path staticDir) {
        return findChunkedLockerMainFile(staticDir) != null;
    }

    private static boolean isChunkedLockerMainSource(String content) {
        return content.contains("title:\"Cosmetic Settings\"")
                && (content.contains("This cosmetic does not have any settings.")
                        || content.contains(CHUNKED_APPEARANCE_MARKER)
                        || content.contains("GomodCosmeticAppearanceCreate="));
    }

    private static Path findChunkedLockerMainFile(Path staticDir) {
        Path preferred = staticDir.resolve("index5.js");
        if (Files.isRegularFile(preferred)) {
            try {
                if (isChunkedLockerMainSource(readUtf8File(preferred))) {
                    return preferred;
                }
            } catch (IOException ignored) {
            }
        }
        try (Stream<Path> files = Files.list(staticDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> isLiveJsChunk(path))
                    .sorted()
                    .filter(path -> {
                        try {
                            return isChunkedLockerMainSource(readUtf8File(path));
                        } catch (IOException error) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException error) {
            return null;
        }
    }

    private static Path findChunkedFile(Path staticDir, String preferredName, String uniqueMarker) {
        Path preferred = staticDir.resolve(preferredName);
        if (Files.isRegularFile(preferred)) {
            return preferred;
        }
        try (Stream<Path> files = Files.list(staticDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(LunarLockerUiBundlePatcher::isLiveJsChunk)
                    .sorted()
                    .filter(path -> {
                        try {
                            return readUtf8File(path).contains(uniqueMarker);
                        } catch (IOException error) {
                            return false;
                        }
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException error) {
            return null;
        }
    }

    private static boolean isLiveJsChunk(Path path) {
        String name = path.getFileName().toString();
        return name.endsWith(".js") && !name.contains(".gomod-backup-") && !name.contains(".gomod-");
    }

    private static void patchChunkedLockerDirectory(Path uiRoot, Path staticDir) {
        final String chunkedFragment;
        final String chunkedExtras;
        final String chunkedBadgeStudio;
        final String chunkedOfficialLoadout;
        final String chunkedFitsActions;
        final String chunkedNametagLogoColor;
        final String homeQueueFragment;
        try {
            chunkedFragment = readChunkedAppearanceFragment();
            chunkedExtras = readChunkedLockerExtrasFragment();
            chunkedBadgeStudio = readChunkedBadgeStudioFragment();
            chunkedOfficialLoadout = readChunkedOfficialLoadoutFragment();
            chunkedFitsActions = readChunkedFitsActionsFragment();
            chunkedNametagLogoColor = readChunkedNametagLogoColorFragment();
            homeQueueFragment = readHomeQueueFragment();
        } catch (IOException error) {
            System.err.println("[gomod] Chunked Locker resource unavailable: "
                    + error.getClass().getSimpleName());
            return;
        }

        Path indexChunk = staticDir.resolve("index.js");
        if (Files.isRegularFile(indexChunk)) {
            patchChunkFile(uiRoot, indexChunk, source -> transformHomeQueueIndex(source, homeQueueFragment));
        }
        patchChunkFile(uiRoot, staticDir.resolve("color-picker-option.js"),
                LunarLockerUiBundlePatcher::transformChunkedSliderFormatting);
        Path scrollFile = findChunkedFile(staticDir, "window-base.js",
                "minWidth:\"275px\",flex:\"1 1 auto\",alignItems:\"flex-start\",alignSelf:\"stretch\",flexDirection:\"column\",gap:\"var(--spacings-radii-3)\"");
        if (scrollFile != null) {
            patchChunkFile(uiRoot, scrollFile, LunarLockerUiBundlePatcher::transformChunkedCosmeticSettingsScroll);
        }
        Path mainChunk = findChunkedLockerMainFile(staticDir);
        if (mainChunk != null) {
            patchChunkFile(uiRoot, mainChunk, source -> {
                String current = applyOptionalChunkStage(uiRoot, mainChunk, "cosmetic-appearance", currentOr(source),
                        s -> transformChunkedCosmeticAppearanceIndex5(s, chunkedFragment));
                current = applyOptionalChunkStage(uiRoot, mainChunk, "locker-extras", current,
                        s -> transformChunkedLockerExtrasIndex5(s, chunkedExtras));
                current = applyOptionalChunkStage(uiRoot, mainChunk, "badge-studio", current,
                        s -> transformChunkedBadgeStudioIndex5(s, chunkedBadgeStudio));
                current = applyOptionalChunkStage(uiRoot, mainChunk, "official-loadout", current,
                        s -> transformChunkedOfficialLoadoutIndex5(s, chunkedOfficialLoadout));
                current = applyOptionalChunkStage(uiRoot, mainChunk, "fits-actions", current,
                        s -> transformChunkedFitsActionsIndex5(s, chunkedFitsActions));
                current = applyOptionalChunkStage(uiRoot, mainChunk, "nametag-logo-color", current,
                        s -> transformChunkedNametagLogoColorIndex5(s, chunkedNametagLogoColor));
                return current;
            });
        }
    }

    private static String currentOr(String value) {
        return value;
    }

    private static String applyOptionalChunkStage(Path uiRoot, Path file, String stageName,
                                                  String source, BundleTransform transform) {
        try {
            return transform.apply(source);
        } catch (UnsupportedBundleException error) {
            System.out.println("[gomod] Locker chunk stage skipped (" + stageName + "): "
                    + uiRoot.relativize(file).toString().replace('\\', '/')
                    + " reason=" + error.getMessage());
            return source;
        }
    }

    private static String readChunkedFitsActionsFragment() throws IOException {
        return readChunkedResource(CHUNKED_FITS_ACTIONS_RESOURCE, "GomodFitsActionsCreate=",
                CHUNKED_FITS_ACTIONS_MARKER, "chunked fits actions UI fragment");
    }

    private static String readChunkedNametagLogoColorFragment() throws IOException {
        return readChunkedResource(CHUNKED_NAMETAG_LOGO_COLOR_RESOURCE, "GomodNametagLogoColorCreate=",
                CHUNKED_NAMETAG_LOGO_COLOR_MARKER, "chunked nametag logo color UI fragment");
    }

    private static String readHomeQueueFragment() throws IOException {
        return readChunkedResource(HOME_QUEUE_RESOURCE, "const GomodHomeQueueVersion=",
                HOME_QUEUE_MARKER, "home queue UI fragment");
    }

    private static String readChunkedOfficialLoadoutFragment() throws IOException {
        return readChunkedResource(CHUNKED_OFFICIAL_LOADOUT_RESOURCE, "GomodOfficialLoadoutCreate=",
                CHUNKED_OFFICIAL_LOADOUT_MARKER, "chunked official loadout UI fragment");
    }

    private static String readChunkedBadgeStudioFragment() throws IOException {
        return readChunkedResource(CHUNKED_BADGE_STUDIO_RESOURCE, "GomodBadgeStudioCreate=",
                CHUNKED_BADGE_STUDIO_MARKER, "chunked badge studio UI fragment");
    }

    private static String readChunkedLockerExtrasFragment() throws IOException {
        return readChunkedResource(CHUNKED_EXTRAS_RESOURCE, "GomodLockerExtrasCreate=",
                CHUNKED_LOCKER_EXTRAS_MARKER, "chunked locker extras UI fragment");
    }

    private static String readChunkedAppearanceFragment() throws IOException {
        return readChunkedResource(CHUNKED_APPEARANCE_RESOURCE, "GomodCosmeticAppearanceCreate=",
                CHUNKED_APPEARANCE_MARKER, "chunked appearance UI fragment");
    }

    private static String readChunkedResource(String resource, String expectedPrefix,
                                              String expectedMarker, String label) throws IOException {
        InputStream stream = LunarLockerUiBundlePatcher.class.getResourceAsStream(resource);
        if (stream == null) {
            throw new IOException("missing " + label);
        }
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                output.write(buffer, 0, read);
            }
            String fragment = new String(output.toByteArray(), StandardCharsets.UTF_8).trim();
            if (!fragment.startsWith(expectedPrefix) || !fragment.contains(expectedMarker)) {
                throw new IOException("invalid " + label);
            }
            return fragment;
        }
    }

    private interface BundleTransform {
        String apply(String source);
    }

    private static void patchChunkFile(Path uiRoot, Path file, BundleTransform transform) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            byte[] originalBytes = Files.readAllBytes(file);
            boolean hasUtf8Bom = originalBytes.length >= 3
                    && (originalBytes[0] & 0xFF) == 0xEF
                    && (originalBytes[1] & 0xFF) == 0xBB
                    && (originalBytes[2] & 0xFF) == 0xBF;
            int offset = hasUtf8Bom ? 3 : 0;
            String original = new String(originalBytes, offset,
                    originalBytes.length - offset, StandardCharsets.UTF_8);
            String patched = transform.apply(original);
            if (patched.equals(original)) {
                return;
            }
            byte[] body = patched.getBytes(StandardCharsets.UTF_8);
            byte[] replacement = hasUtf8Bom ? withUtf8Bom(body) : body;
            replaceWithBackup(uiRoot, file, originalBytes, replacement);
            System.out.println("[gomod] Locker chunk compatibility patch applied: "
                    + uiRoot.relativize(file).toString().replace('\\', '/'));
        } catch (UnsupportedBundleException error) {
            System.out.println("[gomod] Locker chunk patch skipped (unsupported chunk): "
                    + uiRoot.relativize(file).toString().replace('\\', '/')
                    + " reason=" + error.getMessage());
        } catch (Throwable error) {
            System.err.println("[gomod] Locker chunk patch failed safely: "
                    + uiRoot.relativize(file).toString().replace('\\', '/')
                    + " error=" + error.getClass().getSimpleName());
        }
    }

    private static String readUtf8File(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        int offset = bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    static String transformChunkedSliderFormatting(String source) {
        if (Pattern.compile("useRef\\([A-Za-z0-9_$]+\\.format\\|\\|\\(").matcher(source).find()
                && Pattern.compile("=>[A-Za-z0-9_$]+\\.format\\?[A-Za-z0-9_$]+\\.format\\(")
                        .matcher(source).find()) {
            return source;
        }
        Matcher matcher = CHUNKED_SLIDER_PATTERN.matcher(source);
        if (!matcher.find()) {
            throw new UnsupportedBundleException("chunked slider formatter signature was missing");
        }
        String matched = matcher.group(0);
        String sliderVar = matcher.group(1);
        String forwardRefObj = matcher.group(2);
        String propsArg = matcher.group(3);
        String refArg = matcher.group(4);
        String refA = matcher.group(5);
        String hookObj = matcher.group(6);
        String refO = matcher.group(7);
        String refFmt = matcher.group(8);
        String innerA = matcher.group(9);
        String dragRef = matcher.group(10);
        String fnName = matcher.group(11);
        String innerB = matcher.group(12);
        if (matcher.find()) {
            throw new UnsupportedBundleException("chunked slider formatter signature was ambiguous");
        }
        String replacement = sliderVar + "=" + forwardRefObj + ".forwardRef((" + propsArg + "," + refArg + ")=>{"
                + "const " + refA + "=" + hookObj + ".useRef(null),"
                + refO + "=" + hookObj + ".useRef(null),"
                + refFmt + "=" + hookObj + ".useRef(" + propsArg + ".format||(" + innerA + "=>" + innerA + ".toFixed(2))),"
                + dragRef + "=" + hookObj + ".useRef(!1),"
                + fnName + "=" + innerB + "=>" + propsArg + ".format?" + propsArg + ".format(" + innerB + "):" + innerB + ".toFixed(2)";
        return replaceUnique(source, matched, replacement, "chunked Locker slider formatter");
    }

    static String transformChunkedCosmeticSettingsScroll(String source) {
        if (source.contains(CHUNKED_SCROLL_STYLE_SUFFIX)) {
            return source;
        }
        String anchor = "minWidth:\"275px\",flex:\"1 1 auto\",alignItems:\"flex-start\","
                + "alignSelf:\"stretch\",flexDirection:\"column\",gap:\"var(--spacings-radii-3)\"";
        return replaceUnique(source, anchor, anchor + CHUNKED_SCROLL_STYLE_SUFFIX,
                "chunked Cosmetic Settings scroll container");
    }

    static String transformChunkedCosmeticAppearanceIndex5(String source, String fragment) {
        boolean hasAppearanceRoute = source.contains("title:\"Appearance\",children:");
        if (source.contains(CHUNKED_APPEARANCE_MARKER)
                && source.contains("GomodCosmeticAppearanceSettings=GomodCosmeticAppearanceCreate(")
                && hasAppearanceRoute
                && Pattern.compile("fitId:([A-Za-z0-9_$]+)&&\\1\\.id").matcher(source).find()) {
            return source;
        }
        boolean upgradingFragment = source.contains("GomodCosmeticAppearanceCreate=")
                && source.contains("GomodCosmeticAppearanceSettings=GomodCosmeticAppearanceCreate(")
                && hasAppearanceRoute;
        if (source.contains("GomodCosmeticAppearanceSettings=") && !upgradingFragment) {
            throw new UnsupportedBundleException("partially patched chunked Cosmetic Appearance detected");
        }

        Matcher finishMatcher = CHUNKED_INDEX5_FINISH_BUTTON_PATTERN.matcher(source);
        if (!finishMatcher.find()) {
            throw new UnsupportedBundleException("chunked Locker button component signature was missing");
        }
        String buttonVar = finishMatcher.group(2);
        if (finishMatcher.find()) {
            throw new UnsupportedBundleException("chunked Locker button component signature was ambiguous");
        }

        Matcher compMatcher = CHUNKED_INDEX5_SETTINGS_COMPONENT_PATTERN.matcher(source);
        if (!compMatcher.find()) {
            throw new UnsupportedBundleException("chunked Cosmetic Settings component signature was missing");
        }
        String compDecl = compMatcher.group(0);
        String cosmeticVar = compMatcher.group(3);
        String reactVar = compMatcher.group(6);
        String optionsVar = compMatcher.group(7);
        String outfitVar = compMatcher.group(8);
        if (compMatcher.find()) {
            throw new UnsupportedBundleException("chunked Cosmetic Settings component signature was ambiguous");
        }

        Matcher settingsMatcher = CHUNKED_INDEX5_COSMETIC_SETTINGS_PATTERN.matcher(source);
        if (!settingsMatcher.find()) {
            throw new UnsupportedBundleException("chunked Cosmetic Settings section signature was missing");
        }
        String settingsAnchor = settingsMatcher.group(0);
        String jsxVar = settingsMatcher.group(1);
        String sectionVar = settingsMatcher.group(2);
        if (settingsMatcher.find()) {
            throw new UnsupportedBundleException("chunked Cosmetic Settings section signature was ambiguous");
        }

        if (!source.contains("from\"./color-picker-option.js\";")) {
            source = replaceUnique(source, "from\"./index.js\";",
                    "from\"./index.js\";import{R as GomodLockerSlider}from\"./color-picker-option.js\";",
                    "chunked slider import");
        }

        String normalizedFragment = fragment.trim();
        while (normalizedFragment.endsWith(",")) {
            normalizedFragment = normalizedFragment.substring(0, normalizedFragment.length() - 1).trim();
        }
        String injectedDecl = "," + normalizedFragment
                + ",GomodCosmeticAppearanceSettings=GomodCosmeticAppearanceCreate("
                + reactVar + "," + jsxVar + "," + buttonVar + ",GomodLockerSlider)" + compDecl;
        if (upgradingFragment) {
            int existingStart = source.indexOf(",GomodCosmeticAppearanceCreate=");
            int existingEnd = existingStart < 0 ? -1 : source.indexOf(compDecl, existingStart);
            if (existingStart < 0 || existingEnd <= existingStart || existingEnd - existingStart > 32768) {
                throw new UnsupportedBundleException("chunked Cosmetic Appearance upgrade boundaries changed");
            }
            source = source.substring(0, existingStart) + injectedDecl + source.substring(existingEnd + compDecl.length());
        } else {
            source = replaceUnique(source, compDecl, injectedDecl, "chunked Cosmetic Appearance component");
        }

        String fitIdExpr = "fitId:" + outfitVar + "&&" + outfitVar + ".id";
        if (!hasAppearanceRoute) {
            String replacementRoute = jsxVar + ".jsx(" + sectionVar
                    + ",{title:\"Appearance\",children:" + jsxVar
                    + ".jsx(GomodCosmeticAppearanceSettings,{cosmeticId:" + cosmeticVar
                    + ".id,cosmeticName:" + cosmeticVar + ".name,cosmeticType:" + cosmeticVar
                    + ".type," + fitIdExpr + "})}),"
                    + optionsVar + ".length>0&&" + settingsAnchor;
            source = replaceUnique(source, settingsAnchor, replacementRoute, "chunked Cosmetic Appearance route");
        }

        if (!source.contains(CHUNKED_APPEARANCE_MARKER)
                || !source.contains(fitIdExpr)
                || !source.contains("title:\"Appearance\"")) {
            throw new UnsupportedBundleException("chunked Cosmetic Appearance transform failed validation");
        }
        return source;
    }

    static String transformChunkedLockerExtrasIndex5(String source, String extrasFragment) {
        boolean hasHelpers = source.contains(CHUNKED_LOCKER_EXTRAS_MARKER)
                && source.contains("GomodCosmeticOwnershipBadge=GomodLockerExtras.GomodCosmeticOwnershipBadge")
                && source.contains("GomodResetContextItem=GomodLockerExtras.GomodResetContextItem")
                && source.contains("GomodIsCosmeticEdited=GomodLockerExtras.GomodIsCosmeticEdited")
                && source.contains("GomodCosmeticSortSubscribe=GomodLockerExtras.GomodCosmeticSortSubscribe")
                && source.contains("GomodEditedItemsRefresh=GomodLockerExtras.GomodEditedItemsRefresh");
        boolean hasMenu = source.contains("kind:\"cosmetic\",id:") && source.contains("GomodResetContextItem");
        boolean hasCosmeticBadge = source.contains("GomodCosmeticOwnershipBadge,{cosmeticId:");
        boolean hasLayer = source.contains(CHUNKED_OVERLAY_LAYER_ANCHOR + ",zIndex:20");
        boolean hasEditedSort = source.contains(CHUNKED_EDITED_SORT_MARKER)
                && source.contains("GomodCosmeticSortSubscribe(");
        if (hasHelpers && hasMenu && hasCosmeticBadge && hasLayer && hasEditedSort) {
            return source;
        }

        Matcher tooltipMatcher = CHUNKED_TOOLTIP_PATTERN.matcher(source);
        if (!tooltipMatcher.find()) {
            throw new UnsupportedBundleException("chunked cosmetic tooltip signature was missing");
        }
        String jsxVar = tooltipMatcher.group(1);
        String tooltipVar = tooltipMatcher.group(2);
        String tooltipContentVar = tooltipMatcher.group(3);
        String textVar = tooltipMatcher.group(4);
        if (tooltipMatcher.find()) {
            throw new UnsupportedBundleException("chunked cosmetic tooltip signature was ambiguous");
        }

        Matcher menuMatcher = CHUNKED_COSMETIC_MENU_PATTERN.matcher(source);
        if (!menuMatcher.find()) {
            throw new UnsupportedBundleException("chunked cosmetic context menu signature was missing");
        }
        String menuComp = menuMatcher.group(1);
        String menuCosmeticArg = menuMatcher.group(2);
        String menuEquippedArg = menuMatcher.group(3);
        String menuLockedArg = menuMatcher.group(4);
        String menuCtaArg = menuMatcher.group(5);
        String menuOnSettingsArg = menuMatcher.group(6);
        String menuJsxVar = menuMatcher.group(7);
        String menuItemComp = menuMatcher.group(8);
        String menuSettingsCall = menuMatcher.group(9);
        if (menuMatcher.find() || !jsxVar.equals(menuJsxVar)) {
            throw new UnsupportedBundleException("chunked cosmetic context menu signature was ambiguous");
        }

        Matcher wrapperMatcher = CHUNKED_COSMETIC_MENU_WRAPPER_PATTERN.matcher(source);
        if (!wrapperMatcher.find()) {
            throw new UnsupportedBundleException("chunked cosmetic context wrapper signature was missing");
        }
        String visStateVar = wrapperMatcher.group(8);
        String reactVar = wrapperMatcher.group(10);
        String wrapperMenuCall = wrapperMatcher.group(11);
        String wrapperJsxVar = wrapperMatcher.group(13);
        String wrapperMenuComp = wrapperMatcher.group(14);
        if (wrapperMatcher.find() || !jsxVar.equals(wrapperJsxVar) || !menuComp.equals(wrapperMenuComp)) {
            throw new UnsupportedBundleException("chunked cosmetic context wrapper signature was ambiguous");
        }

        if (!hasMenu) {
            String menuParams = menuComp + "=({cosmetic:" + menuCosmeticArg + ",isEquipped:" + menuEquippedArg
                    + ",lockedDiscordRewardText:" + menuLockedArg + ",onDiscordRewardCta:" + menuCtaArg
                    + ",onSettings:" + menuOnSettingsArg + "})=>";
            String visibleMenuParams = menuComp + "=({cosmetic:" + menuCosmeticArg + ",isEquipped:" + menuEquippedArg
                    + ",lockedDiscordRewardText:" + menuLockedArg + ",onDiscordRewardCta:" + menuCtaArg
                    + ",onSettings:" + menuOnSettingsArg + ",visible:gomodVisible})=>";
            source = replaceUnique(source, menuParams, visibleMenuParams, "chunked cosmetic context visible prop");

            String resetCall = menuLockedArg + "==null&&" + jsxVar + ".jsx(GomodResetContextItem,{kind:\"cosmetic\",id:"
                    + menuCosmeticArg + ".id,visible:gomodVisible}),";
            source = replaceUnique(source, menuSettingsCall, resetCall + menuSettingsCall,
                    "chunked cosmetic Reset context item");

            source = replaceUnique(source, wrapperMenuCall,
                    wrapperMenuCall + "visible:" + visStateVar + ".visible,",
                    "chunked cosmetic Reset context visibility");
        }

        if (!hasCosmeticBadge) {
            Matcher headerMatcher = CHUNKED_COSMETIC_CARD_HEADER_PATTERN.matcher(source);
            if (!headerMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic card signature was missing");
            }
            String cardCosVar = headerMatcher.group(1);
            String cardTopRightVar = headerMatcher.group(2);
            int headerEnd = headerMatcher.end();
            int scanEnd = Math.min(source.length(), headerEnd + 4096);
            if (headerMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic card signature was ambiguous");
            }
            String cardWindow = source.substring(headerEnd, scanEnd);
            Matcher cardAnchorMatcher = Pattern.compile(
                    "topLeft:([A-Za-z0-9_$]+),topRight:" + Pattern.quote(cardTopRightVar)
                            + ",children:" + Pattern.quote(cardCosVar) + "\\.display==null\\?")
                    .matcher(cardWindow);
            if (!cardAnchorMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic card topRight anchor was missing");
            }
            String cardAnchor = cardAnchorMatcher.group(0);
            String cardTopLeftVar = cardAnchorMatcher.group(1);
            if (cardAnchorMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic card topRight anchor was ambiguous");
            }
            String wrappedTopRight = "topLeft:" + cardTopLeftVar + ",topRight:" + jsxVar + ".jsxs(" + jsxVar
                    + ".Fragment,{children:[" + cardTopRightVar + "," + jsxVar
                    + ".jsx(GomodCosmeticOwnershipBadge,{cosmeticId:" + cardCosVar
                    + ".id})]}),children:" + cardCosVar + ".display==null?";
            source = replaceUnique(source, cardAnchor, wrappedTopRight, "chunked cosmetic source badge");
        }

        if (!hasLayer) {
            source = replaceUnique(source, CHUNKED_OVERLAY_LAYER_ANCHOR,
                    CHUNKED_OVERLAY_LAYER_ANCHOR + ",zIndex:20",
                    "chunked cosmetic source badge overlay stacking");
        }

        if (!source.contains("GomodCosmeticOwnershipBadge,{emoteId:")) {
            Matcher emoteMatcher = CHUNKED_EMOTE_TILE_PATTERN.matcher(source);
            if (emoteMatcher.find()) {
                String emoteEntry = emoteMatcher.group(1);
                String emoteVar = emoteMatcher.group(3);
                if (!emoteMatcher.find()) {
                    String emoteBadge = "topRight:" + jsxVar + ".jsx(GomodCosmeticOwnershipBadge,{emoteId:"
                            + emoteVar + ".emote.id,isFreeLunarPlus:" + emoteVar + ".isFreeLunarPlus,"
                            + "emoteLimited:" + emoteVar + ".emote.expireTime!=null&&" + emoteVar
                            + ".emote.expireTime>0&&" + emoteVar + ".emote.expiresIn!=null}),";
                    source = replaceUnique(source, emoteEntry, emoteBadge + emoteEntry,
                            "chunked emote source badge");
                }
            }
        }

        if (!source.contains("GomodCosmeticOwnershipBadge,{badgeId:")) {
            Matcher studioBadgeMatcher = Pattern.compile(
                    "topRight:" + Pattern.quote(jsxVar) + "\\.jsx\\(GomodStudioLauncher,\\{kind:\"badge\",item:([A-Za-z0-9_$]+)\\.badge\\}\\)")
                    .matcher(source);
            if (studioBadgeMatcher.find()) {
                String studioTopRight = studioBadgeMatcher.group(0);
                String studioItemVar = studioBadgeMatcher.group(1);
                if (!studioBadgeMatcher.find()) {
                    source = replaceUnique(source, studioTopRight,
                            "topRight:" + jsxVar + ".jsxs(" + jsxVar + ".Fragment,{children:["
                                    + jsxVar + ".jsx(GomodCosmeticOwnershipBadge,{badgeId:" + studioItemVar + ".badge.id}),"
                                    + jsxVar + ".jsx(GomodStudioLauncher,{kind:\"badge\",item:" + studioItemVar + ".badge})]})",
                            "chunked badge source icon with studio");
                }
            } else {
                Matcher badgeMatcher = CHUNKED_BADGE_TILE_PATTERN.matcher(source);
                if (badgeMatcher.find()) {
                    String badgeTopLeft = badgeMatcher.group(1);
                    String badgeVar = badgeMatcher.group(2);
                    String badgeJsx = badgeMatcher.group(3);
                    String badgeChildren = badgeMatcher.group(5);
                    if (!badgeMatcher.find() && jsxVar.equals(badgeJsx)) {
                        String badgeAnchor = badgeTopLeft + badgeChildren;
                        String badgeReplacement = badgeTopLeft + "topRight:" + jsxVar
                                + ".jsx(GomodCosmeticOwnershipBadge,{badgeId:" + badgeVar + ".badge.id}),"
                                + badgeChildren;
                        source = replaceUnique(source, badgeAnchor, badgeReplacement, "chunked badge source icon");
                    }
                }
            }
        }

        if (source.contains("md:{height:\"225px\",minWidth:\"185px\"}")) {
            source = replaceUnique(source, "md:{height:\"225px\",minWidth:\"185px\"}",
                    "md:{height:\"225px\",minWidth:\"175px\"}", "chunked cosmetic tile minimum width");
        }

        if (!hasEditedSort) {
            String newFirstMarker = "{id:\"new-first\",label:\"New first\",shortLabel:\"NEW\",";
            int newFirstIdx = source.indexOf(newFirstMarker);
            if (newFirstIdx < 0 || source.indexOf(newFirstMarker, newFirstIdx + newFirstMarker.length()) >= 0) {
                throw new UnsupportedBundleException("chunked cosmetic New first sort anchor was missing or ambiguous");
            }
            int arrScanEnd = Math.min(source.length(), newFirstIdx + 900);
            String sortWindow = source.substring(newFirstIdx, arrScanEnd);
            Matcher sortTailMatcher = CHUNKED_COSMETIC_SORT_TAIL_PATTERN.matcher(sortWindow);
            if (!sortTailMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic sort array tail was missing");
            }
            String tailAnchor = sortTailMatcher.group(0);
            String tailEntry = sortTailMatcher.group(1);
            String tailComma = sortTailMatcher.group(2);
            if (sortTailMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic sort array tail was ambiguous");
            }
            String editedSortOption = ",{id:\"edited-first\",label:\"Edited first\",shortLabel:\"EDIT\","
                    + "icon:" + jsxVar + ".jsx(\"svg\",{width:\"16\",height:\"16\",viewBox:\"0 0 16 16\",fill:\"none\","
                    + "children:" + jsxVar + ".jsx(\"path\",{d:\"M11.8 1.8a1.5 1.5 0 0 1 2.12 0l.28.28a1.5 1.5 0 0 1 0 2.12L5.9 12.5 2.5 13.5l1-3.4 8.3-8.3Z\","
                    + "stroke:\"currentColor\",strokeWidth:\"1.4\",strokeLinecap:\"round\",strokeLinejoin:\"round\"})}),"
                    + "sort:(e,n)=>{const gomodLeft=GomodIsCosmeticEdited(e.id),gomodRight=GomodIsCosmeticEdited(n.id);"
                    + "return gomodLeft!==gomodRight?gomodLeft?-1:1:e.isFavorite&&!n.isFavorite?-1:!e.isFavorite&&n.isFavorite?1:e.name.localeCompare(n.name)}}";
            source = replaceUnique(source, tailAnchor, tailEntry + editedSortOption + "]" + tailComma,
                    "chunked cosmetic Edited first sort option");

            Matcher cmpMatcher = CHUNKED_COSMETIC_SORT_COMPARATOR_PATTERN.matcher(source);
            if (!cmpMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic sort comparator was missing");
            }
            String cmpOrig = cmpMatcher.group(0);
            String selFn = cmpMatcher.group(1);
            String cmpFn = cmpMatcher.group(5);
            String leftArg = cmpMatcher.group(6);
            String rightArg = cmpMatcher.group(7);
            if (cmpMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic sort comparator was ambiguous");
            }
            String cmpHead = cmpFn + "=(" + leftArg + "," + rightArg + ")=>{";
            int headOffset = cmpOrig.indexOf(cmpHead);
            if (headOffset < 0) {
                throw new UnsupportedBundleException("chunked cosmetic sort comparator head changed");
            }
            String cmpPatched = cmpOrig.substring(0, headOffset + cmpHead.length())
                    + "if(" + selFn + "().id===\"edited-first\"){const gomodLeft=GomodIsCosmeticEdited(" + leftArg
                    + ".id),gomodRight=GomodIsCosmeticEdited(" + rightArg + ".id);if(gomodLeft!==gomodRight)return gomodLeft?-1:1}"
                    + cmpOrig.substring(headOffset + cmpHead.length());
            source = replaceUnique(source, cmpOrig, cmpPatched, "chunked cosmetic Edited first comparator");

            Matcher applyMatcher = CHUNKED_COSMETIC_SORT_APPLY_PATTERN.matcher(source);
            if (!applyMatcher.find()) {
                throw new UnsupportedBundleException("chunked cosmetic sort apply callback was missing");
            }
            String applyOrig = applyMatcher.group(0);
            String applyFn = applyMatcher.group(1);
            String applyReact = applyMatcher.group(2);
            String applyArg = applyMatcher.group(3);
            String sortRefVar = applyMatcher.group(4);
            String masterRefVar = applyMatcher.group(5);
            String sortCmpFn = applyMatcher.group(6);
            String setActiveFn = applyMatcher.group(7);
            String filteredRefVar = applyMatcher.group(8);
            String seenFn = applyMatcher.group(9);
            if (applyMatcher.find() || !reactVar.equals(applyReact)) {
                throw new UnsupportedBundleException("chunked cosmetic sort apply callback was ambiguous");
            }
            String applyPatched = applyFn + "=" + applyReact + ".useCallback(" + applyArg + "=>{"
                    + sortRefVar + ".current=" + applyArg + ","
                    + applyArg + "===\"edited-first\"&&GomodEditedItemsRefresh(),"
                    + masterRefVar + ".current=" + masterRefVar + ".current.sort(" + sortCmpFn + "),"
                    + setActiveFn + "([..." + filteredRefVar + ".current].sort(" + sortCmpFn + ")),"
                    + seenFn + "()},[" + setActiveFn + "," + seenFn + "," + masterRefVar + "]);"
                    + applyReact + ".useEffect(()=>GomodCosmeticSortSubscribe(()=>{"
                    + sortRefVar + ".current===\"edited-first\"&&("
                    + masterRefVar + ".current=" + masterRefVar + ".current.sort(" + sortCmpFn + "),"
                    + setActiveFn + "([..." + filteredRefVar + ".current].sort(" + sortCmpFn + ")))"
                    + "}),[" + setActiveFn + "," + masterRefVar + "]);return{pageEmptyReason:";
            source = replaceUnique(source, applyOrig, applyPatched, "chunked cosmetic Edited first live sort");
        }

        if (!hasHelpers) {
            String normalizedExtras = extrasFragment.trim();
            while (normalizedExtras.endsWith(",")) {
                normalizedExtras = normalizedExtras.substring(0, normalizedExtras.length() - 1).trim();
            }
            String extrasDecl = "const " + normalizedExtras
                    + ",GomodLockerExtras=GomodLockerExtrasCreate("
                    + reactVar + "," + jsxVar + "," + tooltipVar + ","
                    + tooltipContentVar + "," + textVar + "," + menuItemComp + "),"
                    + "GomodCosmeticOwnershipBadge=GomodLockerExtras.GomodCosmeticOwnershipBadge,"
                    + "GomodResetContextItem=GomodLockerExtras.GomodResetContextItem,"
                    + "GomodIsCosmeticEdited=GomodLockerExtras.GomodIsCosmeticEdited,"
                    + "GomodCosmeticSortSubscribe=GomodLockerExtras.GomodCosmeticSortSubscribe,"
                    + "GomodEditedItemsRefresh=GomodLockerExtras.GomodEditedItemsRefresh;";
            String oldDeclStart = "const GomodLockerExtrasCreate=";
            int existingStart = source.indexOf(oldDeclStart);
            int existingEnd = -1;
            int existingEndLen = 0;
            if (existingStart >= 0) {
                for (String endMarker : new String[] {
                        "GomodEditedItemsRefresh=GomodLockerExtras.GomodEditedItemsRefresh;",
                        "GomodResetContextItem=GomodLockerExtras.GomodResetContextItem;"
                }) {
                    int candidateEnd = source.indexOf(endMarker, existingStart);
                    if (candidateEnd > existingStart) {
                        existingEnd = candidateEnd;
                        existingEndLen = endMarker.length();
                        break;
                    }
                }
            }
            if (existingStart >= 0 && existingEnd > existingStart) {
                source = source.substring(0, existingStart) + extrasDecl
                        + source.substring(existingEnd + existingEndLen);
            } else if (source.contains(";export{")) {
                source = replaceUnique(source, ";export{", ";" + extrasDecl + "export{",
                        "chunked Locker extras export anchor");
            } else {
                source = source + "\n" + extrasDecl;
            }
        }

        if (!source.contains(CHUNKED_LOCKER_EXTRAS_MARKER)
                || !source.contains("GomodCosmeticOwnershipBadge,{cosmeticId:")
                || !source.contains("kind:\"cosmetic\",id:")
                || !source.contains("right:0,top:0,zIndex:20")
                || !source.contains(CHUNKED_EDITED_SORT_MARKER)
                || !source.contains("GomodCosmeticSortSubscribe(")) {
            throw new UnsupportedBundleException("chunked Locker extras transform failed validation");
        }
        return source;
    }

    static String transformChunkedBadgeStudioIndex5(String source, String studioFragment) {
        boolean hasHelpers = source.contains(CHUNKED_BADGE_STUDIO_MARKER)
                && source.contains("GomodUseBadgeFilter=GomodBadgeStudio.GomodUseBadgeFilter")
                && source.contains("GomodCustomBadgeImage=GomodBadgeStudio.GomodCustomBadgeImage")
                && source.contains("GomodStudioLauncher=GomodBadgeStudio.GomodStudioLauncher")
                && source.contains("GomodLockerItemContext=GomodBadgeStudio.GomodLockerItemContext");
        boolean hasFilter = source.contains("GomodUseBadgeFilter(")
                && source.contains("GomodCustomBadgeImage,{badgeId:");
        boolean isNametagBadges = source.contains("gui.nametag.badge");
        boolean hasTileStudio = source.contains("GomodLockerItemContext,{kind:\"badge\"")
                && (isNametagBadges
                        ? !source.contains("GomodStudioLauncher,{kind:\"badge\"")
                        : source.contains("GomodStudioLauncher,{kind:\"badge\""));
        if (hasHelpers && hasFilter && hasTileStudio) {
            return source;
        }

        Matcher tooltipMatcher = CHUNKED_TOOLTIP_PATTERN.matcher(source);
        if (!tooltipMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio tooltip signature was missing");
        }
        String jsxVar = tooltipMatcher.group(1);
        String tooltipVar = tooltipMatcher.group(2);
        String tooltipContentVar = tooltipMatcher.group(3);
        String textVar = tooltipMatcher.group(4);
        if (tooltipMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio tooltip signature was ambiguous");
        }

        Matcher finishMatcher = CHUNKED_INDEX5_FINISH_BUTTON_PATTERN.matcher(source);
        if (!finishMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio button signature was missing");
        }
        String buttonJsxVar = finishMatcher.group(1);
        String buttonVar = finishMatcher.group(2);
        if (finishMatcher.find() || !jsxVar.equals(buttonJsxVar)) {
            throw new UnsupportedBundleException("chunked badge studio button signature was ambiguous");
        }

        Matcher compMatcher = CHUNKED_INDEX5_SETTINGS_COMPONENT_PATTERN.matcher(source);
        if (!compMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio react signature was missing");
        }
        String reactVar = compMatcher.group(6);
        if (compMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio react signature was ambiguous");
        }

        Matcher modalMatcher = CHUNKED_MODAL_PATTERN.matcher(source);
        if (!modalMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio modal signature was missing");
        }
        String modalJsxVar = modalMatcher.group(1);
        String modalVar = modalMatcher.group(2);
        if (modalMatcher.find() || !jsxVar.equals(modalJsxVar)) {
            throw new UnsupportedBundleException("chunked badge studio modal signature was ambiguous");
        }

        Matcher iconMatcher = CHUNKED_ICON_RENDER_PATTERN.matcher(source);
        if (!iconMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio icon signature was missing");
        }
        String renderIconVar = iconMatcher.group(1);
        String iconEnumVar = iconMatcher.group(2);
        if (iconMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio icon signature was ambiguous");
        }

        Matcher menuMatcher = CHUNKED_COSMETIC_MENU_PATTERN.matcher(source);
        if (!menuMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio menu signature was missing");
        }
        String menuCosArg = menuMatcher.group(2);
        String menuEqArg = menuMatcher.group(3);
        String menuJsxVar = menuMatcher.group(7);
        String menuItemComp = menuMatcher.group(8);
        String menuBlock = menuMatcher.group(0);
        if (menuMatcher.find() || !jsxVar.equals(menuJsxVar)) {
            throw new UnsupportedBundleException("chunked badge studio menu signature was ambiguous");
        }

        String quotedJsx = Pattern.quote(jsxVar);
        Matcher menuDetailsMatcher = Pattern.compile(
                quotedJsx + "\\.jsx\\(" + Pattern.quote(menuItemComp)
                        + ",\\{title:" + Pattern.quote(menuCosArg) + "\\.name,label:" + Pattern.quote(menuCosArg) + "\\.type\\}\\),"
                        + quotedJsx + "\\.jsx\\(([A-Za-z0-9_$]+),\\{\\}\\),.*?"
                        + "label:" + Pattern.quote(menuEqArg) + "\\?\"Unequip\":\"Equip\","
                        + "icon:" + Pattern.quote(menuEqArg) + "\\?" + quotedJsx + "\\.jsx\\(([A-Za-z0-9_$]+),\\{\\}\\):"
                        + quotedJsx + "\\.jsx\\(([A-Za-z0-9_$]+),\\{\\}\\),"
                        + "onClick:\\(\\)=>([A-Za-z0-9_$]+)\\.cosmetic\\.toggleCosmetic.*?"
                        + "label:\"Settings\",icon:" + quotedJsx + "\\.jsx\\(([A-Za-z0-9_$]+),\\{\\}\\)")
                .matcher(menuBlock);
        if (!menuDetailsMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio context icon signature was missing");
        }
        String dividerVar = menuDetailsMatcher.group(1);
        String unequipIconVar = menuDetailsMatcher.group(2);
        String equipIconVar = menuDetailsMatcher.group(3);
        String bridgeVar = menuDetailsMatcher.group(4);
        String settingsIconVar = menuDetailsMatcher.group(5);

        Matcher wrapperMatcher = CHUNKED_COSMETIC_MENU_WRAPPER_PATTERN.matcher(source);
        if (!wrapperMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio menu wrapper signature was missing");
        }
        String wrapperBlock = wrapperMatcher.group(0);
        if (wrapperMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio menu wrapper signature was ambiguous");
        }
        Matcher contextCompMatcher = Pattern.compile(
                quotedJsx + "\\.jsx\\(([A-Za-z0-9_$]+),\\{usePortal:!0,")
                .matcher(wrapperBlock);
        if (!contextCompMatcher.find()) {
            throw new UnsupportedBundleException("chunked badge studio ContextMenu signature was missing");
        }
        String contextMenuVar = contextCompMatcher.group(1);

        String badgeInnerImgVar;
        Matcher imgMatcher = CHUNKED_BADGE_IMAGE_PATTERN.matcher(source);
        Matcher styledImgMatcher = CHUNKED_BADGE_IMAGE_STYLED_PATTERN.matcher(source);
        if (imgMatcher.find()) {
            String imgOrig = imgMatcher.group(0);
            String outerComp = imgMatcher.group(1);
            String imgParam = imgMatcher.group(2);
            String imgJsx = imgMatcher.group(3);
            String divStyle = imgMatcher.group(4);
            badgeInnerImgVar = imgMatcher.group(5);
            String imgWidth = imgMatcher.group(6);
            String imgHeight = imgMatcher.group(7);
            if (imgMatcher.find() || !jsxVar.equals(imgJsx)) {
                throw new UnsupportedBundleException("chunked badge studio image component signature was ambiguous");
            }
            if (!hasFilter) {
                String imgReplacement = outerComp + "=" + imgParam + "=>{const gomodBadgeFilter=GomodUseBadgeFilter("
                        + imgParam + ".ownedBadge.badge.id);return " + imgJsx
                        + ".jsx(\"div\",{style:{filter:gomodBadgeFilter," + divStyle + "},"
                        + "children:" + imgJsx + ".jsx(GomodCustomBadgeImage,{badgeId:" + imgParam
                        + ".ownedBadge.badge.id,resource:" + imgParam + ".ownedBadge.badge.resource,"
                        + "alt:" + imgParam + ".ownedBadge.badge.name,animated:" + imgParam
                        + ".ownedBadge.badge.animated,width:" + imgWidth + ",height:" + imgHeight + "})})}";
                source = replaceUnique(source, imgOrig, imgReplacement, "chunked badge filter and image");
            }
        } else if (styledImgMatcher.find()) {
            String imgOrig = styledImgMatcher.group(0);
            String outerComp = styledImgMatcher.group(1);
            String imgParam = styledImgMatcher.group(2);
            String bodyPrefix = styledImgMatcher.group(3);
            String imgJsx = styledImgMatcher.group(4);
            String wrapComp = styledImgMatcher.group(5);
            badgeInnerImgVar = styledImgMatcher.group(6);
            String imgWidth = styledImgMatcher.group(7);
            String imgHeight = styledImgMatcher.group(8);
            if (styledImgMatcher.find() || !jsxVar.equals(imgJsx)) {
                throw new UnsupportedBundleException("chunked badge studio styled image component signature was ambiguous");
            }
            if (!hasFilter) {
                String imgReplacement = outerComp + "=" + imgParam + "=>{" + bodyPrefix
                        + "const gomodBadgeFilter=GomodUseBadgeFilter(" + imgParam + ".ownedBadge.badge.id);return "
                        + imgJsx + ".jsx(" + wrapComp + ",{style:{filter:gomodBadgeFilter},children:"
                        + imgJsx + ".jsx(GomodCustomBadgeImage,{badgeId:" + imgParam
                        + ".ownedBadge.badge.id,resource:" + imgParam + ".ownedBadge.badge.resource,"
                        + "alt:" + imgParam + ".ownedBadge.badge.name,animated:" + imgParam
                        + ".ownedBadge.badge.animated,width:" + imgWidth + ",height:" + imgHeight + "})})}";
                source = replaceUnique(source, imgOrig, imgReplacement, "chunked badge styled filter and image");
            }
        } else if (hasFilter) {
            Matcher existingImgCall = Pattern.compile(
                    "GomodBadgeStudio=GomodBadgeStudioCreate\\([^;)]+,([A-Za-z0-9_$]+)\\)")
                    .matcher(source);
            if (!existingImgCall.find()) {
                throw new UnsupportedBundleException("chunked badge studio inner image component reference was missing");
            }
            badgeInnerImgVar = existingImgCall.group(1);
        } else {
            throw new UnsupportedBundleException("chunked badge studio image component signature was missing");
        }

        if (!hasTileStudio) {
            Matcher tileMatcher = CHUNKED_BADGE_TILE_STUDIO_PATTERN.matcher(source);
            Matcher nametagTileMatcher = CHUNKED_BADGE_TILE_NAMETAG_PATTERN.matcher(source);
            if (tileMatcher.find()) {
                String tileOrig = tileMatcher.group(0);
                String tilePrefix = tileMatcher.group(1);
                String itemVar = tileMatcher.group(2);
                String idxVar = tileMatcher.group(4);
                String tileJsx = tileMatcher.group(5);
                String tileComp = tileMatcher.group(6);
                String equippedVar = tileMatcher.group(7);
                String tileMidProps = tileMatcher.group(8);
                String tileBridgeVar = tileMatcher.group(9);
                String existingTopRight = tileMatcher.group(10);
                String childrenProp = tileMatcher.group(11);
                String keyExpr = tileMatcher.group(12);
                if (tileMatcher.find() || !jsxVar.equals(tileJsx) || !bridgeVar.equals(tileBridgeVar)) {
                    throw new UnsupportedBundleException("chunked badge studio tile signature was ambiguous");
                }
                String topRightProp = existingTopRight != null
                        ? "topRight:" + tileJsx + ".jsxs(" + tileJsx + ".Fragment,{children:["
                        + tileJsx + ".jsx(GomodCosmeticOwnershipBadge,{badgeId:" + itemVar + ".badge.id}),"
                        + tileJsx + ".jsx(GomodStudioLauncher,{kind:\"badge\",item:" + itemVar + ".badge})]}),"
                        : "topRight:" + tileJsx + ".jsx(GomodStudioLauncher,{kind:\"badge\",item:" + itemVar + ".badge}),";
                String tileReplacement = tilePrefix
                        + tileJsx + ".jsx(GomodLockerItemContext,{kind:\"badge\",id:" + itemVar + ".badge.id,"
                        + "item:" + itemVar + ".badge,"
                        + "equipped:(" + equippedVar + "==null?void 0:" + equippedVar + ".id)==" + itemVar + ".badge.id,"
                        + "onEquip:()=>{(" + equippedVar + "==null?void 0:" + equippedVar + ".id)==" + itemVar + ".badge.id?"
                        + tileBridgeVar + ".badges.equip(-1):" + tileBridgeVar + ".badges.equip(" + itemVar + ".badge.id)},"
                        + "children:" + tileJsx + ".jsx(" + tileComp + ",{className:" + idxVar + "==0?\"badgesTour2\":void 0,"
                        + "name:" + itemVar + ".badge.name,equipped:(" + equippedVar + "==null?void 0:" + equippedVar + ".id)==" + itemVar + ".badge.id,"
                        + tileMidProps + topRightProp + childrenProp + ")}," + keyExpr + "):null";
                source = replaceUnique(source, tileOrig, tileReplacement, "chunked badge studio tile controls");
            } else if (nametagTileMatcher.find()) {
                String tileOrig = nametagTileMatcher.group(0);
                String tileFn = nametagTileMatcher.group(1);
                String itemVar = nametagTileMatcher.group(2);
                String lockedVar = nametagTileMatcher.group(3);
                String equippedVar = nametagTileMatcher.group(4);
                String onSelectVar = nametagTileMatcher.group(5);
                String tileJsx = nametagTileMatcher.group(6);
                String tileComp = nametagTileMatcher.group(7);
                String tooltipComp = nametagTileMatcher.group(8);
                String badgeImgCall = nametagTileMatcher.group(9);
                if (nametagTileMatcher.find() || !jsxVar.equals(tileJsx)) {
                    throw new UnsupportedBundleException("chunked badge studio nametag tile signature was ambiguous");
                }
                String wrappedChildren = tileJsx + ".jsxs(\"div\",{style:{position:\"relative\",width:\"100%\",height:\"100%\"},children:["
                        + badgeImgCall + ","
                        + tileJsx + ".jsx(\"div\",{style:{position:\"absolute\",top:\"2px\",right:\"2px\",zIndex:20,"
                        + "display:\"flex\",alignItems:\"center\"},children:"
                        + tileJsx + ".jsx(GomodCosmeticOwnershipBadge,{badgeId:" + itemVar + ".badge.id})})]})";
                String tileReplacement = tileFn + "=({ownedBadge:" + itemVar + ",locked:" + lockedVar
                        + ",equipped:" + equippedVar + ",onSelect:" + onSelectVar + "})=>"
                        + tileJsx + ".jsx(GomodLockerItemContext,{kind:\"badge\",id:" + itemVar + ".badge.id,item:"
                        + itemVar + ".badge,equipped:" + equippedVar + ",onEquip:()=>{" + equippedVar + "?"
                        + bridgeVar + ".badges.equip(-1):" + onSelectVar + "(" + itemVar + ".badge.id)},children:"
                        + tileJsx + ".jsx(" + tileComp + ",{label:" + itemVar + ".badge.name,size:\"lg\",tooltip:"
                        + tileJsx + ".jsx(" + tooltipComp + ",{ownedBadge:" + itemVar + ",locked:" + lockedVar + "}),"
                        + "selected:" + equippedVar + ",locked:" + lockedVar + ",onSelect:()=>" + onSelectVar
                        + "(" + itemVar + ".badge.id),children:" + wrappedChildren + "})})";
                source = replaceUnique(source, tileOrig, tileReplacement, "chunked badge studio nametag tile controls");
            } else if (isNametagBadges && source.contains("GomodLockerItemContext,{kind:\"badge\"")
                    && source.contains("GomodStudioLauncher,{kind:\"badge\"")) {
                source = source.replaceAll(
                        "," + Pattern.quote(jsxVar) + "\\.jsx\\(GomodStudioLauncher,\\{kind:\"badge\",item:[A-Za-z0-9_$]+\\.badge\\}\\)",
                        "");
            } else {
                throw new UnsupportedBundleException("chunked badge studio tile signature was missing");
            }
        }

        if (!source.contains("from\"./color-picker-option.js\";")) {
            source = replaceUnique(source, "from\"./index.js\";",
                    "from\"./index.js\";import{R as GomodLockerSlider}from\"./color-picker-option.js\";",
                    "chunked badge studio slider import");
        }

        if (!hasHelpers) {
            String normalizedStudio = studioFragment.trim();
            while (normalizedStudio.endsWith(",")) {
                normalizedStudio = normalizedStudio.substring(0, normalizedStudio.length() - 1).trim();
            }
            String studioDecl = "const " + normalizedStudio
                    + ",GomodBadgeStudio=GomodBadgeStudioCreate("
                    + reactVar + "," + jsxVar + "," + bridgeVar + ","
                    + modalVar + "," + buttonVar + ",GomodLockerSlider,"
                    + tooltipVar + "," + tooltipContentVar + "," + textVar + ","
                    + contextMenuVar + "," + menuItemComp + "," + dividerVar + ","
                    + unequipIconVar + "," + equipIconVar + "," + settingsIconVar + ","
                    + renderIconVar + "," + iconEnumVar + "," + badgeInnerImgVar + "),"
                    + "GomodUseBadgeFilter=GomodBadgeStudio.GomodUseBadgeFilter,"
                    + "GomodCustomBadgeImage=GomodBadgeStudio.GomodCustomBadgeImage,"
                    + "GomodStudioLauncher=GomodBadgeStudio.GomodStudioLauncher,"
                    + "GomodLockerItemContext=GomodBadgeStudio.GomodLockerItemContext;";
            String oldStudioStart = "const GomodBadgeStudioCreate=";
            String oldStudioEnd = "GomodLockerItemContext=GomodBadgeStudio.GomodLockerItemContext;";
            int existingStart = source.indexOf(oldStudioStart);
            int existingEnd = existingStart < 0 ? -1 : source.indexOf(oldStudioEnd, existingStart);
            if (existingStart >= 0 && existingEnd > existingStart) {
                source = source.substring(0, existingStart) + studioDecl
                        + source.substring(existingEnd + oldStudioEnd.length());
            } else if (source.contains(";export{")) {
                source = replaceUnique(source, ";export{", ";" + studioDecl + "export{",
                        "chunked badge studio export anchor");
            } else {
                source = source + "\n" + studioDecl;
            }
        }

        if (!source.contains(CHUNKED_BADGE_STUDIO_MARKER)
                || !source.contains("GomodUseBadgeFilter(")
                || !source.contains("GomodCustomBadgeImage,{badgeId:")
                || !source.contains("GomodLockerItemContext,{kind:\"badge\"")
                || (!isNametagBadges && !source.contains("GomodStudioLauncher,{kind:\"badge\""))) {
            throw new UnsupportedBundleException("chunked badge studio transform failed validation");
        }
        return source;
    }

    static String transformChunkedOfficialLoadoutIndex5(String source, String officialLoadoutFragment) {
        boolean hasHelpers = source.contains(CHUNKED_OFFICIAL_LOADOUT_MARKER)
                && source.contains("GomodYourLoadoutTitleBar=GomodOfficialLoadout.GomodYourLoadoutTitleBar")
                && source.contains("GomodLockerRow=GomodOfficialLoadout.GomodLockerRow");
        boolean hasTitleBar = source.contains("GomodYourLoadoutTitleBar,{TitleBar:");
        boolean hasRow = source.contains("GomodLockerRow,{base:");
        if (hasHelpers && hasTitleBar && hasRow) {
            return source;
        }

        Matcher tooltipMatcher = CHUNKED_TOOLTIP_PATTERN.matcher(source);
        if (!tooltipMatcher.find()) {
            throw new UnsupportedBundleException("chunked official loadout tooltip signature was missing");
        }
        String jsxVar = tooltipMatcher.group(1);
        String tooltipVar = tooltipMatcher.group(2);
        String tooltipContentVar = tooltipMatcher.group(3);
        String textVar = tooltipMatcher.group(4);
        if (tooltipMatcher.find()) {
            throw new UnsupportedBundleException("chunked official loadout tooltip signature was ambiguous");
        }

        Matcher wrapperMatcher = CHUNKED_COSMETIC_MENU_WRAPPER_PATTERN.matcher(source);
        if (!wrapperMatcher.find()) {
            throw new UnsupportedBundleException("chunked official loadout react signature was missing");
        }
        String reactVar = wrapperMatcher.group(10);
        if (wrapperMatcher.find()) {
            throw new UnsupportedBundleException("chunked official loadout react signature was ambiguous");
        }

        Matcher dummyMatcher = CHUNKED_PLAYER_DUMMY_PATTERN.matcher(source);
        if (!dummyMatcher.find()) {
            throw new UnsupportedBundleException("chunked official loadout player dummy signature was missing");
        }
        String dummyJsxVar = dummyMatcher.group(1);
        String playerModelComp = dummyMatcher.group(2);
        String skinEnum = dummyMatcher.group(3);
        String displayEnum = dummyMatcher.group(4);
        String renderContextEnum = dummyMatcher.group(5);
        if (dummyMatcher.find() || !jsxVar.equals(dummyJsxVar)) {
            throw new UnsupportedBundleException("chunked official loadout player dummy signature was ambiguous");
        }

        if (!hasTitleBar) {
            Matcher titleBarMatcher = CHUNKED_YOUR_LOADOUT_TITLE_BAR_PATTERN.matcher(source);
            if (!titleBarMatcher.find()) {
                throw new UnsupportedBundleException("chunked Your Loadout title bar signature was missing");
            }
            String target = titleBarMatcher.group(0);
            String yourLoadoutComp = titleBarMatcher.group(1);
            String param = titleBarMatcher.group(2);
            String titleJsxVar = titleBarMatcher.group(3);
            String titleBarComp = titleBarMatcher.group(4);
            if (titleBarMatcher.find() || !jsxVar.equals(titleJsxVar)) {
                throw new UnsupportedBundleException("chunked Your Loadout title bar signature was ambiguous");
            }
            String replacement = yourLoadoutComp + "=" + param + "=>" + jsxVar
                    + ".jsx(GomodYourLoadoutTitleBar,{TitleBar:" + titleBarComp
                    + ",icon:" + param + ".icon,onHelp:" + param + ".onHelp})";
            source = replaceUnique(source, target, replacement, "chunked Your Loadout title bar");
        }

        if (!hasRow) {
            Matcher rowMatcher = CHUNKED_LOCKER_ROW_PATTERN.matcher(source);
            if (!rowMatcher.find()) {
                throw new UnsupportedBundleException("chunked Locker row signature was missing");
            }
            String rowJsxVar = rowMatcher.group(1);
            String rowComp = rowMatcher.group(2);
            if (rowMatcher.find() || !jsxVar.equals(rowJsxVar)) {
                throw new UnsupportedBundleException("chunked Locker row signature was ambiguous");
            }
            Pattern rowCallPattern = Pattern.compile(
                    "\\b" + Pattern.quote(jsxVar) + "\\.(jsxs?)\\(" + Pattern.quote(rowComp) + ",\\{");
            Matcher callMatcher = rowCallPattern.matcher(source);
            StringBuffer out = new StringBuffer(source.length() + 256);
            int replacements = 0;
            while (callMatcher.find()) {
                replacements++;
                callMatcher.appendReplacement(out, Matcher.quoteReplacement(
                        jsxVar + "." + callMatcher.group(1) + "(GomodLockerRow,{base:" + rowComp + ","));
            }
            callMatcher.appendTail(out);
            if (replacements == 0) {
                throw new UnsupportedBundleException("chunked Locker row call sites were missing");
            }
            source = out.toString();
        }

        if (!hasHelpers) {
            String normalized = officialLoadoutFragment.trim();
            while (normalized.endsWith(",")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            String decl = "const " + normalized
                    + ",GomodOfficialLoadout=GomodOfficialLoadoutCreate("
                    + reactVar + "," + jsxVar + "," + tooltipVar + "," + tooltipContentVar + "," + textVar + ","
                    + playerModelComp + "," + skinEnum + "," + displayEnum + "," + renderContextEnum + "),"
                    + "GomodYourLoadoutTitleBar=GomodOfficialLoadout.GomodYourLoadoutTitleBar,"
                    + "GomodLockerRow=GomodOfficialLoadout.GomodLockerRow;";
            String oldStart = "const GomodOfficialLoadoutCreate=";
            String oldEnd = "GomodLockerRow=GomodOfficialLoadout.GomodLockerRow;";
            int existingStart = source.indexOf(oldStart);
            int existingEnd = existingStart < 0 ? -1 : source.indexOf(oldEnd, existingStart);
            if (existingStart >= 0 && existingEnd > existingStart) {
                source = source.substring(0, existingStart) + decl
                        + source.substring(existingEnd + oldEnd.length());
            } else if (source.contains(";export{")) {
                source = replaceUnique(source, ";export{", ";" + decl + "export{",
                        "chunked official loadout export anchor");
            } else {
                source = source + "\n" + decl;
            }
        }

        if (!source.contains(CHUNKED_OFFICIAL_LOADOUT_MARKER)
                || !source.contains("GomodYourLoadoutTitleBar,{TitleBar:")
                || !source.contains("GomodLockerRow,{base:")) {
            throw new UnsupportedBundleException("chunked official loadout transform failed validation");
        }
        return source;
    }

    static String transformChunkedFitsActionsIndex5(String source, String fitsActionsFragment) {
        boolean hasHelpers = source.contains(CHUNKED_FITS_ACTIONS_MARKER)
                && source.contains("GomodRandomFitButton=GomodFitsActions.GomodRandomFitButton")
                && source.contains("GomodFitShareButton=GomodFitsActions.GomodFitShareButton")
                && source.contains("GomodFitShareDialog=GomodFitsActions.GomodFitShareDialog");
        boolean hasButtons = source.contains("GomodRandomFitButton,{fullAccess:")
                && source.contains("GomodFitShareButton,{fit:");
        boolean hasDialog = source.contains("GomodFitShareDialog,{fit:");
        if (hasHelpers && hasButtons && hasDialog) {
            return source;
        }

        Matcher finishMatcher = CHUNKED_INDEX5_FINISH_BUTTON_PATTERN.matcher(source);
        if (!finishMatcher.find()) {
            throw new UnsupportedBundleException("chunked fits actions button signature was missing");
        }
        String jsxVar = finishMatcher.group(1);
        String buttonVar = finishMatcher.group(2);

        Matcher modalMatcher = CHUNKED_MODAL_PATTERN.matcher(source);
        if (!modalMatcher.find() || !jsxVar.equals(modalMatcher.group(1))) {
            throw new UnsupportedBundleException("chunked fits actions modal signature was missing");
        }
        String modalVar = modalMatcher.group(2);

        Matcher inputMatcher = CHUNKED_FITS_TEXT_INPUT_PATTERN.matcher(source);
        if (!inputMatcher.find() || !jsxVar.equals(inputMatcher.group(1))) {
            throw new UnsupportedBundleException("chunked fits actions text input signature was missing");
        }
        String textInputVar = inputMatcher.group(2);

        Matcher tooltipMatcher = CHUNKED_TOOLTIP_PATTERN.matcher(source);
        if (!tooltipMatcher.find() || !jsxVar.equals(tooltipMatcher.group(1))) {
            throw new UnsupportedBundleException("chunked fits actions tooltip signature was missing");
        }
        String tooltipVar = tooltipMatcher.group(2);
        String tooltipContentVar = tooltipMatcher.group(3);
        String textVar = tooltipMatcher.group(4);

        Matcher dummyMatcher = CHUNKED_PLAYER_DUMMY_PATTERN.matcher(source);
        if (!dummyMatcher.find() || !jsxVar.equals(dummyMatcher.group(1))) {
            throw new UnsupportedBundleException("chunked fits actions player dummy signature was missing");
        }
        String playerModelComp = dummyMatcher.group(2);
        String skinEnum = dummyMatcher.group(3);
        String displayEnum = dummyMatcher.group(4);
        String renderContextEnum = dummyMatcher.group(5);

        Matcher iconMatcher = CHUNKED_ICON_RENDER_PATTERN.matcher(source);
        if (!iconMatcher.find()) {
            throw new UnsupportedBundleException("chunked fits actions icon signature was missing");
        }
        String renderIconVar = iconMatcher.group(1);
        String iconEnumVar = iconMatcher.group(2);

        int tabMarkerIdx = source.indexOf("name:\"OUTFITS_TAB\"");
        if (tabMarkerIdx < 0 || source.indexOf("name:\"OUTFITS_TAB\"", tabMarkerIdx + 1) >= 0) {
            throw new UnsupportedBundleException("chunked fits tab marker was missing or ambiguous");
        }
        int ctxBeforeTab = source.lastIndexOf(".useContext(", tabMarkerIdx);
        int tabStart = ctxBeforeTab < 0 ? -1 : source.lastIndexOf("=()=>", ctxBeforeTab);
        int tabEnd = source.indexOf("TEST_PLAYER_DUMMY_FIT", tabMarkerIdx);
        if (tabStart < 0 || tabEnd < 0) {
            throw new UnsupportedBundleException("chunked fits tab boundaries were missing");
        }
        int modalCloseAnchor = source.indexOf("]})},", tabEnd);
        if (modalCloseAnchor < 0) {
            throw new UnsupportedBundleException("chunked fits tab closing anchor was missing");
        }
        int panelStart = tabStart;
        int panelEnd = modalCloseAnchor + 3;
        String panel = source.substring(panelStart, panelEnd);

        Matcher reactSubMatcher = Pattern.compile(
                "([A-Za-z0-9_$]+)\\.useContext\\([^)]+\\),[A-Za-z0-9_$]+=\\1\\.useRef\\(\\[\\]\\);"
                        + "([A-Za-z0-9_$]+)\\(([A-Za-z0-9_$]+)\\.outfit,").matcher(panel);
        if (!reactSubMatcher.find()) {
            throw new UnsupportedBundleException("chunked fits tab React/bridge subscription signature was missing");
        }
        String reactVar = reactSubMatcher.group(1);
        String subBridgeVar = reactSubMatcher.group(2);
        String bridgeVar = reactSubMatcher.group(3);

        Matcher selectedMatcher = Pattern.compile("selectedOutfit:([A-Za-z0-9_$]+),defaultOutfitId:").matcher(panel);
        if (!selectedMatcher.find()) {
            throw new UnsupportedBundleException("chunked fits tab selectedOutfit signature was missing");
        }
        String selectedFitVar = selectedMatcher.group(1);

        String setSelectedFitVar;
        Matcher freshFitMatcher = Pattern.compile(
                "([A-Za-z0-9_$]+)\\(([A-Za-z0-9_$]+)=>\\2&&([A-Za-z0-9_$]+)\\.outfits\\.find\\(([A-Za-z0-9_$]+)=>\\4\\.id==\\2\\.id\\)\\?\\2:\\3\\.outfits\\.find\\(\\4=>\\4\\.id==\\3\\.defaultOutfitId\\)\\|\\|null\\)")
                .matcher(panel);
        if (freshFitMatcher.find()) {
            setSelectedFitVar = freshFitMatcher.group(1);
            String curVar = freshFitMatcher.group(2);
            String payloadVar = freshFitMatcher.group(3);
            String itemVar = freshFitMatcher.group(4);
            String freshExpr = setSelectedFitVar + "(" + curVar + "=>(" + curVar + "&&" + payloadVar
                    + ".outfits.find(" + itemVar + "=>" + itemVar + ".id==" + curVar + ".id))||"
                    + payloadVar + ".outfits.find(" + itemVar + "=>" + itemVar + ".id==" + payloadVar
                    + ".defaultOutfitId)||null)";
            panel = replaceUnique(panel, freshFitMatcher.group(0), freshExpr, "chunked fits fresh selection");
        } else {
            Matcher alreadyFresh = Pattern.compile(
                    "([A-Za-z0-9_$]+)\\(([A-Za-z0-9_$]+)=>\\(\\2&&[A-Za-z0-9_$]+\\.outfits\\.find\\(").matcher(panel);
            if (!alreadyFresh.find()) {
                throw new UnsupportedBundleException("chunked fits tab selection setter signature was missing");
            }
            setSelectedFitVar = alreadyFresh.group(1);
        }

        if (!panel.contains("const gomodFullAccess=GomodFitsUseFullAccess()")) {
            String returnAnchor = "return " + jsxVar + ".jsxs(";
            String stateHooks = "const gomodFullAccess=GomodFitsUseFullAccess(),"
                    + "gomodFitColors=GomodFitsUseColors(),"
                    + "[gomodShareFit,gomodSetShareFit]=" + reactVar + ".useState(null);"
                    + reactVar + ".useEffect(()=>{if(!gomodFullAccess)gomodSetShareFit(null)},[gomodFullAccess]);"
                    + "GomodFitsInboxViewed(gomodFitColors);";
            panel = replaceUnique(panel, returnAnchor, stateHooks + returnAnchor, "chunked fits state hooks");
        }

        if (!hasButtons) {
            Matcher searchRowMatcher = Pattern.compile(
                    Pattern.quote(jsxVar) + "\\.jsxs\\(([A-Za-z0-9_$]+),\\{children:\\[("
                            + Pattern.quote(jsxVar) + "\\.jsx\\([A-Za-z0-9_$]+,\\{searchText:[A-Za-z0-9_$]+,onSearch:[A-Za-z0-9_$]+\\}\\)),([A-Za-z0-9_$]+)\\]\\}\\)")
                    .matcher(panel);
            if (!searchRowMatcher.find()) {
                throw new UnsupportedBundleException("chunked fits search/grid wrapper signature was missing");
            }
            String colComp = searchRowMatcher.group(1);
            String searchJsx = searchRowMatcher.group(2);
            String gridVar = searchRowMatcher.group(3);
            String toolbarReplacement = jsxVar + ".jsxs(" + colComp + ",{children:["
                    + jsxVar + ".jsxs(\"div\",{style:{display:\"flex\",alignItems:\"center\",gap:\"8px\",width:\"100%\",minWidth:0},children:["
                    + jsxVar + ".jsx(\"div\",{style:{flex:1,minWidth:0},children:" + searchJsx + "}),"
                    + "gomodFullAccess&&" + jsxVar + ".jsx(GomodFitShareButton,{fit:" + selectedFitVar
                    + ",state:gomodFitColors,fullAccess:gomodFullAccess,onOpen:()=>gomodSetShareFit({fit:" + selectedFitVar
                    + ",account:gomodFitColors.account})}),"
                    + "gomodFullAccess&&" + jsxVar + ".jsx(GomodRandomFitButton,{fullAccess:gomodFullAccess,onSelect:"
                    + setSelectedFitVar + "})]}),"
                    + jsxVar + ".jsx(GomodReceivedFitsNotice,{state:gomodFitColors})," + gridVar + "]})";
            panel = replaceUnique(panel, searchRowMatcher.group(0), toolbarReplacement, "chunked fits Random and Share Fit buttons");
        }

        if (!hasDialog) {
            if (!panel.endsWith("]})")) {
                throw new UnsupportedBundleException("chunked fits tab return closing shape was unexpected");
            }
            String dialogJsx = ",gomodFullAccess&&gomodShareFit&&GomodFitOwner(gomodShareFit.account)===GomodFitOwner(gomodFitColors.account)&&"
                    + jsxVar + ".jsx(GomodFitShareDialog,{fit:gomodShareFit.fit,state:gomodFitColors,onClose:()=>gomodSetShareFit(null)},gomodShareFit.account+gomodShareFit.fit.id)";
            panel = panel.substring(0, panel.length() - 3) + dialogJsx + "]})";
        }

        source = source.substring(0, panelStart) + panel + source.substring(panelEnd);

        if (!hasHelpers) {
            String normalized = fitsActionsFragment.trim();
            while (normalized.endsWith(",")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            String decl = "const " + normalized
                    + ",GomodFitsActions=GomodFitsActionsCreate("
                    + reactVar + "," + jsxVar + "," + bridgeVar + "," + subBridgeVar + ","
                    + buttonVar + "," + modalVar + "," + textInputVar + ","
                    + tooltipVar + "," + tooltipContentVar + "," + textVar + ","
                    + playerModelComp + "," + skinEnum + "," + displayEnum + "," + renderContextEnum + ","
                    + renderIconVar + "," + iconEnumVar + "),"
                    + "GomodFitsUseFullAccess=GomodFitsActions.GomodFitsUseFullAccess,"
                    + "GomodFitOwner=GomodFitsActions.GomodFitOwner,"
                    + "GomodRandomFitButton=GomodFitsActions.GomodRandomFitButton,"
                    + "GomodFitsUseColors=GomodFitsActions.GomodFitsUseColors,"
                    + "GomodFitsInboxViewed=GomodFitsActions.GomodFitsInboxViewed,"
                    + "GomodFitShareButton=GomodFitsActions.GomodFitShareButton,"
                    + "GomodReceivedFitsNotice=GomodFitsActions.GomodReceivedFitsNotice,"
                    + "GomodFitShareDialog=GomodFitsActions.GomodFitShareDialog;";
            String oldStart = "const GomodFitsActionsCreate=";
            String oldEnd = "GomodFitShareDialog=GomodFitsActions.GomodFitShareDialog;";
            int existingStart = source.indexOf(oldStart);
            int existingEnd = existingStart < 0 ? -1 : source.indexOf(oldEnd, existingStart);
            if (existingStart >= 0 && existingEnd > existingStart) {
                source = source.substring(0, existingStart) + decl
                        + source.substring(existingEnd + oldEnd.length());
            } else if (source.contains(";export{")) {
                source = replaceUnique(source, ";export{", ";" + decl + "export{",
                        "chunked fits actions export anchor");
            } else {
                source = source + "\n" + decl;
            }
        }

        if (!source.contains(CHUNKED_FITS_ACTIONS_MARKER)
                || !source.contains("GomodRandomFitButton,{fullAccess:")
                || !source.contains("GomodFitShareButton,{fit:")
                || !source.contains("GomodFitShareDialog,{fit:")) {
            throw new UnsupportedBundleException("chunked fits actions transform failed validation");
        }
        return source;
    }

    /** Wires gomod's custom HSV Logo Color picker into Lunar's new Nametag -> Logo Color section. */
    static String transformChunkedNametagLogoColorIndex5(String source, String nametagLogoColorFragment) {
        if (!source.contains("gui.nametag.logoColor")) {
            return source;
        }
        boolean hasHelpers = source.contains(CHUNKED_NAMETAG_LOGO_COLOR_MARKER)
                && source.contains("GomodNametagLogoColorSection=GomodNametagLogoColor.GomodNametagLogoColorSection")
                && source.contains("GomodUseNametagLogoColor=GomodNametagLogoColor.GomodUseNametagLogoColor");
        boolean hasSection = source.contains("GomodNametagLogoColorSection,{searchText:");
        boolean hasPreview = source.contains("GomodUseNametagLogoColor(");
        boolean hasNoPlusOption = source.contains("label:\"None\",selected:0===");
        boolean hasExpandedBadges = source.contains(CHUNKED_NAMETAG_ROOT_STYLE_SCROLL)
                && !CHUNKED_NAMETAG_BADGES_SCROLL_PATTERN.matcher(source).find();
        if (hasHelpers && hasSection && hasPreview && hasNoPlusOption && hasExpandedBadges) {
            return source;
        }

        Matcher finishMatcher = CHUNKED_INDEX5_FINISH_BUTTON_PATTERN.matcher(source);
        if (!finishMatcher.find()) {
            throw new UnsupportedBundleException("chunked nametag logo color button signature was missing");
        }
        String jsxVar = finishMatcher.group(1);
        String buttonVar = finishMatcher.group(2);
        if (finishMatcher.find()) {
            throw new UnsupportedBundleException("chunked nametag logo color button signature was ambiguous");
        }

        Matcher compMatcher = CHUNKED_INDEX5_SETTINGS_COMPONENT_PATTERN.matcher(source);
        if (!compMatcher.find()) {
            throw new UnsupportedBundleException("chunked nametag logo color react signature was missing");
        }
        String reactVar = compMatcher.group(6);
        if (compMatcher.find()) {
            throw new UnsupportedBundleException("chunked nametag logo color react signature was ambiguous");
        }

        if (!hasSection) {
            Matcher emptyMatcher = CHUNKED_NAMETAG_LOGO_EMPTY_PATTERN.matcher(source);
            if (!emptyMatcher.find()) {
                throw new UnsupportedBundleException("chunked nametag noLogoColors signature was missing");
            }
            String emptyOrig = emptyMatcher.group(0);
            String emptyJsx = emptyMatcher.group(2);
            int emptyStart = emptyMatcher.start();
            if (emptyMatcher.find() || !jsxVar.equals(emptyJsx)) {
                throw new UnsupportedBundleException("chunked nametag noLogoColors signature was ambiguous");
            }
            int lookbackStart = Math.max(0, emptyStart - 600);
            String prefixWindow = source.substring(lookbackStart, emptyStart);
            Matcher searchParamMatcher = Pattern.compile("\\(\\{searchText:([A-Za-z0-9_$]+)\\}\\)=>").matcher(prefixWindow);
            String searchParam = null;
            while (searchParamMatcher.find()) {
                searchParam = searchParamMatcher.group(1);
            }
            if (searchParam == null) {
                throw new UnsupportedBundleException("chunked nametag logo color searchText parameter was missing");
            }
            String sectionReplacement = jsxVar + ".jsx(GomodNametagLogoColorSection,{searchText:" + searchParam
                    + ",fallback:" + emptyOrig + "})";
            source = replaceUnique(source, emptyOrig, sectionReplacement, "chunked nametag logo color section");
        }

        if (!hasPreview) {
            Matcher previewMatcher = CHUNKED_NAMETAG_PREVIEW_LOGO_PATTERN.matcher(source);
            if (previewMatcher.find()) {
                String previewOrig = previewMatcher.group(0);
                String dataVar = previewMatcher.group(1);
                if (!previewMatcher.find()) {
                    String previewReplacement = "logoColor:GomodUseNametagLogoColor("
                            + dataVar + "==null?void 0:" + dataVar + ".lunarPlus.logoColor),plusColor:";
                    source = replaceUnique(source, previewOrig, previewReplacement, "chunked nametag preview logo color");
                }
            }
        }

        if (!hasNoPlusOption) {
            Matcher iconMatcher = CHUNKED_ICON_RENDER_PATTERN.matcher(source);
            Matcher gridMatcher = CHUNKED_NAMETAG_PLUS_COLORS_GRID_PATTERN.matcher(source);
            if (iconMatcher.find() && gridMatcher.find()) {
                String renderIconVar = iconMatcher.group(1);
                String iconEnumVar = iconMatcher.group(2);
                String gridOrig = gridMatcher.group(0);
                String gridFn = gridMatcher.group(1);
                String slotsArg = gridMatcher.group(2);
                String activeArg = gridMatcher.group(3);
                String msgArg = gridMatcher.group(4);
                String selectFn = gridMatcher.group(5);
                String colorArg = gridMatcher.group(6);
                String bridgeVar = gridMatcher.group(7);
                String gridJsx = gridMatcher.group(8);
                String gridWrap = gridMatcher.group(9);
                String itemVar = gridMatcher.group(10);
                String hiddenVar = gridMatcher.group(11);
                String tileComp = gridMatcher.group(12);
                String swatchComp = gridMatcher.group(13);
                String toHexFn = gridMatcher.group(14);
                if (!gridMatcher.find() && jsxVar.equals(gridJsx)) {
                    String banIcon = renderIconVar + "(" + iconEnumVar + ".PI_BAN_LEFT_STROKE,{width:\"18px\",height:\"18px\"})";
                    String noneTile = "!" + slotsArg + ".some(gomodSlot=>gomodSlot.item&&gomodSlot.item.color===0)&&"
                            + gridJsx + ".jsx(" + tileComp + ",{label:\"None\",selected:0===" + activeArg
                            + ",onSelect:()=>" + selectFn + "(0),children:" + banIcon + "},\"GOMOD_NO_PLUS\")";
                    String mappedTiles = "..." + slotsArg + ".map(({item:" + itemVar + ",hidden:" + hiddenVar + "})=>"
                            + gridJsx + ".jsx(" + tileComp + ",{label:" + itemVar + ".color===0?\"None\":" + itemVar
                            + ".name,selected:" + itemVar + ".color===" + activeArg + ",hidden:" + hiddenVar
                            + ",onSelect:()=>" + selectFn + "(" + itemVar + ".color),children:" + itemVar
                            + ".color===0?" + banIcon + ":" + gridJsx + ".jsx(" + swatchComp + ",{color:"
                            + toHexFn + "(" + itemVar + ".color)})}," + itemVar + ".color))";
                    String gridReplacement = gridFn + "=({slots:" + slotsArg + ",active:" + activeArg
                            + ",message:" + msgArg + "})=>{const " + selectFn + "=" + colorArg + "=>{"
                            + colorArg + "!==" + activeArg + "&&" + bridgeVar + ".lunarPlus.setColor(" + colorArg + ")};"
                            + "return " + gridJsx + ".jsx(" + gridWrap + ",{message:" + msgArg + ",children:["
                            + noneTile + "," + mappedTiles + "]})}";
                    source = replaceUnique(source, gridOrig, gridReplacement, "chunked nametag Lunar+ None option");
                }
            }
        }

        if (!hasExpandedBadges) {
            if (!source.contains(CHUNKED_NAMETAG_ROOT_STYLE_SCROLL)
                    && source.contains(CHUNKED_NAMETAG_ROOT_STYLE_ANCHOR)) {
                source = replaceUnique(source, CHUNKED_NAMETAG_ROOT_STYLE_ANCHOR, CHUNKED_NAMETAG_ROOT_STYLE_SCROLL,
                        "chunked nametag root scroll");
            }
            Matcher badgesScrollMatcher = CHUNKED_NAMETAG_BADGES_SCROLL_PATTERN.matcher(source);
            if (badgesScrollMatcher.find()) {
                String badgesOrig = badgesScrollMatcher.group(0);
                String tFn = badgesScrollMatcher.group(1);
                String countExpr = badgesScrollMatcher.group(2);
                if (!badgesScrollMatcher.find()) {
                    String badgesReplacement = "className:\"nametagTour4\",title:" + tFn
                            + "(\"gui.nametag.badge\"),count:" + countExpr + ",scrollable:!1,";
                    source = replaceUnique(source, badgesOrig, badgesReplacement, "chunked nametag badges full display");
                }
            }
        }

        if (!hasHelpers) {
            String normalized = nametagLogoColorFragment.trim();
            while (normalized.endsWith(",")) {
                normalized = normalized.substring(0, normalized.length() - 1).trim();
            }
            String decl = "const " + normalized
                    + ",GomodNametagLogoColor=GomodNametagLogoColorCreate("
                    + reactVar + "," + jsxVar + "," + buttonVar + "),"
                    + "GomodNametagLogoColorSection=GomodNametagLogoColor.GomodNametagLogoColorSection,"
                    + "GomodUseNametagLogoColor=GomodNametagLogoColor.GomodUseNametagLogoColor;";
            String oldStart = "const GomodNametagLogoColorCreate=";
            String oldEnd = "GomodUseNametagLogoColor=GomodNametagLogoColor.GomodUseNametagLogoColor;";
            int existingStart = source.indexOf(oldStart);
            int existingEnd = existingStart < 0 ? -1 : source.indexOf(oldEnd, existingStart);
            if (existingStart >= 0 && existingEnd > existingStart) {
                source = source.substring(0, existingStart) + decl
                        + source.substring(existingEnd + oldEnd.length());
            } else if (source.contains(";export{")) {
                source = replaceUnique(source, ";export{", ";" + decl + "export{",
                        "chunked nametag logo color export anchor");
            } else {
                source = source + "\n" + decl;
            }
        }

        if (!source.contains(CHUNKED_NAMETAG_LOGO_COLOR_MARKER)
                || !source.contains("GomodNametagLogoColorSection,{searchText:")) {
            throw new UnsupportedBundleException("chunked nametag logo color transform failed validation");
        }
        return source;
    }

    /** Injects native Blitz Solo/Teams quick-queue buttons into Lunar's WebOSR Home screen ({@code index.js}). */
    static String transformHomeQueueIndex(String source, String homeQueueFragment) {
        if (source == null || homeQueueFragment == null) {
            throw new UnsupportedBundleException("home queue transform input was null");
        }
        boolean hasHelpers = source.contains(HOME_QUEUE_MARKER);
        boolean hasMount = source.contains("GomodMainMenuQueueButtons,{jsx:");
        if (hasHelpers && hasMount) {
            return source;
        }

        if (!hasMount) {
            Matcher matcher = HOME_QUEUE_BUTTONS_PATTERN.matcher(source);
            if (!matcher.find()) {
                throw new UnsupportedBundleException("home queue button row signature was missing");
            }
            String fullMatch = matcher.group(0);
            String jsxVar = matcher.group(1);
            String mapExpr = matcher.group(2);
            String innerJsxVar = matcher.group(6);
            String buttonComp = matcher.group(7);
            String bridgeVar = matcher.group(9);
            if (matcher.find() || !jsxVar.equals(innerJsxVar)) {
                throw new UnsupportedBundleException("home queue button row signature was ambiguous");
            }
            Pattern reactPattern = Pattern.compile(
                    "([A-Za-z0-9_$]+)\\.useEffect\\(\\(\\)=>\\(\\)\\=>\\{"
                            + Pattern.quote(bridgeVar) + "\\.home\\.radio\\.close\\(\\)\\},\\[\\]\\)");
            Matcher reactMatcher = reactPattern.matcher(source);
            if (!reactMatcher.find()) {
                throw new UnsupportedBundleException("home queue react reference was missing");
            }
            String reactVar = reactMatcher.group(1);

            String replacement = jsxVar + ".jsx(\"div\",{style:{display:\"flex\",flexDirection:\"row\","
                    + "flexWrap:\"wrap\",alignItems:\"center\",gap:\"8px\"},children:[" + mapExpr + ","
                    + jsxVar + ".jsx(GomodMainMenuQueueButtons,{jsx:" + jsxVar + ",react:" + reactVar
                    + ",Button:" + buttonComp + ",bridge:" + bridgeVar + "})]})";
            source = replaceUnique(source, fullMatch, replacement, "home queue button row mount");
        }

        if (!hasHelpers) {
            String decl = homeQueueFragment.trim();
            if (!decl.endsWith(";")) {
                decl = decl + ";";
            }
            String oldStart = "const GomodHomeQueueVersion=";
            String oldEndV2 = "\"gomod-queue-teams\")]}):null};";
            String oldEndV2Spaced = "\"gomod-queue-teams\")]}) :null};";
            String oldEndV1 = "\"gomod-queue-teams\")]})};";
            int existingStart = source.indexOf(oldStart);
            int existingEnd = -1;
            int endLen = 0;
            if (existingStart >= 0) {
                for (String candidate : new String[] { oldEndV2, oldEndV2Spaced, oldEndV1 }) {
                    int idx = source.indexOf(candidate, existingStart);
                    if (idx > existingStart) {
                        existingEnd = idx;
                        endLen = candidate.length();
                        break;
                    }
                }
            }
            if (existingStart >= 0 && existingEnd > existingStart) {
                source = source.substring(0, existingStart) + decl
                        + source.substring(existingEnd + endLen);
            } else {
                int exportIdx = source.lastIndexOf(";export{");
                if (exportIdx >= 0) {
                    source = source.substring(0, exportIdx + 1) + decl + source.substring(exportIdx + 1);
                } else {
                    source = source + "\n" + decl;
                }
            }
        }

        if (!source.contains(HOME_QUEUE_MARKER) || !source.contains("GomodMainMenuQueueButtons,{jsx:")) {
            throw new UnsupportedBundleException("home queue transform failed validation");
        }
        return source;
    }

    private static final class UnsupportedBundleException extends RuntimeException {
        private UnsupportedBundleException(String message) {
            super(message);
        }
    }
}
