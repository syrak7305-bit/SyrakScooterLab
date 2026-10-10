# Syrak ScooterLab — R8/ProGuard rules
# Keep BLE + protocol model classes (reflection-free, but defensive for future serialization)
-keep class com.syrak.scooterlab.core.protocol.** { *; }
-keep class com.syrak.scooterlab.core.ble.** { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
