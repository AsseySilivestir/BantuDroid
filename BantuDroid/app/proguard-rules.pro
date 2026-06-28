# Add project specific ProGuard rules here.

# Keep BantuEngine methods (called by reflection / JNI)
-keep class com.bantu.droid.BantuEngine { *; }
-keep class com.bantu.droid.BantuProcess { *; }

# Keep serializable models
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
