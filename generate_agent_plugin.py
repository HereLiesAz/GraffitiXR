import os
import json
import re
import shutil

base_dir = r"g:\My Drive\GraffitiXR\.agents\team_roles_plugin"
agents_dir = os.path.join(base_dir, "agents")
skills_dir = os.path.join(base_dir, "skills")

# Clean up old skills directory if it exists
if os.path.exists(skills_dir):
    shutil.rmtree(skills_dir)

os.makedirs(agents_dir, exist_ok=True)

with open(r"g:\My Drive\GraffitiXR\role_prompts.json", "r", encoding="utf-8") as f:
    text = f.read().strip()

# Splitting the two concatenated JSON objects
parts = text.split('}\n\n{')
if len(parts) == 2:
    json_meta = json.loads(parts[0] + '}')
    json_prompts = json.loads('{' + parts[1])
else:
    print("Could not split json nicely. Aborting.")
    exit(1)

# Write plugin.json
with open(os.path.join(base_dir, "plugin.json"), "w", encoding="utf-8") as f:
    json.dump({
        "name": "General Developer Team",
        "description": "Multi-Session Agent Team with 15 specialized personas. Designed for Orchestrator delegation.",
        "version": "1.2.0"
    }, f, indent=2)

# Global header string
session_header = json_prompts.get("MULTI_AGENT_SESSION_HEADER", "")

def slugify(text):
    return re.sub(r'[^a-zA-Z0-9_]+', '_', text).lower()

# Process each role
processed_count = 0
for key, prompt in json_prompts.items():
    if key == "MULTI_AGENT_SESSION_HEADER":
        continue
        
    meta = json_meta.get(key, {})
    
    # Extract metadata fields
    desc = meta.get("goal", f"Execute the {key} functionality")
    role_name = meta.get("role", key)
    guidelines = meta.get("guidelines", [])
    out_fmt = meta.get("output_format", "")
    
    # Create folder for the agent session
    folder_name = slugify(key)
    agent_path = os.path.join(agents_dir, folder_name)
    os.makedirs(agent_path, exist_ok=True)
    
    # Resolve the prompt (replace header placeholder)
    final_prompt = prompt.replace("{{MULTI_AGENT_SESSION_HEADER}}", session_header)
    
    # Build prompt.md content
    md_content = f"---\nname: {key}\nrole: {role_name}\ndescription: {desc}\n---\n\n"
    md_content += f"# System Prompt: {role_name}\n\n"
    
    if guidelines:
        md_content += "## Guidelines\n"
        for gl in guidelines:
            md_content += f"- {gl}\n"
        md_content += "\n"
        
    if out_fmt:
        md_content += f"## Required Output Format\n> {out_fmt}\n\n"
        
    md_content += f"## Core Instructions\n\n{final_prompt}\n"

    # Write prompt.md
    with open(os.path.join(agent_path, "prompt.md"), "w", encoding="utf-8") as f:
        f.write(md_content)
        
    processed_count += 1

print(f"Plugin 'General Developer Team' V1.2.0 generated successfully in {base_dir}")
print(f"Total agents created: {processed_count}")
