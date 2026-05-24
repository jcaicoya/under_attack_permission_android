# Permission Android Runbook

## Deploy

### Build local

1. Abrir este directorio en Android Studio.
2. Comprobar `local.properties` con la ruta correcta al Android SDK.
3. El proyecto fija Gradle al JBR incluido en Android Studio mediante `gradle.properties`.
4. Compilar y ejecutar en dispositivo físico Android.

No se recomienda usar emulador para validar cámara, micrófono, lock screen o foreground service.

## Arranque y conexión

### Flujo preferente con orchestrator

1. Conectar el dispositivo Android por USB o WiFi ADB.
2. En Orchestrator -> `CONFIGURAR -> ADB`, pulsar `Detectar`.
3. En `ENSAYO -> Apps Android`, lanzar `Companion`.
4. El orchestrator ejecuta `adb reverse tcp:8765 tcp:8765` y lanza la app.
5. Arrancar `permission_qt`.
6. Android conecta a `localhost:8765`.

### Flujo alternativo sin orchestrator

1. Arrancar `permission_qt` en el portátil.
2. Poner portátil y Android en la misma red Wi-Fi.
3. Android intenta primero `localhost:8765`.
4. Si falla, escucha el beacon UDP y conecta automáticamente.
5. Si también falla, introducir IP del portátil manualmente desde la UI desconectada.

## Manejo de la aplicación

- En desconectado, tocar el estado para abrir la conexión manual por IP.
- En conectado, el control lo lleva la app Qt.
- El operador/actor no debería tener controles de ensayo visibles.

## Desinstalar la app

La app registra un Device Admin, lo que bloquea la desinstalación directa. Hay que revocar ese permiso primero.

### Opción A — por script (requiere APK de release en `dist_android/`)

```powershell
.\deploy_android.ps1 -Action uninstall -App permission_android
```

El script intenta revocar el admin vía ADB. Si falla (dispositivo no provisionado como device owner), abre los ajustes de seguridad en el dispositivo y espera confirmación manual.

### Opción B — manualmente en el dispositivo (Samsung One UI en español)

1. **Ajustes → Datos biométricos y seguridad → Otros ajustes de seguridad → Administradores de dispositivos**
2. Tocar **CuarzoPolar** y elegir **Desactivar**.
3. Desinstalar desde **Ajustes → Aplicaciones → Permission → Desinstalar**, o vía ADB:
   ```powershell
   & "C:\Users\caico\AppData\Local\Android\Sdk\platform-tools\adb.exe" uninstall com.cuarzopolar.permission
   ```

### Nota sobre firma

El script `deploy_android.ps1 -Action install` instala el APK de release (firmado con clave de producción). Si se instala un APK debug desde Android Studio sobre una instalación release, Android rechazará la instalación por conflicto de firma — hay que desinstalar primero.

## Kiosk / Device Owner

Para un comportamiento de kiosk real durante `BLOCK`, el dispositivo debe provisionarse como device owner.

Ejemplo típico en dispositivo recién reseteado:

```powershell
adb install app\build\outputs\apk\debug\app-debug.apk
adb shell dpm set-device-owner com.cuarzopolar.permission/.PermissionDeviceAdminReceiver
```

Si Android lo rechaza por estar ya provisionado o con cuentas, hay que resetear de fábrica y repetir antes en el proceso de setup.

## Consideraciones operativas

- Si se cae el WebSocket, Android debe parar micrófono, streaming, pantalla roja y volver a modo normal.
- Si la app se cierra o se swipea, debe desconectar WebSocket y parar el servicio foreground.
- Probar siempre comportamiento real en dispositivo físico de show.
