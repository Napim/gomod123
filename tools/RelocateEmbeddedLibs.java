import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Relocates embedded third-party libraries (Java-WebSocket and SLF4J) into
 * {@code me/napim/gomod/shadow/*} so GoMod's fat JARs cannot collide with
 * Forge, Lunar Client, or other mods on the shared classpath.
 * <p>
 * String literals inside {@code me/napim/gomod/**} are intentionally preserved so
 * Lunar native bytecode hooks targeting {@code org/java_websocket/*} remain intact.
 */
public final class RelocateEmbeddedLibs {
    private static final String WS_FROM = "org/java_websocket/";
    private static final String WS_TO = "me/napim/gomod/shadow/java_websocket/";
    private static final String SLF4J_FROM = "org/slf4j/";
    private static final String SLF4J_TO = "me/napim/gomod/shadow/slf4j/";

    private RelocateEmbeddedLibs() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: RelocateEmbeddedLibs <inputJar> <outputJar>");
        }
        File input = new File(args[0]);
        File output = new File(args[1]);
        if (!input.isFile()) {
            throw new IllegalArgumentException("Input JAR does not exist: " + input.getAbsolutePath());
        }
        File tempOut = new File(output.getParentFile(), output.getName() + ".relocating.tmp");
        relocateJar(input, tempOut);
        Files.move(tempOut.toPath(), output.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void relocateJar(File input, File output) throws IOException {
        Set<String> writtenEntries = new HashSet<String>();
        try (ZipFile zip = new ZipFile(input);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(output))) {

            // Always emit META-INF/MANIFEST.MF first if present so JarInputStream consumers see it immediately.
            ZipEntry manifestEntry = zip.getEntry("META-INF/MANIFEST.MF");
            if (manifestEntry != null) {
                writeRawEntry(zip, manifestEntry, "META-INF/MANIFEST.MF", zos, writtenEntries);
            }

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (entry.isDirectory() || shouldStripEntry(name) || "META-INF/MANIFEST.MF".equals(name)) {
                    continue;
                }

                String outName = remapEntryName(name);
                if (!writtenEntries.add(outName)) {
                    continue;
                }

                byte[] rawBytes = readAllBytes(zip.getInputStream(entry));
                byte[] outBytes;
                if (name.endsWith(".class") && shouldRemapClass(name)) {
                    final boolean insideSlf4j = name.startsWith(SLF4J_FROM);
                    outBytes = remapClassBytes(rawBytes, insideSlf4j);
                } else {
                    outBytes = rawBytes;
                }

                ZipEntry outEntry = new ZipEntry(outName);
                outEntry.setTime(0L);
                zos.putNextEntry(outEntry);
                zos.write(outBytes);
                zos.closeEntry();
            }
        }
    }

    private static void writeRawEntry(ZipFile zip, ZipEntry entry, String outName,
                                      ZipOutputStream zos, Set<String> writtenEntries) throws IOException {
        if (!writtenEntries.add(outName)) {
            return;
        }
        byte[] raw = readAllBytes(zip.getInputStream(entry));
        ZipEntry outEntry = new ZipEntry(outName);
        outEntry.setTime(0L);
        zos.putNextEntry(outEntry);
        zos.write(raw);
        zos.closeEntry();
    }

    private static boolean shouldStripEntry(String name) {
        if ("module-info.class".equals(name) || name.endsWith("/module-info.class")) {
            return true;
        }
        if (!name.startsWith("META-INF/")) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT);
        if (upper.endsWith(".SF") || upper.endsWith(".DSA") || upper.endsWith(".RSA")) {
            return true;
        }
        return name.startsWith("META-INF/maven/")
                || name.startsWith("META-INF/proguard/")
                || name.startsWith("META-INF/versions/");
    }

    private static boolean shouldRemapClass(String entryName) {
        return entryName.startsWith("me/napim/gomod/")
                || entryName.startsWith(WS_FROM)
                || entryName.startsWith(SLF4J_FROM);
    }

    private static String remapEntryName(String entryName) {
        if (entryName.startsWith(WS_FROM)) {
            return WS_TO + entryName.substring(WS_FROM.length());
        }
        if (entryName.startsWith(SLF4J_FROM)) {
            return SLF4J_TO + entryName.substring(SLF4J_FROM.length());
        }
        return entryName;
    }

    private static byte[] remapClassBytes(byte[] rawBytes, final boolean rewriteSlf4jBinderLiteral) {
        ClassReader reader = new ClassReader(rawBytes);
        ClassWriter writer = new ClassWriter(0);
        Remapper remapper = new Remapper() {
            @Override
            public String map(String internalName) {
                if (internalName == null) {
                    return null;
                }
                if (internalName.startsWith(WS_FROM)) {
                    return WS_TO + internalName.substring(WS_FROM.length());
                }
                if (internalName.startsWith(SLF4J_FROM)) {
                    return SLF4J_TO + internalName.substring(SLF4J_FROM.length());
                }
                return internalName;
            }

            @Override
            public Object mapValue(Object value) {
                if (rewriteSlf4jBinderLiteral && value instanceof String) {
                    String s = (String) value;
                    if (s.startsWith(SLF4J_FROM)) {
                        return SLF4J_TO + s.substring(SLF4J_FROM.length());
                    }
                    if (s.startsWith("org.slf4j.")) {
                        return "me.napim.gomod.shadow.slf4j." + s.substring("org.slf4j.".length());
                    }
                }
                return super.mapValue(value);
            }
        };
        reader.accept(new ClassRemapper(writer, remapper), 0);
        return writer.toByteArray();
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        try (InputStream stream = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = stream.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
