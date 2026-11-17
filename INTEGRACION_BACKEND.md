# Conexión con Amauta

La variante `debug` usa `http://10.0.2.2:8080/` para el emulador Android. Para instalarla en un celular conectado a la misma red que la computadora, compila con la IP local de la computadora:

```powershell
.\gradlew.bat :app:assembleDebug -PamautaDebugApiBaseUrl=http://192.168.1.50:8080/
```

Sustituye `192.168.1.50` por la IP local real. El backend debe escuchar en una interfaz accesible desde el celular y el firewall debe permitir el puerto 8080. El tráfico HTTP local está permitido solo en `debug`; la variante `release` requiere configurar un servidor HTTPS real antes de distribuirla.

El cliente usa `GET /api/usuarios/csrf` antes de `POST /api/usuarios/login` y `POST /api/usuarios/logout`. Conserva la cookie de sesión solo en memoria del proceso, envía `X-XSRF-TOKEN` en las peticiones que modifican datos y consulta `GET /api/usuarios/me` al recrear la actividad mientras el proceso siga vivo. Si Android termina el proceso, se vuelve a pedir el inicio de sesión. No se guardan contraseñas ni cookies en el almacenamiento local.

El dashboard consulta `GET /api/habitaciones`, `GET /api/huespedes`, `GET /reservas` y, para roles de recepción o administración, `GET /api/registros`. Si una consulta falla, su cifra queda como «Sin datos» y se muestra un aviso. El total de huéspedes alojados solo está disponible para los roles autorizados por el backend.

Para validar la integración en un dispositivo, inicia Spring Boot en el puerto 8080, entra con una cuenta existente y comprueba Inicio, Habitaciones, Reservas, Actualizar y Cerrar sesión. Sin un backend accesible y credenciales reales, la compilación no demuestra el funcionamiento del login ni de las consultas.

## Interfaz Android

La interfaz usa el logo oficial de `C:\amauta-frontend\public\logoama.png`, una navegación inferior con Inicio, Habitaciones, Huéspedes y Más (Reservas/Recepción), y tarjetas de dashboard de dos columnas en celular o tres en tablet. Las pantallas de consulta presentan mensajes de carga, error y ausencia de registros.

Con el backend activo y una cuenta autorizada aún deben probarse en emulador o dispositivo: inicio y cierre de sesión, los siete contadores del dashboard, los filtros y detalle de habitaciones, la búsqueda de huéspedes, la lista de reservas y la navegación entre las cuatro secciones. Las acciones de estadías y edición de huéspedes también necesitan prueba con datos adecuados y el rol correspondiente.

## CRUD de huéspedes

Android y Angular usan `GET`, `POST`, `PUT /api/huespedes` y `DELETE /api/huespedes/{id}` del mismo backend. El contrato solo contiene `nombre`, `apellido`, `dni`, `procedencia`, `pais`, `profesion` y `telefono`; los tres primeros son obligatorios. Correo, dirección y nacionalidad no existen como campos separados. El backend exige sesión para todas estas rutas y CSRF para las mutaciones. No define una restricción de rol adicional para huéspedes; la eliminación puede ser rechazada por el servidor o la base de datos si existen relaciones.

Tras guardar o eliminar, la app vuelve a solicitar la lista al backend. Si esa consulta falla, muestra un aviso y no reconstruye una lista local. Para comprobar la sincronización con la web, se necesita iniciar sesión con una cuenta existente, crear o editar un huésped autorizado y actualizar la página web. No se deben crear datos permanentes solo para una prueba sin autorización.

## Reservas

Android usa `GET/POST /reservas` y `GET/PUT /reservas/{id}`. Cancelar cambia el estado a `CANCELADO` mediante PUT y conserva el registro. POST y PUT del backend validan huésped, habitación, fechas, estado y cruces de intervalos bajo bloqueo de la habitación; las mutaciones requieren rol de recepción o administración. No hay endpoint de disponibilidad previa por fechas: el formulario consulta habitaciones y reservas para mostrar opciones conservadoras, y el servidor toma la decisión final al guardar. Una habitación ocupada actualmente puede no mostrarse aunque quede libre en una fecha futura.

**Reinicia la instancia Spring Boot que escucha en 8080** para activar las validaciones nuevas. Android bloquea POST/PUT hasta detectar por una consulta de solo lectura que el controlador actualizado está en ejecución. La compilación Maven y las pruebas usaron H2 temporal; no alteraron PostgreSQL. Con una cuenta real, quedan por probar en emulador la selección, creación, edición, cancelación, permisos y sincronización con Angular. No se ejecutaron mutaciones sobre reservas reales automáticamente.

## Recepción

La pestaña Más abre Recepción para roles autorizados. Consulta `GET /api/registros`, `GET /reservas` y `GET /api/habitaciones` para alojados, entradas y salidas previstas, habitaciones ocupadas e historial. El detalle consulta `GET /api/check-out/estadias/{id}/movimientos`. El ingreso usa el nuevo `POST /api/recepcion/check-in` con huésped, habitación, fecha/hora y `reservaId` opcional; el servidor valida capacidad, disponibilidad, huésped activo y reserva vigente en una transacción. Una reserva consumida pasa a `INGRESADO` y ya no admite edición/cancelación. La salida reutiliza `POST /api/check-out/estadias/{id}/confirmar`; el backend deja la habitación pendiente de limpieza. Todas las escrituras envían sesión y CSRF.

**Reinicia Spring Boot en el puerto 8080** para activar el endpoint nuevo. Las pruebas automáticas del backend usan H2 temporal y no comprueban PostgreSQL. En el emulador faltan comprobar con una cuenta real la navegación, permisos, listas, check-in directo y desde reserva, check-out y sincronización con Angular. No se hicieron entradas ni salidas reales como prueba.
