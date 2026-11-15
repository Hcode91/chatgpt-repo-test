# SeXoniX Native Android - gameplay prototype

A standalone Android Java/Canvas recreation of territory-capture gameplay,
built with Gradle and GitHub Actions (no J2ME Loader at runtime). This is an
**early gameplay prototype**, not an exact port or a claim that all mechanics
match the original.

## First milestone
- Native Android APK, Java 17, Android API 26+.
- Offline JAR picker, reading seven original `1.dat` through `7.dat` stages.
- Decoder for modified PNG data: 128x128 indexed images, two palette chunks,
  common IDAT stream. PNG CRCs validated.
- Images stored only in private app storage. The original JAR, classes, music,
  and artwork are **not included in the public GitHub repository or APK**.
- Native capture/trail gameplay simulation, enemies, lives, score, 75%
  territory target, and 7-stage progress cycle.
- Scaled portrait screen and virtual touch direction buttons;
  keyboard and gamepad D-pad support.
- No INTERNET permission, no embedded paid activation, no network service.

## Try it
1. Install `sexonix-native-android-preview-apk` from GitHub Actions.
2. Open the app, select **Import JAR** and choose your personally owned
   original `SeXoniX_J2ME_EN_v1104.jar`.
3. The app locally decodes and keeps only the stage images.
4. Tap **TAP TO START**; use the on-screen arrows to draw from the border
   into the playfield, then rejoin captured territory.
5. At 75% capture, tap or press Fire to move to the next stage.

## Known limits
- Gameplay mechanics and enemy movement are **approximate**, not yet
  reverse-engineered function-for-function.
- Captured-area calculation, game HUD, sounds, menus, saves and progression
  must still be validated against the 128x160 J2ME original.
- Level images imported from the original JAR are used. Music/extra graphics
  and the original portrait background have not been ported.
- Hold a directional button to draw. Touch input and collision rules are
  preliminary.
- Import errors do not upload any data.
- This is for personal research and compatibility testing. Public distribution
  should not ship copyrighted game assets without permission.

## Build
All APK builds run **only on GitHub Actions** using the
`.github/workflows/sexonix-native-android.yml` workflow. No local APK build.
