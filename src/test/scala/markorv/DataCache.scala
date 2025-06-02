package markorv.cache

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

import markorv.config._

class DataCacheSpec extends AnyFreeSpec with Matchers {
    "DataCache should handle read and write operations correctly" in {
        implicit val config: CacheConfig = CacheConfig(64, 4, 4, 5)

        val numRandomTests = 65535
        val timeout = 65535 * 16
        val memDelayMax = 4

        simulate(new DataCache()) { dut =>
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)
            dut.clock.step()

            var cacheContents = scala.collection.mutable.Map[BigInt, BigInt]()
            val random = new Random
            var completedTests = 0
            var cycles = 0

            // Test a mix of reads and writes
            while (completedTests < numRandomTests && cycles < timeout) {
                // Decide if this is a read or write operation
                val isWrite = random.nextBoolean()

                // Generate random address but ensure some reuse to create cache hits
                val address = if (cacheContents.nonEmpty && random.nextBoolean()) {
                    cacheContents.keys.toSeq(random.nextInt(cacheContents.size))
                } else {
                    val blockMask = (1 << config.offsetBits) - 1
                    BigInt(random.nextLong() & 0x7FFFFFFFFFFFFFFFL & ~blockMask)
                }

                if (isWrite) {
                    // Write operation test
                    val writeData = BigInt(config.dataBytes * 8, random)
                    cacheContents += (address -> writeData)

                    // Start the write request
                    dut.io.inWriteReq.valid.poke(true.B)
                    dut.io.inWriteReq.bits.addr.poke(address.U)
                    dut.io.inWriteReq.bits.data.poke(writeData.U)

                    // Wait for the cache to accept our write request
                    var requestCycles = 0
                    while (!dut.io.inWriteReq.ready.peek().litToBoolean && requestCycles < timeout) {
                        dut.clock.step()
                        cycles += 1
                        requestCycles += 1
                    }

                    assert(requestCycles < timeout, s"Timeout waiting for cache to accept write request for address ${address}")

                    // Cache has accepted our request
                    dut.clock.step()
                    cycles += 1

                    // Clear the request
                    dut.io.inWriteReq.valid.poke(false.B)

                    // Handle possible memory operations (read for write-allocate or write-back)
                    var writeCompleted = false
                    var memoryReadHandled = false
                    var memoryWriteHandled = false
                    var testCycles = 0

                    while (!writeCompleted && testCycles < timeout) {
                        // Handle memory read request (for write-allocate on miss)
                        if (dut.io.outReadReq.valid.peek().litToBoolean && !memoryReadHandled) {
                            dut.io.outReadReq.ready.poke(true.B)

                            val delay = random.between(1, memDelayMax+1)
                            dut.clock.step(delay)
                            cycles += delay
                            testCycles += delay

                            // Provide data from memory
                            val memData = if (cacheContents.contains(address)) {
                                cacheContents(address)
                            } else {
                                BigInt(config.dataBytes * 8, random)
                            }

                            dut.io.outReadData.valid.poke(true.B)
                            dut.io.outReadData.bits.poke(memData.U)
                            memoryReadHandled = true

                            dut.clock.step()
                            cycles += 1
                            testCycles += 1

                            dut.io.outReadData.valid.poke(false.B)
                        }

                        // Handle memory write request (for write-back of dirty line)
                        if (dut.io.outWriteReq.valid.peek().litToBoolean && !memoryWriteHandled) {
                            val writeBackAddr = dut.io.outWriteReq.bits.addr.peek().litValue
                            val writeBackData = dut.io.outWriteReq.bits.data.peek().litValue

                            dut.io.outWriteReq.ready.poke(true.B)

                            val delay = random.between(1, memDelayMax+1)
                            dut.clock.step(delay)
                            cycles += delay
                            testCycles += delay

                            // Acknowledge the write
                            dut.io.outWriteResp.valid.poke(true.B)
                            dut.io.outWriteResp.bits.poke(true.B)
                            memoryWriteHandled = true

                            dut.clock.step()
                            cycles += 1
                            testCycles += 1

                            dut.io.outWriteResp.valid.poke(false.B)
                        }

                        // Check if we got a write response from the cache
                        if (dut.io.inWriteResp.valid.peek().litToBoolean) {
                            dut.io.inWriteResp.bits.expect(true.B)
                            writeCompleted = true
                        }

                        if (!writeCompleted) {
                            dut.clock.step()
                            cycles += 1
                            testCycles += 1
                        }
                    }

                    assert(writeCompleted, s"Timeout waiting for cache write response for address ${address}")
                } else {
                    // Read operation test
                    val expectedData = if (cacheContents.contains(address)) {
                        cacheContents(address)
                    } else {
                        val data = BigInt(config.dataBytes * 8, random)
                        cacheContents += (address -> data)
                        data
                    }

                    // Start the read request
                    dut.io.inReadReq.valid.poke(true.B)
                    dut.io.inReadReq.bits.poke(address.U)

                    // Wait for the cache to accept our request
                    var requestCycles = 0
                    while (!dut.io.inReadReq.ready.peek().litToBoolean && requestCycles < timeout) {
                        dut.clock.step()
                        cycles += 1
                        requestCycles += 1
                    }

                    assert(requestCycles < timeout, s"Timeout waiting for cache to accept read request for address ${address}")

                    // Cache has accepted our request
                    dut.clock.step()
                    cycles += 1

                    // Clear the request
                    dut.io.inReadReq.valid.poke(false.B)

                    // If the cache misses, we need to handle the memory request
                    var memoryRequestHandled = false
                    var readResponseReceived = false
                    var testCycles = 0

                    while (!readResponseReceived && testCycles < timeout) {
                        // If there's a memory request, handle it
                        if (dut.io.outReadReq.valid.peek().litToBoolean && !memoryRequestHandled) {
                            // Verify it's asking for the correct address
                            dut.io.outReadReq.bits.expect(address.U)
                            dut.io.outReadReq.ready.poke(true.B)

                            val delay = random.between(1, memDelayMax+1)
                            dut.clock.step(delay)
                            cycles += delay
                            testCycles += delay

                            dut.io.outReadData.valid.poke(true.B)
                            dut.io.outReadData.bits.poke(expectedData.U)
                            memoryRequestHandled = true

                            // Check if we already have a response
                            if (dut.io.inReadData.valid.peek().litToBoolean) {
                                dut.io.inReadData.bits.expect(expectedData.U)
                                readResponseReceived = true
                            }

                            dut.clock.step()
                            cycles += 1
                            testCycles += 1

                            dut.io.outReadData.valid.poke(false.B)
                        }

                        // Check if we received a response from the cache
                        if (dut.io.inReadData.valid.peek().litToBoolean) {
                            dut.io.inReadData.bits.expect(expectedData.U)
                            readResponseReceived = true
                        }

                        if (!readResponseReceived) {
                            dut.clock.step()
                            cycles += 1
                            testCycles += 1
                        }
                    }

                    assert(readResponseReceived, s"Timeout waiting for cache response for address ${address}")
                }

                completedTests += 1

                // Wait a cycle between tests
                dut.clock.step()
                cycles += 1
            }

            assert(completedTests == numRandomTests, s"Only completed ${completedTests} of ${numRandomTests} tests before timeout")

            // Test invalidation
            dut.io.invalidate.poke(true.B)
            dut.clock.step()
            cycles += 1
            dut.io.invalidate.poke(false.B)

            // Wait for invalidation to complete
            var invalidationCycles = 0
            while (!dut.io.invalidateOutfire.peek().litToBoolean && invalidationCycles < timeout) {
                dut.clock.step()
                cycles += 1
                invalidationCycles += 1
            }

            assert(invalidationCycles < timeout, "Timeout waiting for cache invalidation to complete")

            // After invalidation, test dirty bit behavior
            // First, write to an address
            val testAddr = BigInt(random.nextLong() & 0x7FFFFFFFFFFFFFFFL & ~((1 << config.offsetBits) - 1))
            val testData = BigInt(config.dataBytes * 8, random)

            // Write to cache
            dut.io.inWriteReq.valid.poke(true.B)
            dut.io.inWriteReq.bits.addr.poke(testAddr.U)
            dut.io.inWriteReq.bits.data.poke(testData.U)

            // Wait for ready
            while (!dut.io.inWriteReq.ready.peek().litToBoolean) {
                dut.clock.step()
            }

            dut.clock.step()
            dut.io.inWriteReq.valid.poke(false.B)

            // Wait for write to complete, handling any memory operations
            var writeCompleted = false
            var handledMemOps = false

            while (!writeCompleted) {
                // Handle any memory operations
                if (dut.io.outReadReq.valid.peek().litToBoolean && !handledMemOps) {
                    dut.io.outReadReq.ready.poke(true.B)
                    dut.clock.step()

                    dut.io.outReadData.valid.poke(true.B)
                    dut.io.outReadData.bits.poke(0.U) // Doesn't matter what we return
                    dut.clock.step()

                    dut.io.outReadData.valid.poke(false.B)
                    handledMemOps = true
                }

                if (dut.io.outWriteReq.valid.peek().litToBoolean && !handledMemOps) {
                    dut.io.outWriteReq.ready.poke(true.B)
                    dut.clock.step()

                    dut.io.outWriteResp.valid.poke(true.B)
                    dut.io.outWriteResp.bits.poke(true.B)
                    dut.clock.step()

                    dut.io.outWriteResp.valid.poke(false.B)
                    handledMemOps = true
                }

                if (dut.io.inWriteResp.valid.peek().litToBoolean) {
                    writeCompleted = true
                }

                if (!writeCompleted) {
                    dut.clock.step()
                }
            }

            // Now read the same address - should be a hit with the new data
            dut.io.inReadReq.valid.poke(true.B)
            dut.io.inReadReq.bits.poke(testAddr.U)

            while (!dut.io.inReadReq.ready.peek().litToBoolean) {
                dut.clock.step()
            }

            dut.clock.step()
            dut.io.inReadReq.valid.poke(false.B)

            // Wait for read response - should be a hit
            var readResponse = false
            var memoryRead = false

            while (!readResponse) {
                if (dut.io.outReadReq.valid.peek().litToBoolean) {
                    memoryRead = true  // We shouldn't see this for a hit
                }

                if (dut.io.inReadData.valid.peek().litToBoolean) {
                    dut.io.inReadData.bits.expect(testData.U)
                    readResponse = true
                }

                if (!readResponse) {
                    dut.clock.step()
                }
            }

            assert(!memoryRead, "Expected cache hit but saw memory read request")

            println(s"Completed ${completedTests} random read/write tests in ${cycles} cycles")
        }
    }

    "DataCache should correctly handle sequential writes and reads to same address" in {
        implicit val config: CacheConfig = CacheConfig(64, 2, 4, 5)

        simulate(new DataCache()) { dut =>
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)
            dut.clock.step()

            val testAddr = BigInt("1000", 16) << config.offsetBits
            val testData1 = BigInt("deadbeef01234567", 16)
            val testData2 = BigInt("fedcba9876543210", 16)

            // Write first value
            dut.io.inWriteReq.valid.poke(true.B)
            dut.io.inWriteReq.bits.addr.poke(testAddr.U)
            dut.io.inWriteReq.bits.data.poke(testData1.U)

            while (!dut.io.inWriteReq.ready.peek().litToBoolean) {
                dut.clock.step()
            }

            dut.clock.step()
            dut.io.inWriteReq.valid.poke(false.B)

            // Handle memory operations and wait for write to complete
            var writeCompleted = false

            while (!writeCompleted) {
                // Handle memory read
                if (dut.io.outReadReq.valid.peek().litToBoolean) {
                    dut.io.outReadReq.ready.poke(true.B)
                    dut.clock.step()

                    dut.io.outReadData.valid.poke(true.B)
                    dut.io.outReadData.bits.poke(0.U)
                    dut.clock.step()

                    dut.io.outReadData.valid.poke(false.B)
                }

                // Handle memory write
                if (dut.io.outWriteReq.valid.peek().litToBoolean) {
                    dut.io.outWriteReq.ready.poke(true.B)
                    dut.clock.step()

                    dut.io.outWriteResp.valid.poke(true.B)
                    dut.io.outWriteResp.bits.poke(true.B)
                    dut.clock.step()

                    dut.io.outWriteResp.valid.poke(false.B)
                }

                if (dut.io.inWriteResp.valid.peek().litToBoolean) {
                    writeCompleted = true
                }

                if (!writeCompleted) {
                    dut.clock.step()
                }
            }

            // Read back the value
            dut.io.inReadReq.valid.poke(true.B)
            dut.io.inReadReq.bits.poke(testAddr.U)

            while (!dut.io.inReadReq.ready.peek().litToBoolean) {
                dut.clock.step()
            }

            dut.clock.step()
            dut.io.inReadReq.valid.poke(false.B)

            var readCompleted = false

            while (!readCompleted) {
                if (dut.io.inReadData.valid.peek().litToBoolean) {
                    dut.io.inReadData.bits.expect(testData1.U)
                    readCompleted = true
                }

                if (!readCompleted) {
                    dut.clock.step()
                }
            }

            // Write second value to same address
            dut.io.inWriteReq.valid.poke(true.B)
            dut.io.inWriteReq.bits.addr.poke(testAddr.U)
            dut.io.inWriteReq.bits.data.poke(testData2.U)

            while (!dut.io.inWriteReq.ready.peek().litToBoolean) {
                dut.clock.step()
            }

            dut.clock.step()
            dut.io.inWriteReq.valid.poke(false.B)

            // Wait for write to complete
            writeCompleted = false

            while (!writeCompleted) {
                if (dut.io.inWriteResp.valid.peek().litToBoolean) {
                    writeCompleted = true
                }

                if (!writeCompleted) {
                    dut.clock.step()
                }
            }

            // Read back updated value
            dut.io.inReadReq.valid.poke(true.B)
            dut.io.inReadReq.bits.poke(testAddr.U)

            while (!dut.io.inReadReq.ready.peek().litToBoolean) {
                dut.clock.step()
            }

            dut.clock.step()
            dut.io.inReadReq.valid.poke(false.B)

            readCompleted = false

            while (!readCompleted) {
                if (dut.io.inReadData.valid.peek().litToBoolean) {
                    dut.io.inReadData.bits.expect(testData2.U)
                    readCompleted = true
                }

                if (!readCompleted) {
                    dut.clock.step()
                }
            }

            println("Sequential write-read-write-read test completed successfully")
        }
    }
}
