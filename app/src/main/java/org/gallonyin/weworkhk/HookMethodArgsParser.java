package org.gallonyin.weworkhk;

import java.util.Arrays;

/** Pure argument parser for the two Xposed findAndHookMethod call forms. */
final class HookMethodArgsParser {
    private HookMethodArgsParser() {
    }

    static ParsedHook parse(Object... args) {
        if (args == null || args.length < 2) {
            throw new IllegalArgumentException("hook method arguments are incomplete");
        }
        if (args[0] instanceof Class) {
            if (!(args[1] instanceof String) || args.length < 3) {
                throw new IllegalArgumentException("invalid Class hook method arguments");
            }
            return new ParsedHook((Class<?>) args[0], null, null,
                    (String) args[1], Arrays.copyOfRange(args, 2, args.length));
        }
        if (args[0] instanceof String) {
            if (!(args[1] instanceof ClassLoader) || args.length < 3 || !(args[2] instanceof String)) {
                throw new IllegalArgumentException("invalid String hook method arguments");
            }
            return new ParsedHook(null, (String) args[0], (ClassLoader) args[1],
                    (String) args[2], Arrays.copyOfRange(args, 3, args.length));
        }
        throw new IllegalArgumentException("unsupported hook target: " + args[0]);
    }

    static final class ParsedHook {
        final Class<?> targetClass;
        final String className;
        final ClassLoader classLoader;
        final String methodName;
        final Object[] hookArgs;

        private ParsedHook(Class<?> targetClass, String className, ClassLoader classLoader,
                           String methodName, Object[] hookArgs) {
            this.targetClass = targetClass;
            this.className = className;
            this.classLoader = classLoader;
            this.methodName = methodName;
            this.hookArgs = hookArgs;
        }
    }
}
