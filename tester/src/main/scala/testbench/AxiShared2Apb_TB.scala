package testbench

import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.apb._
import spinal.lib.bus.amba4.axi._
import spinal.lib.bus.misc.SizeMapping
import spinal.lib.com.uart.{Apb3UartCtrl, Uart, UartCtrlGenerics, UartCtrlMemoryMappedConfig}
import spinal.lib.io.TriStateArray

import scala.collection.mutable.ListBuffer


case class AxiShared2ApbConfig(
  axiConfig  : Axi4Config,
  enableUART : Boolean = false,
  enableGPIO : Boolean = false,
  nbrGPIO    : Int     = 32
)


object AxiShared2Apb_TB{
  def defaultConfig = {
    AxiShared2ApbConfig(
      axiConfig = Axi4Config(addressWidth = 32,
        dataWidth    = 32,
        idWidth      = 12,
        useId        = true,
        useRegion    = false,
        useBurst     = true,
        useLock      = false,
        useCache     = false,
        useSize      = true,
        useQos       = false,
        useLen       = true,
        useLast      = true,
        useResp      = true,
        useProt      = false,
        useStrb      = true)
    )
  }
}


class AxiShared2Apb_TB(val config: AxiShared2ApbConfig)(apbSlaves: (() => ApbCryptoComponent)*) extends Component {

  val io = new Bundle{
    val axiShared = slave(Axi4Shared(config.axiConfig))
    val gpioA     = if(config.enableGPIO) master(TriStateArray(config.nbrGPIO bits)) else null
    val uart      = if(config.enableUART) master(Uart()) else null
  }

  val apbBridge = Axi4SharedToApb3Bridge(
    addressWidth = 32,
    dataWidth    = 32,
    idWidth      = config.axiConfig.idWidth
  )

  apbBridge.io.axi <> io.axiShared

  // Instantiate all slaves
  val cores = apbSlaves.map(_())

  val gpioACtrl = if(config.enableGPIO) Apb3Gpio(
    gpioWidth = config.nbrGPIO,
    withReadSync = true
  ) else null


  val uartCtrlConfig = UartCtrlMemoryMappedConfig(
    uartCtrlConfig = UartCtrlGenerics(
      dataWidthMax      = 8,
      clockDividerWidth = 20,
      preSamplingSize   = 1,
      samplingSize      = 5,
      postSamplingSize  = 2
    ),
    txFifoDepth = 16,
    rxFifoDepth = 16
  )
  val uartCtrl = if(config.enableUART) Apb3UartCtrl(uartCtrlConfig) else null

  val preSlave = new ListBuffer[(Apb3, SizeMapping)]()
  if(config.enableGPIO){
    preSlave += gpioACtrl.io.apb -> SizeMapping(0x0000, 1 kB)
  }

  if(config.enableUART){
    preSlave += uartCtrl.io.apb -> SizeMapping(0x1000, 1 kB)
  }

  val apbDecoder = Apb3Decoder(
    master  = apbBridge.io.apb,
    slaves  = preSlave.toList :::
      cores.zipWithIndex.map{case (core, index) =>
        core.bus -> SizeMapping((0x1000 * preSlave.length) + (index * 0x1000 ), 1 kB)
      }.toList
  )

  if(config.enableGPIO){
    io.gpioA <> gpioACtrl.io.gpio
  }

  if(config.enableUART) io.uart <> uartCtrl.io.uart
}


