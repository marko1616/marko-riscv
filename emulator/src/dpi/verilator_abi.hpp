/**
 * @file verilator_abi.hpp
 * @brief Utilities for handling Verilator ABI interactions without bitset
 */

#pragma once

#include <cstdint>
#include <array>
#include <limits>
#include <type_traits>
#include <tuple>
#include <cstring>
#include <bit>

#include "svdpi.h"

/**
 * @brief Field representation for hardware fields
 */
template <typename T, size_t W>
struct Field {
    using value_type = T;
    T value;
    static constexpr size_t bit_width = W;
    operator T() const { return value; }
    Field& operator=(const T& val) { value = val; return *this; }
};

/**
 * @brief Utilities for determining appropriate type based on bit width
 */
template <size_t W>
struct BitUtils {
    static_assert(W > 0, "Bit width must be positive");

    struct RawBits {
        uint8_t data[(W + 7) / 8];
    };

    using type = decltype([]<size_t Width = W>() {
        if constexpr (Width == 1)
            return bool{};
        else if constexpr (Width > 1 && Width <= 8)
            return uint8_t{};
        else if constexpr (Width > 8 && Width <= 16)
            return uint16_t{};
        else if constexpr (Width > 16 && Width <= 32)
            return uint32_t{};
        else if constexpr (Width > 32 && Width <= 64)
            return uint64_t{};
        else
            return RawBits{};
    }());
};

/**
 * @brief Computes ceiling of log2 for a value at compile-time
 */
constexpr int log2_ceil(unsigned int n, int p = 0) {
    return (1U << p) >= n ? p : log2_ceil(n, p + 1);
}

namespace detail {
    template <typename T>
    concept HasAsTuple = requires(T t) { t.as_tuple(); };

    template <typename T>
    concept IsField = requires(T t) {
        typename T::value_type;
        { T::bit_width } -> std::convertible_to<size_t>;
    };

    template <typename T>
    concept Decodable = HasAsTuple<T> || IsField<T>;

    inline uint64_t extract_bits(const svBitVecVal* data, size_t start_bit, size_t num_bits) {
        if (num_bits > 64) {
            num_bits = 64;
        }

        uint64_t result = 0;
        size_t word_idx = start_bit / 32;
        size_t bit_offset = start_bit % 32;

        if constexpr (std::endian::native == std::endian::little) {
            size_t bytes_to_copy = (bit_offset + num_bits + 7) / 8;
            if (bytes_to_copy > 8) bytes_to_copy = 8;
            std::memcpy(&result, reinterpret_cast<const uint8_t*>(&data[word_idx]) + (bit_offset / 8), bytes_to_copy);
            result >>= (bit_offset % 8);
            if (num_bits < 64) {
                result &= ((1ULL << num_bits) - 1);
            }
        } else {
            if (bit_offset + num_bits <= 32) {
                uint32_t mask = (num_bits == 32) ? 0xFFFFFFFF : ((1u << num_bits) - 1);
                result = (data[word_idx] >> bit_offset) & mask;
            } else {
                uint32_t bits_from_first = 32 - bit_offset;
                result = (data[word_idx] >> bit_offset);
                if (num_bits > bits_from_first) {
                    uint32_t bits_from_second = num_bits - bits_from_first;
                    uint32_t mask = (bits_from_second == 32) ? 0xFFFFFFFF : ((1u << bits_from_second) - 1);
                    result |= (static_cast<uint64_t>(data[word_idx + 1] & mask)) << bits_from_first;
                    if (bits_from_first + 32 < num_bits) {
                        uint32_t bits_from_third = num_bits - bits_from_first - 32;
                        uint32_t mask = (bits_from_third == 32) ? 0xFFFFFFFF : ((1u << bits_from_third) - 1);
                        result |= (static_cast<uint64_t>(data[word_idx + 2] & mask)) << (bits_from_first + 32);
                    }
                }
            }
        }
        return result;
    }

    template <IsField T>
    void decode_field(T& field, const svBitVecVal* data, size_t& bit_offset) {
        if constexpr (T::bit_width <= 64) {
            field.value = static_cast<typename T::value_type>(
                extract_bits(data, bit_offset, T::bit_width)
            );
        } else {
            // For fields wider than 64 bits, you would need custom logic
            // This is a placeholder for such implementation
            static_assert(T::bit_width <= 64, "Fields wider than 64 bits not yet supported");
        }
        bit_offset += T::bit_width;
    }

    template <HasAsTuple T>
    void decode_field(T& structure, const svBitVecVal* data, size_t& bit_offset) {
        auto tuple = structure.as_tuple();
        std::apply([&data, &bit_offset](auto&... args) {
            (decode_field(args, data, bit_offset), ...);
        }, tuple);
    }

    template <typename T>
    constexpr int calc_element_bit_width();

    template <typename Tuple, size_t I = 0>
    constexpr int calc_tuple_bit_width();

    template <typename T>
    constexpr int calc_element_bit_width() {
        if constexpr (IsField<T>) {
            return T::bit_width;
        } else if constexpr (HasAsTuple<T>) {
            using TupleType = decltype(std::declval<T>().as_tuple());
            return calc_tuple_bit_width<TupleType>();
        } else {
            static_assert(Decodable<T>, "Type must be either a Field or have as_tuple() method");
            return 0; // This line should never be reached
        }
    }

    template <typename Tuple, size_t I>
    constexpr int calc_tuple_bit_width() {
        if constexpr (I == std::tuple_size_v<Tuple>) {
            return 0;
        } else {
            using Element = std::tuple_element_t<I, Tuple>;
            using ElementType = std::remove_reference_t<Element>;

            return calc_element_bit_width<ElementType>() + calc_tuple_bit_width<Tuple, I + 1>();
        }
    }
}

/**
 * @brief Calculates the total bit width of a struct
 */
template <typename T>
constexpr int calc_struct_bit_width() {
    return detail::calc_element_bit_width<T>();
}

/**
 * @brief Decodes a structure from raw svBitVecVal data
 */
template <typename T>
void decode_struct(T& structure, const svBitVecVal* data) {
    static_assert(requires (T t) { t.as_tuple(); }, "T must have as_tuple() method");
    size_t bit_offset = 0;
    auto tuple = structure.as_tuple();
    std::apply([&data, &bit_offset](auto&... args) {
        (detail::decode_field(args, data, bit_offset), ...);
    }, tuple);
}

/**
 * @brief Converts a byte array from SystemVerilog's `struct packed` (received via DPI interface) into a C++ struct
 */
template <typename T>
T bytes_to_struct(const svBitVecVal* raw_data) {
    T result;
    decode_struct(result, raw_data);
    return result;
}
