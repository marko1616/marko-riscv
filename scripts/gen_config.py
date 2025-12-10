import yaml
import pathlib
from jinja2 import Template

BASE_PATH = pathlib.Path(__file__).parent.parent
YAML_CONFIG = BASE_PATH / "assets" / "core_config.yaml"
TEMPLATE_PATH = BASE_PATH / "emulator" / "src" / "config.hpp.j2"
OUTPUT_PATH = BASE_PATH / "emulator" / "src" / "config.hpp"

with open(YAML_CONFIG, "r") as f:
    config_data = yaml.safe_load(f)

with open(TEMPLATE_PATH, "r") as f:
    template_content = f.read()

template = Template(template_content)
rendered_output = template.render(config_data)

with open(OUTPUT_PATH, "w") as f:
    f.write(rendered_output)
