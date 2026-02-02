import os

replacements = {
    "com.hereliesaz.graffitixr.domain.model": "com.hereliesaz.graffitixr.common.model",
    "com.hereliesaz.graffitixr.feature.ar.ArState": "com.hereliesaz.graffitixr.common.model.ArState",
    "com.hereliesaz.graffitixr.feature.editor.RotationAxis": "com.hereliesaz.graffitixr.common.model.RotationAxis",
    "com.hereliesaz.graffitixr.UiState": "com.hereliesaz.graffitixr.common.model.UiState",
    "com.hereliesaz.graffitixr.data.LoadedProject": "com.hereliesaz.graffitixr.common.model.LoadedProject"
}

def replace_in_file(filepath):
    try:
        with open(filepath, 'r') as f:
            content = f.read()

        new_content = content
        for search, replace in replacements.items():
            new_content = new_content.replace(search, replace)

        if new_content != content:
            print(f"Updating {filepath}")
            with open(filepath, 'w') as f:
                f.write(new_content)
    except Exception as e:
        print(f"Error processing {filepath}: {e}")

def main():
    for root, dirs, files in os.walk("."):
        if "build" in root.split(os.sep) or ".git" in root.split(os.sep):
            continue

        for file in files:
            if file.endswith(".kt") or file.endswith(".java") or file.endswith(".xml"):
                replace_in_file(os.path.join(root, file))

if __name__ == "__main__":
    main()
