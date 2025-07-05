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

    template <size_t num_bits, size_t base_offset>
    requires (num_bits <= 64)
    inline uint64_t extract_bits(const svBitVecVal* data) {
        uint64_t result = 0;
        constexpr size_t word_idx = base_offset / 32;
        constexpr size_t bits_offset = base_offset % 32;
        constexpr size_t num_word = ((bits_offset + num_bits + 31) / 32);

        result |= data[word_idx] >> bits_offset;
        for(size_t i = 1; i < num_word; ++i) {
            result |= static_cast<uint64_t>(data[word_idx + i]) << (i * 32 - bits_offset);
        }

        if constexpr (num_bits < 64) {
            result &= (uint64_t(1) << num_bits) - 1;
        }
    
        return result;
    }

    template <Decodable T>
    constexpr size_t get_width() {
        if constexpr (HasAsTuple<T>) {
            size_t total = 0;
            auto calc = [&]<typename... Args>(std::tuple<Args...>) {
                ((total += get_width<std::remove_reference_t<Args>>()), ...);
            };
            calc(T{}.as_tuple());
            return total;
        }
        else if constexpr (IsField<T>) {
            return T::bit_width;
        }
    }

    template <Decodable T>
    constexpr auto get_offsets() {
        if constexpr (HasAsTuple<T>) {
            using TupleType = decltype(T{}.as_tuple());
            constexpr size_t tuple_size = std::tuple_size_v<TupleType>;
            std::array<size_t, tuple_size> offsets{};
            
            size_t current_offset = 0;
            
            [&]<size_t... Is>(std::index_sequence<Is...>) {
                (([&]() {
                    offsets[Is] = current_offset;
                    using ElementType = std::remove_reference_t<std::tuple_element_t<Is, TupleType>>;
                    current_offset += get_width<ElementType>();
                }()), ...);
            }(std::make_index_sequence<tuple_size>{});
            
            return offsets;
        }
        else if constexpr (IsField<T>) {
            return std::array<size_t, 1>{0};
        }
    }

    template <IsField T, size_t base_offset>
    void decode_field(T& field, const svBitVecVal* data) {
        if constexpr (T::bit_width <= 64) {
            field.value = static_cast<typename T::value_type>(
                extract_bits<T::bit_width, base_offset>(data)
            );
        } else {
            // For fields wider than 64 bits, you would need custom logic
            // This is a placeholder for such implementation
            static_assert(T::bit_width <= 64, "Fields wider than 64 bits not yet supported");
        }
    }

    template <HasAsTuple T, size_t base_offset>
    void decode_field(T& structure, const svBitVecVal* data) {
        constexpr auto field_offsets = detail::get_offsets<T>();
        auto tuple = structure.as_tuple();

        using TupleType = decltype(tuple);
        constexpr size_t tuple_size = std::tuple_size_v<TupleType>;

        [&]<size_t... Is>(std::index_sequence<Is...>) {
            (decode_field<
                std::remove_reference_t<decltype(std::get<Is>(tuple))>, 
                base_offset + std::get<Is>(field_offsets)
            >(std::get<Is>(tuple), data), ...);
        }(std::make_index_sequence<tuple_size>{});
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
    constexpr auto field_offsets = detail::get_offsets<T>();
    auto tuple = structure.as_tuple();
    
    [&]<size_t... Is>(std::index_sequence<Is...>) {
        (detail::decode_field<std::remove_reference_t<decltype(std::get<Is>(tuple))>, std::get<Is>(field_offsets)>(std::get<Is>(tuple), data), ...);
    }(std::make_index_sequence<std::tuple_size_v<decltype(tuple)>>{});
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
