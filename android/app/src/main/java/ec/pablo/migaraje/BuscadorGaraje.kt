package ec.pablo.migaraje

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Looper
import java.io.IOException
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Busca el ESP32 en la red local: primero por mDNS (servicio _migaraje._tcp)
 * y, si no contesta, revisando que equipos tienen abierto el puerto 443.
 * Solo averigua IPs candidatas: la identidad se comprueba despues con el certificado.
 */
internal object BuscadorGaraje {
    private const val SERVICIO = "_migaraje._tcp."
    private const val PUERTO = 443
    private const val HILOS_REVISION = 128

    /**
     * Plan B si mDNS no contesta: IPs de la misma red /24 del telefono con el
     * puerto 443 abierto. Todas en paralelo; tarda unas dos veces tiempoMs.
     */
    fun revisarRed(contexto: Context, red: Network, tiempoMs: Int): List<String> {
        val gestor = contexto.applicationContext.getSystemService(ConnectivityManager::class.java)
        val propia = gestor.getLinkProperties(red)?.linkAddresses
            ?.map { it.address }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?: return emptyList()
        val bytes = propia.address.map { it.toInt() and 0xFF }
        val prefijo = "${bytes[0]}.${bytes[1]}.${bytes[2]}."
        val ips = (1..254).map { prefijo + it }.filter { it != propia.hostAddress }
        val hilos = Executors.newFixedThreadPool(HILOS_REVISION)
        try {
            val tareas = ips.map { ip ->
                hilos.submit(Callable { if (puertoAbierto(red, ip, tiempoMs)) ip else null })
            }
            return tareas.mapNotNull { tarea ->
                try {
                    tarea.get()
                } catch (_: Exception) {
                    null
                }
            }
        } finally {
            hilos.shutdownNow()
        }
    }

    private fun puertoAbierto(red: Network, ip: String, tiempoMs: Int): Boolean = try {
        red.socketFactory.createSocket().use { socket ->
            socket.connect(InetSocketAddress(ip, PUERTO), tiempoMs)
            true
        }
    } catch (_: IOException) {
        false
    }

    /** Devuelve la IPv4 local del ESP32, o null si no aparece a tiempo. */
    fun buscar(contexto: Context, tiempoMs: Long): String? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "BuscadorGaraje debe ejecutarse en un hilo de trabajo."
        }
        val nsd = contexto.applicationContext.getSystemService(NsdManager::class.java)
        val encontrada = AtomicReference<String?>(null)
        val terminado = CountDownLatch(1)
        val resolviendo = AtomicBoolean(false)

        val resolucion = object : NsdManager.ResolveListener {
            override fun onServiceResolved(info: NsdServiceInfo) {
                val ip = direccionLocal(info)
                if (ip != null) {
                    encontrada.set(ip)
                    terminado.countDown()
                } else {
                    resolviendo.set(false)
                }
            }
            override fun onResolveFailed(info: NsdServiceInfo, codigo: Int) {
                resolviendo.set(false)
            }
        }
        val descubrimiento = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                // Android solo permite resolver un servicio a la vez.
                if (resolviendo.compareAndSet(false, true)) nsd.resolveService(info, resolucion)
            }
            override fun onStartDiscoveryFailed(tipo: String, codigo: Int) = terminado.countDown()
            override fun onDiscoveryStarted(tipo: String) {}
            override fun onDiscoveryStopped(tipo: String) {}
            override fun onServiceLost(info: NsdServiceInfo) {}
            override fun onStopDiscoveryFailed(tipo: String, codigo: Int) {}
        }

        nsd.discoverServices(SERVICIO, NsdManager.PROTOCOL_DNS_SD, descubrimiento)
        try {
            terminado.await(tiempoMs, TimeUnit.MILLISECONDS)
        } finally {
            try {
                nsd.stopServiceDiscovery(descubrimiento)
            } catch (_: IllegalArgumentException) {
                // La busqueda no llego a empezar.
            }
        }
        return encontrada.get()
    }

    private fun direccionLocal(info: NsdServiceInfo): String? {
        if (info.port != 443) return null
        val direcciones = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses
            else listOfNotNull(@Suppress("DEPRECATION") info.host)
        return direcciones.filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }
}
