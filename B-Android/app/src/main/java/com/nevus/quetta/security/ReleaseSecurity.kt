package com.nevus.quetta.security

data class SigningMaterial(
    val store: String,
    val storePassword: String,
    val alias: String,
    val keyPassword: String,
)

object ReleaseSecurity {
    fun requireReleaseSigning(env: Map<String, String?>): SigningMaterial {
        fun value(name: String): String = env[name]?.takeIf(String::isNotBlank)
            ?: error("Missing release signing variable: $name")

        return SigningMaterial(
            store = value("NEVUS_RELEASE_STORE"),
            storePassword = value("NEVUS_RELEASE_STORE_PASSWORD"),
            alias = value("NEVUS_RELEASE_ALIAS"),
            keyPassword = value("NEVUS_RELEASE_KEY_PASSWORD"),
        )
    }
}
