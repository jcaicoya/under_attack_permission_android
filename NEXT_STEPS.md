# Permission Android Next Steps

## Hecho

- [x] Corregir la desactivación automática del micrófono y el stream (eliminado `pingInterval` de `WebSocketManager`).
- [x] Documentar el proceso de desinstalación (Device Admin) en `RUNBOOK.md`.
- [x] Añadir soporte de revocación de Device Admin al script `deploy_android.ps1`.
- [x] Migrar conexión de Qt (`permission_qt`, puerto 8765) a polar_shield backend (puerto 3002).
- [x] Conexión automática por beacon UDP: ya no requiere ADB tunnel ni configuración manual en el primer arranque.
- [x] `PermissionService`: arranque inteligente — intenta IP guardada directamente; si no hay IP guardada, lanza UDP discovery inmediatamente.
- [x] `startDiscovery()`: guarda la IP descubierta para que los arranques siguientes sean instantáneos.

## Pendientes actuales

- [ ] Migrar a Socket.io client (`io.socket:socket.io-client`) para unificar protocolo y puerto con el resto de la app (actualmente WebSocket crudo en puerto 3002). Hacerlo cuando el flujo del show esté estabilizado.
- [ ] Probar la pérdida de conexión de forma intencional durante ensayo (cerrar backend, desactivar WiFi, bloquear durante pantalla roja).
- [ ] Ajustar resolución y FPS del stream si la latencia WiFi no es estable.
- [ ] Ensayar configuración kiosk/device-owner en el dispositivo real del show.
