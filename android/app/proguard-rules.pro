-keepattributes *Annotation*
-keepattributes InnerClasses

-keepclasseswithmembers class dev.peytonli.tapdrop.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class dev.peytonli.tapdrop.**$$serializer {
    *;
}
-keepclassmembers class dev.peytonli.tapdrop.** {
    *** Companion;
}
-keepclasseswithmembers class dev.peytonli.tapdrop.**$Companion {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class kotlinx.serialization.**$$serializer { *; }
-keepclassmembers class kotlinx.serialization.json.internal.** { *; }

-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
-dontwarn org.bouncycastle.jsse.**

-keep class org.bouncycastle.crypto.engines.AESEngine { *; }
-keep class org.bouncycastle.crypto.modes.GCMBlockCipher { *; }
-keep class org.bouncycastle.crypto.modes.gcm.** { *; }
-keep class org.bouncycastle.crypto.params.AEADParameters { *; }
-keep class org.bouncycastle.crypto.params.KeyParameter { *; }
-dontwarn org.bouncycastle.**
