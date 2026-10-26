package ireader.core.http

/**
 * MANHWA: TLS is the app's network client's own (the system's trusted certificates); this keeps the extensions'
 * calls valid without loosening it. Allowing self-signed certificates is not supported and does nothing.
 */
class SSLConfiguration() {
    fun enableCertificatePinning(pins: Map<String, List<String>>) = Unit
    fun allowSelfSignedCertificates() = Unit
    fun setMinimumTlsVersion(version: TlsVersion) = Unit
}

/**
 * TLS version enum
 */
enum class TlsVersion {
    TLS_1_0,
    TLS_1_1,
    TLS_1_2,
    TLS_1_3,
}
