// Stub for OpenCV 5.0.0 Android SDK missing MLAS (Microsoft Linear Algebra
// Subroutines) dependency.  libopencv_dnn.a references MlasHGemmSupported but
// the SDK omits the MLAS static library.  Returning false makes the DNN module
// fall back to its standard (non-HGEMM) code path — functionally identical,
// just without half-precision GEMM acceleration.

enum CBLAS_TRANSPOSE { CblasNoTrans = 111, CblasTrans = 112, CblasConjTrans = 113 };

bool MlasHGemmSupported(CBLAS_TRANSPOSE, CBLAS_TRANSPOSE) { return false; }
