#include <Arduino.h>
#include <WiFi.h>
#include <esp_random.h>
#include <esp_https_server.h>
#include <esp_tls.h>
#include <mbedtls/base64.h>
#include <mbedtls/ssl.h>
#include <lwip/sockets.h>
#include <fcntl.h>
#include "certificados.h"

// Mi Garaje: version preparada para compartir el codigo fuente.
// Base: firmware probado con Arduino-ESP32 3.3.12.
//
// Preparacion local antes de compilar:
// 1. Copiar configuracion_privada.example.h como configuracion_privada.h.
// 2. Completar los datos SOLO en configuracion_privada.h.
// 3. Generar un certificado y una clave TLS propios en certificados.h.
//    Debe definir los arrays certificadoServidor y clavePrivadaServidor.
// 4. Configurar la app Android con el certificado publico correspondiente.
//
// No publicar configuracion_privada.h ni certificados.h.
// El servidor usa exclusivamente HTTPS. Este programa requiere el circuito
// documentado: transistor NPN externo, GPIO 23 HIGH activa el modulo de rele.
#include "configuracion_privada.h"

const int pinRele = 23;
const unsigned long duracionPulso = 750;
const unsigned long pausaEntrePulsos = 2000;
unsigned long ultimoPulso = 0;
bool huboPulso = false;
String tokenFormulario;
String autorizacionEsperada;
httpd_handle_t servidorSeguro = nullptr;

// Referencias prestadas: el servidor HTTPS sigue siendo dueno de cada TLS.
// Los callbacks y los handlers se ejecutan en la misma tarea del servidor.
const int maxConexionesTLS = 3;
struct SesionTLS {
  int socket = -1;
  esp_tls_t* tls = nullptr;
};
SesionTLS sesionesTLS[maxConexionesTLS];

void registrarSesionTLS(esp_https_server_user_cb_arg_t* evento) {
  if (evento == nullptr || evento->tls == nullptr) return;
  if (evento->user_cb_state == HTTPD_SSL_USER_CB_SESS_CREATE) {
    int socket = -1;
    if (esp_tls_get_conn_sockfd(evento->tls, &socket) != ESP_OK || socket < 0) return;
    for (int i = 0; i < maxConexionesTLS; i++) {
      if (sesionesTLS[i].tls == nullptr) {
        sesionesTLS[i].socket = socket;
        sesionesTLS[i].tls = evento->tls;
        return;
      }
    }
  } else if (evento->user_cb_state == HTTPD_SSL_USER_CB_SESS_CLOSE) {
    for (int i = 0; i < maxConexionesTLS; i++) {
      if (sesionesTLS[i].tls == evento->tls) sesionesTLS[i] = SesionTLS();
    }
  }
}

void cerrarConexionTLS(httpd_handle_t servidor, int socket) {
  (void)servidor;
  esp_tls_t* tls = nullptr;
  for (int i = 0; i < maxConexionesTLS; i++) {
    if (sesionesTLS[i].socket == socket) {
      tls = sesionesTLS[i].tls;
      sesionesTLS[i] = SesionTLS();
      break;
    }
  }
  if (tls != nullptr) {
    auto* ssl = static_cast<mbedtls_ssl_context*>(esp_tls_get_ssl_context(tls));
    // close_notify debe salir ANTES de cerrar el socket TCP.
    // No bloquear indefinidamente si el cliente desaparece durante el cierre.
    int opciones = fcntl(socket, F_GETFL, 0);
    if (ssl != nullptr && opciones >= 0 &&
        fcntl(socket, F_SETFL, opciones | O_NONBLOCK) == 0) {
      const unsigned long inicioCierre = millis();
      while (millis() - inicioCierre < 250) {
        int resultado = mbedtls_ssl_close_notify(ssl);
        if (resultado == 0) break;
        if (resultado != MBEDTLS_ERR_SSL_WANT_READ &&
            resultado != MBEDTLS_ERR_SSL_WANT_WRITE) break;
        delay(1);
      }
    }
  }
  // Al proporcionar close_fn, nos corresponde cerrar el socket una sola vez.
  // HTTPD libera despues el contexto TLS con su propio callback interno.
  if (socket >= 0) lwip_close(socket);
}

