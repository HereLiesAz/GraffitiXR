//
// This file is auto-generated. Please don't modify it!
//
package org.opencv.video;

import org.opencv.core.Scalar;

// C++: class Params

public class TrackerVit_Params {

    protected final long nativeObj;
    protected TrackerVit_Params(long addr) {
      nativeObj = addr;
      
    }

    public TrackerVit_Params() {
        nativeObj = TrackerVit_Params_0();
    }

    // internal usage only
    public static TrackerVit_Params __fromPtr__(long addr) { return new TrackerVit_Params(addr); }

    //
    // C++:   cv::TrackerVit::Params::Params()
    //

    // C++:   cv::TrackerVit::Params::Params()
    private static native long TrackerVit_Params_0();


    //
    // C++: string TrackerVit_Params::net
    //

    // C++: string TrackerVit_Params::net
    private static native String get_net_0(long nativeObj);


    //
    // C++: void TrackerVit_Params::net
    //

    // C++: void TrackerVit_Params::net
    private static native void set_net_0(long nativeObj, String net);


    //
    // C++: int TrackerVit_Params::backend
    //

    // C++: int TrackerVit_Params::backend
    private static native int get_backend_0(long nativeObj);


    //
    // C++: void TrackerVit_Params::backend
    //

    // C++: void TrackerVit_Params::backend
    private static native void set_backend_0(long nativeObj, int backend);


    //
    // C++: int TrackerVit_Params::target
    //

    // C++: int TrackerVit_Params::target
    private static native int get_target_0(long nativeObj);


    //
    // C++: void TrackerVit_Params::target
    //

    // C++: void TrackerVit_Params::target
    private static native void set_target_0(long nativeObj, int target);


    //
    // C++: Scalar TrackerVit_Params::meanvalue
    //

    // C++: Scalar TrackerVit_Params::meanvalue
    private static native double[] get_meanvalue_0(long nativeObj);


    //
    // C++: void TrackerVit_Params::meanvalue
    //

    // C++: void TrackerVit_Params::meanvalue
    private static native void set_meanvalue_0(long nativeObj, double meanvalue_val0, double meanvalue_val1, double meanvalue_val2, double meanvalue_val3);


    //
    // C++: Scalar TrackerVit_Params::stdvalue
    //

    // C++: Scalar TrackerVit_Params::stdvalue
    private static native double[] get_stdvalue_0(long nativeObj);


    //
    // C++: void TrackerVit_Params::stdvalue
    //

    // C++: void TrackerVit_Params::stdvalue
    private static native void set_stdvalue_0(long nativeObj, double stdvalue_val0, double stdvalue_val1, double stdvalue_val2, double stdvalue_val3);


    //
    // C++: float TrackerVit_Params::tracking_score_threshold
    //

    // C++: float TrackerVit_Params::tracking_score_threshold
    private static native float get_tracking_score_threshold_0(long nativeObj);


    //
    // C++: void TrackerVit_Params::tracking_score_threshold
    //

    // C++: void TrackerVit_Params::tracking_score_threshold
    private static native void set_tracking_score_threshold_0(long nativeObj, float tracking_score_threshold);

    // native support for java finalize() or cleaner
    private static native void delete(long nativeObj);

    public long getNativeObjAddr() { return nativeObj; }

    public String get_net() {
        return get_net_0(nativeObj);
    }

    public void set_net(String net) {
        set_net_0(nativeObj, net);
    }

    public int get_backend() {
        return get_backend_0(nativeObj);
    }

    public void set_backend(int backend) {
        set_backend_0(nativeObj, backend);
    }

    public int get_target() {
        return get_target_0(nativeObj);
    }

    public void set_target(int target) {
        set_target_0(nativeObj, target);
    }

    public Scalar get_meanvalue() {
        return new Scalar(get_meanvalue_0(nativeObj));
    }

    public void set_meanvalue(Scalar meanvalue) {
        set_meanvalue_0(nativeObj, meanvalue.val[0], meanvalue.val[1], meanvalue.val[2], meanvalue.val[3]);
    }

    public Scalar get_stdvalue() {
        return new Scalar(get_stdvalue_0(nativeObj));
    }

    public void set_stdvalue(Scalar stdvalue) {
        set_stdvalue_0(nativeObj, stdvalue.val[0], stdvalue.val[1], stdvalue.val[2], stdvalue.val[3]);
    }

    public float get_tracking_score_threshold() {
        return get_tracking_score_threshold_0(nativeObj);
    }

    public void set_tracking_score_threshold(float tracking_score_threshold) {
        set_tracking_score_threshold_0(nativeObj, tracking_score_threshold);
    }

    @Override
    protected void finalize() throws Throwable {
        delete(nativeObj);
    }

}
