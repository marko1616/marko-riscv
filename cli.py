import os
import glob
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
        "name": "build-core",
        "desc": "Build the RISC-V core using mill."
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
        "name": "cpp-lint",
        "desc": "Format all .cpp and .hpp files in emulator/src using clang-format."
    },
    {
        "name": "chisel-lint",
        "desc": "Reformat all Chisel/Scala sources using mill __.reformat."
    },
    {
        "name": "opensta-timing",
        "desc": "Synthesize core with NangateOpenCellLibrary and run OpenSTA timing analysis."
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
    nproc = min(os.cpu_count(), 8)
    command = ["python3", "scripts/batched_test.py", "-j", str(nproc)]
    console.print(Panel.fit(f"[cyan]Running: {' '.join(command)}[/cyan]"))
    try:
        subprocess.run(command, check=True)
        console.print("[green]RISC-V batched tests completed successfully.[/green]")
    except subprocess.CalledProcessError:
        console.print("[red]RISC-V batched test run failed.[/red]")

def run_cpp_lint():
    """Format all .cpp and .hpp files under emulator/src using clang-format."""
    source_dir = os.path.join("emulator", "src")
    files = glob.glob(os.path.join(source_dir, "**", "*.cpp"), recursive=True) + \
            glob.glob(os.path.join(source_dir, "**", "*.hpp"), recursive=True)

    if not files:
        console.print(f"[yellow]No .cpp or .hpp files found under {source_dir}.[/yellow]")
        return

    console.print(Panel.fit(
        f"[cyan]Running clang-format on {len(files)} file(s) in {source_dir}[/cyan]"
    ))

    command = ["clang-format", "-i", "--style=file"] + files
    console.print(f"[dim]$ {' '.join(command[:4])} ... ({len(files)} files)[/dim]")

    try:
        subprocess.run(command, check=True)
        console.print("[green]clang-format completed successfully.[/green]")
        for f in files:
            console.print(f"  [green][OK][/green] {f}")
    except FileNotFoundError:
        console.print("[red]clang-format not found. Please install it first.[/red]")
    except subprocess.CalledProcessError:
        console.print("[red]clang-format failed.[/red]")

def run_chisel_lint():
    """Reformat all Chisel/Scala sources using mill __.reformat."""
    command = ["mill", "__.reformat"]
    work_dir = "core"
    console.print(Panel.fit(f"[cyan]Running: cd {work_dir} && {' '.join(command)}[/cyan]"))

    if not os.path.isdir(work_dir):
        console.print(f"[red]Directory '{work_dir}' not found.[/red]")
        return

    try:
        subprocess.run(command, cwd=work_dir, check=True)
        console.print("[green]mill __.reformat completed successfully.[/green]")
    except FileNotFoundError:
        console.print("[red]mill not found. Please ensure mill is installed and on your PATH.[/red]")
    except subprocess.CalledProcessError:
        console.print("[red]mill __.reformat failed.[/red]")

def run_opensta():
    generated_dir = os.path.join("core", "generated")
    synth_script  = os.path.abspath(os.path.join("assets", "synth_nangate45.ys"))
    sta_script    = os.path.join("assets", "sta.tcl")

    # Synthesis
    console.print(Panel.fit("[cyan]Step 1/2: Synthesizing (Synlig + Yosys + NangateOpenCellLibrary)[/cyan]"))
    if not os.path.isdir(generated_dir):
        console.print(f"[red]Directory '{generated_dir}' not found. Run build-core first.[/red]")
        return
    try:
        subprocess.run(["synlig", synth_script], cwd=generated_dir, check=True)
        console.print("[green]Synthesis completed -> core/generated/top_mapped.v[/green]")
    except FileNotFoundError:
        console.print("[red]synlig not found.[/red]")
        return
    except subprocess.CalledProcessError:
        console.print("[red]Synthesis failed.[/red]")
        return

    # OpenSTA
    console.print(Panel.fit("[cyan]Step 2/2: Running OpenSTA timing analysis[/cyan]"))
    try:
        subprocess.run(["sta", sta_script], check=True)
        console.print("[green]OpenSTA timing analysis completed.[/green]")
    except FileNotFoundError:
        console.print("[red]opensta not found. Install: sudo apt install opensta[/red]")
    except subprocess.CalledProcessError:
        console.print("[red]OpenSTA analysis failed.[/red]")

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
            elif selected == "cpp-lint":
                run_cpp_lint()
            elif selected == "chisel-lint":
                run_chisel_lint()
            elif selected == "opensta-timing":
                run_opensta()
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
