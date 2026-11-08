# CCI Extractor for Android
This branch contains the CCI Extractor Android project packaged as a tar.xz to avoid impacting existing root project files.

The Android source archive is at `.build/cci-app-src.tar.xz`. The GitHub Actions workflow unpacks it and builds an installable debug APK.

The parser was verified locally against the decrypted Yumi's Odd Odyssey CCI (961 files, TitleLogo.btex verified).
