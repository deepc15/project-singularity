# The privileged bridge reaches framework internals by reflection, and the
# user service is instantiated by Shizuku by class name — neither is visible to
# the shrinker.
-keep class com.singular.cast.priv.** { *; }
-keep class com.singular.cast.cast.SingularUserService { *; }

# Shizuku's provider and API are entered from outside the app.
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Accessibility services are named in XML, not referenced from code.
-keep class com.singular.cast.ime.SingularAccessibilityService { *; }
