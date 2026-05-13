package com.example.marketplace.sample

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

class KotestSampleSpec : BehaviorSpec({
    given("a list of integers") {
        val numbers = listOf(1, 2, 3, 4, 5)

        `when`("summed") {
            then("returns 15") {
                numbers.sum() shouldBe 15
            }
        }

        `when`("filtered for even values") {
            val evens = numbers.filter { it % 2 == 0 }
            then("contains only 2 and 4") {
                evens shouldHaveSize 2
                evens shouldBe listOf(2, 4)
            }
        }
    }

    given("property-based assertion") {
        `when`("doubling any non-negative int") {
            then("equals adding to itself") {
                checkAll(Arb.int(0..1_000_000)) { n ->
                    (n * 2) shouldBe n + n
                }
            }
        }
    }
})
