-keepattributes LineNumberTable,SourceFile
-keepnames class me.aap.** { *; }
-keep class me.aap.fermata.auto.** { *; }
-keep class org.videolan.libvlc.** { *; }
-keep class me.aap.fermata.vfs.sftp.** { *; }
-keep class me.aap.fermata.vfs.smb.** { *; }
-keep class me.aap.fermata.vfs.gdrive.** { *; }
-keep class androidx.car.app.** { *; }
# Parked-only video: the car sensors API is loaded by reflection, see DrivingMonitor
-keep class android.support.car.** { *; }
-keep class com.google.android.apps.auto.sdk.service.** { *; }
-keep class org.chromium.net.impl.NativeCronetEngineBuilderImpl { *; }

-dontwarn com.sun.jna.platform.win32.**
-dontwarn com.jcraft.jsch.PageantConnector
-dontwarn okio.*

-keepnames class androidx.media3.exoplayer.ExoPlayerImpl { *; }
-keepnames class androidx.media3.exoplayer.ExoPlayerImplInternal { *; }
