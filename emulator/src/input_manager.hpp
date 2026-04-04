#pragma once

#include <cstdint>
#include <functional>
#include <termios.h>
#include <unistd.h>
#include <sys/select.h>
#include <iostream>
#include <cstdlib>

class InputManager {
public:
    using CharCallback = std::function<void(uint8_t)>;

    enum class PauseAction { Step, Resume, Quit };

    InputManager();
    ~InputManager();

    // Non-blocking poll; guest chars dispatched via callback
    void poll(const CharCallback& enqueue_to_uart);

    // Blocking wait in paused mode; returns desired action
    PauseAction wait_paused(const CharCallback& enqueue_to_uart);

    bool is_paused()        const { return paused_; }
    bool is_force_verbose() const { return force_verbose_; }
    void force_pause() { paused_ = true; }

private:
    void enable_raw_mode();
    void disable_raw_mode();
    bool try_read(uint8_t& ch);
    bool blocking_read(uint8_t& ch);

    // Process one byte. If in paused context, out_action receives the debug command.
    // Returns true if the byte was fully consumed (escape / debug command).
    bool process_char(uint8_t ch,
                      const CharCallback& enqueue_to_uart,
                      bool paused_context,
                      PauseAction* out_action);

    void handle_escape(uint8_t ch,
                       const CharCallback& enqueue_to_uart,
                       bool paused_context,
                       PauseAction* out_action);

    void print_help();

    struct termios orig_termios_;
    static constexpr uint8_t CTRL_A = 0x01;
    bool escape_pending_  = false;
    bool paused_          = false;
    bool force_verbose_   = false;
};
