#include "input_manager.hpp"

InputManager::InputManager()
{
    enable_raw_mode();
}

InputManager::~InputManager()
{
    disable_raw_mode();
}

void InputManager::enable_raw_mode()
{
    tcgetattr(STDIN_FILENO, &orig_termios_);

    struct termios raw = orig_termios_;
    raw.c_iflag &= ~(IGNBRK | BRKINT | PARMRK | ISTRIP | INLCR | IGNCR | ICRNL | IXON | IXOFF | IXANY);
    raw.c_lflag &= ~(ECHO | ECHONL | ICANON | ISIG | IEXTEN);
    raw.c_cflag &= ~(CSIZE | PARENB);
    raw.c_cflag |= CS8;

    tcsetattr(STDIN_FILENO, TCSAFLUSH, &raw);
}

void InputManager::disable_raw_mode()
{
    tcsetattr(STDIN_FILENO, TCSAFLUSH, &orig_termios_);
}

bool InputManager::try_read(uint8_t &ch)
{
    fd_set set;
    struct timeval timeout = {0, 0};
    FD_ZERO(&set);
    FD_SET(STDIN_FILENO, &set);
    if (select(STDIN_FILENO + 1, &set, nullptr, nullptr, &timeout) > 0) {
        if (::read(STDIN_FILENO, &ch, 1) == 1)
            return true;
    }
    return false;
}

bool InputManager::blocking_read(uint8_t &ch)
{
    return (::read(STDIN_FILENO, &ch, 1) == 1);
}

void InputManager::print_help()
{
    std::cerr << "\r\n"
                 "Ctrl+A shortcuts:\r\n"
                 "  Ctrl+A x       — exit emulator\r\n"
                 "  Ctrl+A Ctrl+A  — send Ctrl+A to guest\r\n"
                 "  Ctrl+A p       — toggle pause (enter debug mode)\r\n"
                 "  Ctrl+A v       — toggle force-verbose output\r\n"
                 "  Ctrl+A h       — this help\r\n"
                 "\r\n"
                 "While paused:\r\n"
                 "  Enter / s      — single step\r\n"
                 "  c              — continue (resume)\r\n"
                 "  q              — quit\r\n"
                 "  (Ctrl+A escapes still work)\r\n"
                 "\r\n";
}

void InputManager::handle_escape(uint8_t ch, const CharCallback &enqueue_to_uart, bool paused_context,
                                 InputAction &out_action)
{
    switch (ch) {
    case 'x':
        std::cerr << "\r\nTerminated by Ctrl+A x\r\n";
        disable_raw_mode();
        out_action = InputAction::Exit;
        break;

    case CTRL_A:
        enqueue_to_uart(CTRL_A);
        break;

    case 'p':
        paused_ = !paused_;
        if (paused_) {
            std::cerr << "\r\n[PAUSED] Entering debug mode. Ctrl+A h for help.\r\n";
        } else {
            std::cerr << "\r\n[RESUMED]\r\n";
            out_action = InputAction::PauseResume;
        }
        break;

    case 'v':
        force_verbose_ = !force_verbose_;
        std::cerr << "\r\n[VERBOSE " << (force_verbose_ ? "ON" : "OFF") << "]\r\n";
        break;

    case 'h':
        print_help();
        break;

    default:
        break;
    }
}

bool InputManager::process_char(uint8_t ch, const CharCallback &enqueue_to_uart, bool paused_context,
                                InputAction &out_action)
{
    if (escape_pending_) {
        escape_pending_ = false;
        handle_escape(ch, enqueue_to_uart, paused_context, out_action);
        return true; // consumed
    }

    if (ch == CTRL_A) {
        escape_pending_ = true;
        return true; // consumed
    }

    if (paused_context) {
        switch (ch) {
        case '\r':
        case '\n':
        case 's':
            out_action = InputAction::PauseStep;
            return true;
        case 'c':
            paused_ = false;
            std::cerr << "\r\n[RESUMED]\r\n";
            out_action = InputAction::PauseResume;
            return true;
        case 'q':
            out_action = InputAction::PauseQuit;
            return true;
        default:
            // Ignore unknown keys in pause mode
            return true;
        }
    }

    // Normal mode: forward to guest
    return false;
}

InputManager::InputAction InputManager::poll(const CharCallback &enqueue_to_uart)
{
    InputAction action = InputAction::PauseStep;
    uint8_t ch;
    while (try_read(ch)) {
        if (!process_char(ch, enqueue_to_uart, false, action)) {
            enqueue_to_uart(ch);
        }
        if (paused_)
            break;
    }
    return action;
}

InputManager::InputAction InputManager::wait_paused(const CharCallback &enqueue_to_uart)
{
    while (true) {
        uint8_t ch;
        if (!blocking_read(ch))
            continue;

        InputAction out = InputAction::PauseStep;
        bool consumed = process_char(ch, enqueue_to_uart, true, out);

        if (consumed) {
            // If escape is pending, keep waiting for the next char
            if (escape_pending_)
                continue;
            return out;
        }
    }
    return InputAction::PauseStep; // default
}
