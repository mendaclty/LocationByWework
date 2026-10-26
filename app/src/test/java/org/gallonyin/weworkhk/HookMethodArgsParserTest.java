package org.gallonyin.weworkhk;

import org.junit.Test;

import static org.junit.Assert.*;

public class HookMethodArgsParserTest {

    @Test
    public void parsesClassTargetFormAndKeepsParameterTypesAndCallback() {
        Object callback = new Object();
        HookMethodArgsParser.ParsedHook parsed = HookMethodArgsParser.parse(
                String.class, "substring", int.class, callback);

        assertSame(String.class, parsed.targetClass);
        assertNull(parsed.className);
        assertNull(parsed.classLoader);
        assertEquals("substring", parsed.methodName);
        assertEquals(2, parsed.hookArgs.length);
        assertSame(int.class, parsed.hookArgs[0]);
        assertSame(callback, parsed.hookArgs[1]);
    }

    @Test
    public void parsesClassNameAndClassLoaderForm() {
        ClassLoader loader = getClass().getClassLoader();
        Object callback = new Object();
        HookMethodArgsParser.ParsedHook parsed = HookMethodArgsParser.parse(
                "android.app.Application", loader, "attach", android.content.Context.class, callback);

        assertNull(parsed.targetClass);
        assertEquals("android.app.Application", parsed.className);
        assertSame(loader, parsed.classLoader);
        assertEquals("attach", parsed.methodName);
        assertEquals(2, parsed.hookArgs.length);
        assertSame(android.content.Context.class, parsed.hookArgs[0]);
        assertSame(callback, parsed.hookArgs[1]);
    }

    @Test
    public void rejectsIncompleteOrUnsupportedArguments() {
        assertRejected(new Object[0]);
        assertRejected(new Object[]{String.class});
        assertRejected(new Object[]{42});
        assertRejected(new Object[]{"android.app.Application", "not-a-class-loader", "attach"});
    }

    private static void assertRejected(Object[] args) {
        try {
            HookMethodArgsParser.parse(args);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // 参数不完整或目标类型不支持时应拒绝解析
        }
    }
}
