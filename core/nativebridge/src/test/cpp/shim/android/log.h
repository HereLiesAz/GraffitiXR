#pragma once
// Host shim for <android/log.h>: logcat priorities and __android_log_print routed to stderr.
#include <cstdarg>
#include <cstdio>
#include <cstdlib>

enum { ANDROID_LOG_UNKNOWN = 0, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG,
       ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };

inline int __android_log_print(int prio, const char* tag, const char* fmt, ...) {
    // Quiet by default; GRAFFITIXR_HOST_TEST_VERBOSE=1 shows everything.
    static const bool verbose = [] { const char* v = std::getenv("GRAFFITIXR_HOST_TEST_VERBOSE"); return v && *v == '1'; }();
    if (!verbose && prio < ANDROID_LOG_WARN) return 0;
    std::fprintf(stderr, "[%s] ", tag);
    va_list ap; va_start(ap, fmt); int n = std::vfprintf(stderr, fmt, ap); va_end(ap);
    std::fputc('\n', stderr);
    return n;
}
