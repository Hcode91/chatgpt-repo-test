// Desktop harness for the native CIA engine. It calls the real JNI entry point with a minimal fake
// JNIEnv (no callbacks), and also exposes the bundled ctrtool/makerom so the test can build fixtures
// and inspect results.
//   cia3ds convert <in.cia> <out.cci> <seeddb.bin> <tmpdir>
//   cia3ds ctrtool <args...>
//   cia3ds makerom <args...>
#include <jni.h>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <unistd.h>

extern "C" JNIEXPORT jint JNICALL Java_com_retrotools_cciextractor_NativeCia_nativeDecryptCia(
    JNIEnv *, jobject, jint, jint, jstring, jstring, jstring, jboolean, jobject, jobject, jobject);
extern "C" int makerom_main(int argc, char *argv[]);
int ctrtool_main(int argc, char *argv[], char *envp[]);

static const char *GetUtf(JNIEnv *, jstring s, jboolean *) { return (const char *)s; }
static void ReleaseUtf(JNIEnv *, jstring, const char *) {}
static jint PushFrame(JNIEnv *, jint) { return 0; }
static jobject PopFrame(JNIEnv *, jobject o) { return o; }

int main(int argc, char **argv) {
    if (argc > 1 && !strcmp(argv[1], "ctrtool")) { char *env[] = {nullptr}; return ctrtool_main(argc - 1, argv + 1, env); }
    if (argc > 1 && !strcmp(argv[1], "makerom")) return makerom_main(argc - 1, argv + 1);
    if (argc != 6 || strcmp(argv[1], "convert")) {
        fprintf(stderr, "usage: %s convert <in.cia> <out.cci> <seeddb.bin> <tmpdir> | ctrtool ... | makerom ...\n", argv[0]);
        return 99;
    }
    static JNINativeInterface_ fn;
    memset(&fn, 0, sizeof fn);
    fn.GetStringUTFChars = GetUtf;
    fn.ReleaseStringUTFChars = ReleaseUtf;
    fn.PushLocalFrame = PushFrame;
    fn.PopLocalFrame = PopFrame;
    JNIEnv env;
    env.functions = &fn;
    int in = open(argv[2], O_RDONLY), out = open(argv[3], O_RDWR | O_CREAT | O_TRUNC, 0644);
    if (in < 0 || out < 0) { perror("open"); return 98; }
    int rc = Java_com_retrotools_cciextractor_NativeCia_nativeDecryptCia(&env, nullptr, in, out,
        (jstring)argv[4], (jstring)argv[5], (jstring)argv[2], JNI_TRUE, nullptr, nullptr, nullptr);
    close(in);
    close(out);
    fprintf(stderr, "ENGINE_RC=%d\n", rc);
    return rc;
}
