# Consenzy Android SDK - consumer R8/ProGuard rules for :ui (applied automatically to apps).
# :core ships its own rules inside its jar (META-INF/proguard/consenzy-android.pro).

# ViewModel is created reflectively only through the factory we provide; keep its constructor
# in case apps use the default ViewModelProvider factory.
-keepclassmembers class in.onfinance.consent.ui.ConsentViewModel {
    public <init>(...);
}
