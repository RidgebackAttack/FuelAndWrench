# Eigener App-Code bleibt vollständig erhalten (Serialisierung, Room, WorkManager, Reflexion) –
# R8 verkleinert vor allem die großen Bibliotheken (Compose, Icons).
-keep class de.spritbuch.app.** { *; }

# Texterkennung: der native Code greift per JNI über Namen auf Java-Klassen und Felder zu
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Karte (osmdroid) nutzt teils Reflexion
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# Lesbare Stacktraces
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
-renamesourcefileattribute SourceFile
