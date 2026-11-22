package com.moonsworth.webosr.javascript;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Compile-time signature only; Lunar provides this annotation at runtime. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CallbackJS {
    String value();

    boolean async() default false;
}
