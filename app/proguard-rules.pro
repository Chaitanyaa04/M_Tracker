# Room, WorkManager, and Compose ship their own consumer ProGuard rules.
# Keep only classes referenced by name in AndroidManifest.xml or XML resources;
# Android Gradle Plugin / R8 automatically keeps manifest components.
-repackageclasses 'o'
-allowaccessmodification
