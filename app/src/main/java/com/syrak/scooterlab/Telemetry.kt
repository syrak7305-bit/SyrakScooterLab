package com.syrak.scooterlab.feature.telemetry

/**

* Telemetriedaten des Scooters.

* Standardwerte bedeuten: Es liegen noch keine Messwerte vor.
  */
  data class Telemetry(
  val speedKmh: Float = 0f,
  val voltageV: Float = 0f,
  val currentA: Float = 0f,
  val batteryPercent: Int = 0,
  val motorTempC: Float = 0f,
  val batteryTempC: Float = 0f,
  val cellMinV: Float = 0f,
  val cellMaxV: Float = 0f,
  val odometerKm: Float = 0f,
  ) {
  val powerW: Float
  get() = voltageV * currentA
  
  val cellDeltaMv: Float
  get() = (cellMaxV - cellMinV).coerceAtLeast(0f) * 1000f
  
  val estimatedRangeKm: Float
  get() = 0f
  
  val health: BatteryHealth
  get() = when {
  batteryPercent <= 0 -> BatteryHealth.UNKNOWN
  batteryPercent < 10 -> BatteryHealth.CRITICAL
  batteryPercent < 30 -> BatteryHealth.DEGRADED
  else -> BatteryHealth.NOMINAL
  }
  
  companion object {
  val Empty = Telemetry()
  }
  }

enum class BatteryHealth {
UNKNOWN,
NOMINAL,
DEGRADED,
CRITICAL
}