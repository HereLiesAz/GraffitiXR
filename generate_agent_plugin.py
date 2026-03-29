import os
import json

base_dir = r"g:\My Drive\GraffitiXR\.agents\team_roles_plugin"
skills_dir = os.path.join(base_dir, "skills")
agents_dir = os.path.join(base_dir, "agents")

os.makedirs(skills_dir, exist_ok=True)
os.makedirs(agents_dir, exist_ok=True)

with open(r"g:\My Drive\GraffitiXR\role_prompts.json", "r", encoding="utf-8") as f:
    text = f.read().strip()

# Splitting the two concatenated JSON objects
parts = text.split('}\n\n{')
if len(parts) == 2:
    json1 = json.loads(parts[0] + '}')
    json2 = json.loads('{' + parts[1])
else:
    print("Could not split json nicely. Aborting.")
    exit(1)

# Write plugin.json
with open(os.path.join(base_dir, "plugin.json"), "w", encoding="utf-8") as f:
    json.dump({
        "name": "GraffitiXR Agent Team",
        "description": "Specialized agent capabilities and personas mapped from role_prompts.json",
        "version": "1.0.0"
    }, f, indent=2)

# Write agents/README.md
with open(os.path.join(agents_dir, "README.md"), "w", encoding="utf-8") as f:
    f.write("# Agents Directory\nThis directory holds configured subagents for the GraffitiXR Team plugin.\n")

# Process keys
seen_folders = set()
for key, prompt in json2.items():
    # Attempt to find corresponding metadata in json1
    # Fallbacks for naming mismatch (geminiService vs julesService)
    meta_key = key
    if key == "geminiService.summarizeSession" and "julesService.summarizeSession" in json1:
        meta_key = "julesService.summarizeSession"
        
    meta = json1.get(meta_key, {})
    
    # Extract metadata fields
    desc = meta.get("goal", f"Execute the {key} prompt functionality")
    role = meta.get("role", "")
    guidelines = meta.get("guidelines", [])
    out_fmt = meta.get("output_format", "")
    
    # Create folder for the skill
    folder_name = key.replace(".", "_")
    skill_path = os.path.join(skills_dir, folder_name)
    os.makedirs(skill_path, exist_ok=True)
    
    # Build SKILL.md content
    md_content = f"---\nname: {key}\ndescription: {desc}\n---\n\n"
    
    if role:
        md_content += f"## Role\n{role}\n\n"
    if guidelines:
        md_content += "## Guidelines\n"
        for idx, gl in enumerate(guidelines):
            md_content += f"{idx + 1}. {gl}\n"
        md_content += "\n"
    if out_fmt:
        md_content += f"## Required Output Format\n{out_fmt}\n\n"
        
    md_content += f"## Prompt / Instructions\n```\n{prompt}\n```\n"

    # Write SKILL.md
    with open(os.path.join(skill_path, "SKILL.md"), "w", encoding="utf-8") as f:
        f.write(md_content)
        
    seen_folders.add(folder_name)

print(f"Plugin generated successfully in {base_dir}")
print(f"Total skills populated: {len(seen_folders)}")
