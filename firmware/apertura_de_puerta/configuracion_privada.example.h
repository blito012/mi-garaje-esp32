#pragma once

// PLANTILLA PUBLICA: dejar este archivo sin datos personales.
// Para usar el proyecto, copiarlo como configuracion_privada.h
// en la misma carpeta del archivo apertura_de_puerta.ino.
// Solo esa copia privada debe contener los datos reales.
// El .gitignore del proyecto excluye configuracion_privada.h.

// Red Wi-Fi de 2,4 GHz (puede compartir nombre con la de 5 GHz).
const char* nombreRed = "";
const char* claveRed = "";

// Credenciales del control: independientes de las del Wi-Fi.
// Introducir estos mismos valores en la app al configurarla.
const char* usuarioControl = "usuario";
// Usar una clave unica y aleatoria de entre 20 y 256 bytes.
// Si solo contiene caracteres ASCII, cada caracter ocupa un byte.
const char* claveControl = "";
