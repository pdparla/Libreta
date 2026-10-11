# App-specific R8 rules. OkHttp and Conscrypt ship their own rules inside their artifacts.

# Conscrypt's adapters for the platform's own TLS sockets reference classes that exist only on the device:
# com.android.org.conscrypt on KitKat, org.apache.harmony on older releases. They are not in the SDK, so R8 cannot see them.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
