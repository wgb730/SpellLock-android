# 本项目未开启代码混淆（release 里 isMinifyEnabled = false）。
# 如果以后开启，需要保留无障碍服务与 Activity 的类名，避免被系统按名字找不到。
-keep class com.detox.spelllock.AppAccessibilityService { *; }
-keep class com.detox.spelllock.SpellCheckActivity { *; }
-keep class com.detox.spelllock.MainActivity { *; }
