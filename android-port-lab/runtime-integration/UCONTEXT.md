# Android ARM64 cellFiber compatibility

Android Bionic does not export POSIX getcontext/makecontext/swapcontext/setcontext.
Instead of fake success returns, this port links the open-source
`kaniini/libucontext` ARM64 assembly implementation at pinned commit
`49e671dd52ff6791295d8161ad3b6da7dc5f6f9d`.

The `cellFiber.c` Android branch uses `libucontext_ucontext_t` and its
actual context switching functions; this does not alter macOS, Linux or Windows
source. libucontext is distributed under the permissive ISC license:
https://github.com/kaniini/libucontext/blob/master/LICENSE

First success criterion is linking the complete PS3 runtime and boot scaffold
for Android ARM64 in GitHub Actions. Actual on-device context switching and
correct guest switching semantics are **not yet validated**. Do not treat a
compiled binary as a working inFAMOUS game.