// Los contadores estan en RAM: se borran al reiniciar.
const uint8_t maxRegistros = 8;
const uint8_t maxFallos = 5;
const unsigned long ventanaFallos = 60000;
const unsigned long duracionBloqueo = 60000;
const unsigned long caducidadRegistro = 300000;

struct RegistroIntentos {
  bool usado = false;
  IPAddress ip;
  uint8_t fallos = 0;
  unsigned long inicioVentana = 0;
  unsigned long ultimaActividad = 0;
  bool bloqueado = false;
  unsigned long inicioBloqueo = 0;
};
RegistroIntentos intentos[maxRegistros];

// El servidor HTTPS procesa los handlers en su propia tarea.
// Solo esa tarea modifica tokens, contadores y pulsos despues de setup().

const char pagina[] = R"HTML(
<!DOCTYPE html>
<html lang="es">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Mi garaje</title>
<style>
* { box-sizing: border-box; }
body {
  margin: 0; padding: 28px 16px; background: #f2f4f7;
  color: #182333; font-family: Arial, sans-serif; text-align: center;
}
main { max-width: 480px; margin: 0 auto; }
h1 { font-size: 32px; margin: 12px 0 24px; }
button {
  display: block; width: 100%; min-height: 210px; padding: 28px 16px;
  border: 0; border-radius: 24px; background: #1259bd; color: white;
  font-size: 38px; font-weight: bold; line-height: 1.2;
  cursor: pointer; touch-action: manipulation;
}
button:active { background: #0a3f8c; }
button:disabled { background: #58677b; cursor: wait; }
button:focus-visible { outline: 4px solid #182333; outline-offset: 5px; }
#estado { font-size: 22px; min-height: 60px; line-height: 1.4; }
.nota { font-size: 18px; line-height: 1.5; color: #465467; }
</style>
</head>
<body>
<main>
<h1>Mi garaje</h1>
<form id="control" action="/pulsar" method="POST">
  <input type="hidden" name="token" value="%TOKEN%">
  <button id="boton" type="submit" %BLOQUEADO%>ACTIVAR<br>PUERTA</button>
</form>
<p id="estado" role="status" aria-live="polite">%MENSAJE%</p>
<p class="nota">Equivale a pulsar el botón de pared.<br>
Comprueba la puerta antes de pulsar.</p>
<noscript><p>Si aparece deshabilitado, espera dos segundos y actualiza la página.</p></noscript>
</main>
<script>
const boton = document.getElementById('boton');
const estado = document.getElementById('estado');
const espera = %ESPERA%;
if (espera > 0) {
  boton.disabled = true;
  boton.textContent = 'ESPERA…';
  setTimeout(() => {
    boton.disabled = false;
    boton.innerHTML = 'ACTIVAR<br>PUERTA';
    estado.textContent = 'Listo para otra pulsación.';
  }, espera);
}
document.getElementById('control').addEventListener('submit', () => {
  boton.disabled = true;
  boton.textContent = 'ENVIANDO…';
  estado.textContent = 'Esperando respuesta del control…';
});
// Al volver con el boton Atras, consulta de nuevo sin repetir la orden.
window.addEventListener('pageshow', (evento) => {
  if (evento.persisted) window.location.replace('/');
});
</script>
</body>
</html>
)HTML";

unsigned long esperaRestante() {
  if (!huboPulso) return 0;
  unsigned long transcurrido = millis() - ultimoPulso;
  if (transcurrido >= pausaEntrePulsos) return 0;
  return pausaEntrePulsos - transcurrido;
}

void generarToken() {
  tokenFormulario = "";
  for (int i = 0; i < 4; i++) {
    char bloque[9];
    snprintf(bloque, sizeof(bloque), "%08lx", (unsigned long)esp_random());
    tokenFormulario += bloque;
  }
}


esp_err_t responderConConexion(httpd_req_t* solicitud, const char* estado,
                    const String& contenido, const char* tipo, bool mantenerConexion) {
  httpd_resp_set_status(solicitud, estado);
  httpd_resp_set_type(solicitud, tipo);
  httpd_resp_set_hdr(solicitud, "Cache-Control", "no-store");
  httpd_resp_set_hdr(solicitud, "X-Content-Type-Options", "nosniff");
  httpd_resp_set_hdr(solicitud, "X-Frame-Options", "DENY");
  httpd_resp_set_hdr(solicitud, "Referrer-Policy", "no-referrer");
  httpd_resp_set_hdr(solicitud, "Connection", mantenerConexion ? "keep-alive" : "close");
  esp_err_t resultado = httpd_resp_send(solicitud, contenido.c_str(), contenido.length());
  if (resultado != ESP_OK) return resultado;
  if (mantenerConexion) return ESP_OK;
  // Liberar la sesion TLS al terminar esta respuesta.
  return httpd_sess_trigger_close(solicitud->handle, httpd_req_to_sockfd(solicitud));
}

esp_err_t responder(httpd_req_t* solicitud, const char* estado,
                    const String& contenido, const char* tipo) {
  // Las ordenes, rechazos y comprobaciones publicas terminan la conexion.
  return responderConConexion(solicitud, estado, contenido, tipo, false);
}

int buscarRegistro(const IPAddress& ip, bool crear) {
  const unsigned long ahora = millis();
  int libre = -1;
  for (uint8_t i = 0; i < maxRegistros; i++) {
    if (intentos[i].usado &&
        ahora - intentos[i].ultimaActividad >= caducidadRegistro) {
      intentos[i] = RegistroIntentos();
    }
    if (intentos[i].usado && intentos[i].ip == ip) return i;
    if (!intentos[i].usado && libre < 0) libre = i;
  }
  if (!crear || libre < 0) return -1;
  intentos[libre] = RegistroIntentos();
  intentos[libre].usado = true;
  intentos[libre].ip = ip;
  intentos[libre].inicioVentana = ahora;
  intentos[libre].ultimaActividad = ahora;
  return libre;
}

unsigned long bloqueoRestante(int indice) {
  if (indice < 0 || !intentos[indice].bloqueado) return 0;
  const unsigned long ahora = millis();
  const unsigned long transcurrido = ahora - intentos[indice].inicioBloqueo;
  if (transcurrido < duracionBloqueo) return duracionBloqueo - transcurrido;
  intentos[indice].bloqueado = false;
  intentos[indice].fallos = 0;
  intentos[indice].inicioVentana = ahora;
  return 0;
}

void registrarFallo(int indice) {
  const unsigned long ahora = millis();
  if (ahora - intentos[indice].inicioVentana >= ventanaFallos) {
    intentos[indice].fallos = 0;
    intentos[indice].inicioVentana = ahora;
  }
  intentos[indice].ultimaActividad = ahora;
  if (intentos[indice].fallos < maxFallos) intentos[indice].fallos++;
  if (intentos[indice].fallos >= maxFallos) {
    intentos[indice].bloqueado = true;
    intentos[indice].inicioBloqueo = ahora;
    Serial.println("Acceso bloqueado 60 segundos por intentos incorrectos.");
  }
}

esp_err_t responderBloqueo(httpd_req_t* solicitud, unsigned long espera) {
  String segundos((espera + 999) / 1000);
  httpd_resp_set_hdr(solicitud, "Retry-After", segundos.c_str());
  return responder(solicitud, "429 Too Many Requests",
    "Acceso temporalmente limitado. Espera " + segundos + " segundos.",
    "text/plain; charset=utf-8");
}

bool compararAutorizacion(const char* recibida, size_t longitud) {
  if (longitud != autorizacionEsperada.length()) return false;
  volatile uint8_t diferencia = 0;
  for (size_t i = 0; i < longitud; i++) {
    diferencia = diferencia | (uint8_t(recibida[i]) ^ uint8_t(autorizacionEsperada[i]));
  }
  return diferencia == 0;
}

esp_err_t pedirAcceso(httpd_req_t* solicitud) {
  // Basic se utiliza SOLO dentro de TLS. No existe servidor HTTP en puerto 80.
  httpd_resp_set_hdr(solicitud, "WWW-Authenticate",
    "Basic realm=\"Control del garaje\", charset=\"UTF-8\"");
  return responder(solicitud, "401 Unauthorized", "Acceso no autorizado.",
                   "text/plain; charset=utf-8");
}

bool obtenerIpCliente(httpd_req_t* solicitud, IPAddress& ip) {
  struct sockaddr_storage direccion = {};
  socklen_t longitudDireccion = sizeof(direccion);
  if (getpeername(httpd_req_to_sockfd(solicitud),
      reinterpret_cast<struct sockaddr*>(&direccion), &longitudDireccion) != 0) return false;
  if (direccion.ss_family == AF_INET) {
    const auto* ipv4 = reinterpret_cast<const struct sockaddr_in*>(&direccion);
    ip = IPAddress(ipv4->sin_addr.s_addr);
    return true;
  }
#if CONFIG_LWIP_IPV6
  // ESP-IDF puede entregar una conexion IPv4 como ::ffff:a.b.c.d.
  if (direccion.ss_family == AF_INET6) {
    const auto* ipv6 = reinterpret_cast<const struct sockaddr_in6*>(&direccion);
    const uint8_t* bytes = reinterpret_cast<const uint8_t*>(&ipv6->sin6_addr);
    const uint8_t prefijo[12] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255, 255};
    if (memcmp(bytes, prefijo, sizeof(prefijo)) == 0) {
      ip = IPAddress(bytes[12], bytes[13], bytes[14], bytes[15]);
      return true;
    }
  }
#endif
  // Esta version de la app se configura mediante una direccion IPv4.
  return false;
}

esp_err_t comprobarAcceso(httpd_req_t* solicitud, bool& autorizado) {
  autorizado = false;
  IPAddress ip;
  if (!obtenerIpCliente(solicitud, ip)) {
    return responder(solicitud, "403 Forbidden", "Conexion no admitida.", "text/plain");
  }
  int indice = buscarRegistro(ip, false);
  unsigned long espera = bloqueoRestante(indice);
  if (espera > 0) return responderBloqueo(solicitud, espera);

  size_t longitud = httpd_req_get_hdr_value_len(solicitud, "Authorization");
  if (longitud == 0) return pedirAcceso(solicitud);

  indice = buscarRegistro(ip, true);
  if (indice < 0) return responderBloqueo(solicitud, caducidadRegistro);

  char recibida[512] = {};
  bool correcta = longitud < sizeof(recibida) &&
    httpd_req_get_hdr_value_str(solicitud, "Authorization", recibida, sizeof(recibida)) == ESP_OK &&
    compararAutorizacion(recibida, longitud);
  // No imprimir esta cabecera: contiene las credenciales codificadas.
  volatile char* borrar = recibida;
  for (size_t i = 0; i < sizeof(recibida); i++) borrar[i] = 0;

  if (correcta) {
    intentos[indice] = RegistroIntentos();
    autorizado = true;
    return ESP_OK;
  }
  registrarFallo(indice);
  espera = bloqueoRestante(indice);
  return espera > 0 ? responderBloqueo(solicitud, espera) : pedirAcceso(solicitud);
}

esp_err_t mostrarPagina(httpd_req_t* solicitud) {
  bool autorizado = false;
  esp_err_t acceso = comprobarAcceso(solicitud, autorizado);
  if (!autorizado) return acceso;

  unsigned long espera = esperaRestante();
  String mensaje = "Listo para recibir una pulsación.";
  char consulta[128] = {};
  char resultado[20] = {};
  if (httpd_req_get_url_query_str(solicitud, consulta, sizeof(consulta)) == ESP_OK &&
      httpd_query_key_value(consulta, "resultado", resultado, sizeof(resultado)) == ESP_OK) {
    if (strcmp(resultado, "enviado") == 0) mensaje = "Pulso enviado al relé.";
    if (strcmp(resultado, "espera") == 0) mensaje = "Espera un momento. No se envió otro pulso.";
  }
  if (espera > 0) mensaje += " Espera un momento.";

  String contenido = pagina;
  contenido.replace("%TOKEN%", tokenFormulario);
  contenido.replace("%ESPERA%", String(espera));
  contenido.replace("%BLOQUEADO%", espera > 0 ? "disabled" : "");
  contenido.replace("%MENSAJE%", mensaje);
  // La app nueva pide conservar TLS solo entre GET / y POST /pulsar.
  // Leer Connection antes de enviar: HTTPD elimina entonces las cabeceras recibidas.
  char conexion[32] = {};
  bool mantenerConexion = solicitud->content_len == 0 &&
    httpd_req_get_hdr_value_str(solicitud, "Connection", conexion, sizeof(conexion)) == ESP_OK &&
    String(conexion).equalsIgnoreCase("keep-alive");
  return responderConConexion(solicitud, "200 OK", contenido,
                             "text/html; charset=utf-8", mantenerConexion);
}

esp_err_t volverALaPagina(httpd_req_t* solicitud, const char* destino) {
  httpd_resp_set_hdr(solicitud, "Location", destino);
  return responder(solicitud, "303 See Other", "", "text/plain");
}

esp_err_t pulsarRele(httpd_req_t* solicitud) {
  bool autorizado = false;
  esp_err_t acceso = comprobarAcceso(solicitud, autorizado);
  if (!autorizado) return acceso;

  char tipoRecibido[96] = {};
  if (httpd_req_get_hdr_value_str(solicitud, "Content-Type", tipoRecibido,
                                sizeof(tipoRecibido)) != ESP_OK) {
    return responder(solicitud, "415 Unsupported Media Type", "Formato no admitido.", "text/plain");
  }
  String tipo = tipoRecibido;
  int separador = tipo.indexOf(';');
  if (separador >= 0) tipo = tipo.substring(0, separador);
  tipo.trim();
  if (!tipo.equalsIgnoreCase("application/x-www-form-urlencoded") || solicitud->content_len != 38) {
    return responder(solicitud, "400 Bad Request", "Orden no valida.", "text/plain");
  }

  // Formato exacto: token= seguido por 32 caracteres hexadecimales.
  char cuerpo[39] = {};
  size_t leido = 0;
  const unsigned long inicio = millis();
  while (leido < 38) {
    if (millis() - inicio >= 3000) {
      return responder(solicitud, "408 Request Timeout", "Orden incompleta.", "text/plain");
    }
    int cantidad = httpd_req_recv(solicitud, cuerpo + leido, 38 - leido);
    if (cantidad <= 0) return ESP_FAIL;
    leido += cantidad;
  }
  if (millis() - inicio >= 3000) {
    return responder(solicitud, "408 Request Timeout", "Orden recibida demasiado tarde.", "text/plain");
  }
  String esperado = "token=" + tokenFormulario;
  if (memcmp(cuerpo, esperado.c_str(), 38) != 0) {
    return responder(solicitud, "403 Forbidden", "Formulario vencido. Vuelve a abrir la pagina.",
                     "text/plain; charset=utf-8");
  }
  if (esperaRestante() > 0) {
    Serial.println("Orden no ejecutada: espera entre pulsaciones.");
    return volverALaPagina(solicitud, "/?resultado=espera");
  }

  generarToken(); // Consumir el token ANTES de activar el rele.
  digitalWrite(pinRele, HIGH);
  delay(duracionPulso);
  digitalWrite(pinRele, LOW);
  ultimoPulso = millis();
  huboPulso = true;
  Serial.println("Orden HTTPS autorizada: pulso de 0.75 segundos terminado.");
  return volverALaPagina(solicitud, "/?resultado=enviado");
}

esp_err_t comprobarServidor(httpd_req_t* solicitud) {
  // Comprobacion publica de TLS. No autentica, no entrega tokens y no activa el rele.
  return responder(solicitud, "200 OK", "{\"servicio\":\"mi-garaje\",\"https\":true}",
                   "application/json");
}

void detenerPorError(const char* mensaje) {
  digitalWrite(pinRele, LOW);
  Serial.println(mensaje);
  while (true) delay(1000);
}

void prepararAutorizacion() {
  String datos = String(usuarioControl) + ":" + claveControl;
  unsigned char codificada[512] = {};
  size_t longitud = 0;
  int resultado = mbedtls_base64_encode(codificada, sizeof(codificada) - 1, &longitud,
    reinterpret_cast<const unsigned char*>(datos.c_str()), datos.length());
  if (resultado != 0 || longitud >= sizeof(codificada)) {
    detenerPorError("No se pudo preparar la autenticacion.");
  }
  codificada[longitud] = 0;
  autorizacionEsperada = "Basic " + String(reinterpret_cast<const char*>(codificada));
  volatile unsigned char* borrar = codificada;
  for (size_t i = 0; i < sizeof(codificada); i++) borrar[i] = 0;
}

void iniciarServidor() {
  httpd_ssl_config_t configuracion = HTTPD_SSL_CONFIG_DEFAULT();
  configuracion.transport_mode = HTTPD_SSL_TRANSPORT_SECURE;
  configuracion.port_secure = 443;
  configuracion.servercert = reinterpret_cast<const uint8_t*>(certificadoServidor);
  configuracion.servercert_len = sizeof(certificadoServidor);
  configuracion.prvtkey_pem = reinterpret_cast<const uint8_t*>(clavePrivadaServidor);
  configuracion.prvtkey_len = sizeof(clavePrivadaServidor);
  configuracion.httpd.max_open_sockets = maxConexionesTLS;
  configuracion.user_cb = registrarSesionTLS;
  configuracion.httpd.close_fn = cerrarConexionTLS;
  configuracion.httpd.max_uri_handlers = 3;
  configuracion.httpd.max_req_hdr_len = 2048;
  configuracion.httpd.max_uri_len = 128;
  configuracion.httpd.lru_purge_enable = true;
  configuracion.httpd.recv_wait_timeout = 3;
  configuracion.httpd.send_wait_timeout = 3;
  configuracion.tls_handshake_timeout_ms = 4000;

  esp_err_t resultado = httpd_ssl_start(&servidorSeguro, &configuracion);
  if (resultado != ESP_OK) {
    Serial.printf("Error HTTPS: %s\n", esp_err_to_name(resultado));
    detenerPorError("HTTPS no inicio. Revisa certificados.h. Rele en reposo.");
  }

  httpd_uri_t paginaPrincipal = {};
  paginaPrincipal.uri = "/";
  paginaPrincipal.method = HTTP_GET;
  paginaPrincipal.handler = mostrarPagina;

  httpd_uri_t ordenPulso = {};
  ordenPulso.uri = "/pulsar";
  ordenPulso.method = HTTP_POST;
  ordenPulso.handler = pulsarRele;

  httpd_uri_t salud = {};
  salud.uri = "/salud";
  salud.method = HTTP_GET;
  salud.handler = comprobarServidor;

  if (httpd_register_uri_handler(servidorSeguro, &paginaPrincipal) != ESP_OK ||
      httpd_register_uri_handler(servidorSeguro, &ordenPulso) != ESP_OK ||
      httpd_register_uri_handler(servidorSeguro, &salud) != ESP_OK) {
    httpd_ssl_stop(servidorSeguro);
    servidorSeguro = nullptr;
    detenerPorError("No se pudieron registrar las rutas HTTPS. Rele en reposo.");
  }
}

void setup() {
  digitalWrite(pinRele, LOW);
  pinMode(pinRele, OUTPUT);
  Serial.begin(115200);

  if (strlen(claveControl) < 20 || strlen(claveControl) > 256 ||
      String(claveControl) == "CAMBIA_ESTA_CLAVE") {
    detenerPorError("Completa claveControl: entre 20 y 256 bytes.");
  }
  if (strlen(usuarioControl) == 0 || strlen(usuarioControl) > 64 ||
      strchr(usuarioControl, ':') != nullptr || strchr(usuarioControl, '\r') != nullptr ||
      strchr(usuarioControl, '\n') != nullptr) {
    detenerPorError("usuarioControl no valido.");
  }
  if (strlen(claveRed) == 0) detenerPorError("Completa claveRed con la contrasena del Wi-Fi.");

  prepararAutorizacion();
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.begin(nombreRed, claveRed);
  Serial.println("Conectando al Wi-Fi...");
  while (WiFi.status() != WL_CONNECTED) {
    delay(500);
    Serial.print(".");
  }
  generarToken();
  iniciarServidor();
  Serial.println();
  Serial.println("HTTPS listo. Rele en reposo. Pulso: 0.75 segundos.");
  Serial.println("HTTP desactivado. Puerto HTTPS: 443.");
  Serial.println("Cierre TLS con close_notify habilitado.");
  Serial.println("Limite: 5 fallos en 60 s por IP; bloqueo de 60 s.");
  Serial.print("Prueba sin mover la puerta: https://");
  Serial.print(WiFi.localIP());
  Serial.println("/salud");
  Serial.println("HTTPS: consulta y pulso admitidos en una misma conexion segura.");
}

void loop() {
  // El servidor HTTPS atiende las peticiones en su propia tarea.
  // Informar de una reconexion sin generar ninguna orden.
  static bool conectado = true;
  static IPAddress ultimaIp;
  bool ahora = WiFi.status() == WL_CONNECTED;
  if (ahora && (!conectado || WiFi.localIP() != ultimaIp)) {
    ultimaIp = WiFi.localIP();
    Serial.print("Wi-Fi conectado. IP: ");
    Serial.println(ultimaIp);
  }
  if (!ahora && conectado) Serial.println("Wi-Fi desconectado. Esperando reconexion...");
  conectado = ahora;
  delay(100);
}
