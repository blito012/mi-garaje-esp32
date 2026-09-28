package ec.pablo.migaraje

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.util.Base64
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * HTTPS compartido por la pantalla principal y el widget.
 * Una conexion verificada por operacion: GET del token y POST del pulso.
 * Requiere res/raw/garaje_certificado.pem (solo el certificado publico).
 * Todas las funciones de red deben llamarse desde un hilo de trabajo.
 * No guarda contrasenas ni repite ordenes automaticamente.
 */
object ControlGaraje {
    enum class Estado { LISTO, ENVIADO, ESPERA, CONFIGURAR, SIN_CONEXION, NO_CONFIRMADO }
    data class Resultado(val estado: Estado, val mensaje: String)

    private data class Respuesta(
        val codigo: Int,
        val cabeceras: Map<String, String>,
        val pagina: String
    )
    private data class SeguridadTLS(
        val fabrica: SSLSocketFactory,
        val certificado: X509Certificate
    )

    private class ConexionSegura(val socket: SSLSocket) {
        // Conservar un unico lector durante ambas respuestas.
        val entrada: InputStream = socket.getInputStream().buffered()
    }

    // Conectamos a la IP configurada, pero verificamos el nombre del certificado.
    // No hace falta que el router resuelva garaje.local.
    private const val NOMBRE_SERVIDOR = "garaje.local"
    private const val PUERTO = 443
    private const val LIMITE_MS = 6500L
    private const val PAUSA_MS = 2000L
    private val bloqueo = Any()
    private var enviando = false
    private var esperarHasta = 0L
    private val temporizador = Executors.newSingleThreadScheduledExecutor { tarea ->
        Thread(tarea, "GarajeTimeout").apply { isDaemon = true }
    }

    /** El widget utiliza las credenciales cifradas que ya estaban guardadas. */
    fun activar(contexto: Context): Resultado = ejecutar(contexto, null, true)

    /** Disponible para conectar la pantalla principal en el siguiente paso. */
    fun activar(contexto: Context, datos: CredencialesSeguras.Datos): Resultado =
        ejecutar(contexto, datos, true)

    /** Solo consulta la pagina protegida. Nunca envia POST ni activa el rele. */
    fun probarConexion(contexto: Context, datos: CredencialesSeguras.Datos): Resultado =
        ejecutar(contexto, datos, false)

