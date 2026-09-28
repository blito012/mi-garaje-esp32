package ec.pablo.migaraje

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import java.util.concurrent.atomic.AtomicBoolean

/**
 * La pantalla y el widget comparten ControlGaraje: HTTPS, certificado y bloqueo.
 * Abrir la app, recuperar datos o conceder permisos nunca activa la puerta.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var campoIp: EditText
    private lateinit var campoUsuario: EditText
    private lateinit var campoClave: EditText
    private lateinit var botonProbar: Button
    private lateinit var botonPuerta: Button
    private lateinit var estado: TextView
    private var accesoComprobado = false
    private var ocupada = false
    private val almacen by lazy { CredencialesSeguras(applicationContext) }

    companion object {
        // Bloquea otras instancias de esta pantalla durante una operacion.
        // ControlGaraje comparte ademas su propio bloqueo con el widget.
        private val operacionEnCurso = AtomicBoolean(false)
    }

    private val permisoLocal = "android.permission.ACCESS_LOCAL_NETWORK"
    private val pedirPermiso = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (concedido) ejecutar(activar = false)
        else estado.text = "Permite el acceso a la red local para conectar con el ESP32."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val margenes = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(margenes.left, margenes.top, margenes.right, margenes.bottom)
            insets
        }
        campoIp = findViewById(R.id.campoIp)
        campoUsuario = findViewById(R.id.campoUsuario)
        campoClave = findViewById(R.id.campoClave)
        botonProbar = findViewById(R.id.botonProbar)
        botonPuerta = findViewById(R.id.botonPuerta)
        estado = findViewById(R.id.textoEstado)

        val antiguos = getSharedPreferences("conexion", MODE_PRIVATE)
        campoIp.setText(antiguos.getString("ip", ""))
        campoUsuario.setText(antiguos.getString("usuario", ""))
        botonPuerta.isEnabled = false
        listOf(campoIp, campoUsuario, campoClave).forEach { campo ->
            campo.doAfterTextChanged {
                accesoComprobado = false
                botonPuerta.isEnabled = false
                if (!ocupada) {
                    estado.text = "Datos modificados. Pulsa PROBAR CONEXIÓN para comprobarlos y guardarlos."
                }
            }
        }
        botonProbar.setOnClickListener {
            if (faltaPermisoLocal()) pedirPermiso.launch(permisoLocal)
            else ejecutar(activar = false)
        }
        botonPuerta.setOnClickListener {
            if (accesoComprobado && !ocupada) ejecutar(activar = true)
        }
        cargarDatosGuardados()
    }

    private fun faltaPermisoLocal(): Boolean =
        Build.VERSION.SDK_INT >= 37 &&
                checkSelfPermission(permisoLocal) != PackageManager.PERMISSION_GRANTED

    private fun cargarDatosGuardados() {
        ocupada = true
        accesoComprobado = false
        actualizarControles()
        estado.text = "Leyendo el acceso guardado…"
        try {
            Thread({
                var falloLectura = false
                val guardados = try {
                    almacen.cargar()
                } catch (_: Exception) {
                    falloLectura = true
                    null
                }
                runOnUiThread {
                    if (isDestroyed || isFinishing) return@runOnUiThread
                    if (guardados != null) {
                        campoIp.setText(guardados.ip)
                        campoUsuario.setText(guardados.usuario)
                        campoClave.setText(guardados.clave)
                    }
                    ocupada = false
                    actualizarControles()
                    when {
                        falloLectura -> estado.text =
                            "No se pudo recuperar el acceso guardado. Introduce los datos y pulsa PROBAR CONEXIÓN."
                        guardados == null -> estado.text =
                            "Introduce los datos y pulsa PROBAR CONEXIÓN. Se guardarán si son correctos."
                        faltaPermisoLocal() -> estado.text =
                            "Datos recuperados. Pulsa PROBAR CONEXIÓN para permitir el acceso a la red local."
                        else -> ejecutar(activar = false, guardarConexion = false)
                    }
                }
            }, "GarajeLeerAcceso").start()
        } catch (_: Exception) {
            ocupada = false
            estado.text = "No se pudo leer el acceso. Introduce los datos y pulsa PROBAR CONEXIÓN."
            actualizarControles()
        }
    }

    private fun leerDatos(): CredencialesSeguras.Datos {
        val ip = campoIp.text.toString().trim()
            .removePrefix("https://").removePrefix("http://").removeSuffix("/")
        val partes = ip.split('.')
        val numeros = partes.mapNotNull { it.toIntOrNull() }
        check(partes.size == 4 && numeros.size == 4 && numeros.all { it in 0..255 } &&
                numeros.joinToString(".") == ip) {
            "Revisa la IP. Por ejemplo: 192.168.1.100"
        }
        check(numeros[0] == 10 || (numeros[0] == 172 && numeros[1] in 16..31) ||
                (numeros[0] == 192 && numeros[1] == 168)) { "Utiliza la IP local del ESP32." }
        val usuario = campoUsuario.text.toString().trim()
        val clave = campoClave.text.toString()
        check(usuario.isNotBlank() && usuario.toByteArray(Charsets.UTF_8).size <= 64 &&
                usuario.none { it == ':' || it == '\r' || it == '\n' } &&
                clave.toByteArray(Charsets.UTF_8).size in 20..256) {
            "Revisa el usuario y la clave del control."
        }
        return CredencialesSeguras.Datos(ip, usuario, clave)
    }

    private fun actualizarControles() {
        botonProbar.isEnabled = !ocupada
        botonPuerta.isEnabled = !ocupada && accesoComprobado
        campoIp.isEnabled = !ocupada
        campoUsuario.isEnabled = !ocupada
        campoClave.isEnabled = !ocupada
        botonPuerta.text = if (ocupada) "ESPERA…" else "ACTIVAR PUERTA"
    }

    private fun ejecutar(activar: Boolean, guardarConexion: Boolean = true) {
        if (ocupada) return
        val datos = try {
            leerDatos()
        } catch (e: IllegalStateException) {
            accesoComprobado = false
            estado.text = e.message
            actualizarControles()
            return
        }
        if (faltaPermisoLocal()) {
            accesoComprobado = false
            estado.text = "Pulsa PROBAR CONEXIÓN y permite el acceso a la red local."
            actualizarControles()
            return
        }
        if (!operacionEnCurso.compareAndSet(false, true)) {
            estado.text = "Hay una operación en curso. Espera unos segundos y pulsa PROBAR CONEXIÓN."
            return
        }
        val accesoAnterior = accesoComprobado
        val inicio = SystemClock.elapsedRealtime()
        ocupada = true
        actualizarControles()
        estado.text = if (activar) "Enviando orden…" else "Comprobando conexión segura…"

        try {
            Thread({
                var llamadaDePulso = false
                var correcto = false
                var pulsoConfirmado = false
                val mensaje = try {
                    // No ejecutar un toque que estuvo esperando demasiado para arrancar.
                    if (isDestroyed || isFinishing || SystemClock.elapsedRealtime() - inicio > 1000L) {
                        "No se inició la operación. Vuelve a intentarlo."
                    } else {
                        val resultado = if (activar) {
                            llamadaDePulso = true
                            ControlGaraje.activar(applicationContext, datos)
                        } else {
                            ControlGaraje.probarConexion(applicationContext, datos)
                        }
                        correcto = when (resultado.estado) {
                            ControlGaraje.Estado.LISTO, ControlGaraje.Estado.ENVIADO -> true
                            ControlGaraje.Estado.ESPERA -> accesoAnterior
                            else -> false
                        }
                        pulsoConfirmado = resultado.estado == ControlGaraje.Estado.ENVIADO
                        if (!activar && resultado.estado == ControlGaraje.Estado.LISTO) {
                            // Guardar solo tras verificar TLS, credenciales y pagina protegida.
                            // Si falla la conexion, se conserva el acceso anterior guardado.
                            val guardado = if (guardarConexion) {
                                try {
                                    almacen.guardar(datos)
                                    getSharedPreferences("conexion", MODE_PRIVATE).edit()
                                        .remove("ip").remove("usuario").apply()
                                    true
                                } catch (_: Exception) {
                                    false
                                }
                            } else true
                            when {
                                !guardado -> "Conexión segura verificada, pero no se pudo guardar el acceso. " +
                                        "Puedes usar la puerta ahora; el widget conserva los datos anteriores."
                                guardarConexion -> "Conexión segura verificada. Datos guardados. Ya puedes activar la puerta."
                                else -> "Conexión segura verificada. Ya puedes activar la puerta."
                            }
                        } else resultado.mensaje
                    }
                } catch (_: Exception) {
                    correcto = false
                    if (llamadaDePulso) {
                        "No se pudo confirmar el pulso. Comprueba la puerta antes de volver a intentar. " +
                                "La orden no se repetirá automáticamente."
                    } else "No se pudo comprobar la conexión. Revisa los datos e inténtalo de nuevo."
                }
                try {
                    if (pulsoConfirmado) {
                        runOnUiThread {
                            if (!isDestroyed && !isFinishing) estado.text = "$mensaje\nEspera un momento…"
                        }
                        // Pausa visual entre pulsos; el bloqueo real lo comparte ControlGaraje.
                        SystemClock.sleep(2000)
                    }
                } finally {
                    operacionEnCurso.set(false)
                    runOnUiThread {
                        if (!isDestroyed && !isFinishing) {
                            ocupada = false
                            accesoComprobado = correcto
                            estado.text = mensaje
                            actualizarControles()
                        }
                    }
                }
            }, "GarajePantallaHTTPS").start()
        } catch (_: Exception) {
            operacionEnCurso.set(false)
            ocupada = false
            estado.text = "No se inició la operación. Vuelve a intentarlo."
            actualizarControles()
        }
    }
}
