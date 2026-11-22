package net.weavemc.loader.api;

import org.objectweb.asm.tree.ClassNode;

public abstract class Hook {
    private final String targetClass;

    protected Hook(String targetClass) {
        this.targetClass = targetClass;
    }

    public final String getTargetClass() {
        return targetClass;
    }

    public abstract void transform(ClassNode node, AssemblerConfig cfg);

    public static class AssemblerConfig {
    }
}
