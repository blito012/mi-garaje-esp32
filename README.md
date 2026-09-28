# Mi Garaje — v0.1.0

**Control local con ESP32, app Android y widget. Primera versión pública funcional.**

Control local de un garaje desde el teléfono, con un botón grande y un widget para la pantalla de inicio. El proyecto nació para facilitar el uso a una persona mayor y se desarrolló mediante pruebas en una instalación doméstica.

La versión **v0.1.0** presenta una solución que ya funciona en su instalación de origen y continúa en desarrollo para facilitar su reproducción, mejorar la configuración y añadir nuevas funciones.

## Tu garaje, bajo tu control

Mi Garaje funciona en tu red local, **sin cuentas externas, suscripciones ni servicios en la nube**. Tú conservas el código, las credenciales y el control del equipo. Puedes estudiarlo, modificarlo y repararlo; su funcionamiento no depende de que una empresa mantenga activo un servidor.

Una vez instalado y configurado, el control entre el teléfono y el ESP32 **no necesita salida a Internet**. Sí necesita alimentación y una red Wi-Fi local operativa que permita la comunicación entre ambos. Una interrupción del servicio de Internet no impide por sí misma utilizarlo si esa red sigue funcionando.

Esta es la soberanía tecnológica que busca el proyecto: disponer del código y la documentación para comprender, mantener y adaptar tu propio control. Se utilizan hardware y herramientas de terceros, como ESP32, Android y sus bibliotecas; la autonomía se refiere al funcionamiento local y a la posibilidad de mantener el proyecto, no a la ausencia total de dependencias. La preparación del entorno puede requerir Internet para descargar herramientas y bibliotecas.

El código original se publica bajo **GPLv3**, para que otras personas puedan aprender, adaptarlo y compartirlo conforme a la licencia.

## Cómo funciona

El ESP32 activa un relé durante **0,75 segundos**. Sus contactos hacen lo mismo que una pulsación del botón de pared, que sigue funcionando de manera independiente.

**El control no conoce la posición de la puerta.** Según el motor y su estado, una pulsación puede abrir, detener o cerrar. Por eso la app muestra «ACTIVAR PUERTA» y confirma el pulso, no que la puerta esté abierta.

## Qué incluye

- Firmware para ESP32 con conexión Wi-Fi de 2,4 GHz y servidor HTTPS.
- App Android con botón grande, configuración y almacenamiento cifrado de credenciales.
- Widget para activar el control desde la pantalla de inicio.
- Generador de certificados propios para Windows.
- Plantilla de configuración sin contraseñas ni claves privadas.

Funciona dentro de la red local, sin servicio en la nube. No incluye acceso remoto desde Internet, detección de posición ni apertura automática por proximidad.

## Carpetas

| Ruta | Contenido |
| --- | --- |
| `android/` | Proyecto que se abre en Android Studio. |
| `firmware/apertura_de_puerta/` | Programa que se carga en el ESP32 y plantilla de configuración. |
| `herramientas/generar_certificados.ps1` | Generación local del certificado y la clave TLS. |
| `.gitignore` | Exclusiones de datos privados, archivos locales y compilaciones. |

**Antes de compilar hay que crear la configuración privada y los certificados.** Esos archivos no se distribuyen en el repositorio.

## Circuito utilizado

Materiales del montaje probado:

- ESP32 DevKit V1 de 30 pines, con alimentación USB de 5 V.
- Módulo de relé de 5 V con entrada activa en nivel bajo; en el montaje se utilizó uno con relé Songle SRD-05VDC-SL-C.
- Transistor NPN KSP2222A.
- Una resistencia de 330 Ω y otra de 10 kΩ.
- Fuente USB de 5 V adecuada para alimentar conjuntamente el ESP32 y el módulo.
- Placa para soldar, conectores, cables y caja aislante para interior.

Se utilizaron zócalos para poder sustituir el ESP32 y el transistor. Las resistencias quedaron soldadas.

### Conexiones de control

| Origen | Destino |
| --- | --- |
| GPIO 23 del ESP32 | Resistencia de 330 Ω; su otro extremo va a la base **B** del transistor. |
| Base **B** | Resistencia de 10 kΩ; su otro extremo va al emisor **E**. |
| Emisor **E** | GND del ESP32. |
| Colector **C** | Entrada **IN** del módulo de relé. |
| Alimentación de 5 V | **VCC** del módulo de relé. |
| GND del módulo | GND del ESP32. |

En la placa utilizada, el módulo toma los 5 V del pin VIN/5V con el ESP32 alimentado por USB. Verifica esa función en tu placa antes de reproducirla. La distribución física de las patas del transistor depende del modelo y fabricante: identifica B, C y E por su documentación, no únicamente por la cara plana.

Con este circuito, **GPIO 23 en HIGH activa el relé** y LOW lo desactiva. Se utiliza un módulo con su circuito de accionamiento y protección, no una bobina de relé conectada directamente al transistor.

### Conexión al pulsador

