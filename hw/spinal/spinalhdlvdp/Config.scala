package spinalhdlvdp

import spinal.core._
import spinal.core.sim._

object Config {
  def spinal = SpinalConfig(
    targetDirectory = "hw/gen",
    defaultConfigForClockDomains = ClockDomainConfig(
      resetActiveLevel = HIGH
    ),
    onlyStdLogicVectorAtTopLevelIo = false
  )

  // Sim-infra (owner directive, global): Verilator multithreading. `--threads N` makes the
  // Verilated model execute multithreaded. Owner set 19 (of 20 host cores; 1 left free).
  // Single source of truth — Config.sim applies it; headless sims reference Config.simThreads.
  // (JVM heap is set globally to 16G via the project `.jvmopts`.)
  def simThreads: Int = math.min(19, math.max(1, Runtime.getRuntime.availableProcessors()))
  def sim = SimConfig.withConfig(spinal).withFstWave
    .addSimulatorFlag(s"--threads $simThreads")
}
