# App-specific R8 rules. OkHttp and Conscrypt ship their own rules inside their artifacts.

# Conscrypt's adapters for the platform's own TLS sockets reference classes that exist only on the device:
# com.android.org.conscrypt on KitKat, org.apache.harmony on older releases. They are not in the SDK, so R8 cannot see them.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Markwon's image module has optional SVG and GIF decoders for libraries Libreta does not include; Markwon only
# uses them when those libraries are present.
-dontwarn com.caverock.androidsvg.**
-dontwarn pl.droidsonroids.gif.**
