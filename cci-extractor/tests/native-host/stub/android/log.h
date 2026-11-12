#pragma once
#include <stdio.h>
#include <stdarg.h>
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
static inline int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    va_list ap; va_start(ap, fmt); fprintf(stderr, "[%s] ", tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr); va_end(ap); return 0;
}
