/* Profile-guided optimisation, collecting builds only (EU_PGO=generate): the instrumented runtime and SPU code
 * count what runs, and this writes the counts every 30 s to eu_profile.profraw in the game folder (next to the
 * frame captures), so a run that is stopped from the app still leaves a profile. The final build is then
 * compiled with it (EU_PGO=<merged .profdata>). */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

int __llvm_profile_write_file(void);
void __llvm_profile_set_filename(const char*);

static char g_path[1024];

static void* pgo_writer(void* arg)
{
    (void)arg;
    for (int n = 1;; n++) {
        sleep(30);
        const int r = __llvm_profile_write_file();
        struct stat st;
        const long size = stat(g_path, &st) == 0 ? (long)st.st_size : -1;
        fprintf(stderr, "[pgo] profile written (%d s of play): %s, %ld bytes%s\n", n * 30, g_path, size,
                r ? " -- WRITE FAILED" : "");
    }
    return NULL;
}

__attribute__((constructor)) static void pgo_start(void)
{
    const char* root = getenv("PS3_VFS_ROOT");
    snprintf(g_path, sizeof g_path, "%s/eu_profile.profraw", root && *root ? root : ".");
    __llvm_profile_set_filename(g_path);
    fprintf(stderr, "[pgo] PROFILING BUILD: slower than normal; the profile goes to %s every 30 s\n", g_path);
    pthread_t t;
    if (pthread_create(&t, NULL, pgo_writer, NULL) == 0) pthread_detach(t);
}
