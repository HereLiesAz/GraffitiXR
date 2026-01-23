package org.opencv.core;

public abstract class CleanableMat {

    public final long nativeObj;

    protected CleanableMat(long obj) {
        if (obj == 0)
            throw new UnsupportedOperationException("Native object address is NULL");

        nativeObj = obj;
    }

    private static native void n_delete(long nativeObj);

    @Override
    protected void finalize() throws Throwable {
        n_delete(nativeObj);
        super.finalize();
    }
}