Los contactos **COM y NO** del relé se conectan en paralelo con los dos contactos del pulsador de pared. **NC queda libre**. No se conecta el GND ni la alimentación del ESP32 a los cables del pulsador: el enlace se realiza mediante los contactos aislados del relé.

Este montaje requiere una entrada de pulsador compatible con un cierre momentáneo de contacto. No todos los mandos de pared funcionan así; algunos utilizan comunicación digital. Confirma la compatibilidad del motor antes de conectarlo. No lleves la tensión de red a esta placa ni anules sensores o protecciones del motor.

## Preparación del software

La base del firmware se probó con **Arduino-ESP32 3.3.12**, seleccionando **ESP32 Dev Module**. Utiliza las bibliotecas incluidas en ese paquete.

La app requiere **Android 12 o posterior** (`minSdk = 31`). La configuración incluida declara SDK 37, Android Gradle Plugin 9.4.1 y Gradle 9.6.0; el archivo del daemon solicita JDK 25. Estos son los valores del proyecto compartido, no una lista de versiones alternativas verificadas. Abre `android/` en Android Studio y permite que sincronice sus dependencias y herramientas.

El generador de certificados requiere **Windows, PowerShell 5.1 o posterior, permisos NTFS y OpenSSL 3.x**. Puede localizar OpenSSL en una instalación de Git para Windows.

### 1. Crear la configuración privada

Dentro de `firmware/apertura_de_puerta/`, copia `configuracion_privada.example.h` con el nombre **`configuracion_privada.h`**.

Completa únicamente la copia privada:

| Campo | Valor |
| --- | --- |
| `nombreRed` | Nombre de tu red Wi-Fi de 2,4 GHz. |
| `claveRed` | Contraseña de esa red. |
| `usuarioControl` | Usuario que introducirás en la app. |
| `claveControl` | Contraseña exclusiva del control, aleatoria y de al menos 20 caracteres ASCII; el firmware admite de 20 a 256 bytes. |

La contraseña del control es distinta de la del Wi-Fi. No publiques ninguna de ellas. Si un valor contiene comillas dobles o barras invertidas, debe escribirse con el escape correspondiente en la cadena de C++.

### 2. Generar los certificados de una instalación nueva

Abre PowerShell en la carpeta principal del proyecto y ejecuta:

```powershell
.\herramientas\generar_certificados.ps1
```

Si no encuentra OpenSSL, puedes indicar su ubicación:

```powershell
.\herramientas\generar_certificados.ps1 -RutaOpenSSL "C:\Program Files\Git\usr\bin\openssl.exe"
```

El script crea una carpeta nueva llamada `MiGaraje_TLS` dentro de tu carpeta de usuario de Windows. No sobrescribe una carpeta existente. Para otra instalación, usa una ubicación nueva mediante el parámetro `-CarpetaSalida`.

| Archivo generado | Uso |
| --- | --- |
| `certificados.h` | **Privado.** Copiar junto a `apertura_de_puerta.ino`. Contiene el certificado y la clave privada del servidor. |
| `garaje_certificado.pem` | Certificado público. Copiar a `android/app/src/main/res/raw/`; crear `raw` si no existe. |
| `servidor-clave.pem` | **Privado.** Conservar protegido; nunca incluirlo en la app ni publicarlo. |
| `garaje.cnf` | Configuración utilizada para generar el certificado. |

La app y el ESP32 deben utilizar el mismo certificado. Se genera uno autofirmado, válido durante **730 días**. Guarda los originales en privado y anota su vencimiento. Renovarlo exige actualizar el ESP32 y las apps instaladas.

El nombre `garaje.local` identifica el servidor durante la comprobación TLS. **No configura mDNS ni permite descubrir automáticamente su IP.** La app se conecta a la IP introducida por el usuario y verifica el certificado incorporado.

### 3. Cargar el ESP32

1. Abre `firmware/apertura_de_puerta/apertura_de_puerta.ino` en Arduino IDE.
2. Comprueba que `configuracion_privada.h` y `certificados.h` estén junto al programa.
3. Selecciona la placa y el puerto USB correspondientes, compila y carga.
4. Abre el monitor serie a **115200 baudios** y anota la IP indicada al conectar al Wi-Fi.

Para verificar por primera vez un montaje nuevo, prueba el relé antes de conectarlo a la entrada del motor. Debe permanecer en reposo al arrancar y activarse durante 0,75 segundos únicamente al recibir una orden válida.

### 4. Instalar y configurar Android

1. Abre la carpeta `android/` en Android Studio y termina la sincronización.
2. Comprueba que el certificado esté en `app/src/main/res/raw/garaje_certificado.pem`.
3. Compila e instala la app en el teléfono.
4. Conecta el teléfono a una red Wi-Fi que pueda comunicarse con el ESP32.
5. Introduce la IP, el usuario y la contraseña del control. Pulsa **PROBAR CONEXIÓN**; esta operación no mueve la puerta.
6. Tras verificar el acceso, utiliza **ACTIVAR PUERTA**.
7. Añade el widget **Mi Garaje** desde el selector de widgets del teléfono.

