package me.napim.gomod.features;

import me.napim.gomod.util.GomodPaths;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Centralized logger for reflection-related errors. Writes a detailed log to disk
 * to make diagnosing issues like InvocationTargetException straightforward.
 */
public final class ReflectionLogger {
    private static File reflectionLogFile() {
        return new File(GomodPaths.getGomod123Dir(), "reflection.log");
    }
    private static final AtomicBoolean HANDLER_INSTALLED = new AtomicBoolean(false);

    private ReflectionLogger() {}

    public static String getLogFilePath() {
        return reflectionLogFile().getAbsolutePath();
    }

    public static void installGlobalHandler() {
        if (HANDLER_INSTALLED.compareAndSet(false, true)) {
            ensureDir();
            final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                try {
                    if (isReflectionRelated(throwable)) {
                        log("uncaught", "Uncaught exception on thread '" + thread.getName() + "'", throwable);
                    }
                } catch (Throwable ignored) {
                }
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                } else if (throwable != null) {
                    System.err.println("Exception in thread \"" + thread.getName() + "\"");
                    throwable.printStackTrace();
                }
            });
        }
    }

    public static void logReflectionFailure(String context, Throwable error) {
        log("reflection", context, error);
    }

    public static void logInvocationTarget(String context, Throwable error) {
        Throwable cause = unwrapInvocationTarget(error);
        log("invocation", context + " (cause: " + cause.getClass().getName() + ")", error);
    }

    private static void log(String kind, String context, Throwable error) {
        ensureDir();
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS").format(new Date());
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        error.printStackTrace(pw);
        String stack = sw.toString();

        StringBuilder line = new StringBuilder()
                .append("[" + timestamp + "] ")
                .append("[" + kind + "] ")
                .append(context)
                .append(" | error=" + error.getClass().getName() + ": " + safeMsg(error));

        // Mirror to stderr for regular log visibility
        System.err.println("[gomod] " + line);

        // Append to file
        try (FileWriter fw = new FileWriter(reflectionLogFile(), true)) {
            fw.write(line.toString());
            fw.write(System.lineSeparator());
            fw.write(stack);
            fw.write(System.lineSeparator());
        } catch (IOException io) {
            System.err.println("[gomod] Failed writing reflection log: " + io.getMessage());
        }
    }

    private static boolean isReflectionRelated(Throwable t) {
        if (t == null) return false;
        // Check exception type (walk cause chain)
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String name = cur.getClass().getName();
            if (name.startsWith("java.lang.reflect.") ||
                name.equals("java.lang.NoSuchFieldException") ||
                name.equals("java.lang.NoSuchMethodException") ||
                name.equals("java.lang.IllegalAccessException") ||
                name.equals("java.lang.ClassNotFoundException")) {
                return true;
            }
        }
        // Check stack trace for reflection invocation frames
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            for (StackTraceElement frame : cur.getStackTrace()) {
                String cls = frame.getClassName();
                String method = frame.getMethodName();
                if (cls.startsWith("java.lang.reflect.") ||
                    (cls.equals("java.lang.reflect.Method") && method.equals("invoke")) ||
                    (cls.equals("java.lang.reflect.Field") && method.equals("get"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Throwable unwrapInvocationTarget(Throwable t) {
        try {
            Class<?> ite = Class.forName("java.lang.reflect.InvocationTargetException");
            if (ite.isInstance(t)) {
                Throwable cause = (Throwable) ite.getMethod("getTargetException").invoke(t);
                return cause != null ? cause : t;
            }
        } catch (Throwable ignored) {}
        return t;
    }

    private static void ensureDir() {
        File dir = GomodPaths.getGomod123Dir();
        if (!dir.exists()) {
            try {
                dir.mkdirs();
            } catch (Throwable ignored) {}
        }
        File f = reflectionLogFile();
        if (!f.exists()) {
            try {
                f.createNewFile();
            } catch (Throwable ignored) {}
        }
    }

    private static String safeMsg(Throwable t) {
        try {
            String m = t.getMessage();
            return m == null ? "" : m;
        } catch (Throwable ignored) {
            return "";
        }
    }
}


