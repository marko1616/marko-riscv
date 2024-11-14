import pathlib
import subprocess

if __name__ == "__main__":
    emulator_path = pathlib.Path(__file__).parent / ".." / ".." / "obj_dir" / "VMarkoRvCore"
    hex_path = pathlib.Path(__file__).parent / ".." / "asmtst" / "function.hex"
    assertion = "3"
    command = [str(emulator_path), "-f", str(hex_path), "--assert-last-peek", assertion, "--random-async-interruption"]

    # Initialize counters for pass/fail statistics
    pass_count = 0
    fail_count = 0
    total_runs = 0

    while True:
        process = subprocess.Popen(
            command,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE
        )
        stdout, stderr = process.communicate()
        total_runs += 1

        # Check if the assertion passed
        if stderr.decode().endswith("Assertion passed.\n"):
            pass_count += 1
        else:
            fail_count += 1

        # Output statistics after each run
        print(f"Total runs: {total_runs}, Passed: {pass_count}, Failed: {fail_count}")