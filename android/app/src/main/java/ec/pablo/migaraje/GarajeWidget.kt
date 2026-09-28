package ec.pablo.migaraje

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.widget.RemoteViews
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Conecta el diseño widget_garaje.xml con ControlGaraje.kt.
 * Añadir, redimensionar o actualizar el widget nunca envía un pulso.
 */
class GarajeWidget : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val ocupado = operacionEnCurso.get()
        mostrar(
            context,
            appWidgetManager,
            appWidgetIds,
            if (ocupado) "Esperando respuesta…" else "Toca Mi Garaje para configurar.",
            ocupado
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACCION_ACTIVAR) {
            super.onReceive(context, intent)
            return
        }

        // Los toques adicionales durante una operación se descartan, no se encolan.
        if (!operacionEnCurso.compareAndSet(false, true)) return

        val inicio = SystemClock.elapsedRealtime()
        val contextoApp = context.applicationContext
        val pendiente = goAsync()

        try {
            Thread({
                try {
                    // Si el hilo arrancó tarde, no ejecutar una orden retrasada.
                    if (SystemClock.elapsedRealtime() - inicio > 1000L) {
                        mostrarTodos(contextoApp, "No se envió la orden. Vuelve a pulsar.")
                    } else {
                        mostrarTodos(contextoApp, "Esperando respuesta…", ocupado = true)
                        val resultado = if (SystemClock.elapsedRealtime() - inicio > 1000L) {
                            ControlGaraje.Resultado(
                                ControlGaraje.Estado.SIN_CONEXION,
                                "No se envió la orden. Vuelve a pulsar."
                            )
                        } else {
                            ControlGaraje.activar(contextoApp)
                        }
                        mostrarTodos(contextoApp, resultado.mensaje)
                    }
                } catch (_: Exception) {
                    // No repetir el envío: el relé pudo haber recibido la orden.
                    mostrarTodos(contextoApp, "No se pudo confirmar. Comprueba la puerta.")
                } finally {
                    operacionEnCurso.set(false)
                    pendiente.finish()
                }
            }, "GarajeWidgetEnvio").start()
        } catch (_: Exception) {
            // Si ni siquiera arrancó el hilo, no se llamó a ControlGaraje.
            try {
                mostrarTodos(contextoApp, "No se envió la orden. Abre Mi Garaje.")
            } finally {
                operacionEnCurso.set(false)
                pendiente.finish()
            }
        }
    }

    companion object {
        private const val ACCION_ACTIVAR = "ec.pablo.migaraje.widget.ACTIVAR"
        private val operacionEnCurso = AtomicBoolean(false)

        private fun mostrarTodos(
            contexto: Context,
            mensaje: String,
            ocupado: Boolean = false
        ) {
            // Un fallo al dibujar no debe provocar otro envío al relé.
            try {
                val gestor = AppWidgetManager.getInstance(contexto)
                val ids = gestor.getAppWidgetIds(ComponentName(contexto, GarajeWidget::class.java))
                mostrar(contexto, gestor, ids, mensaje, ocupado)
            } catch (_: Exception) {
                // Se recuperará la vista con la próxima interacción o actualización.
            }
        }

        private fun mostrar(
            contexto: Context,
            gestor: AppWidgetManager,
            ids: IntArray,
            mensaje: String,
            ocupado: Boolean
        ) {
            if (ids.isEmpty()) return

            val activar = Intent(contexto, GarajeWidget::class.java).apply {
                action = ACCION_ACTIVAR
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            val accionBoton = PendingIntent.getBroadcast(
                contexto,
                100,
                activar,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Solo el título abre la app. El botón grande envía la orden directamente.
            val abrirApp = Intent(contexto, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            val accionTitulo = PendingIntent.getActivity(
                contexto,
                101,
                abrirApp,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val vista = RemoteViews(contexto.packageName, R.layout.widget_garaje).apply {
                setTextViewText(R.id.widgetTitulo, "Mi Garaje")
                setTextViewText(R.id.widgetBoton, if (ocupado) "ENVIANDO…" else "ACTIVAR\nPUERTA")
                setTextViewText(R.id.widgetEstado, mensaje)
                setContentDescription(R.id.widgetTitulo, "Abrir Mi Garaje para configurar el acceso")
                setContentDescription(
                    R.id.widgetBoton,
                    if (ocupado) "Enviando orden. Espera un momento." else "Activar puerta del garaje"
                )
                // Mantener el botón recuperable si Android cierra el proceso.
                // operacionEnCurso y ControlGaraje descartan los toques demasiado seguidos.
                setBoolean(R.id.widgetBoton, "setEnabled", true)
                setOnClickPendingIntent(R.id.widgetBoton, accionBoton)
                setOnClickPendingIntent(R.id.widgetTitulo, accionTitulo)
            }
            gestor.updateAppWidget(ids, vista)
        }
    }
}
