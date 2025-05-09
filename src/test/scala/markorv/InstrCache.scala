package markorv.cache

import chisel3._
import chisel3.util._
import chisel3.simulator.EphemeralSimulator._
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import scala.util.Random

import markorv.config._

class InstrCacheSpec extends AnyFreeSpec with Matchers {
    "InstrCache should handle read requests correctly" in {
        implicit val config: CacheConfig = CacheConfig(64, 4, 4, 5)

        val numRandomTests = 65535
        val timeout = 65535 * 16
        val memDelayMax = 4

        simulate(new InstrCache()) { dut =>
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)
            dut.clock.step()

            var cacheContents = scala.collection.mutable.Map[BigInt, BigInt]()

            val random = new Random
            var completedTests = 0
            var cycles = 0

            while (completedTests < numRandomTests && cycles < timeout) {
                // Generate random address but ensure some reuse to create cache hits
                val address = if (cacheContents.nonEmpty && random.nextBoolean()) {
                    cacheContents.keys.toSeq(random.nextInt(cacheContents.size))
                } else {
                    val blockMask = (1 << config.offsetBits) - 1
                    BigInt(random.nextLong() & 0x7FFFFFFFFFFFFFFFL & ~blockMask)
                }

                val expectedData = if (cacheContents.contains(address)) {
                    cacheContents(address)
                } else {
                    val data = BigInt(64, random)
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

                // Now we're either in statRead or will go to statReplace
                dut.io.inReadReq.valid.poke(false.B)
                dut.io.inReadReq.bits.poke(0.U)

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
                        if (dut.io.inReadData.valid.peek().litToBoolean) {
                            dut.io.inReadData.bits.expect(expectedData.U)
                            readResponseReceived = true
                        }

                        dut.clock.step()
                        cycles += 1
                        testCycles += 1
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

                // Clean up signals after a successful test
                dut.io.outReadData.valid.poke(false.B)

                assert(readResponseReceived, s"Timeout waiting for cache response for address ${address}")
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

            // After invalidation, all previously cached addresses should miss
            // Test a few previously cached addresses
            var postInvalidationTests = 0
            val addressesToTest = cacheContents.keys.take(5).toSeq

            for (address <- addressesToTest) {
                val expectedData = cacheContents(address)

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

                dut.clock.step()
                cycles += 1
                dut.io.inReadReq.valid.poke(false.B)

                // After invalidation, we expect a cache miss, so there should be a memory request
                var memoryRequestSeen = false
                var testCycles = 0

                while (!memoryRequestSeen && testCycles < timeout) {
                    if (dut.io.outReadReq.valid.peek().litToBoolean) {
                        dut.io.outReadReq.bits.expect(address.U)
                        memoryRequestSeen = true
                    }

                    if (!memoryRequestSeen) {
                        dut.clock.step()
                        cycles += 1
                        testCycles += 1
                    }
                }

                assert(memoryRequestSeen, s"Expected memory request after invalidation for address ${address}")

                // Provide memory response and check cache response
                dut.io.outReadData.valid.poke(true.B)
                dut.io.outReadData.bits.poke(expectedData.U)

                var readResponseReceived = false
                testCycles = 0

                while (!readResponseReceived && testCycles < timeout) {
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

                dut.clock.step()
                cycles += 1
                testCycles += 1

                dut.io.outReadData.valid.poke(false.B)
                assert(readResponseReceived, s"Timeout waiting for cache response after invalidation for address ${address}")
                postInvalidationTests += 1

                dut.clock.step()
                cycles += 1
            }

            println(s"Completed ${completedTests} random tests and ${postInvalidationTests} post-invalidation tests in ${cycles} cycles")
        }
    }
}
