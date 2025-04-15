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
        
        val num_random_tests = 65535
        val timeout = 65535 * 16
        val mem_delay_max = 4
        
        simulate(new InstrCache()) { dut =>
            dut.reset.poke(true.B)
            dut.clock.step()
            dut.reset.poke(false.B)
            dut.clock.step()
            
            var cache_contents = scala.collection.mutable.Map[BigInt, BigInt]()
            
            val random = new Random
            var completed_tests = 0
            var cycles = 0
            
            while (completed_tests < num_random_tests && cycles < timeout) {
                // Generate random address but ensure some reuse to create cache hits
                val address = if (cache_contents.nonEmpty && random.nextBoolean()) {
                    cache_contents.keys.toSeq(random.nextInt(cache_contents.size))
                } else {
                    val block_mask = (1 << config.offset_bits) - 1
                    BigInt(random.nextLong() & 0x7FFFFFFFFFFFFFFFL & ~block_mask)
                }

                val expected_data = if (cache_contents.contains(address)) {
                    cache_contents(address)
                } else {
                    val data = BigInt(64, random)
                    cache_contents += (address -> data)
                    data
                }
                
                // Start the read request
                dut.io.in_read_req.valid.poke(true.B)
                dut.io.in_read_req.bits.poke(address.U)
                
                // Wait for the cache to accept our request
                var requestCycles = 0
                while (!dut.io.in_read_req.ready.peek().litToBoolean && requestCycles < timeout) {
                    dut.clock.step()
                    cycles += 1
                    requestCycles += 1
                }
                
                assert(requestCycles < timeout, s"Timeout waiting for cache to accept read request for address ${address}")
                
                // Cache has accepted our request
                dut.clock.step()
                cycles += 1
                
                // Now we're either in stat_read or will go to stat_replace
                dut.io.in_read_req.valid.poke(false.B)
                dut.io.in_read_req.bits.poke(0.U)
                
                // If the cache misses, we need to handle the memory request
                var memory_request_handled = false
                var read_response_received = false
                var test_cycles = 0
                
                while (!read_response_received && test_cycles < timeout) {
                    // If there's a memory request, handle it
                    if (dut.io.out_read_req.valid.peek().litToBoolean && !memory_request_handled) {
                        // Verify it's asking for the correct address
                        dut.io.out_read_req.bits.expect(address.U)
                        dut.io.out_read_req.ready.poke(true.B)
                        
                        val delay = random.between(1, mem_delay_max+1)
                        dut.clock.step(delay)
                        cycles += delay
                        test_cycles += delay
                        
                        dut.io.out_read_data.valid.poke(true.B)
                        dut.io.out_read_data.bits.poke(expected_data.U)
                        memory_request_handled = true
                        if (dut.io.in_read_data.valid.peek().litToBoolean) {
                            dut.io.in_read_data.bits.expect(expected_data.U)
                            read_response_received = true
                        }

                        dut.clock.step()
                        cycles += 1
                        test_cycles += 1
                    }
                    
                    // Check if we received a response from the cache
                    if (dut.io.in_read_data.valid.peek().litToBoolean) {
                        dut.io.in_read_data.bits.expect(expected_data.U)
                        read_response_received = true
                    }
                    
                    if (!read_response_received) {
                        dut.clock.step()
                        cycles += 1
                        test_cycles += 1
                    }
                }
                
                // Clean up signals after a successful test
                dut.io.out_read_data.valid.poke(false.B)
                
                assert(read_response_received, s"Timeout waiting for cache response for address ${address}")
                completed_tests += 1
                
                // Wait a cycle between tests
                dut.clock.step()
                cycles += 1
            }
            
            assert(completed_tests == num_random_tests, s"Only completed ${completed_tests} of ${num_random_tests} tests before timeout")
            
            // Test invalidation
            dut.io.invalidate.poke(true.B)
            dut.clock.step()
            cycles += 1
            dut.io.invalidate.poke(false.B)
            
            // Wait for invalidation to complete
            var invalidation_cycles = 0
            while (!dut.io.invalidate_outfire.peek().litToBoolean && invalidation_cycles < timeout) {
                dut.clock.step()
                cycles += 1
                invalidation_cycles += 1
            }
            
            assert(invalidation_cycles < timeout, "Timeout waiting for cache invalidation to complete")
            
            // After invalidation, all previously cached addresses should miss
            // Test a few previously cached addresses
            var post_invalidation_tests = 0
            val addresses_to_test = cache_contents.keys.take(5).toSeq
            
            for (address <- addresses_to_test) {
                val expected_data = cache_contents(address)
                
                // Start the read request
                dut.io.in_read_req.valid.poke(true.B)
                dut.io.in_read_req.bits.poke(address.U)
                
                // Wait for the cache to accept our request
                var requestCycles = 0
                while (!dut.io.in_read_req.ready.peek().litToBoolean && requestCycles < timeout) {
                    dut.clock.step()
                    cycles += 1
                    requestCycles += 1
                }
                
                dut.clock.step()
                cycles += 1
                dut.io.in_read_req.valid.poke(false.B)
                
                // After invalidation, we expect a cache miss, so there should be a memory request
                var memory_request_seen = false
                var test_cycles = 0
                
                while (!memory_request_seen && test_cycles < timeout) {
                    if (dut.io.out_read_req.valid.peek().litToBoolean) {
                        dut.io.out_read_req.bits.expect(address.U)
                        memory_request_seen = true
                    }
                    
                    if (!memory_request_seen) {
                        dut.clock.step()
                        cycles += 1
                        test_cycles += 1
                    }
                }
                
                assert(memory_request_seen, s"Expected memory request after invalidation for address ${address}")
                
                // Provide memory response and check cache response
                dut.io.out_read_data.valid.poke(true.B)
                dut.io.out_read_data.bits.poke(expected_data.U)
                
                var read_response_received = false
                test_cycles = 0
                
                while (!read_response_received && test_cycles < timeout) {
                    if (dut.io.in_read_data.valid.peek().litToBoolean) {
                        dut.io.in_read_data.bits.expect(expected_data.U)
                        read_response_received = true
                    }
                    
                    if (!read_response_received) {
                        dut.clock.step()
                        cycles += 1
                        test_cycles += 1
                    }
                }

                dut.clock.step()
                cycles += 1
                test_cycles += 1
                
                dut.io.out_read_data.valid.poke(false.B)
                assert(read_response_received, s"Timeout waiting for cache response after invalidation for address ${address}")
                post_invalidation_tests += 1
                
                dut.clock.step()
                cycles += 1
            }
            
            println(s"Completed ${completed_tests} random tests and ${post_invalidation_tests} post-invalidation tests in ${cycles} cycles")
        }
    }
}
