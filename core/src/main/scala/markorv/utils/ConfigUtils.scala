package markorv.utils

import chisel3._
import chisel3.util._

import markorv.config._
import markorv.cache.CacheType

object ConfigUtils {
    def getCacheIoConfig(cacheConfig: CacheConfig, cacheType: CacheType.Value): IOConfig = {
            val writeEnable = cacheType match {
                case CacheType.Icache => false
                case CacheType.Dcache => true
            }

            IOConfig(
                read       = true,
                write      = writeEnable,
                atomicity  = false,
                addrWidth  = 64,
                dataWidth  = cacheConfig.dataBytes * 8
            )
    }
}
