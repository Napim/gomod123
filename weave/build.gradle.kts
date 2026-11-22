import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.jvm.tasks.Jar
import java.util.Properties

plugins {
    id("net.weavemc.gradle") version "1.3.4"
}

group = "me.napim"

val gomodVersion = Properties().run {
    rootProject.file("../src/main/resources/gomod.version.properties").inputStream().use { load(it) }
    getProperty("version", "1.0.0").trim()
}
version = gomodVersion

val publicRelease = providers.gradleProperty("publicRelease")
    .map { it.toBoolean() }
    .orElse(true)
    .get()
val qaDirectAssetsTest = providers.gradleProperty("qaDirectAssetsTest")
    .map { it.toBoolean() }
    .orElse(false)
    .get() && !publicRelease
val qaDisableWebSocketDiagnostics = providers.gradleProperty("qaDisableWebSocketDiagnostics")
    .map { it.toBoolean() }
    .orElse(false)
    .get() && !publicRelease

weave {
    configure {
        name = "gomod123"
        modId = "gomod123"
        entryPoints = listOf("me.napim.gomod.weave.GomodWeaveEntrypoint")
        mixinConfigs = listOf("gomod.weave.mixins.json")
        accessWideners = listOf("gomod.weave.accesswidener")
        mcpMappings()
    }
    version("1.8.9")
}

repositories {
    maven("https://repo.spongepowered.org/maven/")
    maven("https://gitlab.com/api/v4/projects/80566527/packages/maven")
    maven("https://maven.minecraftforge.net")
    mavenCentral()
}

val embed by configurations.creating
val relocator by configurations.creating

dependencies {
    implementation("net.weavemc:loader:1.3.4")
    implementation("net.weavemc:internals:1.3.4")
    implementation("net.weavemc.api:api:1.3.4")
    implementation("net.weavemc.api:api-v1_8:1.3.4")

    compileOnly("org.spongepowered:mixin:0.8.5")
    compileOnly("org.jetbrains:annotations:24.0.1")

    implementation("org.java-websocket:Java-WebSocket:1.5.3")
    implementation("org.slf4j:slf4j-api:1.7.36")
    embed("org.java-websocket:Java-WebSocket:1.5.3")
    embed("org.slf4j:slf4j-api:1.7.36")
    embed("org.slf4j:slf4j-nop:1.7.36")

    relocator("org.ow2.asm:asm:9.6")
    relocator("org.ow2.asm:asm-commons:9.6")

    testImplementation("junit:junit:4.13.2")
}

val generateGomodWeaveBuild by tasks.registering {
    val output = layout.buildDirectory.file("generated/sources/gomodWeave/me/napim/gomod/GomodReleaseBuild.java")
    inputs.property("publicRelease", publicRelease)
    inputs.property("qaDirectAssetsTest", qaDirectAssetsTest)
    inputs.property("qaDisableWebSocketDiagnostics", qaDisableWebSocketDiagnostics)
    outputs.file(output)
    doLast {
        val file = output.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """package me.napim.gomod;

/**
 * Compile-time release flavor for the native Weave build.
 * {@code publicRelease=true}: public build; {@code publicRelease=false}: dev/internal build.
 */
public final class GomodReleaseBuild {
    public static final boolean PUBLIC_RELEASE = $publicRelease;
    public static final boolean QA_DIRECT_ASSETS_TEST = $qaDirectAssetsTest;
    public static final boolean QA_DISABLE_WEBSOCKET_DIAGNOSTICS = $qaDisableWebSocketDiagnostics;
    private GomodReleaseBuild() {}
}
"""
        )
    }
}

val publicReleaseMainExclusions = listOf(
    "me/napim/gomod/InternalDcCommands.java",
    "me/napim/gomod/features/DcMirrorBridge.java",
    "me/napim/gomod/features/DcMirrorGateway.java",
    "me/napim/gomod/features/InternalAdvancedPerspectiveAllowlist.java",
    "me/napim/gomod/gui/DcMirrorSettingsGUI.java",
    "me/napim/gomod/gui/ApiKeyGUI.java",
    "me/napim/gomod/util/DcMirrorFormatter.java",
    "me/napim/gomod/command/DcTalkCommand.java",
    "me/napim/gomod/command/DcRdCommand.java",
    "me/napim/gomod/command/DcDdmCommand.java",
)

val weaveMainExclusions = listOf(
    "me/napim/gomod/GomodForgeMod.java",
    "me/napim/gomod/GomodRuntimeBootstrap.java",
    "me/napim/gomod/coremod/**",
    "me/napim/gomod/forge/**",
    "me/napim/gomod/features/HudEditorCrosshairRenderer.java",
    "me/napim/gomod/mixins/GuiIngameForgeAccessor.java",
    "me/napim/gomod/mixins/GuiIngameForgeMixin.java",
    "me/napim/gomod/mixins/PatcherEntityRenderingCompatMixin.java",
)

fun adaptWeaveJavaLine(line: String): String {
    var adapted = line.replace(".getSlotIndex()", ".slotIndex")
    adapted = adapted
        .replace("int keyCode) throws java.io.IOException", "int keyCode)")
        .replace("int keyCode) throws IOException", "int keyCode)")
        .replace("int mouseButton) throws java.io.IOException", "int mouseButton)")
        .replace("int mouseButton) throws IOException", "int mouseButton)")
        .replace("handleMouseInput() throws java.io.IOException", "handleMouseInput()")
        .replace("handleMouseInput() throws IOException", "handleMouseInput()")
        .replace("GuiButton button) throws java.io.IOException", "GuiButton button)")
        .replace("GuiButton button) throws IOException", "GuiButton button)")
    if (adapted.contains(" mouseClicked(")) {
        adapted = adapted
            .replace(" throws java.io.IOException", "")
            .replace(" throws IOException", "")
    }
    return adapted
}

