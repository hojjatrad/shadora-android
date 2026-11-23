# Keep readable stack traces and preserve all JNI / reflection entry points
-dontobfuscate
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*

# Keep all SHADORA application classes, Compose screens, ViewModels, Room entities/DAOs, and VPN bridges
-keep class com.example.** { *; }

# Keep GoMobile / sing-box (libbox.so) JNI bridge classes and callbacks
-keep class io.nekohasekai.libbox.** { *; }
-keep class go.** { *; }

# Keep AmneziaWG / WireGuard (libam-go.so) JNI bridge classes and tunnel backend
-keep class org.amnezia.awg.** { *; }

# Keep OpenVPN 3 (libovpn3.so) SWIG JNI bindings
-keep class net.openvpn.ovpn3.** { *; }

# Keep optional pluggable native cores (Xray libv2ray.aar / Lightway SDK)
-keep class libv2ray.** { *; }
-keep class com.expressvpn.lightway.** { *; }

# Keep AndroidX Room generated implementations
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}

# Suppress harmless optional-dependency warnings from OkHttp / third-party VPN AARs
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.android.material.**
-dontwarn org.koin.**
-dontwarn androidx.constraintlayout.**
-dontwarn com.tencent.**
-dontwarn com.tim.**
-dontwarn io.nekohasekai.libbox.**
-dontwarn org.amnezia.awg.**
-dontwarn net.openvpn.ovpn3.**
-dontwarn kotlin.annotations.**
-dontwarn javax.annotation.**
