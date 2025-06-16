import os
import subprocess
import questionary
from rich.console import Console
from rich.panel import Panel
import typer

app = typer.Typer()
console = Console()

# All available actions with descriptions
TASKS = [
    {
        "name": "init",
        "desc": "Initialize git submodules and build the Capstone disassembly library."
    },
    {
        "name": "build-simulator",
        "desc": "Build the RISC-V emulator using mill and Verilator with C++ sources."
    },
    {
        "name": "build-test-elves",
        "desc": "Assemble and link .S files into .elf test binaries from tests/asmtests/src."
    },
    {
        "name": "build-sim-rom",
        "desc": "Build ROM assets used by the emulator (make -C emulator/assets)."
    },
    {
        "name": "clean-all",
        "desc": "Remove compiled objects, ELF files, and other build artifacts."
    },
    {
        "name": "batched-riscv-tests",
        "desc": "Run all RISC-V ISA tests using scripts/batched_test.py in parallel."
    },
    {
        "name": "exit",
        "desc": "Exit the CLI tool."
    }
]

def run_make_target(target: str):
    """Run a Makefile target using `make`."""
    console.print(Panel.fit(f"[cyan]Running: make {target}[/cyan]"))
    try:
        subprocess.run(["make", target], check=True)
        console.print(f"[green]make {target} completed successfully.[/green]")
    except subprocess.CalledProcessError:
        console.print(f"[red]make {target} failed.[/red]")

def run_batched_tests():
    """Run the batched RISC-V test script using all CPU cores."""
    nproc = os.cpu_count()
    command = ["python3", "scripts/batched_test.py", "-j", str(nproc)]
    console.print(Panel.fit(f"[cyan]Running: {' '.join(command)}[/cyan]"))
    try:
        subprocess.run(command, check=True)
        console.print("[green]RISC-V batched tests completed successfully.[/green]")
    except subprocess.CalledProcessError:
        console.print("[red]RISC-V batched test run failed.[/red]")

def main_menu():
    """Main loop for interactive selection and task execution."""
    while True:
        options = [
            questionary.Choice(
                title=f"{task['name']} - {task['desc']}",
                value=task["name"]
            ) for task in TASKS
        ]

        selected = questionary.select(
            "Choose a task to perform:",
            choices=options
        ).ask()

        if selected == "exit":
            console.print("[bold blue]Exiting. Goodbye![/bold blue]")
            break

        confirm = questionary.confirm(f"Proceed with `{selected}`?").ask()

        if confirm:
            if selected == "batched-riscv-tests":
                run_batched_tests()
            else:
                run_make_target(selected)
        else:
            console.print("[yellow]Cancelled. Returning to menu.[/yellow]")

@app.command()
def interactive():
    """Start the interactive CLI."""
    console.print("[bold green]Welcome to the project's CLI Tool![/bold green]")
    main_menu()

if __name__ == "__main__":
    app()
