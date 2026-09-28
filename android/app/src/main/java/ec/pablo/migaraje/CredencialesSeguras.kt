package ec.pablo.migaraje

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Almacenamiento local cifrado. No conecta con la ESP32 ni acciona el relé.
 * Llamar a guardar, cargar y borrar desde un hilo de trabajo, no desde la interfaz.
 */
class CredencialesSeguras(contexto: Context) {

    // Clase normal para evitar que toString() muestre automáticamente la contraseña.
    class Datos(val ip: String, val usuario: String, val clave: String)

    private val archivo = AtomicFile(
        File(contexto.applicationContext.noBackupFilesDir, "garaje_credenciales_v1.json")
    )

    companion object {
        private const val ALIAS = "mi_garaje_credenciales_aes_v1"
        private const val PROVEEDOR = "AndroidKeyStore"
        private const val TRANSFORMACION = "AES/GCM/NoPadding"
        private const val MAX_ARCHIVO = 16384L
        private val bloqueo = Any()
        private val contextoCifrado = "ec.pablo.migaraje:credenciales:v1"
            .toByteArray(Charsets.UTF_8)
    }

    /** Guardar únicamente después de que la ESP32 acepte las credenciales. */
    fun guardar(datos: Datos) = synchronized(bloqueo) {
        validar(datos)
        val texto = JSONObject()
            .put("ip", datos.ip)
            .put("usuario", datos.usuario)
            .put("clave", datos.clave)
            .toString().toByteArray(Charsets.UTF_8)

        try {
            val cifrador = Cipher.getInstance(TRANSFORMACION)
            // Android genera un IV nuevo en cada guardado: nunca se reutiliza.
            cifrador.init(Cipher.ENCRYPT_MODE, obtenerClave(crear = true))
            cifrador.updateAAD(contextoCifrado)
            val cifrado = cifrador.doFinal(texto)
            val contenido = JSONObject()
                .put("version", 1)
                .put("iv", Base64.encodeToString(cifrador.iv, Base64.NO_WRAP))
                .put("cifrado", Base64.encodeToString(cifrado, Base64.NO_WRAP))
                .toString().toByteArray(Charsets.UTF_8)
            check(contenido.size <= MAX_ARCHIVO) { "Los datos son demasiado grandes." }

            // Escritura atómica para conservar el archivo anterior si falla el guardado.
            val salida = archivo.startWrite()
            try {
                salida.write(contenido)
                archivo.finishWrite(salida)
            } catch (e: Exception) {
                archivo.failWrite(salida)
                throw e
            }
        } finally {
            texto.fill(0)
        }
    }

    /** Devuelve null solo si no hay datos. Un fallo de lectura se comunica como excepción. */
    fun cargar(): Datos? = synchronized(bloqueo) {
        if (!archivo.baseFile.exists()) return@synchronized null
        if (archivo.baseFile.length() > MAX_ARCHIVO) {
            throw IOException("El archivo de credenciales no es válido.")
        }
        val contenido = JSONObject(String(archivo.readFully(), Charsets.UTF_8))
        check(contenido.getInt("version") == 1) { "Versión de credenciales no compatible." }
        val iv = Base64.decode(contenido.getString("iv"), Base64.NO_WRAP)
        val cifrado = Base64.decode(contenido.getString("cifrado"), Base64.NO_WRAP)
        check(iv.size == 12 && cifrado.size >= 16) { "Formato de credenciales no válido." }

        val descifrador = Cipher.getInstance(TRANSFORMACION)
        descifrador.init(
            Cipher.DECRYPT_MODE, obtenerClave(crear = false), GCMParameterSpec(128, iv)
        )
        descifrador.updateAAD(contextoCifrado)
        val texto = descifrador.doFinal(cifrado)
        try {
            val datos = JSONObject(String(texto, Charsets.UTF_8))
            Datos(datos.getString("ip"), datos.getString("usuario"), datos.getString("clave"))
                .also { validar(it) }
        } finally {
            texto.fill(0)
        }
    }

    /** Permite olvidar la conexión guardada sin enviar ninguna orden al garaje. */
    fun borrar() = synchronized(bloqueo) {
        archivo.delete()
        if (archivo.baseFile.exists()) {
            throw IOException("No se pudieron borrar las credenciales.")
        }
    }

    private fun validar(datos: Datos) {
        require(datos.ip.isNotBlank() && datos.ip.length <= 64) { "IP no válida." }
        require(datos.usuario.isNotBlank() && datos.usuario.length <= 256) { "Usuario no válido." }
        require(datos.clave.isNotEmpty() && datos.clave.length <= 2048) { "Clave no válida." }
    }

    private fun obtenerClave(crear: Boolean): SecretKey {
        val almacen = KeyStore.getInstance(PROVEEDOR).apply { load(null) }
        if (almacen.containsAlias(ALIAS)) {
            return almacen.getKey(ALIAS, null) as? SecretKey
                ?: error("No se puede utilizar la clave de cifrado del teléfono.")
        }
        check(crear) { "No está disponible la clave de cifrado. Configura el acceso de nuevo." }
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVEEDOR)
        generador.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return generador.generateKey()
    }
}
