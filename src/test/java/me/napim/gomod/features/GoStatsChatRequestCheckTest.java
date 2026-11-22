package me.napim.gomod.features;

import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * JUnit regression suite for GoStats chat trigger parsing and core safety fixes.
 */
public class GoStatsChatRequestCheckTest {

    public static void main(String[] args) throws Exception {
        GoStatsChatRequestCheckTest suite = new GoStatsChatRequestCheckTest();
        suite.parsesOutgoingGoStatsTriggers();
        suite.sanitizesIncomingScreenshotFilenamesAndValidatesImages();
        suite.apiKeyHashNeverReturnsPlaintextAndHandlesNulls();
        suite.reflectionLoggerAlwaysForwardsUncaughtExceptions();
        System.out.println("GoStatsChatRequestCheckTest passed");
    }

    @Test
    public void parsesOutgoingGoStatsTriggers() {
        expect("hi @gostats123", "hi", false);
        expect("@GOSTATS123 compare these stats @gostats123", "compare these stats", false);
        expect("/msg Friend @gostats123 tell me more", "tell me more", true);
        expect("/shout @gostats123 should I rush mid?", "should I rush mid?", false);
        expect("/r @gostats123 who won?", "who won?", true);
        expect("/reply who won? @gostats123", "who won?", true);
        expectNull("ordinary chat without a mention");
        expectNull("/warp @gostats123 lobby");
        expectNull("/msg friend");
        expectNull("<gostats123> @gostats123 should not recurse");
        expectNull(null);
    }

    @Test
    public void sanitizesIncomingScreenshotFilenamesAndValidatesImages() throws Exception {
        IncomingScreenshotManager.IncomingScreenshot traversal =
                new IncomingScreenshotManager.IncomingScreenshot("../../attacker", "../../evil.exe", "application/octet-stream", new byte[0], 1L);
        assertEquals("evil.png", traversal.fileName);
        assertFalse(traversal.fileName.contains(".."));
        assertFalse(traversal.fileName.contains("/"));
        assertFalse(traversal.fileName.contains("\\"));

        IncomingScreenshotManager.IncomingScreenshot scriptPayload =
                new IncomingScreenshotManager.IncomingScreenshot("friend", "C:\\Windows\\System32\\drop.bat", "text/plain", new byte[0], 1L);
        assertEquals("drop.png", scriptPayload.fileName);

        IncomingScreenshotManager.IncomingScreenshot validJpg =
                new IncomingScreenshotManager.IncomingScreenshot("friend", "capture_01.JPG", "image/jpeg", new byte[0], 1L);
        assertEquals("capture_01.jpg", validJpg.fileName);

        Method validate = IncomingScreenshotManager.class.getDeclaredMethod("validateDecodedImageBytes", byte[].class);
        validate.setAccessible(true);

        // Reject arbitrary executable bytes.
        try {
            validate.invoke(null, (Object) "MZ-not-an-image".getBytes(StandardCharsets.UTF_8));
            fail("Expected non-image payload to be rejected");
        } catch (InvocationTargetException expected) {
            assertTrue(expected.getCause() instanceof IllegalArgumentException);
        }

        // Accept valid small PNG bytes.
        BufferedImage tiny = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        ByteArrayOutputStream pngOut = new ByteArrayOutputStream();
        ImageIO.write(tiny, "png", pngOut);
        validate.invoke(null, (Object) pngOut.toByteArray());
    }

    @Test
    public void apiKeyHashNeverReturnsPlaintextAndHandlesNulls() throws Exception {
        Method keyHash = ApiKeyManager.class.getDeclaredMethod("keyHash", String.class);
        keyHash.setAccessible(true);

        assertNull(keyHash.invoke(null, new Object[]{null}));
        assertNull(keyHash.invoke(null, "   "));

        String secret = "01234567-89ab-cdef-0123-456789abcdef";
        String hashed = (String) keyHash.invoke(null, secret);
        assertNotNull(hashed);
        assertEquals(64, hashed.length());
        assertFalse("Hash must never equal plaintext key", secret.equalsIgnoreCase(hashed));
    }

    @Test
    public void reflectionLoggerAlwaysForwardsUncaughtExceptions() throws Exception {
        Thread.UncaughtExceptionHandler original = Thread.getDefaultUncaughtExceptionHandler();
        Field installedField = ReflectionLogger.class.getDeclaredField("HANDLER_INSTALLED");
        installedField.setAccessible(true);
        AtomicBoolean installedFlag = (AtomicBoolean) installedField.get(null);
        boolean wasInstalled = installedFlag.get();
        try {
            AtomicReference<Throwable> forwarded = new AtomicReference<Throwable>();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> forwarded.set(e));
            installedFlag.set(false);
            ReflectionLogger.installGlobalHandler();

            Thread.UncaughtExceptionHandler active = Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull(active);

            RuntimeException nonReflection = new RuntimeException("boom");
            active.uncaughtException(Thread.currentThread(), nonReflection);
            assertEquals("Non-reflection exceptions must be forwarded", nonReflection, forwarded.get());

            forwarded.set(null);
            NoSuchMethodException reflectionEx = new NoSuchMethodException("missing");
            active.uncaughtException(Thread.currentThread(), reflectionEx);
            assertEquals("Reflection exceptions must also be forwarded to prior handler", reflectionEx, forwarded.get());
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original);
            installedFlag.set(wasInstalled);
        }
    }

    private static void expect(String outgoing, String question, boolean privateReply) {
        GoStatsChatRequest request = GoStatsChatRequest.parseOutgoing(outgoing);
        assertNotNull("Expected GoStats request for: " + outgoing, request);
        assertEquals(question, request.question);
        assertEquals(privateReply, request.privateReply);
    }

    private static void expectNull(String outgoing) {
        assertNull("Expected no GoStats request for: " + outgoing, GoStatsChatRequest.parseOutgoing(outgoing));
    }
}
