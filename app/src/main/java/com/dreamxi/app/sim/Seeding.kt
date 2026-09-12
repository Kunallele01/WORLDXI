package com.dreamxi.app.sim

/**
 * Scrambles a seed before it reaches a random number generator.
 *
 * WHY THIS EXISTS. Seeds in this app are not arbitrary — they are run ids and
 * row ids, so consecutive runs get consecutive seeds. Kotlin's generator is
 * correlated across nearby seeds: two runs seeded 41 and 42 do not produce
 * independent-looking streams. Feeding those in directly put a measurable
 * thumb on the takeover draw: over 6,000 sequential seeds one of three clubs
 * came out 35.5% of the time against an expected 33.3%, which is about 3.6
 * standard deviations — far too large to be luck, and completely invisible in
 * play. With the seed mixed the same 6,000 seeds give 32.9 / 34.1 / 33.1.
 *
 * This is the SplitMix64 finalizer: three xor-shift-multiply rounds that
 * avalanche a small change across all 64 bits, so 41 and 42 become unrelated
 * starting points. It is a bijection, so no seed is lost and none is shared,
 * and it is cheap enough to apply at every call site that seeds anything.
 */
fun mixSeed(seed: Long): Long {
    var z = seed + -0x61C8864680B583EBL
    z = (z xor (z ushr 30)) * -0x40A7B892E31B1A47L
    z = (z xor (z ushr 27)) * -0x6B2FB644ECCEEE15L
    return z xor (z ushr 31)
}
