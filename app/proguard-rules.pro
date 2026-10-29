-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
