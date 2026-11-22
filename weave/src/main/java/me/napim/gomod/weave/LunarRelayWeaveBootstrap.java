package me.napim.gomod.weave;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.napim.gomod.GomodReleaseBuild;
import me.napim.gomod.GomodVersion;
import me.napim.gomod.features.LunarConnectionDiagnostics;
import me.napim.gomod.util.GomodPaths;
import net.minecraft.client.Minecraft;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.io.File;
import java.io.FileReader;
import java.net.URI;
import java.security.ProtectionDomain;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Native Weave compatibility bootstrap for Lunar.
 *
 * <p>The optional ProfileData hook must be removed while the matching Lunar class is being
 * defined. Internal QA builds route authentication, Assets, and HTTP catalog traffic through
 * the Gomod relay. Public-release builds do not install those overrides.</p>
 */
public final class LunarRelayWeaveBootstrap {
    private static final String PROFILE_DATA_CLASS = "com.moonsworth.lunar.ichor.ProfileData";
    private static final String EMOTE_LOGIN_RESPONSE_CLASS =
            "com/lunarclient/websocket/emote/v1/LoginResponse";
    private static final String COSMETIC_V1_LOGIN_RESPONSE_CLASS =
            "com/lunarclient/websocket/cosmetic/v1/LoginResponse";
    private static final String EMOTE_LOGIN_PARSER_CLASS = EMOTE_LOGIN_RESPONSE_CLASS + "$1";
    private static final String EMOTE_WHEEL_BUILDER_CLASS =
            "com/lunarclient/websocket/emote/v1/UpdateEquippedEmotesRequest$Builder";
    private static final String COSMETIC_V2_LOGIN_RESPONSE_CLASS =
            "com/lunarclient/websocket/cosmetic/v2/LoginResponse";
    private static final String JAVA_WEBSOCKET_CLIENT_CLASS =
            "org/java_websocket/client/WebSocketClient";
    private static final String JAVA_WEBSOCKET_IMPL_CLASS =
            "org/java_websocket/WebSocketImpl";
    private static final String JAVA_WEBSOCKET_CLIENT_WRITE_THREAD_CLASS =
            "org/java_websocket/client/WebSocketClient$WebsocketWriteThread";
    private static final String LUNAR_AUTH_SUCCESS_CLIENT_CLASS =
            "com/moonsworth/lunar/client/RHOICRHRRHCICCRORRHHIRIIHHIIHI/"
                    + "RHOICRHRRHCICCRORRHHIRIIHHIIHI";
    private static final String LUNAR_AUTH_SUCCESS_METHOD =
            "IOOCCRIRHHROHIHRCHHRRIOICHIOIR";
    private static final String LUNAR_AUTH_SUCCESS_DESCRIPTOR =
            "(Lcom/lunarclient/authenticator/v1/AuthSuccessMessage;)V";
    private static final String RELAY_HOST = "gomod-service.onrender.com";
    private static final String DIRECT_ASSETS_TEST_HOST = "gomod-service.onrender.com";
    // Lunar's AuthenticatorClient appends "/game" to this base URL. The guarded transform
    // below changes that suffix to the relay's Authenticator endpoint at /ws.
    private static final String RELAY_AUTHENTICATOR = "wss://" + RELAY_HOST;
    // AssetServer appends "/game" to this base, producing the relay's exact /ws/game route.
    private static final String RELAY_ASSET_SERVER = "wss://" + RELAY_HOST + "/ws";
    // AssetServer appends "/game"; this QA-only route therefore resolves directly to API /ws/game.
    private static final String DIRECT_ASSETS_TEST = "wss://" + DIRECT_ASSETS_TEST_HOST + "/ws";
    private static final String RELAY_API = "https://" + RELAY_HOST + "/api/lunar";
    private static final String RELAY_STYNGR = "https://" + RELAY_HOST + "/api/styngr";
    // Exact service layouts verified against the decompiled Lunar 2.22.42 reference and the
    // remapped lunar.jar loaded by the 2026-09-30 UmbraWeave session. Never infer these names
    // from field counts: validate every String field before injecting any PUTSTATIC.
    private static final ServiceEndpointLayout[] LUNAR_SERVICE_LAYOUTS = {
            // Verified from labelled endpoint assignments in the installed 2026-10-22
            // lunar.jar, SHA-256 8A8B6F307786877DF2BF363FACF0D33EC61F70ACC51A5218EBBD5525E27F0F02.
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/HIHCRHIOIOIHORIHICOIOIHIIIOCOC",
                    "OCIHIROHOHOHHRRCCIIRIRCRROIIRI",
                    "RHRORICROCHIIOIRHOCCHCOROCOHHR",
                    "CCICRCHCHIORHIRHORHOROICRICROH",
                    "OCOROIOICHORRRRRCHORRCOCIHIHHC"),
            // Verified from the active 2026-10-20 lunar.jar, SHA-256
            // 6E1A2BB789CF00985DDAFDC4487F8EC0A2EE5FDCFE51E0C224011F09EDA2230C.
            // Lunar kept the class name but changed its endpoint fields in this update.
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/HIHCRHIOIOIHORIHICOIOIHIIIOCOC",
                    "IHOCHHICICHOIHCRHRHRIICHIRCOCC",
                    "CCOOOCHHCOHIRHHCHHCHORIIRCOIIO",
                    "CIIORHOOCOCROCRHHORHIRRIOIRORR",
                    "HIOCOOOHHRHIROOHCRCRIOOORCRCIO"),
            // Verified from Api/Authenticator/AssetServer/Styngr assignments in the installed
            // 2026-10-19 lunar.jar, SHA-256 2F22C130029F711F6BC9DEB912733BC2A11FBA99478E8B4EEB98F44D929464A3.
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/HIHCRHIOIOIHORIHICOIOIHIIIOCOC",
                    "OCRHOIRRIIHOOIIOHIOHRIHIHOCHCI",
                    "OCRCORCIRHORCOICIICHRRHOHRROIH",
                    "IIHOCORCCHOIRIIHOHHHOROOROCRHC",
                    "CCOOOCHHCOHIRHHCHHCHORIIRCOIIO"),
            // Verified from the labelled endpoint assignments in the 2026-10-16 lunar.jar,
            // SHA-256 838704FC527D43DCEFE81CA458A72B52734D3A71B53AD6917DBEBE9302898410.
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/CCORCHIIRCIRRIHIICRICRHOOOIOII",
                    "CHICHCRHIOHHHCOIHICOOIROOHROOH",
                    "IROHHROROIIRHCHIRHIRHICCCHIIHH",
                    "IHRCIHROCRICRCOOCIOIICIHICHICR",
                    "OOIRHOORCCHHRRICRRHIHICRIRCRRR"),
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/HCOOHCRIIRHROCROHROOOOORCRORIR",
                    "IORCHHHCCHIRIRCHCICCOCIORROOCH",
                    "OHRHCICRROOOCICOHOHHICIICRCHCI",
                    "IOHIHCOCIHCCIHOIOOOOOOIHCOOIIR",
                    "CROOIRIRHCIHOICOORIHIOCHRRRHOH"),
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/COOHIICHOIRRHOIHHOCOICHRRHHIIC",
                    "RORHRHIIICRHOHHOCHCHOCRHIHHORO",
                    "ORHOHRROORIHICICCHHOICORHCHOIC",
                    "CRHIICOOROHROCIRIOHRCOIOIHHHOO",
                    "COHIROCRCOIIOHCOHHCOIIIHOOOOHC"),
            // Verified from <clinit>'s Api/Authenticator/AssetServer/Styngr labels in
            // lunar.jar 0763A381214DA346E889565C695B12C794E08CFA2EB4053C3C46E3208774630E.
            new ServiceEndpointLayout(
                    "com/moonsworth/lunar/client/util/COOHIICHOIRRHOIHHOCOICHRRHHIIC",
                    "ICCOHRHCRCIRIRIOOCROROHORHRHHR",
                    "RIOOOHCHICCCOCRRHRICRIHRRIHORI",
                    "HHOIORHROICRCCIIOCHRORRHIHCOCO",
                    "RRIROIOIOHHROCCOHHOICIOOROOOCR")
    };
    private static final String[] LUNAR_AUTHENTICATOR_CLIENT_CLASSES = {
            // Verified from the active 2026-10-19 lunar.jar. The public build changes the
            // service base to the relay after native entitlements arrive, so this exact
            // AuthenticatorClient must rewrite its appended /game to the relay's /ws route.
            "com/moonsworth/lunar/client/CORRIROORHRIRIHCRHROIOCRCCIOIH/RRRRRIRCCIIORHHCCCHICOCRRCOOOH",
            "com/moonsworth/lunar/client/HRIIRRIOOCRCCIOHOOOOCCHCCICCHR/HRIIRRIOOCRCCIOHOOOOCCHCCICCHR",
            "com/moonsworth/lunar/client/COCIICOHOOCCHCHRIRHHCHORROOCOR/COCIICOHOOCCHCHRIRHHCHORROOCOR",
            "com/moonsworth/lunar/client/IOCIHHIRIROCOIROOCORCCIOOIIIRR/IOCIHHIRIROCOIROOCORCCIOOIIIRR"
    };
    // Reconnect entry points verified against the installed Lunar decompilation. Passing
    // null asks Lunar to obtain a fresh Authenticator JWT instead of reusing a route-specific
    // reconnect JWT when changing between official Lunar and the FullAccess API.
    private static final AssetsReconnectLayout[] LUNAR_ASSETS_RECONNECT_LAYOUTS = {
            // Verified against the active 2026-10-26 native lunar.jar
            // SHA-256 D7448213A6F4886769055292A5761470483D045A0962AAFDDA7FDC5C15F83A16.
            // The top-level Lunar client owns the AssetServer reconnect method in this build.
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/IOOCCRIRHHROHIHRCHHRRIOICHIOIR",
                    "OORHHHIIOHCRCIRICOIORCHHHRHORI",
                    "IOOCCRIRHHROHIHRCHHRRIOICHIOIR",
                    "com/moonsworth/lunar/client/RHOICRHRRHCICCRORRHHIRIIHHIIHI/ICCOCOHOCCOROROIRHCCRHICOOOHHC"),
            // Same 2026-10-22 JAR: the singleton getter was remapped again. Its public
            // nullable reconnect-info overload still obtains a fresh Authenticator JWT.
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/RRRRRIRCCIIORHHCCCHICOCRRCOOOH",
                    "IRHRHRCHOOOHOOIIRIOIICRIHHCCRC",
                    "RRRRRIRCCIIORHHCCCHICOCRRCOOOH",
                    "com/moonsworth/lunar/client/CORRIROORHRIRIHCRHROIOCRCCIOIH/HHOICIOHOOOIIRHCIOOCCCCIHROIRH"),
            // Same 2026-10-16 JAR: singleton getter and nullable reconnect-info overload.
            // A null argument builds the handshake on the client thread and requests fresh authentication.
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/CRHOIHOORHICOCOCCCCRRIRICCIRCR",
                    "ORHOIRRIRCICRHIOCHCHOORHORHHHO",
                    "CRHOIHOORHICOCOCCCCRRIRICCIRCR",
                    "com/moonsworth/lunar/client/HRIIRRIOOCRCCIOHOOOOCCHCCICCHR/ROROOCIIRHIOOCHORIOHIHHHOCHRIO"),
            // Current 2026-10-06 Lunar client: identified in the active lunar.jar by its
            // "Establishing connection" / "Connected to the AssetServer" call path. The
            // no-argument reconnect method delegates to the Authenticator with no reconnect JWT.
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/CRRHRIRIHOHRHRCCHRCOICHOOIHOIC",
                    "RCOHHCOHRHHOHORCICRICHICRHHCII",
                    "RRORIIHCOHHCHCROCOIOIOOORRRIII"),
            // Verified from the active 2026-10-20 lunar.jar, SHA-256
            // 6E1A2BB789CF00985DDAFDC4487F8EC0A2EE5FDCFE51E0C224011F09EDA2230C.
            // This Lunar release reconnects Assets through its nullable reconnect-info overload;
            // passing null deliberately requests a fresh Authenticator JWT on the relay route.
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/RRRRRIRCCIIORHHCCCHICOCRRCOOOH",
                    "IHRIHIHCICOOHOIOHHIRCCORHOOOOI",
                    "RRRRRIRCCIIORHHCCCHICOCRRCOOOH",
                    "com/moonsworth/lunar/client/CORRIROORHRIRIHCRHROIOCRCCIOIH/HHOICIOHOOOIIRHCIOOCCCCIHROIRH"),
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/IOHOHIHHHHORRCCRHCRCHCRIOIHRHH",
                    "CCCHRRHIOIIOICCIRHRIRCRRHIIIRR",
                    "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH",
                    "com/moonsworth/lunar/client/IOCIHHIRIROCOIROOCORCCIOOIIIRR/CIIHHRHHHIOIIHHRROOHRHICIHOOOC"),
            // Verified against the active lunar.jar (SHA-256 E0070E628B8A6B3D5DCED02B6C8D2C2B4A351AC61E47627E230931AC8F55A396)
            // on 2026-10-01. This manager emits Lunar's "Establishing connection" / "Connected
            // to the AssetServer" log messages and exposes the reconnect method below.
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/IOHOHIHHHHORRCCRHCRCHCRIOIHRHH",
                    "RICHRRHROOIIRHICCCCCHOIOHRIOCR",
                    "IOHOHIHHHHORRCCRHCRCHCRIOIHRHH",
                    "com/moonsworth/lunar/client/IOCIHHIRIROCOIROOCORCCIOOIIIRR/CIIHHRHHHIOIIHHRROOHRHICIHOOOC"),
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/RCRHRICCICICHOOCCCIRRCIRCICIOC",
                    "RROOCRCIRCROCCRRHIHRRHHRHOHRIC",
                    "RCRHRICCICICHOOCCCIRRCIRCICIOC",
                    "com/moonsworth/lunar/client/COCIICOHOOCCHCHRIRHHCHORROOCOR/OHRIIRCCRIROIORIIHHOCRRIICIROI"),
            new AssetsReconnectLayout(
                    "com/moonsworth/lunar/client/RRHORRHIIIOHHCICIORHHOHHOHIIHR",
                    "CCHHRCIHIRRCOCOHCCCRIHIOIICCRO",
                    "RRHORRHIIIOHHCICIORHHOHHOHIIHR",
                    "com/moonsworth/lunar/client/CIIOHCCOCRHORIRROROICHHRRRHHCC/CCHCHHCHHROIRICOOOCORIIHHOHCCR")
    };
    private static volatile boolean installed;
    private static volatile ServiceEndpointLayout[] activeServiceLayouts = LUNAR_SERVICE_LAYOUTS;
    private static volatile AssetsReconnectLayout[] activeAssetsReconnectLayouts = LUNAR_ASSETS_RECONNECT_LAYOUTS;
    /** Internal QA endpoint routing follows the persisted toggle and can change at runtime. */
    private static volatile boolean qaRelayRoutesEnabled;
    private static volatile boolean qaDirectAssetsRoute;
    private static volatile Instrumentation runtimeInstrumentation;
    private static volatile NativeLunarDebugTransformer runtimeTransformer;
    private static volatile boolean runtimeRetransformCapable;
    private static final Map<Class<?>, OriginalServiceEndpoints> ORIGINAL_SERVICE_ENDPOINTS =
            new WeakHashMap<Class<?>, OriginalServiceEndpoints>();
    private static final ScheduledExecutorService ASSETS_RECONNECT_RETRY =
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "gomod-lunar-assets-reconnect");
                thread.setDaemon(true);
                return thread;
            });
    private static long assetsReconnectRequestId;
    private static volatile String pendingAssetsReconnectReason;
    private static volatile String pendingAssetsReconnectWaitReason = "verified-asset-manager-not-loaded";
    private static volatile int assetsReconnectRetryAttempts;
    private static ScheduledFuture<?> assetsReconnectRetryTask;
    /** Tracks one pending client-thread handoff per reconnect generation. */
    private static long assetsReconnectAttemptQueuedFor = Long.MIN_VALUE;
    private static final long ASSETS_RECONNECT_RETRY_INTERVAL_MS = 10_000L;
    private static final int ASSETS_RECONNECT_RETRY_LIMIT = 120;
    private LunarRelayWeaveBootstrap() {
    }

    public static String getFullAccessReconnectWaitCode() {
        String reason = pendingAssetsReconnectWaitReason;
        return pendingAssetsReconnectReason == null ? "idle" : reason == null ? "starting" : reason;
    }

    /** Reads cached compatibility and dispatcher progress without blocking on native discovery. */
    public static JsonObject getConnectionUsageSnapshot() {
        JsonObject result = LunarRemoteCompatibility.usageSnapshot();
        result.addProperty("reconnectState", getFullAccessReconnectWaitCode());
        result.addProperty("reconnectAttempts", assetsReconnectRetryAttempts);
        return result;
    }

    /** Reads existing dispatcher progress without invoking native Lunar or taking its lock. */
    public static String getFullAccessReconnectWaitMessage() {
        if (pendingAssetsReconnectReason == null) return "";
        String reason = pendingAssetsReconnectWaitReason;
        if ("waiting-for-compatibility-mapping".equals(reason)) {
            String compatibilityWait = LunarRemoteCompatibility.getCompatibilityWaitMessage();
            return compatibilityWait.isEmpty()
                    ? "Waiting for a signed Lunar compatibility mapping; automatic reconnect is paused."
                    : compatibilityWait;
        }
        if ("verified-asset-manager-not-loaded".equals(reason)) {
            return "Waiting for a supported Lunar connection manager; a compatibility update may be needed.";
        }
        if ("asset-manager-not-ready".equals(reason)) {
            return "Waiting for Lunar's connection manager to finish starting.";
        }
        if ("minecraft-client-not-ready".equals(reason)) return "Waiting for Minecraft to finish starting.";
        if ("client-thread-queue-failed".equals(reason)) return "Waiting to retry the connection on the game thread.";
        return "Starting the FullAccess connection on the game thread.";
    }

    static void install(Instrumentation instrumentation) {
        if (installed) {
            return;
        }
        installed = true;

        boolean internalQaBuild = !GomodReleaseBuild.PUBLIC_RELEASE;
        runtimeInstrumentation = instrumentation;
        LunarConnectionDiagnostics.start(GomodVersion.get(), internalQaBuild);
        // Public builds silently use the FullAccess route by design; internal builds retain
        // the developer's saved preference.
        boolean fullAccessRequested = readFullAccessEnabledAtStartup();
        qaRelayRoutesEnabled = startupRelayRouteEnabled(fullAccessRequested,
                GomodReleaseBuild.QA_DIRECT_ASSETS_TEST);
        qaDirectAssetsRoute = qaRelayRoutesEnabled && GomodReleaseBuild.QA_DIRECT_ASSETS_TEST;
        me.napim.gomod.features.LunarOnlineStatus.initializeRuntimeRoutes(
                fullAccessRequested, qaRelayRoutesEnabled);
        LunarConnectionDiagnostics.record("bootstrap.start", "runtime=weave internalQa=" + internalQaBuild
                + " relayRoutesEnabled=" + qaRelayRoutesEnabled
                + " fullAccessRequested=" + fullAccessRequested
                + " readinessBeforeRelay=" + (fullAccessRequested && !qaRelayRoutesEnabled)
                + " directAssetsTest=" + qaDirectAssetsRoute
                + " webSocketDiagnosticsDisabled="
                + GomodReleaseBuild.QA_DISABLE_WEBSOCKET_DIAGNOSTICS);
        if (fullAccessRequested) {
            setRelayProperty("celestialApiAddress", RELAY_HOST + ":443");
            setRelayProperty("celestialApiUseEncryption", "true");
            if (qaRelayRoutesEnabled) {
                LunarConnectionDiagnostics.record("endpoint.configured",
                        "service=auth route=gomod-relay " + endpointLabel(RELAY_AUTHENTICATOR)
                                + " final-path=/ws suffix-transform-required=true");
                LunarConnectionDiagnostics.record("endpoint.configured",
                        "service=assets route=" + (qaDirectAssetsRoute ? "direct-api-test" : "gomod-relay")
                                + " base=" + endpointLabel(assetServerEndpoint())
                                + " final-path=/ws/game");
                System.out.println("[gomod] QA Lunar routes use the Gomod relay (FullAccess enabled)");
            } else {
                // Keep Lunar's official endpoints intact. Relay endpoints are applied to
                // the resolved service fields only when the FullAccess route is active.
                LunarConnectionDiagnostics.record("endpoint.configured",
                        "service=auth route=lunar-official final-path=/game");
                LunarConnectionDiagnostics.record("endpoint.configured",
                        "service=assets route=lunar-official final-path=/game");
                LunarConnectionDiagnostics.record("endpoint.configured",
                        "service=rest-api route=lunar-official default=true");
                LunarConnectionDiagnostics.record("endpoint.configured",
                        "service=styngr route=lunar-official default=true");
                System.out.println(fullAccessRequested
                        ? "[gomod] Preserving official Lunar while FullAccess readiness is checked in the background"
                        : "[gomod] QA FullAccess is disabled; preserving Lunar's official service routes");
            }
        }

        NativeLunarDebugTransformer transformer = new NativeLunarDebugTransformer();
        runtimeTransformer = transformer;
        boolean retransformCapable = false;
        try {
            instrumentation.addTransformer(transformer, true);
            retransformCapable = true;
            System.out.println("[gomod] Lunar relay/compatibility transformer installed (retransform-capable)");
            LunarConnectionDiagnostics.record("instrumentation.installed", "retransformCapable=true");
        } catch (Throwable firstFailure) {
            try {
                instrumentation.addTransformer(transformer);
                System.out.println("[gomod] Lunar relay/compatibility transformer installed");
                LunarConnectionDiagnostics.record("instrumentation.installed", "retransformCapable=false");
            } catch (Throwable secondFailure) {
                installed = false;
                LunarConnectionDiagnostics.record("instrumentation.install_failed",
                        "exception=" + secondFailure.getClass().getName()
                                + " message=" + secondFailure.getMessage());
                System.err.println("[gomod] Could not install Lunar relay/compatibility transformer: "
                        + secondFailure.getClass().getSimpleName() + ": " + secondFailure.getMessage());
            }
        }
        runtimeRetransformCapable = retransformCapable;
        LunarCosmeticAppearanceTransform.install(instrumentation, retransformCapable);
        LunarStudioTransform.install(instrumentation, retransformCapable);

        if (retransformCapable) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if ((LunarNametagDisguiseTransform.TARGET.replace('/', '.').equals(loaded.getName())
                        || LunarNametagLogoTransform.isTarget(loaded.getName().replace('.', '/'))
                        || me.napim.gomod.weave.hooks.LunarHypixelNametagWeaveHook.isTarget(
                                loaded.getName().replace('.', '/'))
                        || LunarGekFirstPersonDisguiseTransform.isTarget(loaded.getName().replace('.', '/'))
                        || LunarRemoteGekDisguiseTransform.TARGET.replace('/', '.').equals(loaded.getName())
                        || LunarDisguiserBadgeTransform.isTarget(loaded.getName().replace('.', '/'))
                        || LunarOpenALAttributeTransform.TARGET.replace('/', '.').equals(loaded.getName())
                        || LunarEmoteWheelPersistenceTransform.isLoadedTarget(loaded)
                        || LunarOutfitPersistenceTransform.isLoadedTarget(loaded)
                        || EMOTE_WHEEL_BUILDER_CLASS.replace('/', '.').equals(loaded.getName())
                        || EMOTE_LOGIN_PARSER_CLASS.replace('/', '.').equals(loaded.getName())
                        || LunarArmDebugTransform.isTarget(loaded.getName().replace('.', '/'))
                        || LunarMenuDebugTransform.isTarget(loaded.getName().replace('.', '/'))
                        || LunarLeaderboardHologramTransform.TARGET.replace('/', '.').equals(loaded.getName())
                        || LunarWorldLabelProbeTransform.isLoadedTarget(loaded)
                        || LunarTabVictimDrawingTransform.isTarget(loaded.getName().replace('.', '/'))
                        || LunarTabVictimDrawingTransform.isLoadedRenderer(loaded)
                        || LunarInventoryQolHotbarTransform.isTarget(loaded.getName().replace('.', '/'))
                        || LunarColorCompatibilityTransform.isLoadedTarget(loaded)
                        || LunarConnectionLogoTransform.isLoadedTarget(loaded)
                        || LunarClothCloakAccessTransform.isLoadedTarget(loaded)
                        || LunarCatalogCompatibilityTransform.isLoadedTarget(loaded)
                        || LunarToggleSprintCompatibilityTransform.isLoadedTarget(loaded)
                        || LunarSidebarBackgroundTransform.TARGET.replace('/', '.').equals(loaded.getName())
                        || LunarMenuSearchFocusTransform.isLoadedTarget(loaded)
                        || LunarHypixelBlitzStatsTransform.isLoadedTarget(loaded)
                        || LunarQuickplayPersistenceTransform.isLoadedTarget(loaded))
                        && instrumentation.isModifiableClass(loaded)) {
                    try {
                        instrumentation.retransformClasses(loaded);
                    } catch (Throwable error) {
                        LunarConnectionDiagnostics.record("instrumentation.lunar_renderer_retransform_failed",
                                "target=" + loaded.getName() + " exception=" + error.getClass().getSimpleName());
                    }
                }
            }
        }

        retransformLoadedGoparkyMolangQuery(instrumentation, retransformCapable);

        transformer.retransformLoadedWebSocketClient(instrumentation, retransformCapable);
        // Keep the payload-free PONG liveness hook in public builds too. Their FullAccess
        // sockets can be healthy while idle, with no application frames to refresh the status.
        transformer.retransformLoadedWebSocketImpl(instrumentation, retransformCapable);
        if (internalQaBuild && !GomodReleaseBuild.QA_DISABLE_WEBSOCKET_DIAGNOSTICS) {
            transformer.retransformLoadedWebSocketClientWriteThread(instrumentation, retransformCapable);
        }
        // The constructor helper derives its suffix from the endpoint passed at runtime.
        // Install it once even when FullAccess starts disabled; toggling routes needs no JVM retransform.
        transformer.retransformLoadedAuthenticatorClient(instrumentation, retransformCapable);
        transformer.retransformLoadedAuthSuccessClient(instrumentation, retransformCapable);

        if (internalQaBuild) {
            forceLoadedQaOverrides(instrumentation);
            recordRuntimeRoutes("startup");
        }
        if (internalQaBuild) {
            retransformLoadedEmoteLoginResponse(instrumentation, retransformCapable);
            retransformLoadedCosmeticV2LoginResponse(instrumentation, retransformCapable);
        } else {
            retransformLoadedPublicCatalogResponses(instrumentation, retransformCapable);
        }
        LunarRemoteCompatibility.start(instrumentation);
    }

    private static void retransformLoadedGoparkyMolangQuery(Instrumentation instrumentation,
                                                             boolean retransformCapable) {
        String targetName = me.napim.gomod.weave.hooks.GoparkyMolangQueryTransform.TARGET.replace('/', '.');
        String targetPrefix = targetName.substring(0, targetName.indexOf('.',
                "com.moonsworth.lunar.client.".length())) + ".";
        int loadedCandidates = 0;
        int retransformRequested = 0;
        int failures = 0;
        boolean exactTargetLoaded = false;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!targetName.equals(loaded.getName()) && !loaded.getName().startsWith(targetPrefix)) continue;
            loadedCandidates++;
            exactTargetLoaded |= targetName.equals(loaded.getName());
            if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) continue;
            try {
                instrumentation.retransformClasses(loaded);
                retransformRequested++;
            } catch (Throwable error) {
                failures++;
            }
        }
        LunarConnectionDiagnostics.record("lunar.goparky_molang_query_retransform_scan",
                "exactTargetLoaded=" + exactTargetLoaded + " packagePrefix=" + targetPrefix
                        + " loadedCandidates=" + loadedCandidates + " requested=" + retransformRequested
                        + " failures=" + failures + " retransformCapable=" + retransformCapable);
        if (!exactTargetLoaded) {
            LunarConnectionDiagnostics.record("lunar.goparky_molang_query_class_not_loaded",
                    "exact-name-absent=true package-scan-armed=true");
        }
    }

    /** Only the explicit direct-Assets QA experiment bypasses normal readiness-first startup. */
    private static boolean startupRelayRouteEnabled(boolean requested, boolean directAssetsTest) {
        return requested && directAssetsTest;
    }

    static boolean shouldDeferFullAccessRoute(boolean enabled, boolean directAssetsQa,
            boolean officialOwnershipReady) {
        return enabled && !directAssetsQa && !officialOwnershipReady;
    }

    /**
     * Read only the one persisted switch needed before Lunar initializes its service
     * constants. Avoid ConfigManager here: Weave pre-init runs before Minecraft's
     * registries are ready, while ConfigManager's full load may initialize game classes.
     * Missing or unreadable config keeps the historical QA default (FullAccess enabled).
     */
    private static boolean readFullAccessEnabledAtStartup() {
        File configFile = new File(GomodPaths.getGomod123Dir(), "gomodConfig.json");
        if (!configFile.isFile()) {
            return true;
        }
        try (FileReader reader = new FileReader(configFile)) {
            JsonElement parsed = new JsonParser().parse(reader);
            if (parsed == null || !parsed.isJsonObject()) {
                return true;
            }
            JsonObject config = parsed.getAsJsonObject();
            JsonElement enabled = config.get("lunarTweaksEnabled");
            if (enabled != null && enabled.isJsonPrimitive()
                    && enabled.getAsJsonPrimitive().isBoolean()) {
                return enabled.getAsBoolean();
            }
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("endpoint.config_read_failed",
                    "setting=lunarTweaksEnabled exception=" + error.getClass().getSimpleName()
                            + " fallback=enabled");
        }
        return true;
    }

    private static void setRelayProperty(String name, String value) {
        String previous = System.getProperty(name);
        if (!value.equals(previous)) {
            System.setProperty(name, value);
            System.out.println("[gomod] Internal QA relay property: " + name + "=" + value);
        }
    }

    private static String assetServerEndpoint() {
        return qaDirectAssetsRoute ? DIRECT_ASSETS_TEST : RELAY_ASSET_SERVER;
    }

    /** Called by the shared settings/status layer when FullAccess is toggled or fails. */
    public static synchronized void setRuntimeFullAccessRoute(boolean enabled, String reason) {
        if (!installed || runtimeInstrumentation == null) {
            return;
        }
        if (shouldDeferFullAccessRoute(enabled, GomodReleaseBuild.QA_DIRECT_ASSETS_TEST,
                me.napim.gomod.features.LunarPublicLoginGuard.isOfficialOwnershipReady())) {
            System.out.println("[gomod] FullAccess retains official route until native entitlements are received");
            return; // Existing readiness retries try again; do not switch before official ownership is captured.
        }
        if (qaRelayRoutesEnabled == enabled) {
            // A FullAccess login response can remain cached after the toggle was enabled while
            // the official route was already active. Re-login on an explicit disable even when
            // no endpoint value needs changing, so Lunar rebuilds its owned-emote list from the
            // account response instead of retaining the prior all-emotes result.
            if (!enabled && "user-toggle".equals(reason)) {
                requestAssetsReconnect(reason);
            }
            return;
        }

        long routeSetupStartedNanos = System.nanoTime();
        qaRelayRoutesEnabled = enabled;
        qaDirectAssetsRoute = enabled && GomodReleaseBuild.QA_DIRECT_ASSETS_TEST;
        me.napim.gomod.features.LunarTabLogoCompatibility.clearRouteLogoWidths();
        LunarConnectionDiagnostics.record("nametag.logo_width_cache_cleared",
                "reason=fullaccess-route-change route=" + (enabled ? "relay" : "official"));
        applyLoadedServiceRoutes(runtimeInstrumentation, reason);
        // resolveAuthenticatorEndpoint handles official/relay bases dynamically. Reapplying its
        // class transform here forces VM deoptimization during an otherwise ordinary reconnect.
        me.napim.gomod.features.LunarOnlineStatus.onRuntimeRouteChanged(enabled, reason);
        recordRuntimeRoutes(reason);
        long routeSetupMs = Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - routeSetupStartedNanos));
        if (routeSetupMs >= 20L) {
            LunarConnectionDiagnostics.record("endpoint.runtime_route_setup_slow",
                    "elapsedMs=" + routeSetupMs + " thread=" + Thread.currentThread().getName()
                            + " route=" + (enabled ? "relay" : "official")
                            + " reason=" + safeReason(reason));
        }
        requestAssetsReconnect(reason);
    }

    public static synchronized void retryRuntimeFullAccessRoute(long expectedGeneration) {
        if (!me.napim.gomod.features.LunarOnlineStatus.isFullAccessRecoveryDesired(expectedGeneration)) return;
        setRuntimeFullAccessRoute(true, "api-ready-auto-retry");
    }

    /** Background handoff after signed-registry or local structural/native-bytecode validation. */
    static synchronized int applyRemoteCompatibility(LunarCompatibilityManifest.Mapping mapping,
            File nativeJar, Class<?>[] loaded) throws Exception {
        if (!installed || GomodReleaseBuild.QA_DIRECT_ASSETS_TEST
                || !me.napim.gomod.features.LunarOnlineStatus.isFullAccessPreferenceEnabled()) return 0;
        Class<?> service = null, manager = null;
        for (Class<?> type : loaded) {
            if (type.getName().equals(mapping.serviceClass.replace('/', '.'))) service = type;
            if (type.getName().equals(mapping.managerClass.replace('/', '.'))) manager = type;
        }
        for (Class<?> type : new Class<?>[]{service, manager}) {
            if (type != null && (type.getProtectionDomain().getCodeSource() == null
                    || !nativeJar.getCanonicalFile().equals(new File(
                            type.getProtectionDomain().getCodeSource().getLocation().toURI()).getCanonicalFile()))) {
                throw new IllegalArgumentException("Remote mapping class has a different native origin");
            }
        }
        if (service != null) {
            for (String name : new String[]{mapping.api, mapping.authenticator, mapping.assets, mapping.styngr}) {
                Field field = service.getDeclaredField(name);
                if (field.getType() != String.class || !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalArgumentException("Remote service field has an unsupported runtime shape");
                }
            }
        }
        if (manager != null) {
            java.lang.reflect.Method getter = manager.getMethod(mapping.getter);
            if (!java.lang.reflect.Modifier.isStatic(getter.getModifiers()) || getter.getReturnType() != manager) {
                throw new IllegalArgumentException("Remote manager getter has an unsupported runtime shape");
            }
            boolean reconnectFound = false;
            for (java.lang.reflect.Method method : manager.getMethods()) {
                if (method.getName().equals(mapping.connect) && method.getReturnType() == void.class
                        && !java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].getName().equals(mapping.infoClass.replace('/', '.'))) {
                    reconnectFound = true;
                }
            }
            if (!reconnectFound) throw new IllegalArgumentException("Remote reconnect signature is unavailable");
        }
        boolean healthy = me.napim.gomod.features.LunarOnlineStatus.isFullAccessConnected();
        ServiceEndpointLayout existing = service == null ? null : serviceLayoutForClass(service);
        boolean sameService = existing != null && existing.apiField.equals(mapping.api)
                && existing.authenticatorField.equals(mapping.authenticator)
                && existing.assetServerField.equals(mapping.assets) && existing.styngrField.equals(mapping.styngr);
        boolean sameManager = false;
        for (AssetsReconnectLayout layout : activeAssetsReconnectLayouts) {
            if (layout.managerClass.equals(mapping.managerClass) && layout.managerGetter.equals(mapping.getter)
                    && layout.connectMethod.equals(mapping.connect) && mapping.infoClass.equals(layout.reconnectInfoClass)) {
                sameManager = true;
            }
        }
        // A newly published mapping must not tear down a connection that is already working.
        if (healthy && (!sameService || !sameManager)) return 0;
        if (!healthy && me.napim.gomod.features.LunarOnlineStatus.hasCurrentFullAccessConnectionInProgressOrOpen()) return 0;
        ServiceEndpointLayout remoteService = new ServiceEndpointLayout(mapping.serviceClass, mapping.api,
                mapping.authenticator, mapping.assets, mapping.styngr);
        AssetsReconnectLayout remoteManager = new AssetsReconnectLayout(mapping.managerClass,
                mapping.getter, mapping.connect, mapping.infoClass);
        ServiceEndpointLayout[] services = new ServiceEndpointLayout[LUNAR_SERVICE_LAYOUTS.length + 1];
        services[0] = remoteService;
        System.arraycopy(LUNAR_SERVICE_LAYOUTS, 0, services, 1, LUNAR_SERVICE_LAYOUTS.length);
        AssetsReconnectLayout[] managers = new AssetsReconnectLayout[LUNAR_ASSETS_RECONNECT_LAYOUTS.length + 1];
        managers[0] = remoteManager;
        System.arraycopy(LUNAR_ASSETS_RECONNECT_LAYOUTS, 0, managers, 1, LUNAR_ASSETS_RECONNECT_LAYOUTS.length);
        activeServiceLayouts = services;
        activeAssetsReconnectLayouts = managers;
        if (healthy) return 1; // This exact mapping already produced fresh FullAccess activity.
        // Register a newly verified, already-loaded owner even while ownership/readiness keeps
        // the official route active. Later toggles use this registry without a client-thread VM scan.
        if (service != null) synchronized (ORIGINAL_SERVICE_ENDPOINTS) {
            if (!ORIGINAL_SERVICE_ENDPOINTS.containsKey(service)) {
                ORIGINAL_SERVICE_ENDPOINTS.put(service, new OriginalServiceEndpoints(
                        readStaticString(service, mapping.authenticator), readStaticString(service, mapping.assets),
                        readStaticString(service, mapping.api), readStaticString(service, mapping.styngr)));
            }
        }
        if (service == null || !me.napim.gomod.features.LunarPublicLoginGuard.isOfficialOwnershipReady()
                || !qaRelayRoutesEnabled) return 3; // Normal readiness/account guards own the handoff.
        captureServiceDefaults(service, readStaticString(service, mapping.authenticator),
                readStaticString(service, mapping.assets), readStaticString(service, mapping.api),
                readStaticString(service, mapping.styngr));
        if (manager == null) return 3; // Existing deferred class-load reconnect will use the new mapping.
        me.napim.gomod.features.LunarOnlineStatus.onRuntimeRouteChanged(true, "relay-compatibility-update");
        requestAssetsReconnect("relay-compatibility-update");
        LunarConnectionDiagnostics.record("lunar.compatibility_handoff", "mapping=" + mapping.id
                + " nativeValidation=passed clientThreadDispatcher=true");
        return 2;
    }

    /** Captures Lunar's unmodified endpoints after its initializer, then applies current QA mode. */
    public static void captureServiceDefaults(Class<?> owner, String authenticator,
                                              String assetServer, String api, String styngr) {
        if (owner == null) {
            return;
        }
        ServiceEndpointLayout layout = serviceLayoutForClass(owner);
        if (layout == null) {
            return;
        }
        synchronized (ORIGINAL_SERVICE_ENDPOINTS) {
            if (!ORIGINAL_SERVICE_ENDPOINTS.containsKey(owner)) {
                ORIGINAL_SERVICE_ENDPOINTS.put(owner,
                        new OriginalServiceEndpoints(authenticator, assetServer, api, styngr));
            }
        }
        try {
            applyServiceRoute(owner, layout, qaRelayRoutesEnabled);
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("endpoint.apply_failed",
                    "service=resolved-endpoints service-class=" + layout.binaryName()
                            + " exception=" + error.getClass().getName());
        }
    }

    private static void forceLoadedQaOverrides(Instrumentation instrumentation) {
        boolean found = false;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            ServiceEndpointLayout layout = serviceLayoutForClass(loaded);
            if (layout != null) {
                found = true;
                try {
                    OriginalServiceEndpoints defaults = new OriginalServiceEndpoints(
                            readStaticString(loaded, layout.authenticatorField),
                            readStaticString(loaded, layout.assetServerField),
                            readStaticString(loaded, layout.apiField),
                            readStaticString(loaded, layout.styngrField));
                    synchronized (ORIGINAL_SERVICE_ENDPOINTS) {
                        if (!ORIGINAL_SERVICE_ENDPOINTS.containsKey(loaded)) {
                            ORIGINAL_SERVICE_ENDPOINTS.put(loaded, defaults);
                        }
                    }
                    applyServiceRoute(loaded, layout, qaRelayRoutesEnabled);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("endpoint.apply_failed",
                            "service=resolved-endpoints service-class=" + layout.binaryName()
                                    + " exception=" + error.getClass().getName()
                                    + " message=" + error.getMessage());
                }
            }
        }
        if (!found) {
            LunarConnectionDiagnostics.record("endpoint.apply_pending",
                    "service-class=not-loaded layouts=" + LUNAR_SERVICE_LAYOUTS.length
                            + " transformer-will-apply-on-definition=true");
        }
    }

    private static void applyLoadedServiceRoutes(Instrumentation instrumentation, String reason) {
        // Endpoint owners are registered at initialization (or by the startup loaded-class pass).
        // A user toggle must not request another VM-wide class snapshot on Minecraft's thread.
        final Class<?>[] owners;
        synchronized (ORIGINAL_SERVICE_ENDPOINTS) {
            owners = ORIGINAL_SERVICE_ENDPOINTS.keySet().toArray(new Class<?>[0]);
        }
        for (Class<?> loaded : owners) {
            ServiceEndpointLayout layout = serviceLayoutForClass(loaded);
            if (layout == null) {
                continue;
            }
            try {
                synchronized (ORIGINAL_SERVICE_ENDPOINTS) {
                    if (!ORIGINAL_SERVICE_ENDPOINTS.containsKey(loaded)) {
                        ORIGINAL_SERVICE_ENDPOINTS.put(loaded, new OriginalServiceEndpoints(
                                readStaticString(loaded, layout.authenticatorField),
                                readStaticString(loaded, layout.assetServerField),
                                readStaticString(loaded, layout.apiField),
                                readStaticString(loaded, layout.styngrField)));
                    }
                }
                applyServiceRoute(loaded, layout, qaRelayRoutesEnabled);
                LunarConnectionDiagnostics.record("endpoint.runtime_applied",
                        "service-class=" + layout.binaryName()
                                + " route=" + (qaRelayRoutesEnabled ? "gomod-relay" : "lunar-official")
                                + " reason=" + safeReason(reason));
            } catch (Throwable error) {
                LunarConnectionDiagnostics.record("endpoint.runtime_apply_failed",
                        "service-class=" + layout.binaryName()
                                + " exception=" + error.getClass().getSimpleName());
            }
        }
    }

    private static void applyServiceRoute(Class<?> owner, ServiceEndpointLayout layout, boolean relay) throws Exception {
        OriginalServiceEndpoints defaults;
        synchronized (ORIGINAL_SERVICE_ENDPOINTS) {
            defaults = ORIGINAL_SERVICE_ENDPOINTS.get(owner);
        }
        if (defaults == null) {
            return;
        }
        setStaticString(owner, layout.authenticatorField, relay ? RELAY_AUTHENTICATOR : defaults.authenticator);
        setStaticString(owner, layout.assetServerField,
                relay ? assetServerEndpoint() : defaults.assetServer);
        setStaticString(owner, layout.apiField, relay ? RELAY_API : defaults.api);
        setStaticString(owner, layout.styngrField, relay ? RELAY_STYNGR : defaults.styngr);
    }

    private static String readStaticString(Class<?> owner, String fieldName) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String) field.get(null);
    }

    private static void recordRuntimeRoutes(String reason) {
        boolean relay = qaRelayRoutesEnabled;
        LunarConnectionDiagnostics.record("qa.routes",
                "auth=" + (relay ? "gomod-relay" : "lunar-official")
                        + " assets=" + (relay ? (qaDirectAssetsRoute ? "direct-api-test" : "gomod-relay")
                        : "lunar-official")
                        + " rest-api=" + (relay ? "gomod-relay" : "lunar-official")
                        + " styngr=" + (relay ? "gomod-relay" : "lunar-official")
                        + " reason=" + safeReason(reason));
    }

    private static boolean shouldWaitForCompatibilityMapping() {
        return qaRelayRoutesEnabled
                && me.napim.gomod.features.LunarOnlineStatus.isFullAccessPreferenceEnabled()
                && LunarRemoteCompatibility.isCurrentBuildMissingFromRegistry();
    }

    private static boolean isReconnectPausedForCompatibilityMapping() {
        return "waiting-for-compatibility-mapping".equals(pendingAssetsReconnectWaitReason);
    }

    /** Stop the unsupported-build discovery loop until the signed registry selects this build. */
    static synchronized void pauseAssetsReconnectForCompatibilityMapping() {
        if (!installed || !qaRelayRoutesEnabled
                || !me.napim.gomod.features.LunarOnlineStatus.isFullAccessPreferenceEnabled()
                || !LunarRemoteCompatibility.isCurrentBuildMissingFromRegistry()
                || me.napim.gomod.features.LunarOnlineStatus.hasCurrentFullAccessConnectionInProgressOrOpen()) {
            return;
        }
        if (isReconnectPausedForCompatibilityMapping()) {
            if (assetsReconnectRetryTask != null) {
                assetsReconnectRetryTask.cancel(false);
                assetsReconnectRetryTask = null;
            }
            assetsReconnectAttemptQueuedFor = Long.MIN_VALUE;
            return;
        }
        int stoppedAttempts = assetsReconnectRetryAttempts;
        ++assetsReconnectRequestId; // Invalidate any retry already queued to Minecraft's client thread.
        pendingAssetsReconnectReason = "compatibility-mapping-required";
        pendingAssetsReconnectWaitReason = "waiting-for-compatibility-mapping";
        assetsReconnectAttemptQueuedFor = Long.MIN_VALUE;
        if (assetsReconnectRetryTask != null) {
            assetsReconnectRetryTask.cancel(false);
            assetsReconnectRetryTask = null;
        }
        LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_paused",
                "reason=missing-signed-compatibility-mapping stoppedAttempts=" + stoppedAttempts
                        + " registryRevision=" + LunarRemoteCompatibility.usageSnapshot()
                                .get("registryRevision"));
    }

    private static synchronized void requestAssetsReconnect(String reason) {
        if (shouldWaitForCompatibilityMapping()) {
            pauseAssetsReconnectForCompatibilityMapping();
            return;
        }
        long requestId = ++assetsReconnectRequestId;
        pendingAssetsReconnectReason = safeReason(reason);
        pendingAssetsReconnectWaitReason = "verified-asset-manager-not-loaded";
        assetsReconnectRetryAttempts = 0;
        if (assetsReconnectRetryTask != null) {
            assetsReconnectRetryTask.cancel(false);
            assetsReconnectRetryTask = null;
        }
        ASSETS_RECONNECT_RETRY.execute(() -> runAssetsReconnectAttempt(requestId, false));
    }

    private static void runAssetsReconnectAttempt(long requestId, boolean retry) {
        synchronized (LunarRelayWeaveBootstrap.class) {
            if (requestId != assetsReconnectRequestId || pendingAssetsReconnectReason == null) return;
            if (isReconnectPausedForCompatibilityMapping()) return;
            if (shouldWaitForCompatibilityMapping()) {
                pauseAssetsReconnectForCompatibilityMapping();
                return;
            }
            if (assetsReconnectAttemptQueuedFor == requestId) return;
        }

        // Lunar's AssetServer reconnect path reads OpenGL-backed client state. The recovery
        // watchdog and retry executor are background threads, so always hand this invocation
        // back to Minecraft's client thread before calling into Lunar.
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft == null) {
            synchronized (LunarRelayWeaveBootstrap.class) {
                if (requestId == assetsReconnectRequestId && pendingAssetsReconnectReason != null) {
                    deferAssetsReconnect(requestId, "minecraft-client-not-ready");
                }
            }
            return;
        }
        if (minecraft.isCallingFromMinecraftThread()) {
            ASSETS_RECONNECT_RETRY.execute(() -> runAssetsReconnectAttempt(requestId, retry));
            return;
        }
        Instrumentation instrumentation = runtimeInstrumentation;
        if (instrumentation == null) return;
        // Discovery crosses the VM boundary. Keep it off the client thread and outside our
        // monitor so a queued client callback never waits for a full loaded-class snapshot.
        final Class<?>[] loadedClasses = instrumentation.getAllLoadedClasses();
        final String reason;
        synchronized (LunarRelayWeaveBootstrap.class) {
            if (requestId != assetsReconnectRequestId || pendingAssetsReconnectReason == null) return;
            if (assetsReconnectAttemptQueuedFor == requestId) return;
            assetsReconnectAttemptQueuedFor = requestId;
            reason = pendingAssetsReconnectReason;
        }
        final long queuedAtNanos = System.nanoTime();
        try {
            minecraft.addScheduledTask(() -> {
                synchronized (LunarRelayWeaveBootstrap.class) {
                    if (assetsReconnectAttemptQueuedFor == requestId) {
                        assetsReconnectAttemptQueuedFor = Long.MIN_VALUE;
                    }
                }
                runAssetsReconnectAttemptOnClientThread(requestId, retry, queuedAtNanos, loadedClasses);
            });
            LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_queued",
                    "route=" + (qaRelayRoutesEnabled ? "relay" : "official")
                            + " reason=" + safeReason(reason)
                            + " target=minecraft-client-thread discovery=background");
        } catch (Throwable error) {
            synchronized (LunarRelayWeaveBootstrap.class) {
                if (assetsReconnectAttemptQueuedFor == requestId) {
                    assetsReconnectAttemptQueuedFor = Long.MIN_VALUE;
                }
                if (requestId == assetsReconnectRequestId && pendingAssetsReconnectReason != null) {
                    deferAssetsReconnect(requestId, "client-thread-queue-failed");
                }
            }
            LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_queue_failed",
                    "exception=" + error.getClass().getSimpleName());
        }
    }

    private static synchronized void runAssetsReconnectAttemptOnClientThread(
            long requestId, boolean retry, long queuedAtNanos, Class<?>[] loadedClasses) {
        if (requestId != assetsReconnectRequestId || pendingAssetsReconnectReason == null) {
            return;
        }
        if (isReconnectPausedForCompatibilityMapping()) return;
        if (shouldWaitForCompatibilityMapping()) {
            pauseAssetsReconnectForCompatibilityMapping();
            return;
        }
        long clientQueueWaitMs = queuedAtNanos == 0L ? 0L
                : Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAtNanos));

        // Lunar can begin its own Auth/Assets flow as soon as the endpoint fields change. A
        // delayed class-load retry used to call connect() again even when that flow was already
        // open, closing the working socket and repeating authentication on the client thread.
        if (qaRelayRoutesEnabled
                && me.napim.gomod.features.LunarOnlineStatus.hasCurrentFullAccessConnectionInProgressOrOpen()) {
            pendingAssetsReconnectReason = null;
            pendingAssetsReconnectWaitReason = null;
            if (assetsReconnectRetryTask != null) {
                assetsReconnectRetryTask.cancel(false);
                assetsReconnectRetryTask = null;
            }
            LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_skipped",
                    "route=relay reason=fullaccess-connection-already-in-progress-or-open"
                            + " clientQueueWaitMs=" + clientQueueWaitMs);
            return;
        }
        if (retry && assetsReconnectRetryAttempts >= ASSETS_RECONNECT_RETRY_LIMIT) {
            if (assetsReconnectRetryTask != null) {
                assetsReconnectRetryTask.cancel(false);
                assetsReconnectRetryTask = null;
            }
            LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_retry_expired",
                    "reason=" + pendingAssetsReconnectWaitReason
                            + " attempts=" + assetsReconnectRetryAttempts
                            + " route=" + (qaRelayRoutesEnabled ? "relay" : "official"));
            return;
        }
        if (retry) {
            assetsReconnectRetryAttempts++;
        }

        long attemptStartedNanos = System.nanoTime();
        ReconnectAttempt result = attemptAssetsReconnect(pendingAssetsReconnectReason, loadedClasses);
        long attemptMs = Math.max(0L,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - attemptStartedNanos));
        if (result == ReconnectAttempt.REQUESTED || result == ReconnectAttempt.FAILED
                || clientQueueWaitMs >= 100L || attemptMs >= 20L) {
            LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_attempt",
                    "result=" + result.name().toLowerCase(java.util.Locale.ROOT)
                            + " route=" + (qaRelayRoutesEnabled ? "relay" : "official")
                            + " clientQueueWaitMs=" + clientQueueWaitMs
                            + " attemptMs=" + attemptMs
                            + " thread=" + Thread.currentThread().getName());
        }
        if (result == ReconnectAttempt.REQUESTED || result == ReconnectAttempt.FAILED) {
            pendingAssetsReconnectReason = null;
            pendingAssetsReconnectWaitReason = null;
            if (assetsReconnectRetryTask != null) {
                assetsReconnectRetryTask.cancel(false);
                assetsReconnectRetryTask = null;
            }
            return;
        }
        pendingAssetsReconnectWaitReason = result == ReconnectAttempt.MANAGER_NOT_READY
                ? "asset-manager-not-ready" : "verified-asset-manager-not-loaded";

        deferAssetsReconnect(requestId, pendingAssetsReconnectWaitReason);
    }

    private static void deferAssetsReconnect(long requestId, String waitReason) {
        if (shouldWaitForCompatibilityMapping()) {
            pauseAssetsReconnectForCompatibilityMapping();
            return;
        }
        pendingAssetsReconnectWaitReason = waitReason;
        if (assetsReconnectRetryTask == null) {
            LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_deferred",
                    "reason=" + pendingAssetsReconnectWaitReason
                            + " retryIntervalMs=" + ASSETS_RECONNECT_RETRY_INTERVAL_MS
                            + " retryLimit=" + ASSETS_RECONNECT_RETRY_LIMIT
                            + " route=" + (qaRelayRoutesEnabled ? "relay" : "official"));
            assetsReconnectRetryTask = ASSETS_RECONNECT_RETRY.scheduleAtFixedRate(
                    () -> runAssetsReconnectAttempt(requestId, true),
                    ASSETS_RECONNECT_RETRY_INTERVAL_MS,
                    ASSETS_RECONNECT_RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static ReconnectAttempt attemptAssetsReconnect(String reason, Class<?>[] loadedClasses) {
        for (AssetsReconnectLayout layout : activeAssetsReconnectLayouts) {
            Class<?> managerClass = null;
            for (Class<?> loaded : loadedClasses) {
                if (layout.managerClass.replace('/', '.').equals(loaded.getName())) {
                    managerClass = loaded;
                    break;
                }
            }
            if (managerClass == null) {
                continue;
            }
            try {
                java.lang.reflect.Method getter = managerClass.getMethod(layout.managerGetter);
                if (!java.lang.reflect.Modifier.isStatic(getter.getModifiers())
                        || getter.getReturnType() != managerClass) {
                    continue; // A remapped name can now refer to an unrelated instance method.
                }
                Object manager = getter.invoke(null);
                if (manager == null) {
                    return ReconnectAttempt.MANAGER_NOT_READY;
                }
                if (layout.reconnectInfoClass == null) {
                    java.lang.reflect.Method reconnect = managerClass.getDeclaredMethod(layout.connectMethod);
                    reconnect.setAccessible(true);
                    reconnect.invoke(manager);
                } else {
                    Class<?> reconnectInfoClass = Class.forName(
                            layout.reconnectInfoClass.replace('/', '.'), false, managerClass.getClassLoader());
                    managerClass.getMethod(layout.connectMethod, reconnectInfoClass)
                            .invoke(manager, new Object[]{null});
                }
                LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_requested",
                        "route=" + (qaRelayRoutesEnabled ? "relay" : "official")
                                + " reason=" + safeReason(reason));
                return ReconnectAttempt.REQUESTED;
            } catch (NoSuchMethodException unsupportedLayout) {
                continue;
            } catch (Throwable error) {
                LunarConnectionDiagnostics.record("endpoint.runtime_reconnect_failed",
                        "manager=" + layout.managerClass.replace('/', '.')
                                + " exception=" + rootCause(error).getClass().getSimpleName());
                return ReconnectAttempt.FAILED;
            }
        }
        return ReconnectAttempt.MANAGER_NOT_LOADED;
    }

    private static void onAssetsReconnectManagerClassLoading(String internalName) {
        final long requestId;
        synchronized (LunarRelayWeaveBootstrap.class) {
            if (pendingAssetsReconnectReason == null || isReconnectPausedForCompatibilityMapping()) {
                return;
            }
            requestId = assetsReconnectRequestId;
        }
        LunarConnectionDiagnostics.record("instrumentation.assets_manager_class_seen",
                "class=" + internalName.replace('/', '.') + " pendingReconnect=true");
        ASSETS_RECONNECT_RETRY.schedule(
                () -> runAssetsReconnectAttempt(requestId, false), 100L, TimeUnit.MILLISECONDS);
    }

    private static boolean isAssetsReconnectManagerClass(String internalName) {
        for (AssetsReconnectLayout layout : activeAssetsReconnectLayouts) {
            if (layout.managerClass.equals(internalName)) {
                return true;
            }
        }
        return false;
    }

    private enum ReconnectAttempt {
        REQUESTED,
        MANAGER_NOT_READY,
        MANAGER_NOT_LOADED,
        FAILED
    }

    private static String safeReason(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "unspecified";
        }
        return reason.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    private static Throwable rootCause(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private static ServiceEndpointLayout serviceLayoutForClass(Class<?> owner) {
        if (owner == null) {
            return null;
        }
        for (ServiceEndpointLayout layout : activeServiceLayouts) {
            if (layout.binaryName().equals(owner.getName())) {
                try {
                    if (owner.getDeclaredField(layout.apiField).getType() == String.class
                            && owner.getDeclaredField(layout.authenticatorField).getType() == String.class
                            && owner.getDeclaredField(layout.assetServerField).getType() == String.class
                            && owner.getDeclaredField(layout.styngrField).getType() == String.class) {
                        return layout;
                    }
                } catch (NoSuchFieldException unsupportedLayout) {
                    // Try all verified layouts for this class name, not just the first one.
                }
            }
        }
        return null;
    }

    private static ServiceEndpointLayout serviceLayoutForInternalName(String internalName) {
        for (ServiceEndpointLayout layout : activeServiceLayouts) {
            if (layout.internalName.equals(internalName)) {
                return layout;
            }
        }
        return null;
    }

    private static boolean isAuthenticatorClientClass(String internalName) {
        for (String name : LUNAR_AUTHENTICATOR_CLIENT_CLASSES) {
            if (name.equals(internalName)) {
                return true;
            }
        }
        return false;
    }

    /** Keep the host and suffix coherent even when an unknown Lunar layout keeps native endpoints. */
    public static String resolveAuthenticatorEndpoint(String base) {
        return base + (RELAY_AUTHENTICATOR.equals(base) ? "/ws" : "/game");
    }

    /**
     * Correct the current connection URI at the WebSocket boundary as a fallback for Lunar
     * releases whose service-constant class is loaded before our endpoint transformer sees it.
     * WebSocketClient exposes a non-final {@code uri} field and a public {@code getURI()}.
     */
    public static void redirectLunarWebSocketToQaRoute(Object client) {
        if (!qaRelayRoutesEnabled || client == null) {
            return;
        }
        String role = null;
        try {
            Method getter = client.getClass().getMethod("getURI");
            Object value = getter.invoke(client);
            if (!(value instanceof URI)) {
                return;
            }
            URI current = (URI) value;
            if (current.getRawQuery() != null || current.getRawUserInfo() != null
                    || !"/game".equals(current.getPath())) {
                return;
            }

            URI target;
            if ("authenticator.lunarclientprod.com".equalsIgnoreCase(current.getHost())) {
                role = "auth";
                target = URI.create(RELAY_AUTHENTICATOR + "/ws");
            } else if ("websocket.lunarclientprod.com".equalsIgnoreCase(current.getHost())) {
                role = "assets";
                target = URI.create(assetServerEndpoint() + "/game");
            } else if (RELAY_HOST.equalsIgnoreCase(current.getHost())) {
                // A renamed AuthenticatorClient may miss the exact constructor transform and
                // build relayBase + "/game". The relay's Authenticator is mounted at /ws; its
                // AssetServer already has the /ws prefix in its base and arrives as /ws/game.
                role = "auth-fallback";
                target = URI.create(RELAY_AUTHENTICATOR + "/ws");
            } else {
                return;
            }

            Field uriField = null;
            for (Class<?> type = client.getClass(); type != null; type = type.getSuperclass()) {
                try {
                    uriField = type.getDeclaredField("uri");
                    break;
                } catch (NoSuchFieldException ignored) {
                    // Search the superclass that owns WebSocketClient.uri.
                }
            }
            if (uriField == null) {
                throw new NoSuchFieldException("WebSocketClient.uri");
            }
            uriField.setAccessible(true);
            uriField.set(client, target);
            if (!target.equals(getter.invoke(client))) {
                throw new IllegalStateException("uri-field-update-not-visible");
            }
            LunarConnectionDiagnostics.record("websocket.relay_uri_rewritten",
                    "role=" + role + " fromHost=" + current.getHost() + " fromPath=" + current.getPath()
                            + " toHost=" + target.getHost() + " toPath=" + target.getPath());
        } catch (Throwable error) {
            LunarConnectionDiagnostics.record("websocket.relay_uri_rewrite_failed",
                    "role=" + (role == null ? "unknown" : role)
                            + " exception=" + error.getClass().getSimpleName());
        }
    }

    private static void setStaticString(Class<?> owner, String fieldName, String value) throws Exception {
        Field field = owner.getDeclaredField(fieldName);
        field.setAccessible(true);
        try {
            field.set(null, value);
        } catch (IllegalAccessException finalFieldError) {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field unsafeField = unsafeClass.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            Object unsafe = unsafeField.get(null);
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, field);
            long offset = (Long) unsafeClass.getMethod("staticFieldOffset", Field.class)
                    .invoke(unsafe, field);
            unsafeClass.getMethod("putObjectVolatile", Object.class, long.class, Object.class)
                    .invoke(unsafe, base, offset, value);
        }
    }

    private static final class ServiceEndpointLayout {
        final String internalName;
        final String apiField;
        final String authenticatorField;
        final String assetServerField;
        final String styngrField;

        ServiceEndpointLayout(String internalName, String apiField, String authenticatorField,
                              String assetServerField, String styngrField) {
            this.internalName = internalName;
            this.apiField = apiField;
            this.authenticatorField = authenticatorField;
            this.assetServerField = assetServerField;
            this.styngrField = styngrField;
        }

        String binaryName() {
            return internalName.replace('/', '.');
        }
    }

    private static final class OriginalServiceEndpoints {
        final String authenticator;
        final String assetServer;
        final String api;
        final String styngr;

        OriginalServiceEndpoints(String authenticator, String assetServer, String api, String styngr) {
            this.authenticator = authenticator;
            this.assetServer = assetServer;
            this.api = api;
            this.styngr = styngr;
        }
    }

    private static final class AssetsReconnectLayout {
        final String managerClass;
        final String managerGetter;
        final String connectMethod;
        final String reconnectInfoClass;

        AssetsReconnectLayout(String managerClass, String managerGetter, String connectMethod,
                              String reconnectInfoClass) {
            this.managerClass = managerClass;
            this.managerGetter = managerGetter;
            this.connectMethod = connectMethod;
            this.reconnectInfoClass = reconnectInfoClass;
        }

        AssetsReconnectLayout(String managerClass, String managerGetter, String connectMethod) {
            this(managerClass, managerGetter, connectMethod, null);
        }
    }

    private static void retransformLoadedEmoteLoginResponse(Instrumentation instrumentation,
                                                             boolean retransformCapable) {
        boolean found = false;
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName().replace('.', '/');
            if (!EMOTE_LOGIN_RESPONSE_CLASS.equals(name) && !EMOTE_LOGIN_PARSER_CLASS.equals(name)
                    && !EMOTE_WHEEL_BUILDER_CLASS.equals(name)) {
                continue;
            }
            found = true;
            if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                LunarConnectionDiagnostics.record("instrumentation.emote_retransform_unavailable",
                        "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                System.err.println("[gomod] QA emote flag could not be applied: Lunar emote response class "
                        + "was already loaded and cannot be retransformed");
                continue;
            }
            try {
                instrumentation.retransformClasses(loaded);
                LunarConnectionDiagnostics.record("instrumentation.emote_retransform_success",
                        "class=" + name.replace('/', '.'));
            } catch (Throwable error) {
                LunarConnectionDiagnostics.record("instrumentation.emote_retransform_failed",
                        "exception=" + error.getClass().getName() + " message=" + error.getMessage());
                System.err.println("[gomod] QA emote flag retransform failed: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
        }
        if (!found) LunarConnectionDiagnostics.record("instrumentation.emote_class_not_loaded",
                "transformer-will-apply-on-definition=true");
    }

    private static void retransformLoadedCosmeticV2LoginResponse(Instrumentation instrumentation,
                                                                  boolean retransformCapable) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (!COSMETIC_V2_LOGIN_RESPONSE_CLASS.replace('/', '.').equals(loaded.getName())) {
                continue;
            }
            if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                LunarConnectionDiagnostics.record("instrumentation.cosmetic_retransform_unavailable",
                        "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                System.err.println("[gomod] QA cosmetic count guard could not be applied: Lunar Cosmetics v2 "
                        + "LoginResponse was already loaded and cannot be retransformed");
                return;
            }
            try {
                instrumentation.retransformClasses(loaded);
                LunarConnectionDiagnostics.record("instrumentation.cosmetic_retransform_success",
                        "class=" + COSMETIC_V2_LOGIN_RESPONSE_CLASS.replace('/', '.'));
            } catch (Throwable error) {
                LunarConnectionDiagnostics.record("instrumentation.cosmetic_retransform_failed",
                        "exception=" + error.getClass().getName() + " message=" + error.getMessage());
                System.err.println("[gomod] QA cosmetic count guard retransform failed: "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
            }
            return;
        }
        LunarConnectionDiagnostics.record("instrumentation.cosmetic_class_not_loaded",
                "transformer-will-apply-on-definition=true");
    }

    private static void retransformLoadedPublicCatalogResponses(Instrumentation instrumentation,
                                                                  boolean retransformCapable) {
        String[] targets = {
                COSMETIC_V1_LOGIN_RESPONSE_CLASS,
                COSMETIC_V2_LOGIN_RESPONSE_CLASS,
                EMOTE_LOGIN_RESPONSE_CLASS
        };
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName().replace('.', '/');
            boolean target = false;
            for (String candidate : targets) target |= candidate.equals(name);
            if (!target) continue;
            if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                System.err.println("[gomod] Public Locker catalog filter unavailable for already-loaded "
                        + loaded.getName());
                continue;
            }
            try {
                instrumentation.retransformClasses(loaded);
            } catch (Throwable error) {
                System.err.println("[gomod] Public Locker catalog filter could not patch "
                        + loaded.getName() + ": " + error.getClass().getSimpleName());
            }
        }
    }

    private static String endpointLabel(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            return "host=" + uri.getHost() + " path=" + uri.getPath();
        } catch (Throwable ignored) {
            return "endpoint=unparseable";
        }
    }

    private static final class NativeLunarDebugTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                 ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            byte[] input = LunarHypixelBlitzStatsTransform.transform(className, classfileBuffer);
            if (input == null) input = classfileBuffer;
            byte[] quickplay = LunarQuickplayPersistenceTransform.transform(className, input);
            if (quickplay != null) input = quickplay;
            byte[] colors = LunarColorCompatibilityTransform.transform(className, input);
            byte[] ownership = LunarPublicLoginTransform.transform(className, colors == null ? input : colors);
            input = ownership != null ? ownership : colors != null ? colors : input;
            byte[] outfits = LunarOutfitPersistenceTransform.transform(className, input);
            if (outfits != null) input = outfits;
            byte[] catalog = LunarCatalogCompatibilityTransform.transform(className, input);
            if (catalog != null) input = catalog;
            // Spray managers are also covered by the catalog's already-loaded-class registry.
            byte[] sprayLighting = LunarSprayLightingTransform.transform(className, input);
            if (sprayLighting != null) input = sprayLighting;
            byte[] worldLabelProbe = LunarWorldLabelProbeTransform.transform(className, input);
            if (worldLabelProbe != null) input = worldLabelProbe;
            byte[] companion = LunarCompanionAndConflictTransform.transform(className, input);
            if (companion != null) input = companion;
            byte[] clothCloak = LunarClothCloakAccessTransform.transform(className, input, classBeingRedefined);
            if (clothCloak != null) input = clothCloak;
            byte[] sprint = LunarToggleSprintCompatibilityTransform.transform(className, input);
            if (sprint != null) input = sprint;
            byte[] sidebar = LunarSidebarBackgroundTransform.transform(className, input);
            if (sidebar != null) input = sidebar;
            byte[] other = transformOther(loader, className, classBeingRedefined, input);
            if (other != null) input = other;
            byte[] connectionLogo = LunarConnectionLogoTransform.transform(loader, className, input);
            return connectionLogo != null ? connectionLogo : input == classfileBuffer ? null : input;
        }

        private byte[] transformOther(ClassLoader loader, String className, Class<?> classBeingRedefined, byte[] classfileBuffer) {
            if (className == null || classfileBuffer == null) {
                return null;
            }

            if (LunarInventoryQolHotbarTransform.isTarget(className)) {
                return LunarInventoryQolHotbarTransform.transform(className, classfileBuffer);
            }

            if (me.napim.gomod.weave.hooks.LunarHypixelNametagWeaveHook.isTarget(className)) {
                return me.napim.gomod.weave.hooks.LunarHypixelNametagWeaveHook
                        .transformBytes(className, classfileBuffer, loader);
            }

            if (me.napim.gomod.weave.hooks.GoparkyMolangQueryTransform
                    .mayContainQuery(className, classfileBuffer)) {
                return me.napim.gomod.weave.hooks.GoparkyMolangQueryTransform
                        .transform(className, classfileBuffer);
            }

            if (LunarNametagDisguiseTransform.TARGET.equals(className)) {
                byte[] names = LunarNametagDisguiseTransform.transform(className, classfileBuffer);
                byte[] logos = LunarNametagLogoTransform.transform(className, names == null ? classfileBuffer : names);
                return logos == null ? names : logos;
            }

            if (LunarNametagLogoTransform.isTarget(className)) {
                return LunarNametagLogoTransform.transform(className, classfileBuffer);
            }

            if (LunarGekFirstPersonDisguiseTransform.isTarget(className)) {
                return LunarGekFirstPersonDisguiseTransform.transform(className, classfileBuffer);
            }

            if (LunarRemoteGekDisguiseTransform.TARGET.equals(className)) {
                return LunarRemoteGekDisguiseTransform.transform(className, classfileBuffer);
            }

            if (LunarDisguiserBadgeTransform.isTarget(className)) {
                return LunarDisguiserBadgeTransform.transform(className, classfileBuffer);
            }

            if (LunarOpenALAttributeTransform.TARGET.equals(className)) {
                return LunarOpenALAttributeTransform.transform(className, classfileBuffer);
            }

            if (LunarEmoteWheelPersistenceTransform.mayContain(className, classfileBuffer)) {
                byte[] wheel = LunarEmoteWheelPersistenceTransform.transform(className, classfileBuffer);
                if (wheel != null) return wheel;
            }

            if (LunarModelAttachmentsDisguiseTransform.TARGET.equals(className)) {
                byte[] guarded = LunarModelAttachmentsDisguiseTransform.transform(className, classfileBuffer);
                byte[] traced = LunarArmDebugTransform.transform(className, guarded == null ? classfileBuffer : guarded);
                return traced == null ? guarded : traced;
            }

            if (LunarArmDebugTransform.isTarget(className)) {
                return LunarArmDebugTransform.transform(className, classfileBuffer);
            }

            if (LunarLeaderboardHologramTransform.TARGET.equals(className)) {
                return LunarLeaderboardHologramTransform.transform(className, classfileBuffer);
            }

            if (LunarTabVictimDrawingTransform.isTarget(className)
                    || LunarTabVictimDrawingTransform.mayContainRenderer(className, classfileBuffer)) {
                return LunarTabVictimDrawingTransform.transform(className, classfileBuffer);
            }

            if (LunarMenuDebugTransform.isTarget(className)) {
                return LunarMenuDebugTransform.transform(className, classfileBuffer);
            }

            if (LunarMenuSearchFocusTransform.mayContainCandidate(className, classfileBuffer)) {
                byte[] transformed = LunarMenuSearchFocusTransform.transform(className, classfileBuffer);
                if (transformed != null) return transformed;
            }

            if (JAVA_WEBSOCKET_CLIENT_CLASS.equals(className)) {
                return patchLunarWebSocketCallbacks(classfileBuffer);
            }

            if (JAVA_WEBSOCKET_IMPL_CLASS.equals(className)) {
                if (GomodReleaseBuild.PUBLIC_RELEASE
                        || GomodReleaseBuild.QA_DISABLE_WEBSOCKET_DIAGNOSTICS) {
                    return patchLunarWebSocketImplLiveness(classfileBuffer);
                }
                return patchLunarWebSocketImpl(classfileBuffer);
            }

            if (JAVA_WEBSOCKET_CLIENT_WRITE_THREAD_CLASS.equals(className)) {
                if (GomodReleaseBuild.PUBLIC_RELEASE
                        || GomodReleaseBuild.QA_DISABLE_WEBSOCKET_DIAGNOSTICS) {
                    return null;
                }
                return patchLunarWebSocketClientWriteThread(classfileBuffer);
            }

            if (LUNAR_AUTH_SUCCESS_CLIENT_CLASS.equals(className)
                    || (className.startsWith("com/moonsworth/lunar/client/")
                        && LunarNativeLayoutDiscovery.authSuccessHandlerName(classfileBuffer) != null)) {
                byte[] legacy = patchLegacyAuthSuccessHandler(classfileBuffer);
                byte[] routed = patchAuthenticatorEndpointSuffix(legacy == null ? classfileBuffer : legacy);
                return routed == null ? legacy : routed;
            }

            if (isAssetsReconnectManagerClass(className)) {
                onAssetsReconnectManagerClassLoading(className);
                byte[] peerProfileHooks = patchOfficialProfileHandoff(classfileBuffer);
                if (peerProfileHooks != null) return peerProfileHooks;
                LunarConnectionDiagnostics.record("lunar_peer_logo_profile.handoff_hook",
                        "installed=false reason=native_close_site_not_unique");
            }

            ServiceEndpointLayout serviceLayout = serviceLayoutForInternalName(className);
            if (serviceLayout != null) {
                return patchResolvedServiceEndpoints(classfileBuffer, serviceLayout);
            }

            if (isAuthenticatorClientClass(className)) {
                return patchAuthenticatorEndpointSuffix(classfileBuffer);
            }

            if (EMOTE_LOGIN_RESPONSE_CLASS.equals(className)) {
                byte[] unlocked = enableLocalQaEmoteCatalog(classfileBuffer);
                byte[] result = unlocked == null ? classfileBuffer : unlocked;
                return GomodReleaseBuild.PUBLIC_RELEASE ? filterPublicCatalogOwnership(result, className) : result;
            }

            if ((EMOTE_LOGIN_PARSER_CLASS.equals(className)
                    || EMOTE_WHEEL_BUILDER_CLASS.equals(className))) {
                return patchEmoteWheelPersistence(classfileBuffer, EMOTE_LOGIN_PARSER_CLASS.equals(className));
            }

            if (COSMETIC_V1_LOGIN_RESPONSE_CLASS.equals(className)
                    || COSMETIC_V2_LOGIN_RESPONSE_CLASS.equals(className)) {
                byte[] result = COSMETIC_V1_LOGIN_RESPONSE_CLASS.equals(className) ? classfileBuffer
                        : suppressDuplicateDevCosmeticCatalogFlag(classfileBuffer);
                if (result == null) result = classfileBuffer;
                return GomodReleaseBuild.PUBLIC_RELEASE ? filterPublicCatalogOwnership(result, className)
                        : COSMETIC_V1_LOGIN_RESPONSE_CLASS.equals(className) ? null : result;
            }

            if (classBeingRedefined != null || !className.startsWith("com/moonsworth/lunar/")) {
                return null;
            }

            try {
                if (className.startsWith("com/moonsworth/lunar/client/")
                        && new String(classfileBuffer, StandardCharsets.ISO_8859_1)
                        .contains(PROFILE_DATA_CLASS)) {
                    return removeMissingProfileDataHook(className, classfileBuffer);
                }

                // Do not infer Lunar targets from obfuscated field counts. That heuristic
                // rewrote unrelated classes whenever their shape happened to match and was
                // unsafe across Lunar client versions. Only patch the explicitly identified
                // compatibility targets above.
                return null;
            } catch (Throwable error) {
                System.err.println("[gomod] Native Lunar debug transform failed for " + className + ": "
                        + error.getClass().getSimpleName() + ": " + error.getMessage());
                LunarConnectionDiagnostics.record("instrumentation.transform_failed",
                        "class=" + className + " exception=" + error.getClass().getName()
                                + " message=" + error.getMessage());
                return null;
            }
        }

        private byte[] filterPublicCatalogOwnership(byte[] classfileBuffer, String className) {
            boolean emoteResponse = EMOTE_LOGIN_RESPONSE_CLASS.equals(className);
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patchedRows = {false};
            boolean[] patchedIds = {false};
            boolean[] patchedAllFlag = {false};
            boolean[] patchedPlusColors = {false};
            boolean[] patchedLocalColors = {false};
            boolean[] patchedSettings = {false};
            boolean[] patchedOutfits = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    String rowsGetter = emoteResponse ? "getOwnedEmotesList" : "getOwnedCosmeticsList";
                    String idGetter = emoteResponse ? "getOwnedEmoteIdsList" : "getOwnedCosmeticIdsList";
                    String rowFilter = emoteResponse ? "filterOwnedEmotes" : "filterOwnedCosmetics";
                    String idFilter = emoteResponse ? "filterOwnedEmoteIds" : "filterOwnedCosmeticIds";
                    if (!emoteResponse && "getOutfitsList".equals(name)
                            && "()Ljava/util/List;".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            "filterOwnedOutfits",
                                            "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false);
                                    patchedOutfits[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (!emoteResponse && "getSettings".equals(name)
                            && "()Lcom/lunarclient/websocket/cosmetic/v1/CustomizableCosmeticSettings;"
                            .equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            "filterPublicCosmeticSettings",
                                            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST,
                                            "com/lunarclient/websocket/cosmetic/v1/CustomizableCosmeticSettings");
                                    patchedSettings[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (!emoteResponse && "getAvailableLunarPlusColorsList".equals(name)
                            && "()Ljava/util/List;".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            "filterAvailableLunarPlusColors",
                                            "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false);
                                    patchedPlusColors[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (!emoteResponse && ("getLogoColor".equals(name) || "getPlusColor".equals(name))
                            && "()Lcom/lunarclient/common/v1/Color;".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            "filterMarkedColor", "(Ljava/lang/Object;)Ljava/lang/Object;", false);
                                    super.visitTypeInsn(Opcodes.CHECKCAST, "com/lunarclient/common/v1/Color");
                                    patchedLocalColors[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (rowsGetter.equals(name) && "()Ljava/util/List;".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            rowFilter,
                                            "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false);
                                    patchedRows[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (idGetter.equals(name) && "()Ljava/util/List;".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            idFilter,
                                            "(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;", false);
                                    patchedIds[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (emoteResponse && "getHasAllEmotesFlag".equals(name)
                            && "()Z".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.IRETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitInsn(Opcodes.SWAP);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarPublicCatalogOwnership",
                                            "filterAllOwnedEmotesFlag",
                                            "(Ljava/lang/Object;Z)Z", false);
                                    patchedAllFlag[0] = true;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    return visitor;
                }
            };
            reader.accept(modifier, 0);
            if (!patchedRows[0] && !patchedIds[0] && !patchedPlusColors[0]
                    && !patchedLocalColors[0] && !patchedSettings[0] && !patchedOutfits[0]) {
                System.err.println("[gomod] Public Locker catalog filter found no ownership getters in " + className);
                return null;
            }
            System.out.println("[gomod] Public Locker ownership filter applied to " + className.replace('/', '.')
                    + " rows=" + patchedRows[0] + " ids=" + patchedIds[0]
                    + " lunarPlusColors=" + patchedPlusColors[0]
                    + " markedColorFields=" + patchedLocalColors[0]
                    + " settings=" + patchedSettings[0] + " outfits=" + patchedOutfits[0]
                    + (emoteResponse ? " allEmotesFlag=" + patchedAllFlag[0] : ""));
            return writer.toByteArray();
        }

        private byte[] patchLunarWebSocketCallbacks(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patchedCallbacks = {false};
            boolean[] patchedConnectMethods = {false};
            boolean[] patchedTransportReads = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    final boolean opened = "onWebsocketOpen".equals(name)
                            && "(Lorg/java_websocket/WebSocket;Lorg/java_websocket/handshake/Handshakedata;)V"
                            .equals(descriptor);
                    final boolean closed = "onWebsocketClose".equals(name)
                            && "(Lorg/java_websocket/WebSocket;ILjava/lang/String;Z)V".equals(descriptor);
                    final boolean failed = "onWebsocketError".equals(name)
                            && "(Lorg/java_websocket/WebSocket;Ljava/lang/Exception;)V".equals(descriptor);
                    final boolean receivedMessage = "onWebsocketMessage".equals(name)
                            && ("(Lorg/java_websocket/WebSocket;Ljava/lang/String;)V".equals(descriptor)
                            || "(Lorg/java_websocket/WebSocket;Ljava/nio/ByteBuffer;)V".equals(descriptor));
                    final boolean connectAttempt = "connect".equals(name) || "connectBlocking".equals(name)
                            || "reconnect".equals(name) || "reconnectBlocking".equals(name);
                    final boolean readerLoop = "run".equals(name) && "()V".equals(descriptor);
                    if (!opened && !closed && !failed && !receivedMessage && !connectAttempt && !readerLoop) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (readerLoop) {
                                return;
                            }
                            if (connectAttempt) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/weave/LunarRelayWeaveBootstrap",
                                        "redirectLunarWebSocketToQaRoute",
                                        "(Ljava/lang/Object;)V", false);
                                if (!GomodReleaseBuild.QA_DISABLE_WEBSOCKET_DIAGNOSTICS) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitLdcInsn(name);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarConnectionDiagnostics",
                                            "onSocketConnectAttempt",
                                            "(Ljava/lang/Object;Ljava/lang/String;)V", false);
                                }
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarOnlineStatus",
                                        "onWebSocketConnectAttempt",
                                        "(Ljava/lang/Object;)V", false);
                                patchedConnectMethods[0] = true;
                            } else if (closed) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ILOAD, 2);
                                super.visitVarInsn(Opcodes.ALOAD, 3);
                                super.visitVarInsn(Opcodes.ILOAD, 4);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarOnlineStatus",
                                        "onWebSocketClosed",
                                        "(Ljava/lang/Object;ILjava/lang/String;Z)V", false);
                                patchedCallbacks[0] = true;
                            } else if (failed) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarOnlineStatus",
                                        "onWebSocketError",
                                        "(Ljava/lang/Object;Ljava/lang/Throwable;)V", false);
                                patchedCallbacks[0] = true;
                            } else if (receivedMessage) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitVarInsn(Opcodes.ALOAD, 2);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarOnlineStatus",
                                        "onWebSocketMessage",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                patchedCallbacks[0] = true;
                            } else {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarOnlineStatus",
                                        "onWebSocketOpened",
                                        "(Ljava/lang/Object;)V", false);
                                patchedCallbacks[0] = true;
                            }
                        }

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                            if (!GomodReleaseBuild.QA_DISABLE_WEBSOCKET_DIAGNOSTICS
                                    && readerLoop && "java/io/InputStream".equals(owner)
                                    && "read".equals(methodName) && "([B)I".equals(methodDescriptor)) {
                                // Preserve the returned int for WebSocketClient's existing
                                // dup/istore sequence while giving diagnostics the client and
                                // the raw read count. No payload is inspected or retained.
                                super.visitInsn(Opcodes.DUP);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onWebSocketTransportRead",
                                        "(Ljava/lang/Object;I)V", false);
                                patchedTransportReads[0] = true;
                            }
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patchedCallbacks[0]) {
                System.err.println("[gomod] Lunar online status hook did not find WebSocket callbacks");
                LunarConnectionDiagnostics.record("instrumentation.websocket_callbacks_missing",
                        "callbacks=not-found");
                return null;
            }
            LunarConnectionDiagnostics.record("instrumentation.websocket_callbacks_patched",
                    "callbacks=true connectAttemptMethods=" + patchedConnectMethods[0]
                            + " transportRead=" + patchedTransportReads[0]);
            System.out.println("[gomod] Lunar authentication WebSocket status hook installed");
            return writer.toByteArray();
        }

        private byte[] patchLunarWebSocketImpl(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false, false, false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    final boolean decode = "decode".equals(name)
                            && "(Ljava/nio/ByteBuffer;)V".equals(descriptor);
                    // Text, binary, ping, and pong writes converge on this private
                    // collection method after their public send wrappers create frames.
                    final boolean outboundFrames = "send".equals(name)
                            && "(Ljava/util/Collection;)V".equals(descriptor);
                    final boolean pong = "updateLastPong".equals(name) && "()V".equals(descriptor);
                    if (!decode && !outboundFrames && !pong) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            if (decode) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer",
                                        "remaining", "()I", false);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onWebSocketImplDecode",
                                        "(Ljava/lang/Object;I)V", false);
                                patched[0] = true;
                            } else if (outboundFrames) {
                                super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onWebSocketImplOutboundFrames",
                                        "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                                patched[1] = true;
                            } else {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onWebSocketImplPong",
                                        "(Ljava/lang/Object;)V", false);
                                patched[2] = true;
                            }
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0] && !patched[1] && !patched[2]) {
                LunarConnectionDiagnostics.record("instrumentation.websocket_impl_hooks_missing",
                        "decode=false outboundFrame=false pong=false");
                return null;
            }
            LunarConnectionDiagnostics.record("instrumentation.websocket_impl_hooks_patched",
                    "decodedInboundBytes=" + patched[0] + " outboundFrameAttempts=" + patched[1]
                            + " receivedPong=" + patched[2]);
            return writer.toByteArray();
        }

        /** Public-safe transport heartbeat hook; it records no payload or diagnostic data. */
        private byte[] patchLunarWebSocketImplLiveness(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"updateLastPong".equals(name) || !"()V".equals(descriptor)) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "me/napim/gomod/features/LunarOnlineStatus",
                                    "onWebSocketImplPong", "(Ljava/lang/Object;)V", false);
                            patched[0] = true;
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            return patched[0] ? writer.toByteArray() : null;
        }

        private byte[] patchOfficialProfileHandoff(byte[] bytes) {
            ClassReader reader = new ClassReader(bytes);
            org.objectweb.asm.tree.ClassNode node = new org.objectweb.asm.tree.ClassNode();
            reader.accept(node, 0);
            org.objectweb.asm.tree.MethodInsnNode match = null;
            org.objectweb.asm.tree.MethodNode caller = null;
            for (org.objectweb.asm.tree.MethodNode method : node.methods) {
                if (!method.desc.endsWith("Lcom/lunarclient/websocket/handshake/v1/Handshake$Builder;)V")) continue;
                for (org.objectweb.asm.tree.AbstractInsnNode instruction : method.instructions) {
                    if (!(instruction instanceof org.objectweb.asm.tree.MethodInsnNode)) continue;
                    org.objectweb.asm.tree.MethodInsnNode call = (org.objectweb.asm.tree.MethodInsnNode) instruction;
                    if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.startsWith("com/moonsworth/lunar/")
                            || !"closeBlocking".equals(call.name) || !"()V".equals(call.desc)) continue;
                    if (match != null) return null;
                    match = call;
                    caller = method;
                }
            }
            if (match == null) return null;
            caller.instructions.set(match, new org.objectweb.asm.tree.MethodInsnNode(Opcodes.INVOKESTATIC,
                    "me/napim/gomod/features/LunarSpectatorCosmetics", "closeForRouteChange", "(Ljava/lang/Object;)V", false));
            ClassWriter writer = new ClassWriter(reader, 0);
            node.accept(writer);
            LunarConnectionDiagnostics.record("lunar_peer_logo_profile.handoff_hook", "installed=true");
            return writer.toByteArray();
        }

        private byte[] patchLunarWebSocketClientWriteThread(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false, false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"runWriteData".equals(name) || !"()V".equals(descriptor)) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        private int writeCallIndex;
                        private int pendingFlushBufferLocal = -1;

                        @Override
                        public void visitMethodInsn(int opcode, String owner, String methodName,
                                                    String methodDescriptor, boolean isInterface) {
                            boolean outputWrite = "java/io/OutputStream".equals(owner)
                                    && "write".equals(methodName)
                                    && "([BII)V".equals(methodDescriptor);
                            boolean outputFlush = "java/io/OutputStream".equals(owner)
                                    && "flush".equals(methodName)
                                    && "()V".equals(methodDescriptor);
                            if (outputWrite) {
                                // Java-WebSocket 1.5.3's client writer dequeues the complete
                                // frame into local 1; its interrupted-queue drain uses local 3.
                                int frameBufferLocal = writeCallIndex++ == 0 ? 1 : 3;
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD,
                                        JAVA_WEBSOCKET_CLIENT_WRITE_THREAD_CLASS,
                                        "webSocketClient", "Lorg/java_websocket/client/WebSocketClient;");
                                super.visitVarInsn(Opcodes.ALOAD, frameBufferLocal);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onClientWriterPingWriteAttempt",
                                        "(Ljava/lang/Object;Ljava/nio/ByteBuffer;)I", false);
                                super.visitVarInsn(Opcodes.ISTORE, 4);

                                super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD,
                                        JAVA_WEBSOCKET_CLIENT_WRITE_THREAD_CLASS,
                                        "webSocketClient", "Lorg/java_websocket/client/WebSocketClient;");
                                super.visitVarInsn(Opcodes.ILOAD, 4);
                                super.visitVarInsn(Opcodes.ALOAD, frameBufferLocal);
                                super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/nio/ByteBuffer",
                                        "limit", "()I", false);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onClientWriterPingWriteCompleted",
                                        "(Ljava/lang/Object;II)V", false);
                                pendingFlushBufferLocal = frameBufferLocal;
                                patched[0] = true;
                                return;
                            }

                            super.visitMethodInsn(opcode, owner, methodName, methodDescriptor, isInterface);
                            if (outputFlush && pendingFlushBufferLocal >= 0) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitFieldInsn(Opcodes.GETFIELD,
                                        JAVA_WEBSOCKET_CLIENT_WRITE_THREAD_CLASS,
                                        "webSocketClient", "Lorg/java_websocket/client/WebSocketClient;");
                                super.visitVarInsn(Opcodes.ILOAD, 4);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarConnectionDiagnostics",
                                        "onClientWriterPingFlushCompleted",
                                        "(Ljava/lang/Object;I)V", false);
                                pendingFlushBufferLocal = -1;
                                patched[1] = true;
                            }
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0] || !patched[1]) {
                LunarConnectionDiagnostics.record("instrumentation.websocket_client_writer_hooks_missing",
                        "outputStreamWrite=" + patched[0] + " outputStreamFlush=" + patched[1]);
                return null;
            }
            LunarConnectionDiagnostics.record("instrumentation.websocket_client_writer_hooks_patched",
                    "outputStreamWrite=true outputStreamFlush=true pingPayload=omitted");
            return writer.toByteArray();
        }

        /**
         * The current Lunar AuthenticatorClient constructs its URL as
         * {@code serviceOverrideAuthenticator + "/game"}. The local API's Authenticator
         * handler is mounted at {@code /ws}, while its Assets handler is at {@code /ws/game}.
         * Keep the transformation narrowly pinned to this class and exact concat recipe.
         */
        private byte[] patchAuthenticatorEndpointSuffix(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"<init>".equals(name)) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitInvokeDynamicInsn(String dynamicName, String dynamicDescriptor,
                                                           org.objectweb.asm.Handle bootstrapMethodHandle,
                                                           Object... bootstrapMethodArguments) {
                            if ("makeConcatWithConstants".equals(dynamicName)
                                    && bootstrapMethodArguments.length > 0
                                    && "\u0001/game".equals(bootstrapMethodArguments[0])) {
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/weave/LunarRelayWeaveBootstrap",
                                        "resolveAuthenticatorEndpoint",
                                        "(Ljava/lang/String;)Ljava/lang/String;", false);
                                patched[0] = true;
                                return;
                            }
                            super.visitInvokeDynamicInsn(dynamicName, dynamicDescriptor,
                                    bootstrapMethodHandle, bootstrapMethodArguments);
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0]) {
                LunarConnectionDiagnostics.record("authenticator.route_transform_skipped",
                        "reason=expected-base-plus-game-recipe-not-found client-version-layout-changed=true");
                return null;
            }
            LunarConnectionDiagnostics.record("authenticator.route_transform_applied",
                    "auth-final-path=base-derived relay=/ws official=/game unknown-layout-keeps-official=true");
            return writer.toByteArray();
        }

        private byte[] patchLegacyAuthSuccessHandler(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            String discoveredHandler = LunarNativeLayoutDiscovery.authSuccessHandlerName(classfileBuffer);
            final String handlerName = discoveredHandler == null ? LUNAR_AUTH_SUCCESS_METHOD : discoveredHandler;
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!handlerName.equals(name)
                            || !LUNAR_AUTH_SUCCESS_DESCRIPTOR.equals(descriptor)) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            Label continueOriginal = new Label();
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "me/napim/gomod/features/LunarLegacyAuthSuccessJwtFallback",
                                    "consume", "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            super.visitJumpInsn(Opcodes.IFEQ, continueOriginal);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(continueOriginal);
                            patched[0] = true;
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0]) {
                LunarConnectionDiagnostics.record("authenticator.legacy_jwt_transform_skipped",
                        "reason=handler-signature-not-found target="
                                + LUNAR_AUTH_SUCCESS_CLIENT_CLASS.replace('/', '.')
                                + " method=" + LUNAR_AUTH_SUCCESS_METHOD);
                return null;
            }
            LunarConnectionDiagnostics.record("authenticator.legacy_jwt_transform_applied",
                    "target=" + LUNAR_AUTH_SUCCESS_CLIENT_CLASS.replace('/', '.')
                            + " handler=" + handlerName + LUNAR_AUTH_SUCCESS_DESCRIPTOR);
            return writer.toByteArray();
        }

        private void retransformLoadedAuthenticatorClient(Instrumentation instrumentation,
                                                            boolean retransformCapable) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!isAuthenticatorClientClass(loaded.getName().replace('.', '/'))) {
                    continue;
                }
                if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                    LunarConnectionDiagnostics.record("authenticator.route_retransform_unavailable",
                            "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                    return;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("authenticator.route_retransform_failed",
                            "exception=" + error.getClass().getName()
                                    + " message=" + error.getMessage());
                }
                return;
            }
            LunarConnectionDiagnostics.record("authenticator.client_class_not_loaded",
                    "transformer-will-apply-on-definition=true");
        }

        private void retransformLoadedAuthSuccessClient(Instrumentation instrumentation,
                                                         boolean retransformCapable) {
            String targetName = LUNAR_AUTH_SUCCESS_CLIENT_CLASS.replace('/', '.');
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!targetName.equals(loaded.getName())) {
                    continue;
                }
                if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                    LunarConnectionDiagnostics.record("authenticator.legacy_jwt_retransform_unavailable",
                            "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                    return;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("authenticator.legacy_jwt_retransform_failed",
                            "exception=" + error.getClass().getName());
                }
                return;
            }
            LunarConnectionDiagnostics.record("authenticator.legacy_jwt_client_class_not_loaded",
                    "transformer-will-apply-on-definition=true");
        }

        private void retransformLoadedWebSocketClient(Instrumentation instrumentation,
                                                       boolean retransformCapable) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!JAVA_WEBSOCKET_CLIENT_CLASS.replace('/', '.').equals(loaded.getName())) {
                    continue;
                }
                if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                    LunarConnectionDiagnostics.record("instrumentation.websocket_retransform_unavailable",
                            "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                    System.err.println("[gomod] Lunar online status hook could not be applied: "
                            + "WebSocketClient was already loaded and cannot be retransformed");
                    return;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("instrumentation.websocket_retransform_failed",
                            "exception=" + error.getClass().getName() + " message=" + error.getMessage());
                    System.err.println("[gomod] Lunar online status hook retransform failed: "
                            + error.getClass().getSimpleName() + ": " + error.getMessage());
                }
                return;
            }
            LunarConnectionDiagnostics.record("instrumentation.websocket_class_not_loaded",
                    "transformer-will-apply-on-definition=true");
        }

        private void retransformLoadedWebSocketImpl(Instrumentation instrumentation,
                                                     boolean retransformCapable) {
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!JAVA_WEBSOCKET_IMPL_CLASS.replace('/', '.').equals(loaded.getName())) {
                    continue;
                }
                if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                    LunarConnectionDiagnostics.record("instrumentation.websocket_impl_retransform_unavailable",
                            "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                    return;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record("instrumentation.websocket_impl_retransform_failed",
                            "exception=" + error.getClass().getName()
                                    + " message=" + error.getMessage());
                }
                return;
            }
            LunarConnectionDiagnostics.record("instrumentation.websocket_impl_class_not_loaded",
                    "transformer-will-apply-on-definition=true");
        }

        private void retransformLoadedWebSocketClientWriteThread(Instrumentation instrumentation,
                                                                   boolean retransformCapable) {
            String writerClassName = JAVA_WEBSOCKET_CLIENT_WRITE_THREAD_CLASS.replace('/', '.');
            for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
                if (!writerClassName.equals(loaded.getName())) {
                    continue;
                }
                if (!retransformCapable || !instrumentation.isModifiableClass(loaded)) {
                    LunarConnectionDiagnostics.record(
                            "instrumentation.websocket_client_writer_retransform_unavailable",
                            "classAlreadyLoaded=true retransformCapable=" + retransformCapable);
                    return;
                }
                try {
                    instrumentation.retransformClasses(loaded);
                } catch (Throwable error) {
                    LunarConnectionDiagnostics.record(
                            "instrumentation.websocket_client_writer_retransform_failed",
                            "exception=" + error.getClass().getSimpleName());
                }
                return;
            }
            LunarConnectionDiagnostics.record("instrumentation.websocket_client_writer_class_not_loaded",
                    "transformer-will-apply-on-definition=true");
        }

        private byte[] patchResolvedServiceEndpoints(byte[] classfileBuffer,
                                                     ServiceEndpointLayout requestedLayout) {
            ClassReader reader = new ClassReader(classfileBuffer);
            Set<String> declaredStringFields = new HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public FieldVisitor visitField(int access, String name, String descriptor,
                                               String signature, Object value) {
                    if ((access & Opcodes.ACC_STATIC) != 0 && "Ljava/lang/String;".equals(descriptor)) {
                        declaredStringFields.add(name);
                    }
                    return null;
                }
            }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            ServiceEndpointLayout matchingLayout = null;
            for (ServiceEndpointLayout candidate : activeServiceLayouts) {
                if (candidate.internalName.equals(reader.getClassName())
                        && declaredStringFields.contains(candidate.authenticatorField)
                        && declaredStringFields.contains(candidate.assetServerField)
                        && declaredStringFields.contains(candidate.apiField)
                        && declaredStringFields.contains(candidate.styngrField)) {
                    matchingLayout = candidate;
                    break;
                }
            }
            final ServiceEndpointLayout layout = matchingLayout;
            if (layout == null) {
                System.err.println("[gomod] Lunar service endpoint field layout changed; "
                        + "leaving native endpoints untouched instead of injecting invalid field writes");
                LunarConnectionDiagnostics.record("endpoint.transform_skipped",
                        "reason=lunar-field-layout-mismatch service-class=" + requestedLayout.binaryName());
                return null;
            }
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"<clinit>".equals(name) || !"()V".equals(descriptor)) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.RETURN && !patched[0]) {
                                patched[0] = true;
                                super.visitLdcInsn(Type.getObjectType(layout.internalName));
                                putCurrentField(layout.authenticatorField);
                                putCurrentField(layout.assetServerField);
                                putCurrentField(layout.apiField);
                                putCurrentField(layout.styngrField);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/weave/LunarRelayWeaveBootstrap",
                                        "captureServiceDefaults",
                                        "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/String;"
                                                + "Ljava/lang/String;Ljava/lang/String;)V", false);
                                LunarConnectionDiagnostics.record("endpoint.transform_applied",
                                        "service-class=" + layout.binaryName()
                                                + " dynamicRouteCapture=true route="
                                                + (qaRelayRoutesEnabled ? "gomod-relay" : "lunar-official"));
                            }
                            super.visitInsn(opcode);
                        }

                        private void putCurrentField(String fieldName) {
                            super.visitFieldInsn(Opcodes.GETSTATIC, layout.internalName, fieldName,
                                    "Ljava/lang/String;");
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0]) {
                System.err.println("[gomod] Relay transform could not find Lunar service initializer");
                LunarConnectionDiagnostics.record("endpoint.transform_skipped",
                        "reason=lunar-service-initializer-not-found");
                return null;
            }
            return writer.toByteArray();
        }

        private byte[] patchEmoteWheelPersistence(byte[] classfileBuffer, final boolean loginParser) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            final String expectedName = loginParser ? "parsePartialFrom" : "build";
            final String resultType = loginParser ? EMOTE_LOGIN_RESPONSE_CLASS
                    : "com/lunarclient/websocket/emote/v1/UpdateEquippedEmotesRequest";
            final String expectedDescriptor = loginParser
                    ? "(Lcom/google/protobuf/CodedInputStream;Lcom/google/protobuf/ExtensionRegistryLite;)L" + resultType + ";"
                    : "()L" + resultType + ";";
            boolean[] patched = {false};
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!expectedName.equals(name) || !expectedDescriptor.equals(descriptor)) return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                if (!loginParser) super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarEmoteWheelPersistence",
                                        loginParser ? "restoreLogin" : "rememberRequest",
                                        loginParser ? "(Ljava/lang/Object;)Ljava/lang/Object;" : "(Ljava/lang/Object;)V", false);
                                if (loginParser) super.visitTypeInsn(Opcodes.CHECKCAST, resultType);
                                patched[0] = true;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            }, 0);
            LunarConnectionDiagnostics.record("emote_wheel.transform_" + (patched[0] ? "applied" : "skipped"),
                    "target=" + (loginParser ? "login-parser" : "user-update-builder")
                            + " fullAccessGate=relay-route scope=local-internal-qa-build");
            return patched[0] ? writer.toByteArray() : null;
        }

        private byte[] enableLocalQaEmoteCatalog(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!"getHasAllEmotesFlag".equals(name) || !"()Z".equals(descriptor)) {
                        return visitor;
                    }
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                // Expose the all-emotes catalog only while the relay route is
                                // actually active. Preference can be enabled while API readiness
                                // is pending on Lunar's official route.
                                Label keepAccountValue = new Label();
                                super.visitInsn(Opcodes.DUP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarOnlineStatus",
                                        "isFullAccessRouteActive", "()Z", false);
                                super.visitJumpInsn(Opcodes.IFEQ, keepAccountValue);
                                super.visitInsn(Opcodes.POP);
                                super.visitInsn(Opcodes.ICONST_1);
                                super.visitLabel(keepAccountValue);
                                super.visitInsn(Opcodes.IRETURN);
                                patched[0] = true;
                                return;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0]) {
                System.err.println("[gomod] QA emote catalog hook did not find LoginResponse.getHasAllEmotesFlag()");
                LunarConnectionDiagnostics.record("emote_catalog.transform_skipped",
                        "reason=login-response-flag-method-not-found");
                return null;
            }
            System.out.println("[gomod] Internal QA emote catalog flag applied locally");
            LunarConnectionDiagnostics.record("emote_catalog.transform_applied",
                    "fullAccessGate=active-relay-route otherwise=preserve-account-flag "
                            + "scope=local-internal-qa-build");
            return writer.toByteArray();
        }

        /**
         * The dev API enumerates its full catalog as explicit OwnedCosmetic IDs. Lunar then
         * adds the all-cosmetics flag to that same list when calculating Locker counts. In
         * the internal build, suppress the redundant flag only when FullAccess is enabled
         * and a large explicit catalog is present; otherwise preserve Lunar's account value.
         */
        private byte[] suppressDuplicateDevCosmeticCatalogFlag(byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            boolean[] patched = {false};
            boolean[] patchedOwnedList = {false};
            ClassVisitor modifier = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor visitor = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if ("getOwnedCosmeticsList".equals(name)
                            && "()Ljava/util/List;".equals(descriptor)) {
                        return new MethodVisitor(Opcodes.ASM9, visitor) {
                            @Override
                            public void visitInsn(int opcode) {
                                if (opcode == Opcodes.ARETURN) {
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
                                            "getClass", "()Ljava/lang/Class;", false);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                                            "getClassLoader", "()Ljava/lang/ClassLoader;", false);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarSlenderMeOwnership",
                                            "appendIfFullAccess",
                                            "(Ljava/util/List;Ljava/lang/ClassLoader;Ljava/lang/Object;)Ljava/util/List;", false);
                                    super.visitVarInsn(Opcodes.ALOAD, 0);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Object",
                                            "getClass", "()Ljava/lang/Class;", false);
                                    super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                                            "getClassLoader", "()Ljava/lang/ClassLoader;", false);
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                            "me/napim/gomod/features/LunarGomodMascotCatalog",
                                            "appendOwnedCompanion",
                                            "(Ljava/util/List;Ljava/lang/ClassLoader;)Ljava/util/List;", false);
                                    patchedOwnedList[0] = true;
                                    super.visitInsn(opcode);
                                    return;
                                }
                                super.visitInsn(opcode);
                            }
                        };
                    }
                    if (!"getHasAllCosmeticsFlag".equals(name) || !"()Z".equals(descriptor)) return visitor;
                    return new MethodVisitor(Opcodes.ASM9, visitor) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (opcode == Opcodes.IRETURN) {
                                // Keep the injected bytecode branch-free. The previous inline
                                // branch merged a value already on the operand stack with a
                                // rewritten return path and produced an invalid StackMapTable
                                // in Lunar 2.22.42 (VerifyError in getHasAllCosmeticsFlag).
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitInsn(Opcodes.SWAP);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "me/napim/gomod/features/LunarCosmeticCatalogGuard",
                                        "applyDevCatalogCountGuard",
                                        "(Ljava/lang/Object;Z)Z", false);
                                super.visitInsn(Opcodes.IRETURN);
                                patched[0] = true;
                                return;
                            }
                            super.visitInsn(opcode);
                        }
                    };
                }
            };
            reader.accept(modifier, 0);
            if (!patched[0] || !patchedOwnedList[0]) {
                System.err.println("[gomod] QA cosmetic count guard did not find "
                        + "the expected LoginResponse methods");
                LunarConnectionDiagnostics.record("cosmetic_catalog.transform_skipped",
                        "reason=login-response-method-not-found");
                return null;
            }
            System.out.println("[gomod] Internal QA cosmetic catalog double-count guard applied locally");
            LunarConnectionDiagnostics.record("cosmetic_catalog.transform_applied",
                    "flag=false-when-fullaccess-enabled-and-owned-count-at-least-5000 "
                            + "otherwise=preserve-account-flag;slenderMe=9753-when-fullaccess-enabled "
                            + "scope=local-internal-qa-build");
            return writer.toByteArray();
        }

        private byte[] removeMissingProfileDataHook(String className, byte[] classfileBuffer) {
            ClassReader reader = new ClassReader(classfileBuffer);
            Set<String> optionalProfileMethods = new HashSet<>();
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        private boolean referencesProfileData;

                        @Override
                        public void visitLdcInsn(Object value) {
                            if (PROFILE_DATA_CLASS.equals(value)) {
                                referencesProfileData = true;
                            }
                        }

                        @Override
                        public void visitEnd() {
                            if (referencesProfileData && descriptor.endsWith(")V")) {
                                optionalProfileMethods.add(name + descriptor);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

            if (optionalProfileMethods.isEmpty()) {
                return null;
            }

            ClassWriter writer = new ClassWriter(reader,
                    ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
            ClassVisitor visitor = new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                  String signature, String[] exceptions) {
                    MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!optionalProfileMethods.contains(name + descriptor)) {
                        return output;
                    }

                    output.visitCode();
                    output.visitInsn(Opcodes.RETURN);
                    output.visitMaxs(0, 0);
                    output.visitEnd();
                    System.out.println("[gomod] Removed missing Lunar Ichor ProfileData hook in "
                            + className + "." + name);
                    return null;
                }
            };
            reader.accept(visitor, 0);
            return writer.toByteArray();
        }
    }

}
