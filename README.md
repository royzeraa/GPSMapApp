# SimpleGPSMapApp

Aplicación Android (Java, minSdk 30) para la actividad sumativa **2.3: Manejo de GPS y mapas** (Programación Android, Santo Tomás).

Muestra un mapa de Google Maps con la ubicación actual del usuario, permite añadir marcadores y capturar y mostrar una imagen.

## Funcionalidades

- Mostrar la ubicación actual en un mapa (punto azul + marcador "Mi ubicación") con actualización en tiempo real.
- Solicitud de permisos de ubicación en tiempo de ejecución (`ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION`).
- Añadir marcadores con una **pulsación larga** sobre el mapa; cada marcador obtiene su dirección (Geocoder) en un hilo de fondo.
- Botón **Foto**: captura una imagen con la cámara, la muestra en pantalla y la guarda en el almacenamiento interno de la app.
- Botón **Centrar**: vuelve a la ubicación actual. Botón **Limpiar**: elimina los marcadores añadidos.

## Uso de Threads

| Hilo | Tarea |
|------|-------|
| `HandlerThread` (`gps-location-thread`) | Recibe las lecturas del GPS fuera del hilo principal |
| `ExecutorService` (1 hilo) | Geocoder (dirección del marcador) y guardado de la foto |
| `runOnUiThread` | Devuelve los resultados a la interfaz |

## Requisitos

- Android Studio (Koala o superior) y JDK 11+ (incluido en Android Studio).
- Dispositivo o emulador con **Google Play Services** (imagen "Google APIs" / "Google Play") y API 30 o superior.
- Una **API Key de Google Maps** con *Maps SDK for Android* habilitado.

## Instrucciones de Ejecución

1. Clona el repositorio: `git clone https://github.com/USERNAME/SimpleGPSMapApp.git`
2. Abre el proyecto en Android Studio y espera la sincronización de Gradle.
3. Añade tu API Key de Google Maps en `app/src/main/res/values/google_maps_api.xml`:
   ```xml
   <string name="google_maps_key" templateMergeStrategy="preserve" translatable="false">TU_API_KEY</string>
   ```
4. Ejecuta la aplicación en un dispositivo o emulador Android y acepta el permiso de ubicación.
   - En el emulador, fija una ubicación en *Extended controls → Location*.

## Estructura

```
app/src/main/
├── AndroidManifest.xml        # permisos + meta-data de la API Key
├── java/.../MainActivity.java # mapa, GPS, permisos, threads, marcadores, foto
└── res/
    ├── layout/activity_main.xml       # MapView + panel de controles
    └── values/google_maps_api.xml     # API Key
```

## Subir a GitHub

```
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/USERNAME/SimpleGPSMapApp.git
git push -u origin main
```