val prepareWeaveSources by tasks.registering(Copy::class) {
    val output = layout.buildDirectory.dir("generated/sources/sharedWeave")
    inputs.property("publicRelease", publicRelease)
    doFirst {
        delete(output.get().asFile)
    }
    from("../src/main/java") {
        val exclusions = weaveMainExclusions + if (publicRelease) publicReleaseMainExclusions else emptyList()
        exclude(*exclusions.toTypedArray())
        filter { line: String -> adaptWeaveJavaLine(line) }
        filesMatching("me/napim/gomod/gui/HudPositionFieldPair.java") {
            filter { line: String ->
                line.replace(" throws java.io.IOException", "")
                    .replace(" throws IOException", "")
            }
        }
    }
    if (publicRelease) {
        from("../src/publicRelease/java") {
            include("**/*.java")
            filter { line: String -> adaptWeaveJavaLine(line) }
        }
    }
    into(output)
}

sourceSets {
    main {
        java {
            srcDir(prepareWeaveSources.map { it.destinationDir })
            srcDir(layout.buildDirectory.dir("generated/sources/gomodWeave"))
            srcDir("src/lunarCompileOnly/java")
        }
        resources {
            srcDir("../src/main/resources")
            exclude(
                "mcmod.info",
                "gomod_at.cfg",
                "gomod.mixins.json"
            )
        }
    }
    test {
        java {
            srcDir("../src/test/java")
        }
    }
}

tasks.processResources {
    filesMatching("assets/lunar/dev_cosmetics.json") {
        // Cosmetic JIT resources must use Lunar's accepted download domain.
        filter { line: String -> line.replace("lunar:cosmetics/", "lunar-jit:cosmetics/") }
    }
}

tasks.compileJava {
    dependsOn(generateGomodWeaveBuild, prepareWeaveSources)
    options.encoding = "UTF-8"
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
}

tasks.test {
    classpath += configurations.compileClasspath.get()
}

val compileRelocator by tasks.registering(JavaCompile::class) {
    setSource(files("../tools/RelocateEmbeddedLibs.java"))
    classpath = relocator
    destinationDirectory.set(layout.buildDirectory.dir("classes/relocator"))
    sourceCompatibility = "1.8"
    targetCompatibility = "1.8"
    options.encoding = "UTF-8"
}

val relocateWeaveJar by tasks.registering(JavaExec::class) {
    dependsOn(compileRelocator)
    mainClass.set("RelocateEmbeddedLibs")
    classpath = files(compileRelocator.map { it.destinationDirectory }, relocator)
    doFirst {
        val builtJar = tasks.named<Jar>("jar").get().archiveFile.get().asFile
        args(builtJar.absolutePath, builtJar.absolutePath)
    }
}

tasks.named<Jar>("jar") {
    archiveBaseName.set(if (publicRelease) "gomod123-lunar" else "gomod123-lunar-devbuild")
    archiveClassifier.set(if (qaDisableWebSocketDiagnostics) "qa-wsdiag-off" else "")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("com/moonsworth/webosr/javascript/CallbackJS.class", "module-info.class", "**/module-info.class")
    from(embed.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude(
            "module-info.class",
            "**/module-info.class",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/maven/**",
            "META-INF/proguard/**",
            "META-INF/versions/**"
        )
    }
    finalizedBy(relocateWeaveJar)
}

val weaveRequiredHooks = listOf(
    "me.napim.gomod.weave.hooks.RenderManagerGuiOpacityWeaveHook",
    "me.napim.gomod.weave.hooks.LunarClothCloakOpacityWeaveHook",
    "me.napim.gomod.weave.hooks.LunarCosmeticRenderOpacityWeaveHook",
    "me.napim.gomod.weave.hooks.LunarDisguiseFirstPersonCosmeticsWeaveHook",
    "me.napim.gomod.weave.hooks.LunarCurrentClothCloakOpacityWeaveHook",
    "me.napim.gomod.weave.hooks.LunarCurrentClothVertexAlphaWeaveHook",
    "me.napim.gomod.weave.hooks.GlStateManagerGuiOpacityWeaveHook",
    "me.napim.gomod.weave.hooks.LunarHypixelNametagWeaveHook",
    "me.napim.gomod.weave.hooks.ItemRendererPeeWeaveHook",
    "me.napim.gomod.weave.hooks.ModelPlayerPeePoseWeaveHook"
)

tasks.named("writeModConfig") {
    doLast {
        val modJson = layout.buildDirectory.file("weave/weave.mod.json").get().asFile
        if (modJson.exists()) {
            var text = modJson.readText()
            val hooksBlock = "\"hooks\": [\n" +
                weaveRequiredHooks.joinToString(",\n") { "        \"$it\"" } +
                "\n    ]"
            text = if (text.contains("\"hooks\"")) {
                text.replace(Regex("\"hooks\"\\s*:\\s*\\[[\\s\\S]*?]"), hooksBlock)
            } else {
                text.replace(Regex("\\n}\\s*$"), ",\n    $hooksBlock\n}")
            }
            modJson.writeText(text)
        }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(8))
    }
}
