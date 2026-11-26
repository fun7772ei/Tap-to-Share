# Keep Android components referenced in AndroidManifest.xml (handled by default AAPT rules)
# Keep custom View constructors used via reflection if inflated from XML
-keepclasseswithmembers class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
