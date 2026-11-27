# AppOpsShizuku R8/ProGuard-Regeln.
#
# R8 ist standardmaessig AUS (app/build.gradle.kts): wer ihn probieren will, baut mit  -Pminify=true  und
# testet das Release-APK auf einem Geraet (Tarnung/Patch, Shizuku, Signieren, Dex-Patch).
# Es wird nur verkleinert, nicht umbenannt: lesbare Stacktraces (Fehlerprotokoll) und weniger Reflection-Risiko.
-dontobfuscate

# Shizuku-API (Binder/AIDL, Provider) nicht strippen
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Stealth-Modus: Bibliotheken, die Klassen/Provider ueber Namen laden
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.jf.** { *; }
-keep class com.android.apksig.** { *; }
-dontwarn org.antlr.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.errorprone.annotations.**
