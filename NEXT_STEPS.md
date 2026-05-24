# Permission Android Next Steps

## Hecho

- [x] Corregir la desactivación automática del micrófono y el stream: se eliminó `pingInterval` de `WebSocketManager` (OkHttp cerraba la conexión cada 5 s si Qt no respondía al PING a tiempo) y se corrigió el hilo de actualización del foreground service en `PermissionService`.
- [x] Documentar el proceso de desinstalación de la app (Device Admin) en `RUNBOOK.md`.
- [x] Añadir soporte de revocación de Device Admin al script `deploy_android.ps1`.

## Pendientes actuales

- [ ] Ensayar la configuración kiosk/device-owner en el tablet o teléfono real del show.
- [ ] Decidir si el dispositivo de producción debe ir en modo normal o lock-task.
- [ ] Añadir una checklist clara de validación de conexión pre-show.
- [ ] Probar la pérdida de conexión de forma intencional durante ensayo:
  - cerrar Qt
  - cerrar Android
  - desactivar Wi-Fi
  - bloquear y desbloquear Android durante pantalla roja
- [ ] Ajustar resolución y FPS del stream si el render del portátil o la latencia Wi-Fi no son estables.
