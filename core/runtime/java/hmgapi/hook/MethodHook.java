package hmgapi.hook;

import java.lang.reflect.Member;

/**
 * A hook on one method or constructor, shaped like Xposed's XC_MethodHook.
 * Override either or both callbacks. Calling {@link Param#setResult} or
 * {@link Param#setThrowable} in {@link #beforeHookedMethod} skips the original
 * method and any hooks registered after this one. Mods hook through
 * ModContext.hook, which orders "replace" hooks after every other one on a method.
 */
public abstract class MethodHook {
    static final int PRIORITY_DEFAULT = 0;
    static final int PRIORITY_CORE_AFTER = 1;
    static final int PRIORITY_REPLACE = 2;

    /** order on one method: beforeHookedMethod runs low-to-high, afterHookedMethod high-to-low */
    int priority;

    protected void beforeHookedMethod(Param param) throws Throwable {}

    protected void afterHookedMethod(Param param) throws Throwable {}

    public static final class Param {
        public final Member method;
        public final Object thisObject;
        public final Object[] args;
        private Object result;
        private Throwable throwable;
        boolean returnEarly;

        Param(Member method, Object thisObject, Object[] args) {
            this.method = method;
            this.thisObject = thisObject;
            this.args = args;
        }

        public Object getResult() { return result; }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
            this.returnEarly = true;
        }

        public Throwable getThrowable() { return throwable; }

        public boolean hasThrowable() { return throwable != null; }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
            this.returnEarly = true;
        }

        void reset() {
            result = null;
            throwable = null;
            returnEarly = false;
        }

        /** The result, or rethrows the throwable. */
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }
}