Las credenciales se guardan cifradas en cada teléfono después de comprobar el acceso. Si Android solicita acceso a la red local, permite ese acceso para utilizar el control.

Para distribuir una versión final entre tus propios teléfonos, genera un APK firmado con tu propia clave de firma y guarda esa clave en privado. Las futuras actualizaciones deben conservar la firma e incrementar `versionCode`. No compartas el archivo de firma ni sus contraseñas.

## Protecciones y límites

- HTTPS y comprobación del certificado específico del ESP32 en la app.
- Autenticación antes de obtener el formulario o ejecutar una orden.
- Token aleatorio que cambia antes de ejecutar cada pulso aceptado.
- Pausa de dos segundos después de finalizar un pulso.
- Bloqueo temporal por IP: cinco fallos de autenticación en 60 segundos producen un bloqueo de 60 segundos. Los registros son limitados y se borran al reiniciar.
- Credenciales de Android cifradas con Android Keystore; copia de seguridad desactivada.
- Sin repetición automática de órdenes cuando su resultado no se puede confirmar.

Estas medidas no equivalen a una auditoría de seguridad ni garantizan protección absoluta. El firmware contiene secretos necesarios para operar y no incorpora protección adicional frente a extracción física de la memoria. El control utiliza credenciales compartidas, sin cuentas individuales ni revocación por teléfono.

El proyecto está pensado para una red doméstica de confianza. No abras puertos del router para exponerlo directamente a Internet. Si una respuesta se pierde, la orden podría haberse ejecutado: comprueba la puerta antes de volver a pulsar.

Los sensores ópticos del motor siguen gestionando obstáculos. No informan a esta app de si la puerta está abierta o cerrada.

## Problemas frecuentes

| Síntoma | Qué revisar |
| --- | --- |
| Falta `configuracion_privada.h` o `certificados.h` | Completar los pasos 1 y 2 antes de compilar el firmware. |
| Android no encuentra `R.raw.garaje_certificado` | Crear `res/raw` y copiar el certificado público con su nombre exacto. |
| No conecta | Alimentación, IP actual, Wi-Fi, permiso de red local y posible aislamiento de dispositivos en el router. |
| Error de certificado | Coincidencia entre app y ESP32, vigencia del certificado y fecha del teléfono. |
| Código 429 | Esperar a que termine el bloqueo temporal por intentos de acceso fallidos y revisar las credenciales. |
| No se pudo confirmar el pulso | Comprobar físicamente la puerta antes de repetir. |
| Cambió la IP tras un reinicio | Consultar el monitor serie y actualizar la IP en cada teléfono. |

La IP se obtiene por DHCP: que se conserve durante varias pruebas no garantiza que nunca cambie. Una reserva DHCP en el router puede estabilizarla, pero este proyecto no la configura automáticamente.

## Archivos privados y publicación

El `.gitignore` excluye la configuración privada, certificados, claves de firma, compilaciones y archivos locales del entorno. El certificado público no es una contraseña, pero cada instalación debe generar el suyo y por eso no se distribuye uno preconfigurado.

Revisa los archivos antes de cada publicación. Un `.gitignore` no borra secretos que ya se hayan añadido a Git o publicado. Los binarios del firmware también pueden contener credenciales.

## Estado y posibles mejoras

La instalación doméstica de origen se probó con la app, el widget y varios teléfonos. La copia pública recibió revisión estática; todavía falta validar de principio a fin una instalación nueva a partir de este repositorio. La generación criptográfica se comprobó con OpenSSL; queda pendiente probar el flujo completo de PowerShell y sus permisos en Windows.

Posibles siguientes pasos:

- Documentar con fotografías el montaje final y sus conexiones.
- Facilitar el descubrimiento del ESP32 y la gestión de cambios de IP.
- Incorporar sensores de posición para mostrar el estado real de la puerta.
- Añadir gestión individual de dispositivos y revocación de acceso.
- Simplificar la renovación de certificados.

Al informar de un problema, indica la versión de Android, la placa, las versiones de las herramientas y el mensaje de error. Elimina contraseñas y claves privadas de capturas, archivos y registros.

## Licencia

Copyright (C) 2026 Pablo Briceño.

El código original de Mi Garaje se distribuye bajo la **GNU General Public License, versión 3 únicamente** (`GPL-3.0-only`). Puedes utilizarlo, modificarlo y redistribuirlo conforme a sus condiciones. Al distribuir una versión modificada cubierta por la licencia, debes mantener GPLv3 y proporcionar el código fuente correspondiente según sus términos.

Se distribuye sin garantía, en los términos establecidos por la licencia. Consulta el texto completo en [LICENSE](LICENSE).

Las bibliotecas, herramientas y otros componentes de terceros conservan sus propias licencias y avisos; esta declaración no los sustituye.