    private fun ejecutar(
        contexto: Context,
        datosIndicados: CredencialesSeguras.Datos?,
        activarPuerta: Boolean
    ): Resultado {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "ControlGaraje debe ejecutarse en un hilo de trabajo."
        }
        synchronized(bloqueo) {
            if (enviando || (activarPuerta && SystemClock.elapsedRealtime() < esperarHasta)) {
                return Resultado(Estado.ESPERA, "Espera un momento antes de volver a pulsar.")
            }
            enviando = true
        }
        var pudoEnviarOrden = false
        val limite = SystemClock.elapsedRealtime() + LIMITE_MS
        try {
            val contextoApp = contexto.applicationContext
            val datos = datosIndicados ?: try {
                CredencialesSeguras(contextoApp).cargar()
            } catch (_: Exception) {
                return Resultado(Estado.CONFIGURAR, "Abre Mi Garaje y revisa el acceso guardado.")
            } ?: return Resultado(Estado.CONFIGURAR, "Primero configura el acceso en Mi Garaje.")
            validarDatos(datos)

            if (Build.VERSION.SDK_INT >= 37 && contextoApp.checkSelfPermission(
                    "android.permission.ACCESS_LOCAL_NETWORK"
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return Resultado(Estado.CONFIGURAR, "Abre Mi Garaje y permite el acceso a la red local.")
            }
            val gestor = contextoApp.getSystemService(ConnectivityManager::class.java)
            val red = gestor.allNetworks.firstOrNull { candidata ->
                gestor.getNetworkCapabilities(candidata)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return Resultado(Estado.SIN_CONEXION, "Conecta el telefono al Wi-Fi de casa.")

            val seguridad = cargarSeguridad(contextoApp)
            // Basic SOLO dentro de TLS, despues de verificar certificado e identidad.
            val acceso = crearAutorizacion(datos)
            return conConexion(red, datos.ip, seguridad, limite) { conexion ->
                val pagina = solicitar(conexion, "GET", "/", acceso, null, limite, activarPuerta)
                if (pagina.codigo == 429) return@conConexion informarBloqueo(pagina)
                if (pagina.codigo == 401 || pagina.codigo == 403) {
                    return@conConexion Resultado(Estado.CONFIGURAR, "Usuario o clave rechazados. Revisa el acceso en Mi Garaje.")
                }
                if (pagina.codigo != 200) {
                    return@conConexion Resultado(Estado.SIN_CONEXION, "El control respondio con codigo ${pagina.codigo}. No se envio la orden.")
                }
                val token = Regex("""name=["']token["']\s+value=["']([a-fA-F0-9]{32})["']""")
                    .find(pagina.pagina)?.groupValues?.get(1)
                    ?: return@conConexion Resultado(Estado.CONFIGURAR, "No se reconoce la pagina del control. Revisa el programa del ESP32.")

                if (!activarPuerta) return@conConexion Resultado(Estado.LISTO, "Conexion HTTPS verificada. Puedes activar la puerta.")

                if (!pagina.cabeceras["connection"].equals("keep-alive", true)) {
                    return@conConexion Resultado(Estado.CONFIGURAR,
                        "Actualiza el programa del ESP32 para usar la conexion rapida. No se envio la orden.")
                }
                val respuesta = solicitar(
                    conexion, "POST", "/pulsar", acceso,
                    "token=$token".toByteArray(Charsets.UTF_8), limite, false
                ) {
                    // Reservar tiempo antes de enviar el unico POST de esta operacion.
                    if (restante(limite) < 1500) throw SocketTimeoutException()
                    pudoEnviarOrden = true
                }
                return@conConexion when {
                    respuesta.codigo == 303 && respuesta.cabeceras["location"] == "/?resultado=enviado" ->
                        Resultado(Estado.ENVIADO, "Pulso enviado.")
                    respuesta.codigo == 303 && respuesta.cabeceras["location"] == "/?resultado=espera" ->
                        Resultado(Estado.ESPERA, "Espera un momento. No se envio otro pulso.")
                    respuesta.codigo == 429 -> informarBloqueo(respuesta)
                    respuesta.codigo == 401 ->
                        Resultado(Estado.CONFIGURAR, "Orden rechazada. Revisa el usuario y la clave.")
                    respuesta.codigo == 403 ->
                        Resultado(Estado.ESPERA, "Orden rechazada: acceso o formulario no valido. No se repetira automaticamente.")
                    else -> Resultado(Estado.NO_CONFIRMADO, "No se pudo confirmar. Comprueba la puerta antes de volver a pulsar.")
                }
            }
        } catch (error: Exception) {
            return when {
                pudoEnviarOrden -> Resultado(Estado.NO_CONFIRMADO,
                    "No se pudo confirmar. Comprueba la puerta. La orden no se repetira automaticamente.")
                error is SSLException || error is CertificateException -> Resultado(Estado.CONFIGURAR,
                    "No se pudo verificar la conexion segura. Revisa el certificado y la fecha del telefono. No se envio la orden.")
                error is SecurityException -> Resultado(Estado.CONFIGURAR,
                    "Abre Mi Garaje y revisa los permisos de red.")
                error is IllegalStateException -> Resultado(Estado.CONFIGURAR,
                    error.message ?: "Revisa la configuracion en Mi Garaje.")
                else -> Resultado(Estado.SIN_CONEXION,
                    "No se envio la orden. Revisa Wi-Fi, IP y ESP32.")
            }
        } finally {
            synchronized(bloqueo) {
                if (pudoEnviarOrden) {
                    esperarHasta = maxOf(esperarHasta, SystemClock.elapsedRealtime() + PAUSA_MS)
                }
                enviando = false
            }
        }
    }

    private fun cargarSeguridad(contexto: Context): SeguridadTLS {
        val certificado = contexto.resources.openRawResource(R.raw.garaje_certificado).use {
            CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
        }
        // Los certificados usados como ancla no siempre comprueban su propia fecha.
        certificado.checkValidity()
        val almacen = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("garaje", certificado)
        }
        val confianza = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(almacen)
        }
        val tls = SSLContext.getInstance("TLS").apply {
            init(null, confianza.trustManagers, null)
        }
        return SeguridadTLS(tls.socketFactory, certificado)
    }

    private fun crearAutorizacion(datos: CredencialesSeguras.Datos): String {
        val bytes = "${datos.usuario}:${datos.clave}".toByteArray(Charsets.UTF_8)
        return try {
            "Basic " + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } finally {
            bytes.fill(0)
        }
    }

    /** Una negociacion TLS por operacion completa, sin reintentos. */
    private fun conConexion(
        red: Network,
        ip: String,
        seguridad: SeguridadTLS,
        limite: Long,
        accion: (ConexionSegura) -> Resultado
    ): Resultado {
        red.socketFactory.createSocket().use { transporte ->
            val cierre = temporizador.schedule(
                Runnable { try { transporte.close() } catch (_: IOException) {} },
                restante(limite).toLong(), TimeUnit.MILLISECONDS
            )
            try {
                transporte.connect(InetSocketAddress(ip, PUERTO), restante(limite))
                transporte.soTimeout = restante(limite)
                transporte.tcpNoDelay = true
                val seguro = seguridad.fabrica.createSocket(
                    transporte, NOMBRE_SERVIDOR, PUERTO, true
                ) as SSLSocket
                seguro.use { socket ->
                    socket.soTimeout = restante(limite)
                    socket.enabledProtocols = socket.supportedProtocols.filter {
                        it == "TLSv1.2" || it == "TLSv1.3"
                    }.toTypedArray()
                    socket.sslParameters = socket.sslParameters.apply {
                        endpointIdentificationAlgorithm = "HTTPS"
                    }
                    // Primero verificar TLS; solo despues enviar las credenciales.
                    socket.startHandshake()
                    val recibido = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                        ?: throw SSLHandshakeException("Falta el certificado del ESP32.")
                    recibido.checkValidity()
                    if (!MessageDigest.isEqual(recibido.encoded, seguridad.certificado.encoded)) {
                        throw SSLHandshakeException("El certificado no coincide con el del garaje.")
                    }
                    restante(limite)
                    socket.soTimeout = restante(limite)
                    return accion(ConexionSegura(socket))
                }
            } finally {
                cierre.cancel(false)
            }
        }
    }

    private fun solicitar(
        conexion: ConexionSegura,
        metodo: String,
        ruta: String,
        acceso: String,
        cuerpo: ByteArray?,
        limite: Long,
        mantenerConexion: Boolean,
        antesDeEnviar: () -> Unit = {}
    ): Respuesta {
        val cabeceras = buildString {
            append("$metodo $ruta HTTP/1.1\r\n")
            append("Host: $NOMBRE_SERVIDOR\r\n")
            append("Connection: ${if (mantenerConexion) "keep-alive" else "close"}\r\n")
            append("Authorization: $acceso\r\n")
            if (cuerpo != null) {
                append("Content-Type: application/x-www-form-urlencoded\r\n")
                append("Content-Length: ${cuerpo.size}\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.UTF_8)
        // Enviar cabeceras y cuerpo juntos evita dos escrituras TLS pequenas.
        val peticion = cabeceras + (cuerpo ?: ByteArray(0))
        cabeceras.fill(0)
        try {
            conexion.socket.soTimeout = restante(limite)
            val salida = conexion.socket.getOutputStream()
            antesDeEnviar()
            salida.write(peticion)
            salida.flush()
            return leerRespuesta(conexion, limite)
        } finally {
            peticion.fill(0)
        }
    }

    private fun leerRespuesta(conexion: ConexionSegura, limite: Long): Respuesta {
        val socket = conexion.socket
        val entrada = conexion.entrada
        val primera = leerLinea(entrada, limite)
        val codigo = Regex("""HTTP/1\.[01] (\d{3})(?: .*|)""")
            .matchEntire(primera)?.groupValues?.get(1)?.toIntOrNull()
            ?: throw IOException("Respuesta HTTP no reconocida.")
        val campos = mutableMapOf<String, String>()
        var total = primera.length + 2
        while (true) {
            val linea = leerLinea(entrada, limite)
            total += linea.length + 2
            if (total > 16384) throw IOException("Cabeceras demasiado grandes.")
            if (linea.isEmpty()) break
            val separador = linea.indexOf(':')
            if (separador <= 0) throw IOException("Cabecera no valida.")
            val nombre = linea.substring(0, separador).lowercase(Locale.ROOT)
            val valor = linea.substring(separador + 1).trim()
            if (!Regex("[a-z0-9!#$%&'*+.^_`|~-]+").matches(nombre)) {
                throw IOException("Nombre de cabecera no valido.")
            }
            if (nombre in campos) throw IOException("Cabecera duplicada.")
            campos[nombre] = valor
        }
        // ESP-IDF envia Content-Length. No admitir respuestas ambiguas o truncadas.
        if (campos.containsKey("transfer-encoding") ||
            campos["content-encoding"]?.let { !it.equals("identity", true) } == true
        ) throw IOException("Formato de respuesta no compatible.")
        val longitud = campos["content-length"]?.toIntOrNull()
            ?: throw IOException("Falta el tamano de la respuesta.")
        if (longitud !in 0..65536) throw IOException("Tamano de respuesta no valido.")
        val contenido = ByteArray(longitud)
        var leido = 0
        while (leido < longitud) {
            socket.soTimeout = restante(limite)
            val cantidad = entrada.read(contenido, leido, longitud - leido)
            if (cantidad <= 0) throw IOException("Respuesta incompleta.")
            leido += cantidad
        }
        restante(limite)
        return Respuesta(codigo, campos, String(contenido, Charsets.UTF_8))
    }

    private fun informarBloqueo(respuesta: Respuesta): Resultado {
        val segundos = (respuesta.cabeceras["retry-after"]?.toLongOrNull() ?: 60L)
            .coerceIn(1L, 300L)
        synchronized(bloqueo) {
            esperarHasta = maxOf(esperarHasta, SystemClock.elapsedRealtime() + segundos * 1000L)
        }
        return Resultado(Estado.ESPERA, "Acceso temporalmente bloqueado. Espera $segundos segundos. No se envio el pulso.")
    }

    private fun restante(limite: Long): Int {
        val tiempo = limite - SystemClock.elapsedRealtime()
        if (tiempo <= 0) throw SocketTimeoutException()
        return tiempo.coerceAtMost(LIMITE_MS).toInt()
    }

    private fun leerLinea(entrada: InputStream, limite: Long): String {
        val linea = StringBuilder()
        while (linea.length < 4096) {
            restante(limite)
            val valor = entrada.read()
            if (valor < 0) throw IOException("Conexion cerrada antes de recibir la respuesta.")
            if (valor == 10) {
                if (!linea.endsWith("\r")) throw IOException("Fin de linea HTTP no valido.")
                return linea.dropLast(1).toString()
            }
            linea.append(valor.toChar())
        }
        throw IOException("Linea HTTP demasiado larga.")
    }

    private fun validarDatos(datos: CredencialesSeguras.Datos) {
        val partes = datos.ip.split('.')
        val numeros = partes.mapNotNull { it.toIntOrNull() }
        check(partes.size == 4 && numeros.size == 4 && numeros.all { it in 0..255 } &&
                numeros.joinToString(".") == datos.ip
        ) { "Revisa la direccion IP en Mi Garaje." }
        check(numeros[0] == 10 || (numeros[0] == 172 && numeros[1] in 16..31) ||
                (numeros[0] == 192 && numeros[1] == 168)
        ) { "Usa la direccion IP local del ESP32 en Mi Garaje." }
        check(datos.usuario.isNotBlank() && datos.usuario.toByteArray(Charsets.UTF_8).size <= 64 &&
                datos.usuario.none { it == ':' || it == '\r' || it == '\n' } &&
                datos.clave.toByteArray(Charsets.UTF_8).size in 20..256
        ) { "Revisa el usuario y la clave del control en Mi Garaje." }
    }
}
