# 👟 Step Injector

Inyecta pasos en **Google Fit** / **Health Connect** sin mover el teléfono.

---

## ✨ Características

| Función | Descripción |
|---|---|
| **Tiempo real** | Inyecta los pasos gradualmente, lote a lote, durante el tiempo que tú elijas |
| **Instantáneo** | Registra todos los pasos como datos históricos de los últimos N minutos (inmediato) |
| **Variación natural** | Añade ±30% de variación aleatoria para simular una caminata real |
| **Preajustes** | Botones rápidos para 2 000, 5 000 y 10 000 pasos |

---

## 📋 Requisitos

- Android **9.0+** (API 26)
- **Health Connect** instalado ([descargar en Play Store](https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata))  
  — En Android 14+ ya viene incorporado, no hace falta instalar nada.
- Los datos de Health Connect se sincronizan automáticamente con **Google Fit** y **Google Salud**.

---

## 🔨 Compilar e instalar

### Opción A — Android Studio (recomendado)

1. Instala [Android Studio](https://developer.android.com/studio) (versión Hedgehog o superior).
2. Abre este proyecto: **File → Open** → selecciona la carpeta `StepInjector`.
3. Deja que Gradle sincronice las dependencias (requiere Internet).
4. Conecta tu móvil en modo depuración USB (o usa el emulador).
5. Pulsa ▶ **Run** (`Shift+F10`).

### Opción B — línea de comandos

```bash
# En macOS/Linux
chmod +x gradlew
./gradlew assembleDebug

# El APK se genera en:
# app/build/outputs/apk/debug/app-debug.apk
```

Instala el APK:
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## 🚀 Uso

1. Abre la app → acepta los permisos de **Health Connect**.
2. Escribe el número de pasos que quieres añadir.
3. Escribe la duración en minutos (cuánto tiempo quieres que tarde).
4. Elige el modo:
   - **Tiempo real** → la app inyecta un lote cada minuto en segundo plano.
   - **Instantáneo** → todos los pasos se insertan de golpe como pasado.
5. Activa *Variación natural* si quieres que los lotes no sean exactamente iguales.
6. Pulsa **INICIAR INYECCIÓN** y ve la barra de progreso avanzar.

---

## ⚙️ Cómo funciona

### Modo tiempo real
```
Para cada minuto del total:
    pasos_lote = totalPasos / duracionMinutos  (±30% si variación activada)
    startTime  = ahora - (pasos_lote × 0,6 s)   // ~100 pasos/min
    endTime    = ahora
    → inserta StepsRecord en Health Connect
    → espera (duracion_ms / numLotes) ms
```

### Modo instantáneo
```
Divide los pasos en numLotes registros
Cada registro tiene timestamps en el pasado (hace duracionMinutos hasta ahora)
Los inserta todos de una vez vía HealthConnectClient.insertRecords()
```

Los registros no se solapan: cada `startTime` es mayor que el `endTime` anterior.

---

## 🔒 Permisos solicitados

| Permiso | Motivo |
|---|---|
| `health.READ_STEPS` | Leer registros de pasos existentes |
| `health.WRITE_STEPS` | Insertar nuevos registros de pasos |

No se recogen ni envían datos fuera del dispositivo.

---

## ❓ Preguntas frecuentes

**¿Los pasos aparecen en Google Fit?**  
Sí, Health Connect sincroniza automáticamente con Google Fit y la app Google Salud.

**¿Cuánto tarda el modo tiempo real?**  
Exactamente los minutos que configures. 60 min → 60 min de espera real.

**¿Puedo usarlo en segundo plano?**  
Sí, déjalo abierto en segundo plano. Android puede matarlo si hay poca RAM; en ese caso usa el modo Instantáneo.

**¿Funciona sin conexión a Internet?**  
Sí, Health Connect es local. No necesita red.
